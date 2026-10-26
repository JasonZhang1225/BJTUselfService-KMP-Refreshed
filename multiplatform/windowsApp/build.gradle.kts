import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

group = "team.bjtuss.bjtuselfservice"
version = "0.1.0"

val windowsPackageDisplayName = System.getenv("WINDOWS_PACKAGE_NAME")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: "交大自由行 KMP"
val windowsPackageDescription = System.getenv("WINDOWS_PACKAGE_DESCRIPTION")
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: "交大自由行 Kotlin Multiplatform Windows 应用"

// ---------------------------------------------------------------------------------------------
// 课程回放「左右并排合成」用的原生小程序。
//
// 只用系统自带的 Media Foundation，不打包 ffmpeg；编译产物约 280 KB，对 MSI 体积影响可忽略。
// 需要本机装有 Visual Studio C++ 工具链：找不到就跳过编译，运行时合成功能自动降级为「分别保存两路」。
val playbackCompositorSource = file("src/main/native/BJTUPlaybackCompositor.cpp")
val playbackCompositorResourcesDir = layout.buildDirectory.dir("generated/windows-native")
val playbackCompositorExe = playbackCompositorResourcesDir.map { it.file("native/BJTUPlaybackCompositor.exe") }
// 编译中间产物（.obj / .bat）必须放在资源目录之外：资源目录整个会被打进 jar，
// 放在里面的话这些中间文件会跟着进安装包（实测 .obj 有 568 KB）。
val playbackCompositorBuildDir = layout.buildDirectory.dir("tmp/windows-native")

/**
 * 编译合成小程序：用 vswhere（再退回扫安装目录）找 MSVC 的 vcvars64.bat，再调 cl 编译。
 * 找不到工具链就跳过，运行时合成功能自动降级。
 */
