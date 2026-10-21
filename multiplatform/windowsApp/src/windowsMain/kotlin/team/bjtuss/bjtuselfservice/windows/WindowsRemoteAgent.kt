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

/** 用后台模式启动（开机启动项使用）：不显示主窗口，常驻任务栏通知区域。 */
internal const val BACKGROUND_ARGUMENT = "--background"

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
internal fun autostartCommand(exe: String): String = "\"$exe\" $BACKGROUND_ARGUMENT"

/**
 * 开机启动：当前用户的 `HKCU\...\Run`（不需要管理员，也不是 LocalSystem 服务），
 * 以用户自己的登录会话运行，才能读取其 DPAPI 数据。默认关闭，只在用户打开开关时写入。
 */
internal class WindowsRemoteHost(
    private val appExecutable: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile },
) : RemoteHostPlatform {
    override val deviceName: String =
        (System.getenv("COMPUTERNAME")?.takeIf(String::isNotBlank)?.let { "$it（Windows）" } ?: "Windows 电脑").take(64)

    override val autostartSupported: Boolean get() = appExecutable != null
    override val traySupported: Boolean get() = java.awt.SystemTray.isSupported()

    override fun isAutostartEnabled(): Boolean {
        val exe = appExecutable ?: return false
        val value = runCatching {
            if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) null
            else Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }.getOrNull() ?: return false
        return value == autostartCommand(exe)
    }

    /**
     * 直接调用注册表 API（Unicode），不经 reg.exe：安装路径含中文（“交大自由行 KMP”），
     * reg.exe 的输出是系统代码页、参数里的引号也会被 Java 的命令行拼接打乱，导致写入后校验失败。
     */
    override fun setAutostart(enabled: Boolean): Boolean {
        val exe = appExecutable ?: return false
        return runCatching {
            if (enabled) {
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, autostartCommand(exe))
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
            }
            isAutostartEnabled() == enabled
        }.getOrDefault(false)
    }

    private companion object {
        const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE_NAME = "BJTUselfServiceKMP"
    }
}
