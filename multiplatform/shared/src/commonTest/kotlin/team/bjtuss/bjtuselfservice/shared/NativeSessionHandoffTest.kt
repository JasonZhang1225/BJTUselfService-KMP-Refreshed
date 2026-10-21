package team.bjtuss.bjtuselfservice.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NativeSessionHandoffTest {
    @Test
    fun delayedOldHostDisposalCannotClearTheNewHostSession() {
        val handoff = NativeSessionHandoff<Any>()
        val oldHost = Any()
        val newHost = Any()
        val currentSession = Any()
        handoff.update(oldHost, Any())
        assertTrue(handoff.update(newHost, currentSession))

        assertFalse(handoff.update(oldHost, null))
        assertSame(currentSession, handoff.session)
        // onDispose 与 onDestroy 都会清理，重复清理同样不能影响新宿主。
        assertFalse(handoff.update(oldHost, null))
        assertSame(currentSession, handoff.session)
    }

    @Test
    fun transferringTheSameSessionStillTransfersCleanupOwnership() {
        val handoff = NativeSessionHandoff<Any>()
        val oldHost = Any()
        val newHost = Any()
        val session = Any()
        assertTrue(handoff.update(oldHost, session))
        assertFalse(handoff.update(newHost, session))
        assertFalse(handoff.update(oldHost, null))
        assertSame(session, handoff.session)

        assertTrue(handoff.update(newHost, null))
        assertNull(handoff.session)
    }

    @Test
    fun currentHostLogoutClearsSessionAndRepeatedPublicationIsIdempotent() {
        val handoff = NativeSessionHandoff<Any>()
        val owner = Any()
        val session = Any()
        assertFalse(handoff.update(owner, null))
        assertTrue(handoff.update(owner, session))
        assertFalse(handoff.update(owner, session))
        assertSame(session, handoff.session)
        assertTrue(handoff.update(owner, null))
        assertNull(handoff.session)
        assertFalse(handoff.update(owner, null))
    }

    @Test
    fun liveHostCanRepublishBeforeNavigationAfterItsEarlierCleanup() {
        val handoff = NativeSessionHandoff<Any>()
        val owner = Any()
        val session = Any()
        handoff.update(owner, session)
        handoff.update(owner, null)

        assertTrue(handoff.update(owner, session))
        assertSame(session, handoff.session)
    }
}
