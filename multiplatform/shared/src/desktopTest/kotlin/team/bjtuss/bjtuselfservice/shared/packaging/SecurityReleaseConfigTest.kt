package team.bjtuss.bjtuselfservice.shared.packaging

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityReleaseConfigTest {
    private val root = findRepositoryRoot()

    @Test
    fun androidCaptchaUsesPinnedOnnxArtifactAndRecordedChecksum() {
        val catalog = root.resolve("multiplatform/gradle/libs.versions.toml").readText()
        assertTrue("onnxRuntime = \"1.30.0\"" in catalog)
        assertTrue("okhttp = \"5.3.2\"" in catalog)
        assertFalse("pytorchAndroid" in catalog)

        val onnx = root.resolve("multiplatform/androidApp/src/main/assets/BJTUCaptcha.onnx")
        val legacy = root.resolve("multiplatform/androidApp/src/main/assets/BJTUCaptcha.pt")
        assertTrue(onnx.isFile)
        assertFalse(legacy.exists())

        val manifest = root.resolve("multiplatform/tools/captcha/validation_manifest.json").readText()
        val expected = Regex("\"android_onnx_sha256\"\\s*:\\s*\"([0-9a-f]{64})\"")
            .find(manifest)
            ?.groupValues
            ?.get(1)
            ?: error("android_onnx_sha256 missing")
        assertEquals(expected, sha256(onnx))
    }

    @Test
    fun gradleDistributionAndWrapperArePinnedAndCiValidatesThem() {
        val properties = root.resolve(
            "multiplatform/gradle/wrapper/gradle-wrapper.properties",
        ).readText()
        assertTrue(
            "distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06" in
                properties,
        )
        assertEquals(
            "b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13",
            sha256(root.resolve("multiplatform/gradle/wrapper/gradle-wrapper.jar")),
        )

        listOf("kmp-package.yml", "release.yml", "debug.yml").forEach { workflow ->
            val text = root.resolve(".github/workflows/$workflow").readText()
            assertTrue(
                "gradle/actions/wrapper-validation@3f131e8634966bd73d06cc69884922b02e6faf92" in text,
                "$workflow must validate Gradle wrappers before builds",
            )
        }
        val legacyRelease = root.resolve(".github/workflows/release.yml").readText()
        // 冻结根 Android 只打 v1.7.0；其余 v* 标签由 kmp-package.yml 打包。
        // 不要再要求已删除的 Liquid 字符串守卫，那会让本测试在当前 HEAD 失败。
        assertTrue("github.ref_name == 'v1.7.0'" in legacyRelease)
        assertFalse("github.ref_name != 'v1.7.0'" in legacyRelease)
    }

    @Test
    fun securityCheckWorkflowWatchesMainAndAuditNotDeletedLiquidBranch() {
        val workflow = root.resolve(".github/workflows/kmp-security-check.yml").readText()
        assertTrue("branches: [main, audit]" in workflow)
        assertFalse("branches: [Liquid]" in workflow)
    }

    @Test
    fun androidWebViewClearsCookieJarBeforeInjectingSessionCookies() {
        val source = root.resolve(
            "multiplatform/shared/src/androidMain/kotlin/team/bjtuss/bjtuselfservice/shared/webview/SchoolWebView.android.kt",
        ).readText()
        val factory = source.substringAfter("factory = { context ->")
            .substringBefore("actual fun openExternalUrl")
        val clearAt = factory.indexOf("cookieManager.removeAllCookies")
        val setCookieAt = factory.indexOf("cookieManager.setCookie")
        val loadUrlAt = factory.indexOf("loadUrl(request.url)")
        assertTrue(clearAt >= 0)
        assertTrue(setCookieAt > clearAt)
        assertTrue(loadUrlAt > setCookieAt)
    }

    @Test
    fun smartPlatformHandshakeUsesRedirectDisabledTransport() {
        val sources = listOf(
            "multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/data/homework/HomeworkRemoteDataSource.kt",
            "multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/data/courseware/CoursewareRemoteDataSource.kt",
            "multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/data/course/CourseScheduleRemoteDataSource.kt",
        ).map { root.resolve(it).readText() }
        sources.forEach { source ->
            val handshake = source.substringAfter("followSmartHandshakeRedirects")
                .substringBefore("if (settled")
                .ifBlank { source.substringAfter("followSmartHandshakeRedirects") }
            assertTrue("executeWithoutRedirects" in source)
            assertFalse("{ request -> execute(request) }" in handshake)
            assertFalse("executeSoft(request)" in handshake)
        }
    }

    @Test
    fun appleCalendarHelpersReconcileManagedCourseRangeBeforeSavingNewSnapshot() {
        val ios = root.resolve(
            "multiplatform/shared/src/iosMain/kotlin/team/bjtuss/bjtuselfservice/shared/calendar/IosSystemCalendarGateway.kt",
        ).readText()
        val mac = root.resolve("multiplatform/desktopApp/src/main/swift/SystemCalendarHelper.swift").readText()

        listOf(ios, mac).forEach { source ->
            assertTrue("managedCourseRange" in source)
            assertTrue("courseMarkerPrefix" in source || "managedCourseStableIdFromMarker" in source)
            assertTrue("EKSpanFutureEvents" in source || "span: .futureEvents" in source)
        }
        assertTrue(ios.indexOf("oldSeries.forEach") < ios.indexOf("batch.events.forEach"))
        assertTrue(mac.indexOf("firstOccurrenceBySeries.values") < mac.indexOf("for (draft, start, end) in dated"))
        assertTrue("store.remove(event, span: .futureEvents" in mac)
        assertTrue("calendarItemIdentifier" in mac)
        assertTrue("calendarItemIdentifier" in ios)
        assertTrue("unmarked" in mac.lowercase() || "user-created" in mac.lowercase())
    }

    @Test
    fun debugSecuritySmokeActivityRequiresSignaturePermission() {
        val manifest = root.resolve("multiplatform/androidApp/src/debug/AndroidManifest.xml").readText()
        assertTrue("android:protectionLevel=\"signature\"" in manifest)
        assertTrue("android:permission=\"team.bjtuss.bjtuselfservice.kmp.permission.SECURITY_SMOKE\"" in manifest)
    }

    @Test
    fun iosDeploymentTargetIsAtLeast16() {
        val config = root.resolve("multiplatform/iosApp/Configuration/Config.xcconfig").readText()
        assertTrue("IPHONEOS_DEPLOYMENT_TARGET = 16.0" in config)
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

private fun findRepositoryRoot(): File {
    var directory = File(".").canonicalFile
    repeat(8) {
        if (directory.resolve("multiplatform/gradle/libs.versions.toml").isFile) return directory
        directory = directory.parentFile ?: return@repeat
    }
    error("repository root not found")
}
