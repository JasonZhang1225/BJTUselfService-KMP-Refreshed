import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

group = "team.bjtuss.bjtuselfservice"
version = "0.1.0"

// A packaging run supplies the same identifier to every target; otherwise use
// the local build time so successive local builds can be distinguished.
abstract class GeneratePackageBuildInfo : DefaultTask() {
    @get:Input
    abstract val packageBuildId: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val requestedId = packageBuildId.get()
        require(requestedId == "local" || requestedId.matches(Regex("[0-9]+\\.[0-9]+"))) { "Invalid package build ID" }
        val id = if (requestedId == "local") {
            "local-" + ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        } else {
            requestedId
        }
        val output = outputDir.get().file("team/bjtuss/bjtuselfservice/shared/update/PackageBuildInfo.kt").asFile
        output.parentFile.mkdirs()
        val content = "package team.bjtuss.bjtuselfservice.shared.update\n\ninternal const val PACKAGE_BUILD_ID = \"$id\"\n"
        if (!output.isFile || output.readText() != content) output.writeText(content)
    }
}

val generatePackageBuildInfo = tasks.register<GeneratePackageBuildInfo>("generatePackageBuildInfo") {
    packageBuildId.set(providers.environmentVariable("BJTU_PACKAGE_BUILD_ID").orElse("local"))
    outputDir.set(layout.buildDirectory.dir("generated/packageBuildInfo/commonMain"))
    // The clock is read at execution time, including when configuration cache is reused.
    outputs.upToDateWhen { false }
}

kotlin {
    android {
        namespace = "team.bjtuss.bjtuselfservice.shared"
        compileSdk = 36
        minSdk = 28

        androidResources {
            enable = true
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    // 手动添加 JVM 共用源集前先保留默认 Apple 层级。
    applyDefaultHierarchyTemplate()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main") {
            cinterops {
                create("uikitSheet") {
                    defFile(project.file("src/iosMain/cinterop/UIKitSheetBridge.def"))
                    // includeDirs 会把头文件登记为任务输入；只用 -I 时改了 .h 不会重新生成绑定。
                    includeDirs(project.file("src/iosMain/cinterop"))
                }
            }
        }
        binaries.framework {
            baseName = "BJTUShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain { kotlin.srcDir(generatePackageBuildInfo) }
        // JVM 与 Android 共用 MP4 盒处理代码；Apple 仍使用原生 AVFoundation。
        val mediaJvmMain = maybeCreate("mediaJvmMain")
        mediaJvmMain.dependsOn(getByName("commonMain"))
        getByName("androidMain").dependsOn(mediaJvmMain)
        getByName("desktopMain").dependsOn(mediaJvmMain)
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            implementation(libs.navigation3.ui)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ksoup)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqldelight.runtime)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // Pin the concrete engine dependency instead of relying on Ktor's
            // transitive version so security review and upgrades are explicit.
            implementation(libs.okhttp)
            implementation(libs.sqldelight.android.driver)
        }

        // iOS 原生（appleMain 是 Apple 各端的公共上游）：网络走 Darwin(URLSession)，缓存库走原生 sqlite driver。
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }

        val desktopTest by getting {
            dependencies { implementation(compose.desktop.currentOs) }
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna)
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
    }
}

sqldelight {
    databases {
        create("CacheDatabaseSql") {
            packageName.set("team.bjtuss.bjtuselfservice.shared.cache.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "team.bjtuss.bjtuselfservice.shared.resources"
}
