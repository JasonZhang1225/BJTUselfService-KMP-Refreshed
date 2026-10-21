package team.bjtuss.bjtuselfservice.shared.system

import java.io.File
import java.util.prefs.Preferences
import team.bjtuss.bjtuselfservice.shared.feature.settings.AppStartupHost

/** 开机启动项附带的参数：表示这次是登录时自动启动，不是用户手动打开。 */
const val BACKGROUND_LAUNCH_ARGUMENT = "--background"

/** 静默启动偏好（非机密）：存当前用户的 Java Preferences，启动时在读缓存前就能取到。默认开启。 */
class DesktopStartupPreferences(nodePath: String = "/team/bjtuss/bjtuselfservice/kmp/startup") {
    private val node = Preferences.userRoot().node(nodePath)
    fun silentStart(): Boolean = node.getBoolean(SILENT_KEY, true)
    fun setSilentStart(enabled: Boolean) {
        node.putBoolean(SILENT_KEY, enabled)
        runCatching { node.flush() }
    }

    private companion object {
        const val SILENT_KEY = "silent_start"
    }
}

/**
 * 是否以隐藏窗口启动：只有开机自动启动（带 [BACKGROUND_LAUNCH_ARGUMENT]）且用户开了静默启动，
 * 并且有办法重新打开窗口（Windows 托盘、macOS 程序坞）时才隐藏。用户手动打开时总是显示窗口。
 */
fun shouldStartHidden(args: Array<String>, silentStart: Boolean, canReopen: Boolean): Boolean =
    BACKGROUND_LAUNCH_ARGUMENT in args && silentStart && canReopen

/**
 * macOS 登录项：写 `~/Library/LaunchAgents` 里的用户级 LaunchAgent，登录时用 `open` 启动 App 包。
 * macOS 13 起它出现在“系统设置 → 通用 → 登录项与扩展”的“允许在后台”里，用户可随时关闭；
 * 关闭开关时删除该文件。只支持安装版（能定位到 .app 包），源码运行不提供。
 */
class MacLaunchAgentStartupHost(
    private val home: File = File(System.getProperty("user.home")),
    appExecutable: String? = System.getProperty("jpackage.app-path"),
    private val preferences: DesktopStartupPreferences = DesktopStartupPreferences(),
    private val openSettings: () -> Boolean = {
        runCatching {
            ProcessBuilder("/usr/bin/open", "x-apple.systempreferences:com.apple.LoginItems-Settings.extension")
                .start().waitFor() == 0
        }.getOrDefault(false)
    },
) : AppStartupHost {
    /** `.../交大自由行 KMP.app/Contents/MacOS/BJTUselfServiceKMP` → `.../交大自由行 KMP.app`。 */
    private val bundle: File? = appExecutable?.let(::File)?.parentFile?.parentFile?.parentFile
        ?.takeIf { it.name.endsWith(".app") && it.isDirectory }

    private val plist: File get() = File(home, "Library/LaunchAgents/$LABEL.plist")

    override val autostartSupported: Boolean get() = bundle != null
    override val unsupportedReason = "只有安装到“应用程序”的正式版支持开机启动，当前运行环境无法设置。"
    override val systemManageHint = "“系统设置 → 通用 → 登录项与扩展”"
    override val silentStartDetail = "开机启动时不打开窗口，在后台运行；点程序坞图标即可打开。"

    override fun isAutostartEnabled(): Boolean {
        val path = bundle?.absolutePath ?: return false
        return runCatching { plist.isFile && plist.readText().contains(xmlEscape(path)) }.getOrDefault(false)
    }

    override fun setAutostart(enabled: Boolean): Boolean {
        val path = bundle?.absolutePath ?: return false
        return runCatching {
            if (enabled) {
                plist.parentFile.mkdirs()
                plist.writeText(launchAgentPlist(path))
            } else if (plist.isFile) {
                plist.delete()
            }
            isAutostartEnabled() == enabled
        }.getOrDefault(false)
    }

    override fun isSilentStart(): Boolean = preferences.silentStart()
    override fun setSilentStart(enabled: Boolean) = preferences.setSilentStart(enabled)
    override fun openSystemSettings(): Boolean = openSettings()

    companion object {
        const val LABEL = "team.bjtuss.bjtuselfservice.kmp.autostart"

        internal fun launchAgentPlist(bundlePath: String): String = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>Label</key>
              <string>$LABEL</string>
              <key>ProgramArguments</key>
              <array>
                <string>/usr/bin/open</string>
                <string>-a</string>
                <string>${xmlEscape(bundlePath)}</string>
                <string>--args</string>
                <string>$BACKGROUND_LAUNCH_ARGUMENT</string>
              </array>
              <key>RunAtLoad</key>
              <true/>
              <key>LimitLoadToSessionType</key>
              <string>Aqua</string>
            </dict>
            </plist>
        """.trimIndent() + "\n"

        private fun xmlEscape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
