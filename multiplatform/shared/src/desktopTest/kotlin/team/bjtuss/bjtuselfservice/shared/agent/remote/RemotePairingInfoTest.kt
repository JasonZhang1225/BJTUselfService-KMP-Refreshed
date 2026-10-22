package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemotePairingInfoTest {
    private val value = REMOTE_PAIRING_INFO_PREFIX + """{"siteUrl":"https://site.example","pairingCode":"ABCD2345","platformAccess":"fixture-access","expiresAt":1800000600}"""

    @Test fun acceptsOwnerGeneratedInformationForItsExactSite() {
        val info = parseRemotePairingInfo(value, 1800000000)
        assertEquals("https://site.example", info.siteUrl)
        assertEquals("ABCD2345", info.pairingCode)
        assertEquals("fixture-access", info.platformAccess)
    }

    @Test fun rejectsMissingPrefixShortCodeAndIncompleteInformation() {
        for (invalid in listOf("ABCD2345", value.removePrefix(REMOTE_PAIRING_INFO_PREFIX), REMOTE_PAIRING_INFO_PREFIX + "{}", value.replace("siteUrl", "missingUrl"))) {
            kotlin.test.assertFails { parseRemotePairingInfo(invalid, 1800000000) }
        }
    }

    @Test fun rejectsInsecureSitesExpiredOrInvalidSecretsBeforeNetworkAccess() {
        assertFailsWith<IllegalArgumentException> { parseRemotePairingInfo(value.replace("https://site.example", "http://site.example"), 1800000000) }
        assertFailsWith<IllegalArgumentException> { parseRemotePairingInfo(value, 1800000600) }
        assertFailsWith<IllegalArgumentException> { parseRemotePairingInfo(value.replace("fixture-access", "bad access"), 1800000000) }
    }
}
