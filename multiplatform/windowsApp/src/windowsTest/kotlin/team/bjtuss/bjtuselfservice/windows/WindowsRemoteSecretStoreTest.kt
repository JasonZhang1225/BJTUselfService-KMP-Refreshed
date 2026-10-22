package team.bjtuss.bjtuselfservice.windows

import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/** 只在 Windows 上有意义（DPAPI）；使用独立的测试节点，不读写用户真实的远程令牌。 */
class WindowsRemoteSecretStoreTest {
    private val path = "/team/bjtuss/bjtuselfservice/kmp/test-remote-${System.nanoTime()}"

    @AfterTest
    fun cleanUp() {
        runCatching { Preferences.userRoot().node(path).removeNode() }
    }

    @Test
    fun `device token and platform access round trip through dpapi`() = runBlocking<Unit> {
        if (!System.getProperty("os.name").startsWith("Windows")) return@runBlocking
        val store = WindowsRemoteSecretStore(path)
        store.saveDeviceToken("bjtud_test_token_value_0123456789")
        store.savePlatformAccess("platform-access-for-test")
        assertEquals("bjtud_test_token_value_0123456789", store.loadDeviceToken())
        assertEquals("platform-access-for-test", store.loadPlatformAccess())
        val raw = Preferences.userRoot().node(path).get("device_token", "")
        assertFalse("bjtud_test" in raw, "偏好里只有 DPAPI 密文")
        store.savePlatformAccess(null)
        assertNull(store.loadPlatformAccess())
        store.clear()
        assertNull(store.loadDeviceToken())
    }
}
