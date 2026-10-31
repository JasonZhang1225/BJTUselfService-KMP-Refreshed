import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.File
import javax.inject.Inject

/** jpackage 的 --name / .app 文件名必须是 ASCII；用户看见的名字用这个中文。 */
val desktopPackageName = "BJTUselfServiceKMP"
val desktopPackageVersion = "2.0.0"
val macDisplayName = "交大自由行 KMP"

fun resolveDesktopPackageJavaHome(): String {
    val candidates = listOfNotNull(
        System.getenv("DESKTOP_PACKAGE_JAVA_HOME")?.trim()?.takeIf(String::isNotEmpty),
        System.getenv("JAVA_HOME")?.trim()?.takeIf(String::isNotEmpty),
        // Android Studio 的 Gradle 守护进程通常没有 JAVA_HOME（用自带 JBR），
        // 但 IDE 同步也会配置本模块；缺这一项会让整仓同步直接失败。打包时若该
        // JDK 没有 jpackage，会由 jpackage 任务自己报错。
        System.getProperty("java.home"),
        "/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home",
    )
    return candidates.firstOrNull { File(it).isDirectory }
        ?: error("Set DESKTOP_PACKAGE_JAVA_HOME or JAVA_HOME to a full JDK that contains jpackage")
}

@DisableCachingByDefault(because = "Invokes the Swift compiler")
abstract class CompileMacCalendarHelper : DefaultTask() {
    @get:InputFile
    abstract val swiftSource: RegularFileProperty

    /** 额外 Swift 源文件（例如 VideoFrame 的 compose-indexed）。 */
    @get:InputFiles
    @get:Optional
    abstract val extraSwiftSources: ConfigurableFileCollection

    @get:OutputFile
    abstract val executable: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val developerDirectory: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val output = executable.get().asFile
        output.parentFile.mkdirs()
        val sources = buildList {
            add(swiftSource.get().asFile.absolutePath)
            extraSwiftSources.files.forEach { add(it.absolutePath) }
        }
        execOperations.exec {
            developerDirectory.orNull?.let { environment("DEVELOPER_DIR", it) }
            commandLine(
                buildList {
                    addAll(listOf(
                        "xcrun", "swiftc", "-O", "-parse-as-library",
                        "-target", "arm64-apple-macos12.0",
                        "-framework", "AppKit",
                        "-framework", "EventKit",
                        "-framework", "Foundation",
                        "-framework", "AVFoundation",
                        "-framework", "VideoToolbox",
                        "-framework", "CoreMedia",
                        "-framework", "CoreVideo",
                        "-framework", "CoreImage",
                    ))
                    addAll(sources)
                    addAll(listOf("-o", output.absolutePath))
                }
            )
        }.assertNormalExitValue()
    }
}

@DisableCachingByDefault(because = "Invokes Apple's Clang compiler")
abstract class CompileMacInputSourceHelper : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputFile
    abstract val library: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val developerDirectory: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val output = library.get().asFile
        output.parentFile.mkdirs()
        execOperations.exec {
            developerDirectory.orNull?.let { environment("DEVELOPER_DIR", it) }
            commandLine(
                "xcrun",
                "clang",
                "-O2",
                "-dynamiclib",
                "-fblocks",
                "-fobjc-arc",
                "-target",
                "arm64-apple-macos12.0",
                "-framework",
                "AppKit",
                "-framework",
                "Foundation",
                source.get().asFile.absolutePath,
                "-o",
                output.absolutePath,
            )
        }.assertNormalExitValue()
    }
}

@DisableCachingByDefault(because = "Mutates and re-signs the packaged macOS app bundle")
abstract class FinalizeMacDistributable : DefaultTask() {
    /** 本地验证过、由新 SDK 重编译的原始 jpackage 启动器；未指定时不替换 jpackage 输出。 */
    @get:Optional
    @get:InputFile
    abstract val sdkLauncher: RegularFileProperty

    @get:InputFile
    abstract val privacyManifest: RegularFileProperty

    @get:Internal
    abstract val appBundle: DirectoryProperty

