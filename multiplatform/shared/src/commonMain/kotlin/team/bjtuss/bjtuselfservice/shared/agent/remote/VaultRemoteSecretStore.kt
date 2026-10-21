package team.bjtuss.bjtuselfservice.shared.agent.remote

import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

/**
 * 手机/平板的远程密钥：复用系统安全存储（Android Keystore、Apple Keychain）的凭据保险库，
 * 每项一个独立条目（[vaultFor] 按名字给出），值放在密码字段。不进 SQLite 或普通偏好。
 */
class VaultRemoteSecretStore(private val vaultFor: (String) -> CredentialVault) : RemoteSecretStore {
    private suspend fun read(key: String): String? = vaultFor(key).load()?.password
    private suspend fun write(key: String, value: String?) {
        val vault = vaultFor(key)
        if (value.isNullOrEmpty()) vault.clear() else vault.save(Credentials(key, value))
    }

    override suspend fun loadDeviceToken(): String? = read(DEVICE_TOKEN)
    override suspend fun saveDeviceToken(token: String) = write(DEVICE_TOKEN, token)
    override suspend fun loadPlatformAccess(): String? = read(PLATFORM_ACCESS)
    override suspend fun savePlatformAccess(value: String?) = write(PLATFORM_ACCESS, value)
    override suspend fun loadPlatformAccessSiteUrl(): String? = read(PLATFORM_SITE)
    override suspend fun savePlatformAccessSiteUrl(siteUrl: String?) = write(PLATFORM_SITE, siteUrl)
    override suspend fun clear() {
        write(DEVICE_TOKEN, null)
        write(PLATFORM_ACCESS, null)
        write(PLATFORM_SITE, null)
    }

    private companion object {
        const val DEVICE_TOKEN = "device_token"
        const val PLATFORM_ACCESS = "platform_access"
        const val PLATFORM_SITE = "platform_site"
    }
}