val compileWindowsPlaybackCompositor by tasks.registering {
    group = "build"
    description = "编译 Windows 端回放合成小程序（Media Foundation）"
    // 配置缓存不允许 doLast 捕获脚本对象，所以这里把要用到的路径抄成局部值，
    // 下面的 doLast 里只碰局部值与 Task 自己的成员。
    val sourcePath = playbackCompositorSource.absolutePath
    val exePath = playbackCompositorExe.get().asFile.absolutePath
    val buildDirPath = playbackCompositorBuildDir.get().asFile.absolutePath
    val resourcesDirPath = playbackCompositorResourcesDir.get().asFile.absolutePath
    inputs.file(playbackCompositorSource)
    outputs.file(playbackCompositorExe)
    doLast {
        val target = File(exePath)
        // 找 MSVC 的 vcvars64.bat：先用 vswhere，再扫常见安装位置。
        var vcvars: String? = null
        val vswhere = listOfNotNull(
            System.getenv("ProgramFiles(x86)")?.let { File(it, "Microsoft Visual Studio/Installer/vswhere.exe") },
            File("C:/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe"),
        ).firstOrNull { it.isFile }
        if (vswhere != null) {
            val process = ProcessBuilder(
                vswhere.absolutePath,
                "-latest",
                "-products", "*",
                "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                "-property", "installationPath",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            process.waitFor()
            vcvars = output.lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { File(it, "VC/Auxiliary/Build/vcvars64.bat") }
                .firstOrNull { it.isFile }
                ?.absolutePath
        }
        if (vcvars == null) {
            // 兜底：Build Tools 与各版本 VS 的默认安装位置。
            for (root in listOf(
                "C:/Program Files/Microsoft Visual Studio/2022",
                "C:/Program Files/Microsoft Visual Studio/2019",
                "C:/Program Files (x86)/Microsoft Visual Studio/2022",
                "C:/Program Files (x86)/Microsoft Visual Studio/2019",
                "C:/BuildTools",
            )) {
                val directory = File(root)
                if (!directory.isDirectory) continue
                val found = directory.listFiles()
                    ?.sortedByDescending { it.name }
                    ?.firstNotNullOfOrNull { edition ->
                        File(edition, "VC/Auxiliary/Build/vcvars64.bat").takeIf { it.isFile }?.absolutePath
                    }
                if (found != null) {
                    vcvars = found
                    break
                }
            }
        }
        if (vcvars == null) {
            logger.lifecycle("[windows] 找不到 Visual Studio C++ 工具链，跳过合成小程序编译（合成功能不可用）")
            target.delete()
            return@doLast
        }
        target.parentFile.mkdirs()
        // 资源目录里只应该留下 exe：上次编译残留的 .obj / .bat 会被打进 jar，先清掉。
        target.parentFile.listFiles()
            ?.filter { it.name != target.name }
            ?.forEach { it.delete() }
        // 旧版把中间产物写在资源目录下的 tmp/ 里，会被打进 jar；这里清掉残留。
        File(resourcesDirPath, "tmp").deleteRecursively()
        // 走 .bat 而不是拼 cmd 参数：vcvars 路径与源码路径都可能有空格，命令行转义太容易出错。
        // 脚本与 cl 的中间产物都放在 build/tmp 下，别混进要打进 jar 的资源目录。
        val scriptDirectory = File(buildDirPath)
        scriptDirectory.mkdirs()
        val script = File(scriptDirectory, "compile-playback-compositor.bat")
        script.writeText(
            """
            @echo off
            chcp 65001 >nul
            call "$vcvars" >nul
            if errorlevel 1 exit /b 1
            cd /d "${scriptDirectory.absolutePath}"
            cl /nologo /utf-8 /EHsc /std:c++17 /O2 /W3 /MT /DWINVER=0x0A00 /D_WIN32_WINNT=0x0A00 "$sourcePath" /Fe:"${target.absolutePath}" /link mfplat.lib mfreadwrite.lib mfuuid.lib propsys.lib ole32.lib /ENTRY:wmainCRTStartup
            """.trimIndent() + "\n",
            Charsets.UTF_8,
        )
        val process = ProcessBuilder("cmd.exe", "/d", "/c", script.absolutePath)
            .redirectErrorStream(true)
            .start()
        val log = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        if (code != 0 || !target.isFile) {
            throw GradleException("编译合成小程序失败（退出码 $code）：\n$log")
        }
        logger.lifecycle("[windows] 合成小程序已编译：${target.absolutePath}（${target.length()} 字节）")
    }
}

// 资源处理前先编出小程序，否则 jar 里不会有它。
tasks.matching { it.name == "processResources" || it.name.endsWith("ProcessResources") }.configureEach {
    dependsOn(compileWindowsPlaybackCompositor)
}

kotlin {
    jvm("windows") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    sourceSets {
        val windowsMain by getting {
            // 合成小程序编译产物以资源形式进 jar（native/BJTUPlaybackCompositor.exe），
            // 运行时再释放到 %LOCALAPPDATA%，这样不依赖 jpackage 的内部目录约定。
            resources.srcDir(playbackCompositorResourcesDir)
            // 只有 native/ 下的 exe 该进 jar：构建中间产物一律排除（防止再次污染安装包）。
            // 源码里的资源目录没有 .bat / .obj，这个排除不会误伤。
            resources.exclude("tmp/**", "**/*.obj", "**/*.bat")
            dependencies {
                implementation(project(":shared"))
                implementation(libs.compose.desktop.windows.x64)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.jna)
                implementation(libs.jna.platform)
                implementation(libs.ktor.client.cio)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }
        val windowsTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.serialization.json)
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// 安装包只面向 Windows x64：sqlite-jdbc/JNA 只保留 Windows x86_64 原生库。
extra["desktopNativeFilter.configuration"] = "windowsRuntimeClasspath"
extra["desktopNativeFilter.keep"] = listOf("org/sqlite/native/Windows/x86_64/", "com/sun/jna/win32-x86-64/")
apply(from = rootProject.file("gradle/desktop-native-filter.gradle.kts"))

compose.desktop {
    application {
        mainClass = "team.bjtuss.bjtuselfservice.windows.MainKt"
        // jpackage 只在完整 JDK 有，Android Studio/PyCharm JBR 没有；打包显式指向本机完整 JDK。
        javaHome = providers.environmentVariable("WINDOWS_PACKAGE_JAVA_HOME")
            .orElse("C:/Users/zjg/jdk21/jdk-21.0.8+9")
            .get()
        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED",
        )
        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            // jdk.charsets：AWT 原生菜单（托盘）在中文 Windows 上需要 GBK 编码器，缺了会显示方框。
            modules("java.sql", "jdk.charsets", "jdk.localedata")
            // 中文显示名。英文系统上 jpackage 默认选代码页 1252 的 en .wxl，WiX light 会把中文
            // 打成 `?????` 并报 311；CI 用 -Duser.language=zh 让它选代码页 936 的中文 .wxl。
            // WINDOWS_PACKAGE_NAME/… 仍可手动传 ASCII 兜底。
            packageName = windowsPackageDisplayName
            // jpackage 版本必须是三段数字。应用内显示版本为 1.8.6-KMP / Build 44；
            // 安装器使用数值版本 1.8.6，靠 main.wxs IncludeMaximum=yes 覆盖同一大版本。
            packageVersion = "1.8.6"
            description = windowsPackageDescription
            vendor = "BJTUselfService Contributors"
            windows {
                // 系统级标准位置：C:\Program Files\<packageName>。
                // 双击 MSI 由 msiexec 在启动时前台弹出 UAC（不要改 Burn EXE 清单，
                // 会毁掉 WiX 数据包）。EXE 仍可作备用，但推荐用 MSI。
                perUserInstall = false
                dirChooser = true
                menu = true
                shortcut = true
                menuGroup = windowsPackageDisplayName
                // 固定升级码，后续同范围安装才能覆盖升级。
                upgradeUuid = "8f3a1c2e-7b64-4d91-a5e0-2c9b6f4d8a17"
                iconFile.set(project.file("src/windowsMain/resources/BJTUselfServiceKMP.ico"))
            }
        }
    }
}

