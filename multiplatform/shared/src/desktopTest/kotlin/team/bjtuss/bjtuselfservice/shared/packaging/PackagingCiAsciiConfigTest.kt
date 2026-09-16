package team.bjtuss.bjtuselfservice.shared.packaging

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PackagingCiAsciiConfigTest {
    @Test
    fun ciWorkflowKeepsUtf8ForChineseInstallerMetadata() {
        val workflow = File(findRepoRoot(), ".github/workflows/kmp-package.yml").readText()
        assertTrue("chcp 65001" in workflow)
        assertTrue("WINDOWS_PACKAGE_NAME" !in workflow)
        assertTrue("WINDOWS_PACKAGE_DESCRIPTION" !in workflow)
    }

    @Test
    fun windowsGradleUsesChineseInstallerStrings() {
        val gradle = File(findRepoRoot(), "multiplatform/windowsApp/build.gradle.kts").readText()
        assertTrue("packageName = \"交大自由行 KMP\"" in gradle)
        assertTrue("description = \"交大自由行 Kotlin Multiplatform Windows 应用\"" in gradle)
        assertTrue("menuGroup = \"交大自由行 KMP\"" in gradle)
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