    @get:InputFile
    abstract val inputSourceHelper: RegularFileProperty

    @get:InputFile
    abstract val calendarHelper: RegularFileProperty

    @get:InputFile
    abstract val videoFrameHelper: RegularFileProperty

    @get:Input
    abstract val displayName: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun finalizeBundle() {
        val bundle = appBundle.get().asFile
        sdkLauncher.orNull?.asFile?.let { launcher ->
            val target = bundle.resolve("Contents/MacOS/BJTUselfServiceKMP")
            check(target.isFile) { "Missing jpackage app launcher: $target" }
            launcher.copyTo(target, overwrite = true)
            check(target.setExecutable(true, false)) { "Cannot make launcher executable: $target" }
        }
        val resourcesDirectory = bundle.resolve("Contents/Resources")
        resourcesDirectory.mkdirs()
        privacyManifest.get().asFile.copyTo(
            resourcesDirectory.resolve("PrivacyInfo.xcprivacy"),
            overwrite = true,
        )
        // Remove assets left by an incremental build from before model removal.
        resourcesDirectory.resolve("Captcha").deleteRecursively()
        val inputSourceDirectory = resourcesDirectory.resolve("InputSource")
        inputSourceDirectory.deleteRecursively()
        inputSourceDirectory.mkdirs()
        inputSourceHelper.get().asFile.copyTo(
            inputSourceDirectory.resolve("libBJTUInputSourceHelper.dylib"),
            overwrite = true,
        )
        val calendarDirectory = resourcesDirectory.resolve("Calendar")
        calendarDirectory.deleteRecursively()
        calendarDirectory.mkdirs()
        val calendarHelperTarget = calendarDirectory.resolve("BJTUCalendarHelper")
        calendarHelper.get().asFile.copyTo(calendarHelperTarget, overwrite = true)
        calendarHelperTarget.setExecutable(true, false)
        val videoFrameDirectory = resourcesDirectory.resolve("VideoFrame")
        videoFrameDirectory.deleteRecursively()
        videoFrameDirectory.mkdirs()
        val videoFrameHelperTarget = videoFrameDirectory.resolve("BJTUVideoFrameHelper")
        videoFrameHelper.get().asFile.copyTo(videoFrameHelperTarget, overwrite = true)
        videoFrameHelperTarget.setExecutable(true, false)
        // App Store 导出合规：仅使用系统 HTTPS/Keychain，无非豁免加密。
        // createDistributable 可能为 UP-TO-DATE，因此收尾任务必须可重复执行。
        val encryptionKey = ":ITSAppUsesNonExemptEncryption"
        val infoPlist = bundle.resolve("Contents/Info.plist").absolutePath
        fun setOrAddPlistString(key: String, value: String) {
            val setResult = execOperations.exec {
                commandLine(
                    "/usr/libexec/PlistBuddy",
                    "-c",
                    "Set :$key $value",
                    infoPlist,
                )
                isIgnoreExitValue = true
            }
            if (setResult.exitValue != 0) {
                execOperations.exec {
                    commandLine(
                        "/usr/libexec/PlistBuddy",
                        "-c",
                        "Add :$key string $value",
                        infoPlist,
                    )
                }.assertNormalExitValue()
            }
        }
        fun setOrAddPlistBool(key: String, value: Boolean) {
            val setResult = execOperations.exec {
                commandLine(
                    "/usr/libexec/PlistBuddy",
                    "-c",
                    "Set :$key $value",
                    infoPlist,
                )
                isIgnoreExitValue = true
            }
            if (setResult.exitValue != 0) {
                execOperations.exec {
                    commandLine(
                        "/usr/libexec/PlistBuddy",
                        "-c",
                        "Add :$key bool $value",
                        infoPlist,
                    )
                }.assertNormalExitValue()
            }
        }
        // 菜单栏 / Dock / Launchpad 用这两个键。jpackage 的 packageName 仍走 ASCII，
        // 避免可执行文件名和非 ASCII --name 把打包打坏；用户看见的名字在收尾阶段改成中文。
        val visibleName = displayName.get()
        setOrAddPlistString("CFBundleName", visibleName)
        setOrAddPlistString("CFBundleDisplayName", visibleName)
        setOrAddPlistBool("LSHasLocalizedDisplayName", true)
        val localizedNames = """
            |"CFBundleName" = "$visibleName";
            |"CFBundleDisplayName" = "$visibleName";
            |""".trimMargin()
        listOf("zh-Hans.lproj", "zh_CN.lproj").forEach { locale ->
            val localeDirectory = resourcesDirectory.resolve(locale)
            localeDirectory.mkdirs()
            localeDirectory.resolve("InfoPlist.strings").writeText(localizedNames)
        }
        setOrAddPlistString(
            "NSCalendarsFullAccessUsageDescription",
            "用于创建本学期课表、选课课表和单场考试日历，并更新同一日程。",
        )
        setOrAddPlistString(
            "NSCalendarsUsageDescription",
            "用于把你主动选择的课程表或单场考试加入系统日历。",
        )
        setOrAddPlistBool("ITSAppUsesNonExemptEncryption", false)

        execOperations.exec {
            commandLine(
                "codesign",
                "--force",
                "--deep",
                "--sign",
                "-",
                bundle.absolutePath,
            )
        }.assertNormalExitValue()
    }
}

