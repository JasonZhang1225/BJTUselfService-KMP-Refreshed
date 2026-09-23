package team.bjtuss.bjtuselfservice.windows

import java.util.Base64
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsCacheKeyTest {
    @Test
    fun cacheKeyRoundTripsThroughDpapiWithoutRawKeyInPreferences() {
        val node = Preferences.userRoot().node(
            "/team/bjtuss/bjtuselfservice/kmp/test-cache-${System.nanoTime()}",
        )
        try {
            val created = loadOrCreateWindowsCacheKey(node)
            assertTrue(created.created)
            assertTrue(created.bytes.size == 32)

            val stored = assertNotNull(node.get("cache_key_payload", null))
            assertFalse(stored == Base64.getEncoder().encodeToString(created.bytes))

            val reopened = loadOrCreateWindowsCacheKey(node)
            assertFalse(reopened.created)
            assertContentEquals(created.bytes, reopened.bytes)
            created.bytes.fill(0)
            reopened.bytes.fill(0)
        } finally {
            val parent = node.parent()
            node.removeNode()
            parent.flush()
        }
    }
}
