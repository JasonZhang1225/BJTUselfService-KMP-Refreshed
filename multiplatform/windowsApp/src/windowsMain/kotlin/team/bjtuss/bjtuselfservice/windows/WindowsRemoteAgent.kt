package team.bjtuss.bjtuselfservice.windows

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
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
    override suspend fun clear() {
        write(DEVICE_TOKEN_KEY, null)
        write(PLATFORM_ACCESS_KEY, null)
    }

    private companion object {
        const val DEVICE_TOKEN_KEY = "device_token"
        const val PLATFORM_ACCESS_KEY = "platform_access"
        val ENTROPY: ByteArray = "team.bjtuss.bjtuselfservice.kmp.remote.v1".encodeToByteArray()
    }
}

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

    override fun isAutostartEnabled(): Boolean {
        val exe = appExecutable ?: return false
        val output = reg("query", RUN_KEY, "/v", VALUE_NAME) ?: return false
        return exe in output && BACKGROUND_ARGUMENT in output
    }

    override fun setAutostart(enabled: Boolean): Boolean {
        val exe = appExecutable ?: return false
        return if (enabled) {
            reg("add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", "\"$exe\" $BACKGROUND_ARGUMENT", "/f") != null
        } else {
            reg("delete", RUN_KEY, "/v", VALUE_NAME, "/f") != null || !isAutostartEnabled()
        }
    }

    /** 调用系统 reg.exe（参数逐个传入，不经 shell）；失败返回 null。 */
    private fun reg(vararg args: String): String? = runCatching {
        val process = ProcessBuilder(listOf("reg.exe") + args).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            return null
        }
        output.takeIf { process.exitValue() == 0 }
    }.getOrNull()

    private companion object {
        const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
        const val VALUE_NAME = "BJTUselfServiceKMP"
    }
}