@DisableCachingByDefault(because = "Rewrites the packaged DMG volume name, app filename, and icons")
abstract class FinalizeMacDmg : DefaultTask() {
    @get:Internal
    abstract val dmgFile: RegularFileProperty

    @get:InputFile
    abstract val volumeIcon: RegularFileProperty

    @get:Input
    abstract val volumeName: Property<String>

    @get:Input
    abstract val sourceAppFileName: Property<String>

    @get:Input
    abstract val displayAppFileName: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun finalizeDmg() {
        val sourceDmg = dmgFile.get().asFile
        check(sourceDmg.isFile) { "DMG not found: $sourceDmg" }
        val work = temporaryDir
        work.deleteRecursively()
        work.mkdirs()
        val rwDmg = File(work, "rw.dmg")
        val mountPoint = File(work, "mnt")
        val convertedDmg = File(work, "final.dmg")
        mountPoint.mkdirs()

        execOperations.exec {
            commandLine(
                "hdiutil",
                "convert",
                sourceDmg.absolutePath,
                "-format",
                "UDRW",
                "-o",
                rwDmg.absolutePath,
            )
        }.assertNormalExitValue()

        execOperations.exec {
            commandLine(
                "hdiutil",
                "attach",
                rwDmg.absolutePath,
                "-readwrite",
                "-nobrowse",
                "-mountpoint",
                mountPoint.absolutePath,
            )
        }.assertNormalExitValue()

        try {
            val oldApp = mountPoint.resolve(sourceAppFileName.get())
            val newApp = mountPoint.resolve(displayAppFileName.get())
            when {
                oldApp.exists() -> {
                    if (newApp.exists()) newApp.deleteRecursively()
                    check(oldApp.renameTo(newApp)) { "failed to rename $oldApp to $newApp" }
                }
                newApp.exists() -> logger.lifecycle("DMG app already named ${newApp.name}")
                else -> error("neither $oldApp nor $newApp exists in the DMG")
            }

            val volumeIconFile = mountPoint.resolve(".VolumeIcon.icns")
            if (volumeIconFile.exists()) {
                volumeIconFile.setWritable(true)
            }
            volumeIcon.get().asFile.copyTo(volumeIconFile, overwrite = true)
            execOperations.exec {
                commandLine("xcrun", "SetFile", "-a", "C", mountPoint.absolutePath)
            }.assertNormalExitValue()

            val visibleName = volumeName.get()
            val infoPlist = newApp.resolve("Contents/Info.plist")
            execOperations.exec {
                commandLine(
                    "/usr/libexec/PlistBuddy",
                    "-c",
                    "Set :CFBundleName $visibleName",
                    infoPlist.absolutePath,
                )
                isIgnoreExitValue = true
            }
            execOperations.exec {
                commandLine(
                    "/usr/libexec/PlistBuddy",
                    "-c",
                    "Set :CFBundleDisplayName $visibleName",
                    infoPlist.absolutePath,
                )
                isIgnoreExitValue = true
            }
            val localizedNames = """
                |"CFBundleName" = "$visibleName";
                |"CFBundleDisplayName" = "$visibleName";
                |""".trimMargin()
            val resourcesDirectory = newApp.resolve("Contents/Resources")
            listOf("zh-Hans.lproj", "zh_CN.lproj").forEach { locale ->
                val localeDirectory = resourcesDirectory.resolve(locale)
                localeDirectory.mkdirs()
                localeDirectory.resolve("InfoPlist.strings").writeText(localizedNames)
            }

            execOperations.exec {
                commandLine(
                    "codesign",
                    "--force",
                    "--deep",
                    "--sign",
                    "-",
                    newApp.absolutePath,
                )
            }.assertNormalExitValue()

            mountPoint.resolve(".DS_Store").delete()
            val layoutScript = File(work, "layout.applescript")
            layoutScript.writeText(
                """
                tell application "Finder"
                  try
                    set targetFolder to (POSIX file "${mountPoint.absolutePath}" as alias)
                    open targetFolder
                    delay 0.4
                    set theWin to container window of targetFolder
                    set current view of theWin to icon view
                    set toolbar visible of theWin to false
                    set statusbar visible of theWin to false
                    set bounds of theWin to {400, 140, 1000, 520}
                    set opts to icon view options of theWin
                    set arrangement of opts to not arranged
                    set icon size of opts to 128
                    try
                      set background picture of opts to file ".background:background.tiff" of targetFolder
                    end try
                    set position of item "${displayAppFileName.get()}" of targetFolder to {160, 200}
                    set position of item "Applications" of targetFolder to {480, 200}
                    close theWin
                  end try
                end tell
                """.trimIndent(),
            )
            execOperations.exec {
                commandLine("osascript", layoutScript.absolutePath)
                isIgnoreExitValue = true
            }

            execOperations.exec {
                commandLine("diskutil", "rename", mountPoint.absolutePath, volumeName.get())
            }.assertNormalExitValue()
        } finally {
            execOperations.exec {
                commandLine("hdiutil", "detach", mountPoint.absolutePath, "-force")
                isIgnoreExitValue = true
            }
        }

        execOperations.exec {
            commandLine(
                "hdiutil",
                "convert",
                rwDmg.absolutePath,
                "-format",
                "UDZO",
                "-imagekey",
                "zlib-level=9",
                "-o",
                convertedDmg.absolutePath,
            )
        }.assertNormalExitValue()

        convertedDmg.copyTo(sourceDmg, overwrite = true)
        applyFinderIcon(sourceDmg, volumeIcon.get().asFile)
    }