val realJavaHomePath = providers.environmentVariable("WINDOWS_PACKAGE_JAVA_HOME")
    .orElse("C:/Users/zjg/jdk21/jdk-21.0.8+9")
    .get()
    .trimEnd('/', '\\')
val jpackageOverridePath = file("packaging/jpackage").absolutePath
val shimCsPath = file("packaging/JpackageShim.cs").absolutePath
val cscPath = "C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe"
val shimHomePath = file("${layout.buildDirectory.get().asFile}/jpackage-shim").absolutePath
val jpackageResourcesPath = file("${layout.buildDirectory.get().asFile}/compose/tmp/resources").absolutePath

val compileJpackageShim by tasks.registering {
    inputs.file(shimCsPath)
    inputs.dir(jpackageOverridePath)
    outputs.dir(shimHomePath)
    val realJdk = realJavaHomePath
    val overrideDir = jpackageOverridePath
    val shimSource = shimCsPath
    val compiler = cscPath
    val outHome = shimHomePath
    doLast {
        val bin = File(outHome, "bin")
        bin.mkdirs()
        check(File(compiler).isFile) { "找不到 csc.exe：$compiler" }
        val compile = ProcessBuilder(
            compiler,
            "/nologo",
            "/target:exe",
            "/out:${File(bin, "jpackage.exe").absolutePath}",
            shimSource,
        ).inheritIO().start()
        check(compile.waitFor() == 0) { "编译 jpackage shim 失败" }
        File(bin, "shim.config").writeText(
            "real=$realJdk/bin/jpackage.exe\noverride=$overrideDir\n",
            Charsets.UTF_8,
        )
    }
}

afterEvaluate {
    listOf("packageExe", "packageMsi").forEach { taskName ->
        tasks.named<AbstractJPackageTask>(taskName) {
            dependsOn(compileJpackageShim)
            // compose.desktop 也会写 javaHome，必须在 afterEvaluate 再盖回去。
            javaHome.set(shimHomePath)
            val overrideWxs = File(jpackageOverridePath, "main.wxs")
            val resourcesDir = jpackageResourcesPath
            doFirst {
                val dest = File(resourcesDir)
                dest.mkdirs()
                overrideWxs.copyTo(File(dest, "main.wxs"), overwrite = true)
            }
            if (taskName == "packageMsi") {
                // 配置缓存不能序列化脚本对象：路径在配置阶段算好，执行阶段只用纯值和 ProcessBuilder。
                val rewriteScript = file("packaging/rewrite-msi-product-code.ps1").absolutePath
                // 传目录而不是写死版本号的文件名，脚本会取目录里的 MSI。
                val msiPath = layout.buildDirectory.dir("compose/binaries/main/msi")
                    .get().asFile.absolutePath
                doLast {
                    val process = ProcessBuilder(
                        "powershell.exe",
                        "-NoProfile",
                        "-ExecutionPolicy", "Bypass",
                        "-File", rewriteScript,
                        msiPath,
                    ).redirectErrorStream(true).start()
                    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
                    check(process.waitFor() == 0) { "改写 MSI ProductCode 失败：$output" }
                }
            }
        }
    }
}
