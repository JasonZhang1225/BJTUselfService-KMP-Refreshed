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
        assertTrue("!contains(github.ref_name, 'Liquid')" in legacyRelease)
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