    private fun applyFinderIcon(target: File, icon: File) {
        val script = File(temporaryDir, "set-icon.applescript")
        script.writeText(
            """
            use framework "Foundation"
            use framework "AppKit"
            set sourceImage to current application's NSImage's alloc()'s initWithContentsOfFile:"${icon.absolutePath}"
            if sourceImage is missing value then error "failed to load icon ${icon.absolutePath}"
            -- Finder 自定义文件图标不应使用满幅方形 app icon；保留透明留白并裁出圆角。
            set canvas to current application's NSImage's alloc()'s initWithSize:(current application's NSMakeSize(512, 512))
            canvas's lockFocus()
            current application's NSColor's clearColor()'s |set|()
            set iconRect to current application's NSMakeRect(64, 64, 384, 384)
            set clipPath to current application's NSBezierPath's bezierPathWithRoundedRect_xRadius_yRadius_(iconRect, 84.0, 84.0)
            clipPath's addClip()
            sourceImage's drawInRect_fromRect_operation_fraction_(iconRect, current application's NSZeroRect, current application's NSCompositingOperationSourceOver, 1.0)
            canvas's unlockFocus()
            set ok to current application's NSWorkspace's sharedWorkspace()'s setIcon:canvas forFile:"${target.absolutePath}" options:0
            if (ok as boolean) is false then error "NSWorkspace setIcon failed"
            """.trimIndent(),
        )
        execOperations.exec {
            commandLine("osascript", script.absolutePath)
        }.assertNormalExitValue()
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.compose.desktop.macos.arm64)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
}

