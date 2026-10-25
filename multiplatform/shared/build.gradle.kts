import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

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
    // macOS 原生端：SwiftUI 宿主（macosApp）承载共享 Compose 业务界面，
    // 与 iOS 端同构（Kotlin 导出框架 + Xcode 宿主工程）。只出 Apple Silicon。
    macosArm64()

    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "BJTUShared"
            isStatic = true
        }
    }
    // UIKit cinterop 只对 iOS 目标存在；macOS 端没有 UIKit，放到全部原生目标上会直接编译失败。
    targets.withType<KotlinNativeTarget>().matching { it.name.startsWith("ios") }.configureEach {
        compilations.getByName("main") {
            cinterops {
                create("uikitSheet") {
                    defFile(project.file("src/iosMain/cinterop/UIKitSheetBridge.def"))
                    // includeDirs 会把头文件登记为任务输入；只用 -I 时改了 .h 不会重新生成绑定。
                    includeDirs(project.file("src/iosMain/cinterop"))
                }
            }
        }
    }

    sourceSets {
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
