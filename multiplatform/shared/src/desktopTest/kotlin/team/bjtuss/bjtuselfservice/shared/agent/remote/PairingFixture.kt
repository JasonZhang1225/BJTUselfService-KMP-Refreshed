package team.bjtuss.bjtuselfservice.shared.agent.remote

/** 虚构长码，与 Site /owner/connection 返回格式一致。 */
internal fun pairingFixture(site: String = "https://site.example", code: String = "ABCD2345", access: String = "fixture-access") =
    REMOTE_PAIRING_INFO_PREFIX + """{"siteUrl":"$site","pairingCode":"$code","platformAccess":"$access","expiresAt":1800000600}"""