// 应用只打 Apple Silicon 包：sqlite-jdbc/JNA 只保留 macOS arm64 原生库。
extra["desktopNativeFilter.configuration"] = "runtimeClasspath"
extra["desktopNativeFilter.keep"] = listOf("org/sqlite/native/Mac/aarch64/", "com/sun/jna/darwin-aarch64/")
apply(from = rootProject.file("gradle/desktop-native-filter.gradle.kts"))

compose.desktop {
    application {
        mainClass = "team.bjtuss.bjtuselfservice.desktop.MainKt"
        // jpackage 只在完整 JDK 有，Android Studio JBR 没有；运行时检测/打包单独指向系统完整 JDK，
        // 与守护进程 JDK 解耦（Kotlin 编译仍在 JBR 下跑，避免 KGP 在 JDK 25 下崩溃）。
        // 本机默认 Temurin 25；CI / 其他机器走 DESKTOP_PACKAGE_JAVA_HOME 或 JAVA_HOME。
        javaHome = resolveDesktopPackageJavaHome()
        val inputSourceHelperFile =
            layout.buildDirectory.file("generated/input-source/libBJTUInputSourceHelper.dylib")
        val calendarHelperFile = layout.buildDirectory.file("generated/calendar/BJTUCalendarHelper")
        val videoFrameHelperFile = layout.buildDirectory.file("generated/video-frame/BJTUVideoFrameHelper")
        jvmArgs += listOf(
            "--enable-native-access=ALL-UNNAMED",
            // `run` 直接启动 JVM，不经过打包后的 Info.plist；显式设置后 Dock
            // 在开发运行时也与正式 .app 使用同一个中文名称。
            "-Xdock:name=$macDisplayName",
            // 标题栏跟随系统外观（深色模式时标题栏不再保持白色）。
            "-Dapple.awt.application.appearance=system",
            "-Dbjtu.input-source.helper=${inputSourceHelperFile.get().asFile.absolutePath}",
            "-Dbjtu.calendar.helper=${calendarHelperFile.get().asFile.absolutePath}",
            "-Dbjtu.video-frame.helper=${videoFrameHelperFile.get().asFile.absolutePath}",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            modules("java.sql")
            packageName = desktopPackageName
            // Compose Desktop 的 packageVersion 仅允许 MAJOR.MINOR.PATCH；应用内版本 2.0.0-Alpha / Build 45。
            packageVersion = desktopPackageVersion
            description = "交大自由行 Kotlin Multiplatform macOS 应用"
            vendor = "BJTUselfService Contributors"
            macOS {
                iconFile.set(project.file("src/main/resources/BJTUselfServiceKMP-v2.icns"))
                bundleID = "team.bjtuss.bjtuselfservice.kmp.macos"
                // 菜单栏 / Dock / About·Hide·Quit；DMG 里的 .app 文件名由 FinalizeMacDmg 改成同一中文。
                dockName = macDisplayName
                appCategory = "public.app-category.education"
                minimumSystemVersion = "12.0"
                packageBuildVersion = "45"
            }
        }
    }
}


val macPrivacyManifest = layout.projectDirectory.file(
    "src/main/appResources/macos/PrivacyInfo.xcprivacy",
)
val calendarSwiftSource = project.file("src/main/swift/SystemCalendarHelper.swift")
val inputSourceHelperSource = project.file("src/main/native/InputSourceHelper.m")
val inputSourceHelperFile =
    layout.buildDirectory.file("generated/input-source/libBJTUInputSourceHelper.dylib")
