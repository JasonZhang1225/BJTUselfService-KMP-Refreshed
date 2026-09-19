package team.bjtuss.bjtuselfservice.shared.packaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PackagingCiAsciiConfigTest {
    @Test
    fun ciWorkflowOverridesInstallerMetadataWithAsciiForWix() {
        val workflow = File(findRepoRoot(), ".github/workflows/kmp-package.yml").readText()
        assertTrue("chcp 65001" in workflow)
        // 英文代码页下 WiX light 会把中文打成 `?????` 并报 311，所以 CI 必须传 ASCII 元数据。
        // 把这两行改回中文会让 windows 打包再次失败。
        assertTrue("WINDOWS_PACKAGE_NAME: BJTUselfServiceKMP" in workflow)
        assertTrue("WINDOWS_PACKAGE_DESCRIPTION: BJTU Self Service KMP" in workflow)
    }

    @Test
    fun windowsGradleUsesChineseInstallerStrings() {
        val gradle = File(findRepoRoot(), "multiplatform/windowsApp/build.gradle.kts").readText()
        // 本地构建没有代码页问题，默认值保留中文显示名，只由上面两个环境变量在 CI 覆盖。
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
