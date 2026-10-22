package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlinx.serialization.Serializable

const val REMOTE_PAIRING_INFO_PREFIX = "bjtuss-pair-v1:"

/** 只用于用户从已登录 Site 复制到 App；不得记录、持久化到普通设置或自动发送。 */
@Serializable
internal class RemotePairingInfo(
    val siteUrl: String,
    val pairingCode: String,
    val platformAccess: String,
    val expiresAt: Long,
)

internal fun parseRemotePairingInfo(value: String, siteUrl: String, now: Long): RemotePairingInfo {
    require(value.length <= 16_384)
    val info = remoteJson.decodeFromString(RemotePairingInfo.serializer(), value.removePrefix(REMOTE_PAIRING_INFO_PREFIX))
    require(normalizeSiteUrl(info.siteUrl) == siteUrl)
    require(Regex("^[A-Z2-9]{8}$").matches(info.pairingCode))
    require(info.expiresAt > now)
    remotePlatformAuthorization(info.platformAccess)
    return info
}