val calendarHelperFile = layout.buildDirectory.file("generated/calendar/BJTUCalendarHelper")
val videoFrameSwiftSource = project.file("src/main/swift/VideoFrameHelper.swift")
val videoFrameHelperFile = layout.buildDirectory.file("generated/video-frame/BJTUVideoFrameHelper")

// 不改全局 xcode-select：显式环境优先，否则项目局部选择完整 Xcode。
val xcodeDeveloperDirectory = sequenceOf(
    System.getenv("DEVELOPER_DIR"),
    "/Applications/Xcode-beta.app/Contents/Developer",
    "/Applications/Xcode.app/Contents/Developer",
).filterNotNull().firstOrNull { candidate -> File(candidate).isDirectory }

val compileMacCalendarHelper by tasks.registering(CompileMacCalendarHelper::class) {
    swiftSource.set(calendarSwiftSource)
    executable.set(calendarHelperFile)
    xcodeDeveloperDirectory?.let(developerDirectory::set)
}

// 课程回放画面预览用的截帧小程序；与日历助手同一个 swiftc 任务类型（Swift 会自动链接 AVFoundation 等框架）。
val compileMacVideoFrameHelper by tasks.registering(CompileMacCalendarHelper::class) {
    swiftSource.set(videoFrameSwiftSource)
    extraSwiftSources.from(project.file("src/main/swift/IndexedSideBySideCompose.swift"))
    executable.set(videoFrameHelperFile)
    xcodeDeveloperDirectory?.let(developerDirectory::set)
}

val compileMacInputSourceHelper by tasks.registering(CompileMacInputSourceHelper::class) {
    source.set(inputSourceHelperSource)
    library.set(inputSourceHelperFile)
    xcodeDeveloperDirectory?.let(developerDirectory::set)
}

// Compose Desktop's appResourcesRootDir is a JVM runtime resource directory
// (Contents/app/resources), not Apple's required Contents/Resources location.
// Until formal Developer ID signing is configured, finish the local app image by
// copying the manifest to the bundle root and renewing its existing ad-hoc seal.
val finalizeMacDistributable by tasks.registering(FinalizeMacDistributable::class) {
    dependsOn(compileMacInputSourceHelper, compileMacCalendarHelper, compileMacVideoFrameHelper)
    privacyManifest.set(macPrivacyManifest)
    inputSourceHelper.set(inputSourceHelperFile)
    calendarHelper.set(calendarHelperFile)
    videoFrameHelper.set(videoFrameHelperFile)
    displayName.set(macDisplayName)
    // 显式选择启动器，避免普通打包依赖本机试验产物或静默提升最低系统要求。
    providers.gradleProperty("macosSdkLauncher").orNull?.let { sdkLauncher.set(file(it)) }
    appBundle.set(
        layout.buildDirectory.dir("compose/binaries/main/app/$desktopPackageName.app"),
    )
}

val finalizeMacDmg by tasks.registering(FinalizeMacDmg::class) {
    dependsOn(tasks.named("packageDmg"))
    dmgFile.set(
        layout.buildDirectory.file(
            "compose/binaries/main/dmg/$desktopPackageName-$desktopPackageVersion.dmg",
        ),
    )
    // DMG volume/file icon: installer artwork; keep the app icon unchanged.
    volumeIcon.set(project.file("src/main/resources/BJTUselfServiceKMP-installer-volume-v2.icns"))
    volumeName.set(macDisplayName)
    sourceAppFileName.set("$desktopPackageName.app")
    displayAppFileName.set("$macDisplayName.app")
}

tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(finalizeMacDistributable)
}

// packageDmg 只 dependsOn createDistributable，不保证等 finalizedBy 跑完；显式挂上，避免 Info.plist 中文名还没写进就打 DMG。
tasks.matching { it.name == "packageDmg" }.configureEach {
    dependsOn(finalizeMacDistributable)
    finalizedBy(finalizeMacDmg)
}

tasks.matching { it.name == "run" }.configureEach {
    dependsOn(compileMacInputSourceHelper, compileMacCalendarHelper, compileMacVideoFrameHelper)
}
