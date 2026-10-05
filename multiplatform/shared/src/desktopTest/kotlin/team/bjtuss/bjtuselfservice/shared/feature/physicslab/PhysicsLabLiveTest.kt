package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.network.KtorSchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.schoolHttpEngineFactory

/** Opt-in campus read-only probe. Credential values and server HTML are never emitted. */
class PhysicsLabLiveTest {
    @Test fun liveProtectedEnrollmentQuery() = runBlocking {
        val filename = System.getenv("PHYSICS_LAB_TEST_ENV_PATH") ?: return@runBlocking
        val values = File(filename).readLines().filter { '=' in it && !it.startsWith('#') }
            .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim().trim('\'', '"') }
        val credentials = Credentials(assertNotNull(values["PHYLAB_USERNAME"]), assertNotNull(values["PHYLAB_PASSWORD"]))
        val transport = KtorSchoolHttpTransport(schoolHttpEngineFactory())
        try {
            val labs = withTimeout(30_000) { PhysicsLabRemote(transport).fetch(credentials) }
            assertTrue(labs.all { it.period in 1..6 && it.weekCount in 1..2 && it.name.isNotBlank() })
            println("physicslab_read_only_verified=true count=${labs.size}")
        } finally { transport.close() }
    }
}
