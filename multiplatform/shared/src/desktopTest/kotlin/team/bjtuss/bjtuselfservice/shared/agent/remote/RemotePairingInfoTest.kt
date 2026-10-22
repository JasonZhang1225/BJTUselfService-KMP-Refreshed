package team.bjtuss.bjtuselfservice.shared.agent.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemotePairingInfoTest {
    private val value = REMOTE_PAIRING_INFO_PREFIX + """{"siteUrl":"https://site.example","pairingCode":"ABCD2345","platformAccess":"fixture-access","expiresAt":1800000600}"""

    @Test fun acceptsOwnerGeneratedInformationForItsExactSite() {
        val info = parseRemotePairingInfo(value, "https://site.example", 1800000000)
        assertEquals("ABCD2345", info.pairingCode)
        assertEquals("fixture-access", info.platformAccess)
    }

    @Test fun rejectsOtherSitesExpiredOrInvalidSecretsBeforeNetworkAccess() {
        assertFailsWith<IllegalArgumentException> { parseRemotePairingInfo(value, "https://other.example", 1800000000) }
        assertFailsWith<IllegalArgumentException> { parseRemotePairingInfo(value, "https://site.example", 1800000600) }
        assertFailsWith<IllegalArgumentException> { parseRemotePairingInfo(value.replace("fixture-access", "bad access"), "https://site.example", 1800000000) }
    }
}
