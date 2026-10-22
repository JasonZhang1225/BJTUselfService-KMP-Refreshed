package team.bjtuss.bjtuselfservice.windows

import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WindowsRemoteSiteAccessTest {
    @Test fun platformSiteBindingIsEncryptedAndClearedWithItsCredential() = runBlocking {
        val path = "/team/bjtuss/tests/site-access-${UUID.randomUUID()}"
        val store = WindowsRemoteSecretStore(path)
        val node = Preferences.userRoot().node(path)
        try {
            store.savePlatformAccess("fictional-platform-access")
            store.savePlatformAccessSiteUrl("https://fictional.example")
            assertEquals("fictional-platform-access", store.loadPlatformAccess())
            assertEquals("https://fictional.example", store.loadPlatformAccessSiteUrl())
            assertNotEquals("https://fictional.example", node.get("platform_site", null))
            store.clear()
            assertNull(store.loadPlatformAccess())
            assertNull(store.loadPlatformAccessSiteUrl())
        } finally { node.removeNode(); Preferences.userRoot().flush() }
    }
}
