package team.bjtuss.bjtuselfservice.shared.packaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackagingCiAsciiConfigTest {
    @Test
    fun ciWorkflowPackagesChineseNameWithChineseWixLocalization() {
        val workflow = File(findRepoRoot(), ".github/workflows/kmp-package.yml").readText()
        assertTrue("chcp 65001" in workflow)
        // jpackage 按 JVM 默认语言选 WiX .wxl：英文 runner 用 en（代码页 1252）会把中文名打成
        // `?????` 并报 light 311。CI 强制 zh_CN 让它改用代码页 936 的中文 .wxl。
        assertTrue("-Duser.language=zh -Duser.country=CN" in workflow)
        // 不再用 ASCII 兜底覆盖安装器名称，否则装完又变成英文名。
        assertFalse("WINDOWS_PACKAGE_NAME:" in workflow)
        assertFalse("WINDOWS_PACKAGE_DESCRIPTION:" in workflow)
    }

    @Test
    fun iosJobUsesXcode27SdkForDuoApis() {
        val workflow = File(findRepoRoot(), ".github/workflows/kmp-package.yml").readText()
        assertTrue("runs-on: xcode-27" in workflow)
        assertTrue("Xcode_27.1.app" in workflow)
        assertTrue("Need iPhoneOS SDK >= 27.1" in workflow)
    }

    @Test
    fun debugTagsPackageAndOfficialReleaseRequiresAnExplicitDispatch() {
        val root = findRepoRoot()
        val packageWorkflow = root.resolve(".github/workflows/kmp-package.yml").readText()
        val packageTriggers = packageWorkflow.substringAfter("\non:\n").substringBefore("\nconcurrency:")
        val tagPush = packageTriggers.substringAfter("  push:").substringBefore("  workflow_dispatch:")
        assertEquals(listOf("- \"debug-*\""), tagPush.lineSequence().map { it.trim() }.filter { it.startsWith("- ") }.toList())
        assertFalse(packageTriggers.lineSequence().any { it.trim() == "release:" })
        val publishInput = packageTriggers.substringAfter("      publish_release:").substringBefore("      mirror_tag:")
        assertTrue("default: false" in publishInput && "type: boolean" in publishInput)
        val release = packageWorkflow.substringAfter("\n  release:").substringBefore("\n  gitee-mirror:")
        assertTrue("github.event_name == 'workflow_dispatch' && inputs.publish_release" in release)
        assertTrue("startsWith(github.ref, 'refs/tags/v')" in release)
        assertTrue("!contains(github.ref_name, 'alpha')" in release)
        assertTrue("!contains(github.ref_name, 'beta')" in release)
        val mirror = packageWorkflow.substringAfter("\n  gitee-mirror:")
        assertTrue("needs: release" in mirror)
        assertTrue("needs.release.result == 'success'" in mirror)
        assertTrue("github.event_name == 'workflow_dispatch' && inputs.mirror_tag != ''" in mirror)

        val mirrorWorkflow = root.resolve(".github/workflows/gitee-release-mirror.yml").readText()
        val mirrorTriggers = mirrorWorkflow.substringAfter("\non:\n").substringBefore("\npermissions:")
        assertFalse(mirrorTriggers.lineSequence().any { it.trim() == "release:" || it.trim() == "push:" })
    }

    @Test
    fun windowsGradleUsesChineseInstallerStrings() {
        val gradle = File(findRepoRoot(), "multiplatform/windowsApp/build.gradle.kts").readText()
        // 本地与 CI 都用中文显示名；环境变量覆盖只留作手动兜底。
        assertTrue("packageName = windowsPackageDisplayName" in gradle)
        assertTrue("menuGroup = windowsPackageDisplayName" in gradle)
        assertTrue("\"交大自由行 KMP\"" in gradle)
        assertTrue("\"交大自由行 Kotlin Multiplatform Windows 应用\"" in gradle)
    }

    private fun findRepoRoot(): File {
        var dir = File(".").canonicalFile
        repeat(8) {
            val found = File(dir, ".github/workflows/kmp-package.yml").isFile &&
                File(dir, "multiplatform/windowsApp/build.gradle.kts").isFile
            if (found) return dir
            dir = dir.parentFile ?: error("repo root not found from ${File(".").canonicalFile}")
        }
        error("repo root not found from ${File(".").canonicalFile}")
    }
}
