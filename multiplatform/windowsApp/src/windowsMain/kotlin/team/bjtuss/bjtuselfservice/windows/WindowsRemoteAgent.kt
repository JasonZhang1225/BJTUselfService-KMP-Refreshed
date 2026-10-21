package team.bjtuss.bjtuselfservice.windows

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.io.File
import java.util.Base64
import java.util.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteHostPlatform
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteSecretStore
import team.bjtuss.bjtuselfservice.shared.feature.settings.AppStartupHost
import team.bjtuss.bjtuselfservice.shared.system.BACKGROUND_LAUNCH_ARGUMENT
import team.bjtuss.bjtuselfservice.shared.system.DesktopStartupPreferences


/**
 * 远程设备令牌与平台访问材料：DPAPI 加密（绑定当前 Windows 用户）后存用户偏好，
 * 不写普通设置、源码、命令参数或日志。
 */
internal class WindowsRemoteSecretStore(
    nodePath: String = "/team/bjtuss/bjtuselfservice/kmp/remote",
) : RemoteSecretStore {
    private val node = Preferences.userRoot().node(nodePath)

    private suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        val payload = node.get(key, null) ?: return@withContext null
        String(Dpapi.cryptUnprotect(Base64.getDecoder().decode(payload), ENTROPY), Charsets.UTF_8)
    }

    private suspend fun write(key: String, value: String?) = withContext(Dispatchers.IO) {
        if (value == null) node.remove(key)
        else node.put(key, Base64.getEncoder().encodeToString(Dpapi.cryptProtect(value.toByteArray(Charsets.UTF_8), ENTROPY)))
        node.flush()
    }

    override suspend fun loadDeviceToken(): String? = read(DEVICE_TOKEN_KEY)
    override suspend fun saveDeviceToken(token: String) = write(DEVICE_TOKEN_KEY, token)
    override suspend fun loadPlatformAccess(): String? = read(PLATFORM_ACCESS_KEY)
    override suspend fun savePlatformAccess(value: String?) = write(PLATFORM_ACCESS_KEY, value)
    override suspend fun loadPlatformAccessSiteUrl(): String? = read(PLATFORM_SITE_KEY)
    override suspend fun savePlatformAccessSiteUrl(siteUrl: String?) = write(PLATFORM_SITE_KEY, siteUrl)
    override suspend fun clear() {
        write(DEVICE_TOKEN_KEY, null)
        write(PLATFORM_ACCESS_KEY, null)
        write(PLATFORM_SITE_KEY, null)
    }

    private companion object {
        const val DEVICE_TOKEN_KEY = "device_token"
        const val PLATFORM_ACCESS_KEY = "platform_access"
        const val PLATFORM_SITE_KEY = "platform_site"
        val ENTROPY: ByteArray = "team.bjtuss.bjtuselfservice.kmp.remote.v1".encodeToByteArray()
    }
}

/** 启动项命令：带引号的可执行文件路径 + 后台参数。 */
internal fun autostartCommand(exe: String): String = "\"$exe\" $BACKGROUND_LAUNCH_ARGUMENT"

/**
 * StartupApproved 里的值是任务管理器“启动应用”的开关：首字节为偶数表示启用、奇数表示用户已禁用。
 * 值不存在视为启用。
 */
internal fun startupApprovedEnabled(value: ByteArray?): Boolean = value == null || value.isEmpty() || value[0].toInt() and 1 == 0

/** 远程常驻的设备信息与托盘能力。 */
internal class WindowsRemoteHost : RemoteHostPlatform {
    override val deviceName: String =
        (System.getenv("COMPUTERNAME")?.takeIf(String::isNotBlank)?.let { "$it（Windows）" } ?: "Windows 电脑").take(64)

    override val traySupported: Boolean get() = java.awt.SystemTray.isSupported()
}

/**
 * 开机启动：写当前用户的 `HKCU\...\Run`（不需要管理员，也不是服务），以用户自己的登录会话运行，
 * 才能读取其 DPAPI 数据。它会出现在任务管理器“启动应用”里，用户在那里禁用后
 * （写入 `StartupApproved\Run`），这里如实显示为关闭；只有用户在 App 里重新打开时才恢复启用。
 * 直接调用注册表 API（Unicode），不经 reg.exe：安装路径含中文，reg.exe 的输出是系统代码页。
 */
internal class WindowsStartupHost(
    private val appExecutable: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile },
    private val preferences: DesktopStartupPreferences = DesktopStartupPreferences(),
) : AppStartupHost {
    override val autostartSupported: Boolean get() = appExecutable != null
    override val unsupportedReason = "只有安装版支持开机启动，当前运行环境无法设置。"
    override val systemManageHint = "“任务管理器 → 启动应用”"
    override val silentStartDetail = "开机启动时不弹出窗口，只在托盘中运行；双击托盘图标即可打开。"

    override fun isAutostartEnabled(): Boolean {
        val exe = appExecutable ?: return false
        return runCatching {
            val registered = Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME) &&
                Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME) == autostartCommand(exe)
            registered && startupApprovedEnabled(approvedValue())
        }.getOrDefault(false)
    }

    override fun setAutostart(enabled: Boolean): Boolean {
        val exe = appExecutable ?: return false
        return runCatching {
            if (enabled) {
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, autostartCommand(exe))
                // 用户在 App 里明确打开：清掉任务管理器里的“已禁用”标记。
                if (approvedValue() != null) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, VALUE_NAME)
            } else {
                if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                    Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
                }
                if (approvedValue() != null) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, VALUE_NAME)
            }
            isAutostartEnabled() == enabled
        }.getOrDefault(false)
    }

    override fun isSilentStart(): Boolean = preferences.silentStart()
    override fun setSilentStart(enabled: Boolean) = preferences.setSilentStart(enabled)

    override fun openSystemSettings(): Boolean = runCatching {
        // Windows 11 的“设置 → 应用 → 启动”；与任务管理器“启动应用”是同一份开关。
        ProcessBuilder("explorer.exe", "ms-settings:startupapps").start()
        true
    }.getOrDefault(false)

    private fun approvedValue(): ByteArray? =
        if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, APPROVED_KEY) ||
            !Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, VALUE_NAME)) null
        else Advapi32Util.registryGetBinaryValue(WinReg.HKEY_CURRENT_USER, APPROVED_KEY, VALUE_NAME)

    private companion object {
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val APPROVED_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run"
        const val VALUE_NAME = "BJTUselfServiceKMP"
    }
}
