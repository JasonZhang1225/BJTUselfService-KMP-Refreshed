package team.bjtuss.bjtuselfservice.shared.runtime

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import java.nio.file.Files
import javax.swing.SwingUtilities
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.App
import team.bjtuss.bjtuselfservice.shared.cache.CacheStoreHandle
import team.bjtuss.bjtuselfservice.shared.cache.CacheOpenState
import team.bjtuss.bjtuselfservice.shared.auth.LoginState
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.auth.UnavailableCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.calendar.UnavailableSystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsRecordingDownloader
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryAccess
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskManager
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTaskStore
import team.bjtuss.bjtuselfservice.shared.feature.courseware.StoredPlaybackTask
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import team.bjtuss.bjtuselfservice.shared.feature.shell.rememberShellData
import team.bjtuss.bjtuselfservice.shared.feature.shell.ShellData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.files.FileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult
import team.bjtuss.bjtuselfservice.shared.files.UnavailableDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.UnavailableFileGateway
import team.bjtuss.bjtuselfservice.shared.media.PlaybackPlatformHooks
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault
import team.bjtuss.bjtuselfservice.shared.security.AccountPreferences
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.cleanup.*
import team.bjtuss.bjtuselfservice.shared.agent.remote.*

class AppRuntimeTest {
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun removingAndRemountingLoginBindingKeepsTheRuntimeAccountAlive() = SwingUtilities.invokeAndWait {
        withFixture {
            val fixture = this
            val visible = mutableStateOf(true)
            val entryLoggingIn = mutableStateOf(true)
            val preferences = mutableStateOf(AppPreferences())
            val profile = mutableStateOf(profile("fixture-a"))
            var published: AuthenticatedSession? = null
            val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
                if (visible.value) {
                    published = fixture.bindPresentation(profile.value, preferences.value, entryLoggingIn.value)
                }
            }
            var frame = 0L
            fun settle() { repeat(5) { scene.render(++frame * 16_000_000L).close() } }
            try {
                settle()
                val account = runtime.currentAccount!!
                val presentation = published!!
                assertTrue(presentation.entryLoggingIn)

                entryLoggingIn.value = false
                requestHandler = { awaitCancellation() }
                preferences.value = AppPreferences(dynamicColor = false)
                profile.value = profile.value.copy(name = "恢复后的测试名称")
                settle()
                assertSame(presentation, published)
                assertSame(account.graph, runtime.currentAccount!!.graph)
                assertFalse(presentation.entryLoggingIn)
                assertEquals(preferences.value, presentation.loginSyncPreferences)
                assertEquals(profile.value, presentation.profile)

                visible.value = false
                settle()
                assertFalse(account.isClosed)
                assertFalse(account.graph.refreshCoordinator.isClosed)
                assertSame(presentation, runtime.session.value)

                visible.value = true
                settle()
                assertSame(presentation, published)
                assertSame(account, runtime.currentAccount)
                assertTrue(networkCalls > 0, "Login completion starts account work without a page host")
            } finally { scene.close() }

            val account = runtime.currentAccount!!
            assertFalse(account.isClosed)
            runtime.close()
            assertTrue(account.isClosed)
            assertTrue(account.graph.refreshCoordinator.isClosed)
        }
    }

    @Test
    fun sameAccountRetainsGraphAndPresentationWhileBindingsUpdate() = withFixture {
        requestHandler = { awaitCancellation() }
        val first = bind("fixture-a", entryLoggingIn = true)
        val presentation = first.presentation
        presentation.classroomIntroBannerDismissed = true
        var savedByLatestBinding = false
        val updatedProfile = profile("fixture-a").copy(name = "更新后的测试名称")
        val preferences = AppPreferences(dynamicColor = false)

        val rebound = bind("fixture-a", entryLoggingIn = false, preferences = preferences)
        rebound.update(updatedProfile, false, Credentials("fixture-a", "updated-fixture-password"), preferences,
            onPreferencesChanged = { savedByLatestBinding = true; true }, onLogout = {}, onPurgeLogout = {})

        assertSame(first, rebound)
        assertSame(first.graph, rebound.graph)
        assertSame(presentation, runtime.session.value)
        assertSame(presentation.libraryModel, rebound.presentation.libraryModel)
        assertEquals(updatedProfile, presentation.profile)
        assertEquals(preferences, presentation.loginSyncPreferences)
        assertFalse(presentation.entryLoggingIn)
        assertTrue(presentation.classroomIntroBannerDismissed)
        presentation.settingsModel.setDynamicColor(false)
        assertTrue(savedByLatestBinding)
        assertTrue(networkCalls > 0, "Completed login starts its automatic batch")
    }

    @Test
    fun switchingAccountClosesOldResourcesAndIgnoresOldLogoutAndAuthentication() = runBlocking {
        withFixture {
            var oldLogoutCalls = 0
            val first = bind("fixture-a", onLogout = { oldLogoutCalls++ })
            val second = bind("fixture-b")

            assertTrue(first.isClosed)
            assertTrue(first.graph.refreshCoordinator.isClosed)
            assertNotSame(first.presentation, second.presentation)
            assertNotSame(first.presentation.libraryModel, second.presentation.libraryModel)
            assertSame(second.presentation, runtime.session.value)
            first.presentation.onLogout()
            assertFalse(first.presentation.probeSession!!.invoke())
            assertFalse(first.presentation.reauthenticateSession!!.invoke())
            assertEquals(0, oldLogoutCalls)
            assertEquals(0, networkCalls)
            assertFalse(second.isClosed)
        }
    }

    @Test
    fun reloginOfSameAccountHasNewIdentityAndCannotBeEndedByOldSession() = withFixture {
        val first = bind("fixture-a")
        assertTrue(runtime.endAccount(first))
        assertNull(runtime.session.value)
        val second = bind("fixture-a")

        assertTrue(second.id > first.id)
        assertNotSame(first.presentation, second.presentation)
        assertFalse(runtime.endAccount(first))
        first.presentation.onLogout()
        assertSame(second.presentation, runtime.session.value)
        assertFalse(second.isClosed)
    }

    @Test
    fun logoutUsesLatestCallbackAndClosesOnlyItsAccount() = withFixture {
        var initialCalls = 0
        var latestCalls = 0
        val account = bind("fixture-a", onLogout = { initialCalls++ })
        account.update(profile("fixture-a"), true, Credentials("fixture-a", "fixture-password"), AppPreferences(),
            onPreferencesChanged = { true },
            onLogout = { latestCalls++; runtime.endAccount(account) }, onPurgeLogout = {})

        account.presentation.onLogout()
        account.presentation.onLogout()

        assertEquals(0, initialCalls)
        assertEquals(1, latestCalls)
        assertNull(runtime.session.value)
        assertTrue(account.isClosed)
        assertTrue(account.graph.refreshCoordinator.isClosed)
        assertEquals(0, networkCalls)
    }

    @Test
    fun lateEndTransitionCannotResetALaterLoginOfTheSameAccount() = withFixture {
        val failedAccount = bind("fixture-a")
        var transitions = 0
        val delayedDismiss = { runtime.endAccount(failedAccount) { transitions++ } }
        assertTrue(delayedDismiss())
        assertEquals(1, transitions)
        val next = bind("fixture-a")

        assertFalse(delayedDismiss())
        assertEquals(1, transitions)
        assertSame(next.presentation, runtime.session.value)
        assertFalse(next.isClosed)
    }

    @Test
    fun runtimeCloseIsIdempotentAndRejectsNewAccountCreation() = withFixture {
        val account = bind("fixture-a")
        runtime.close()
        runtime.close()

        assertTrue(account.isClosed)
        assertTrue(account.graph.refreshCoordinator.isClosed)
        assertNull(runtime.session.value)
        assertFailsWith<IllegalStateException> { bind("fixture-b") }
        assertEquals(0, networkCalls)
    }

    @Test
    fun failedReplacementLeavesNoPublishedOldSessionAndBlankScopeDoesNotReleaseIt() = withFixture {
        val account = bind("fixture-a")
        assertFailsWith<IllegalArgumentException> {
            runtime.obtainAccount("  ") { error("Must not create a blank account") }
        }
        assertSame(account.presentation, runtime.session.value)
        assertFalse(account.isClosed)

        assertFailsWith<IllegalStateException> {
            runtime.obtainAccount("fixture-b") { error("Fixture construction failure") }
        }
        assertTrue(account.isClosed)
        assertNull(runtime.session.value)
        assertNull(runtime.currentAccount)
        assertSame(bind("fixture-b").presentation, runtime.session.value)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun hostRootRecreationRetainsLoginWorkAndDoesNotPublishLogout() = SwingUtilities.invokeAndWait {
        withFixture {
            val fixture = this
            requestHandler = { awaitCancellation() }
            lateinit var account: AccountSession
            val login = runtime.loginContext(securityStore)
            val loginWork = CompletableDeferred<Unit>()
            var starts = 0
            var finished = false
            login.startOnce { starts++; loginWork.await(); finished = true }
            login.protocol.value // The fake transport is reused, never a new network session.
            login.storageReady.value = true
            bind("fixture-a", entryLoggingIn = false)
            login.state.value = LoginState.SignedIn(profile("fixture-a"))
            login.username.value = "fixture-a"
            login.password.value = "fixture-password"
            val visible = mutableStateOf(true)
            val publications = mutableListOf<AuthenticatedSession?>()
            val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
                if (visible.value) fixture.hostApp { publications += it }
            }
            var frame = 0L
            fun settle() { repeat(5) { scene.render(++frame * 16_000_000L).close() } }
            try {
                settle()
                account = checkNotNull(runtime.currentAccount)
                assertTrue(publications.isNotEmpty())
                assertSame(account.presentation, publications.last())
                visible.value = false
                settle()
                assertFalse(account.isClosed)
                assertFalse(finished)
                assertSame(account.presentation, publications.last())
                visible.value = true
                settle()
                assertSame(account, runtime.currentAccount)
                assertSame(login, runtime.loginContext(securityStore))
                assertEquals(LoginState.SignedIn(profile("fixture-a")), login.state.value)
                assertEquals(1, starts)
                assertTrue(publications.none { it == null })
                loginWork.complete(Unit)
                assertTrue(finished)
            } finally { scene.close() }
            assertFalse(account.isClosed)
            assertEquals(0, transportCloses)
            runBlocking { runtime.closeAndJoin() }
            assertTrue(account.isClosed)
            assertEquals(1, transportCloses)
            assertEquals("", login.password.value)
            assertTrue(networkCalls > 0, "Restored signed-in account starts automatic work")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun credentialRestoreContinuesAcrossRootReconstructionAndRunsOnlyOnce() = SwingUtilities.invokeAndWait {
        withFixture {
            val fixture = this
            val gate = CompletableDeferred<Boolean>()
            var restoreCalls = 0
            val store = AccountSecurityStore(object : CredentialVault {
                override suspend fun save(credentials: Credentials) = error("No credentials in this fixture")
                override suspend fun load(): Credentials? = error("Remember is disabled")
                override suspend fun clear() = error("Host reconstruction must not clear credentials")
            }, object : AccountPreferences {
                override suspend fun shouldRememberCredentials(): Boolean { restoreCalls++; return gate.await() }
                override suspend fun setShouldRememberCredentials(enabled: Boolean) = Unit
                override suspend fun clearRememberCredentialsSetting() = Unit
            })
            val visible = mutableStateOf(true)
            val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
                if (visible.value) fixture.hostApp(store) { }
            }
            var frame = 0L
            fun settle() { repeat(5) { scene.render(++frame * 16_000_000L).close() } }
            try {
                settle()
                assertEquals(1, restoreCalls)
                val login = runtime.loginContext(store)
                assertFalse(login.storageReady.value)
                visible.value = false
                settle()
                visible.value = true
                settle()
                assertEquals(1, restoreCalls)
                visible.value = false
                settle()
                gate.complete(false)
                assertTrue(login.storageReady.value)
                assertEquals(LoginState.SignedOut, login.state.value)
                assertEquals(0, networkCalls)
            } finally { scene.close() }
        }
    }

    @Test
    fun closingHostCancelsLoginAndDoesNotOpenAnUnusedTransport() = withFixture {
        val login = runtime.loginContext(securityStore)
        val pending = CompletableDeferred<Unit>()
        var cancelled = false
        login.startOnce { try { pending.await() } finally { cancelled = true } }
        runtime.close()
        assertTrue(cancelled)
        assertEquals(0, transportCloses)
        assertFailsWith<IllegalStateException> { runtime.loginContext(securityStore) }
    }

    @Test
    fun lateModelRequestCannotRestoreDataIntoANewLoginOfTheSameAccount() = withFixture { runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var finalized = false
        requestHandler = { request ->
            try {
                withContext(NonCancellable) { entered.complete(Unit); release.await() }
                SchoolHttpResponse(200, request.url, body = "fixture-response".encodeToByteArray())
            } finally { finalized = true }
        }
        val old = bind("fixture-a")
        val running = async(Dispatchers.Default) { runCatching { old.graph.gradeModel.refresh() } }
        try {
            entered.await()
            runtime.endAccount(old)
            val fresh = bind("fixture-a")
            fresh.graph.cacheStore.putMetadata("fixture-a", "incarnation", "fresh")
            val frozen = old.graph.gradeModel.state.value
            val stopped = async { old.graph.stopAndJoin() }
            assertFalse(stopped.isCompleted)
            assertFalse(finalized)
            release.complete(Unit)
            stopped.await(); running.await()
            assertTrue(finalized)
            assertSame(fresh, runtime.currentAccount)
            assertEquals(frozen, old.graph.gradeModel.state.value)
            assertEquals("fresh", cache.metadata("fixture-a", "incarnation"))
            assertFailsWith<kotlinx.coroutines.CancellationException> {
                old.graph.cacheStore.putMetadata("fixture-a", "incarnation", "late")
            }
        } finally { release.complete(Unit); running.join() }
    } }

    @Test
    fun logoutCallbackRunsOnlyAfterAccountFinalizersHaveFinished() = withFixture { runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finalizing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val notified = CompletableDeferred<Boolean>()
        var finalized = false
        val account = bind("fixture-a", onLogout = { notified.complete(finalized) })
        val running = launch(Dispatchers.Default) { account.graph.accountWork.run {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { finalizing.complete(Unit); finish.await(); finalized = true } }
        } }
        try {
            entered.await()
            account.presentation.onLogout()
            finalizing.await()
            assertFalse(notified.isCompleted)
            assertFalse(account.graph.accountWork.isActive)
            finish.complete(Unit)
            assertTrue(notified.await())
            running.join()
        } finally { finish.complete(Unit); running.join() }
    } }

    @Test
    fun runtimeKeepsPlatformResourcesUntilAccountDrainCompletes() = withFixture { runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val account = bind("fixture-a")
        var released = false
        val running = launch(Dispatchers.Default) { account.graph.accountWork.run {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { finish.await() } }
        } }
        try {
            entered.await()
            runtime.close { released = true }
            assertNull(runtime.session.value)
            assertFalse(released)
            finish.complete(Unit)
            runtime.closeAndJoin()
            assertTrue(released)
            running.join()
        } finally { finish.complete(Unit); running.join() }
    } }

    @Test
    fun cancellingEntryLoginPreventsItsLateCommitAndAllowsTheNextLogin() = withFixture { runBlocking {
        val login = runtime.loginContext(securityStore)
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val job = login.launch {
            withContext(NonCancellable) { entered.complete(Unit); response.await() }
            accountCommit { cache.putMetadata("fixture-a", "entry-profile", "late") }
            login.state.value = LoginState.SignedIn(profile("fixture-a"))
        }
        try {
            entered.await()
            val old = login.stopAuthentication()
            login.state.value = LoginState.SignedOut
            cache.clearAccount("fixture-a")
            val fresh = login.launch { accountCommit { cache.putMetadata("fixture-a", "entry-profile", "fresh") } }
            fresh.join()
            response.complete(Unit)
            old.join(); job.join()
            assertTrue(job.isCancelled)
            assertEquals("fresh", cache.metadata("fixture-a", "entry-profile"))
            assertEquals(LoginState.SignedOut, login.state.value)
        } finally { response.complete(Unit); login.stopAuthentication().join(); job.join() }
    } }

    @Test
    fun entryLoginSecureWriteFinishesBeforeLogoutClearsItsCredentials() = withFixture { runBlocking {
        val login = runtime.loginContext(securityStore)
        val account = bind("fixture-a")
        val saving = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var saved: String? = null
        val job = login.launch {
            withContext(NonCancellable) {
                saving.complete(Unit); finish.await(); saved = "fixture-only"
            }
        }
        try {
            saving.await()
            account.graph.close()
            val cleanup = async { account.graph.stopAndJoin(); saved = null }
            assertFalse(cleanup.isCompleted)
            finish.complete(Unit)
            cleanup.await(); job.join()
            assertNull(saved)
        } finally { finish.complete(Unit); login.stopAuthentication().join(); job.join() }
    } }

    @Test
    fun signoutCoordinatesAllLocalStoresAndKeepsOtherOwnersAndDevicePreferences() = withFixture { runBlocking {
        val account = bind("fixture-a")
        cache.putMetadata("fixture-a", "physicslab.enabled", "true")
        cache.putMetadata("fixture-a", "assignment.aggregate.filters", "local-filter")
        cache.putMetadata("fixture-b", "physicslab.enabled", "other-account")
        cache.savePreferences(AppPreferences(dynamicColor = false))
        val preferences = cache.preferences()
        val remote = RemoteAgentStore(cache)
        remote.saveConfig(RemoteAgentConfig(accountScope = "fixture-a", siteUrl = "https://fixture.invalid", deviceId = "fixture-device"))
        val report = cleanupService(account).execute(CleanupOperation.SIGN_OUT)
        assertTrue(report.succeeded)
        assertFalse(account.graph.accountWork.isActive)
        assertNull(cache.metadata("fixture-a", "physicslab.enabled"))
        assertNull(cache.metadata("fixture-a", "assignment.aggregate.filters"))
        assertEquals("other-account", cache.metadata("fixture-b", "physicslab.enabled"))
        assertEquals(preferences, cache.preferences())
        assertNull(remote.loadConfig().accountScope)
        assertNull(remote.loadConfig().deviceId)
        assertEquals(setOf("lab:fixture-a", "demo:fixture-a", "vlab:fixture-a", "vlab:fixture-a.moodle-session", "citel:fixture-a", "primary", "remote:device_token", "remote:platform_access", "remote:platform_site"), clearedVaults.toSet())
        assertEquals(listOf("temp", "presentation:false", "web"), platformCalls)
    } }

    @Test
    fun cacheClearKeepsLocalRecordsCredentialsAndAccountTasks() = withFixture { runBlocking {
        val account = bind("fixture-a")
        cache.putMetadata("fixture-a", "courseware_tree_v1", "refetchable")
        cache.putMetadata("fixture-a", "assignment.aggregate.filters", "keep-filter")
        cache.putMetadata("fixture-a", "physicslab.enabled", "true")
        val report = cleanupService(account).execute(CleanupOperation.ACCOUNT_CACHE)
        assertTrue(report.succeeded)
        assertTrue(account.graph.accountWork.isActive)
        assertNull(cache.metadata("fixture-a", "courseware_tree_v1"))
        assertEquals("keep-filter", cache.metadata("fixture-a", "assignment.aggregate.filters"))
        assertEquals("true", cache.metadata("fixture-a", "physicslab.enabled"))
        assertTrue(clearedVaults.isEmpty())
        assertTrue(platformCalls.isEmpty())
    } }

    @Test
    fun signoutFailureContinuesOtherVaultsAndDatabaseAndCanBeRetried() = withFixture { runBlocking {
        val account = bind("fixture-a")
        cache.putMetadata("fixture-a", "physicslab.enabled", "true")
        failingVault = "lab:fixture-a"
        val service = cleanupService(account)
        val failed = service.execute(CleanupOperation.SIGN_OUT)
        assertFalse(failed.succeeded)
        assertEquals(listOf("系统安全存储"), failed.failureLabels)
        assertTrue("demo:fixture-a" in clearedVaults)
        assertTrue("remote:platform_site" in clearedVaults)
        assertNull(cache.metadata("fixture-a", "physicslab.enabled"))
        failingVault = null
        assertTrue(service.execute(CleanupOperation.SIGN_OUT).succeeded)
    } }

    @Test
    fun signoutDoesNotRemoveAnotherAccountsRemoteBindingOrTokens() = withFixture { runBlocking {
        val account = bind("fixture-a")
        val remote = RemoteAgentStore(cache)
        val binding = RemoteAgentConfig(accountScope = "fixture-b", siteUrl = "https://fixture.invalid", deviceId = "fixture-b-device")
        remote.saveConfig(binding)
        assertTrue(cleanupService(account).execute(CleanupOperation.SIGN_OUT).succeeded)
        assertEquals(binding, remote.loadConfig())
        assertTrue(clearedVaults.none { it.startsWith("remote:") })
    } }

    @Test
    fun allLocalDataDiscoversMetadataOnlyOwnersAndResetsDeviceState() = withFixture { runBlocking {
        val account = bind("fixture-a")
        cache.putMetadata("fixture-b", "physicslab.enabled", "true")
        cache.savePreferences(AppPreferences(dynamicColor = false))
        assertTrue(cleanupService(account).execute(CleanupOperation.ALL_LOCAL_DATA).succeeded)
        assertTrue(cache.accountScopes().isEmpty())
        assertEquals(AppPreferences(), cache.preferences())
        assertTrue("lab:fixture-b" in clearedVaults)
        assertTrue("vlab:fixture-a.moodle-session" in clearedVaults)
        assertTrue("vlab:fixture-b.moodle-session" in clearedVaults)
        assertTrue("device:false" in platformCalls)
        assertTrue("presentation:true" in platformCalls)
    } }

    @Test
    fun successfulSignoutReturnsItsReportWithoutStagingItForLogout() = withFixture { runBlocking {
        requestHandler = { SchoolHttpResponse(200, it.url) }
        val account = bind("fixture-a")
        assertTrue(runtime.endAccount(account))

        val report = runtime.cleanup(account, CleanupOperation.SIGN_OUT)

        assertTrue(report.succeeded)
        assertSame(report, runtime.cleanupCoordinator.lastReport.value)
        assertNull(runtime.takeCompletedCleanup(account))
    } }

    @Test
    fun signoutRetryKeepsFailureReportingWithoutStagingEitherReport() = withFixture { runBlocking {
        requestHandler = { SchoolHttpResponse(200, it.url) }
        val account = bind("fixture-a")
        failingVault = "lab:fixture-a"
        assertTrue(runtime.endAccount(account))

        val report = runtime.cleanup(account, CleanupOperation.SIGN_OUT)
        assertTrue("vlab:fixture-a" in clearedVaults)

        assertEquals(listOf("系统安全存储"), report.failureLabels)
        assertNull(runtime.takeCompletedCleanup(account))
        failingVault = null
        assertTrue(assertNotNull(runtime.retryLastCleanup()).succeeded)
        assertNull(runtime.takeCompletedCleanup(account))
    } }

    @Test
    fun failedOldCleanupCannotBeRetriedAgainstNewLoginOfSameAccount() = withFixture { runBlocking {
        val old = bind("fixture-a")
        failingVault = "lab:fixture-a"
        val report = runtime.cleanup(old, CleanupOperation.SIGN_OUT)
        assertFalse(report.succeeded)
        runtime.endAccount(old)
        val fresh = bind("fixture-a")
        cache.putMetadata("fixture-a", "physicslab.enabled", "new-session")
        failingVault = null
        assertNull(runtime.retryLastCleanup())
        assertSame(fresh, runtime.currentAccount)
        assertEquals("new-session", cache.metadata("fixture-a", "physicslab.enabled"))
    } }

    @Test
    fun settingsWipeFailureStillLeavesRevokedSessionAndKeepsItsPartialReport() = withFixture { runBlocking {
        val account = bind("fixture-a")
        var purgedLogoutCalls = 0
        account.update(profile("fixture-a"), true, Credentials("fixture-a", "fixture-password"), AppPreferences(),
            onPreferencesChanged = { true }, onLogout = {}, onPurgeLogout = { purgedLogoutCalls++ })
        failingVault = "lab:fixture-a"
        cache.putMetadata("fixture-a", "physicslab.enabled", "true")
        account.presentation.settingsModel.clearAllLocalData()
        assertEquals(1, purgedLogoutCalls, "A revoked session cannot remain usable after partial local deletion")
        assertFalse(account.graph.accountWork.isActive)
        val report = runtime.takeCompletedCleanup(account)!!
        assertTrue(report.accountWorkStopped)
        assertFalse(report.succeeded)
        assertEquals(CleanupStatus.SUCCEEDED, report.items.single { it.step == CleanupStep.DATABASE }.status)
        assertTrue("系统安全存储" in report.failureLabels)
        assertNull(runtime.takeCompletedCleanup(account))
    } }

    @Test
    fun switchingAccountCancelsOldPhysicalOnlineAttemptBeforeItCanCommit() = withFixture { runBlocking {
        val old = bind("fixture-a")
        val model = old.graph.phyVlabModel
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var committed = false
        val attempt = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                model.runManualLogin {
                    try {
                        entered.complete(Unit)
                        withContext(NonCancellable) { release.await() }
                        model.submitManualLogin {
                            model.rememberManualSession("fixture-lab")
                            model.sessionChanged()
                            old.refreshService.refreshPhyVlabAfterAccountSaved()
                            committed = true
                        }
                    } finally {
                        finished.complete(Unit)
                    }
                }
            }
        }
        withTimeout(5_000) { entered.await() }
        val next = try {
            bind("fixture-b")
        } finally {
            release.complete(Unit)
        }
        withTimeout(5_000) {
            assertIs<CancellationException>(attempt.await().exceptionOrNull())
            finished.await()
        }
        assertFalse(committed)
        assertSame(next, runtime.currentAccount)
    } }

    @Test
    fun uninstallHandoffBelongsToTheSessionThatPreparedIt() = withFixture { runBlocking {
        requestHandler = { SchoolHttpResponse(200, it.url) }
        val old = bind("fixture-a")
        var purgedLogoutCalls = 0
        old.rebindCallbacks(
            onPreferencesChanged = { true },
            onLogout = {},
            onPurgeLogout = { purgedLogoutCalls++ },
        )
        old.presentation.settingsModel.prepareForUninstall()
        assertEquals(1, purgedLogoutCalls)
        val report = assertNotNull(runtime.cleanupCoordinator.lastReport.value)
        assertTrue(report.succeeded)
        assertTrue("vlab:fixture-a.moodle-session" in clearedVaults)
        assertEquals(CleanupOperation.PREPARE_UNINSTALL, report.request.operation)

        assertTrue(runtime.endAccount(old))
        val fresh = bind("fixture-a")
        assertNull(runtime.takeCompletedCleanup(fresh))
        assertSame(report, runtime.takeCompletedCleanup(old))
        assertNull(runtime.takeCompletedCleanup(old))
    } }

    @Test
    fun restartedRuntimeRecoversSignoutWithoutRecreatingAnyAccountOrRepeatingSuccessfulStores() = withFixture { runBlocking {
        bindCleanup()
        val account = bind("fixture-a")
        cache.putMetadata("fixture-a", "physicslab.enabled", "true")
        cache.putMetadata("fixture-b", "physicslab.enabled", "keep")
        failingVault = "lab:fixture-a"
        runtime.endAccount(account)
        assertFalse(runtime.cleanup(account, CleanupOperation.SIGN_OUT).localDataCleared)
        assertNotNull(journalPayload)
        val previouslyCleared = clearedVaults.toList()
        val calls = platformCalls.toList()
        failingVault = null
        val restarted = recoveryRuntime()
        try {
            val reports = restarted.recoverPendingCleanup()
            assertTrue(reports.single().succeeded)
            assertEquals(CleanupStatus.SKIPPED, reports.single().items.single { it.step == CleanupStep.SCHOOL_SESSION }.status)
            assertNull(restarted.currentAccount)
            assertNull(restarted.session.value)
            assertEquals(previouslyCleared + "lab:fixture-a", clearedVaults)
            assertEquals(calls, platformCalls)
            assertEquals("keep", cache.metadata("fixture-b", "physicslab.enabled"))
            assertNull(journalPayload)
            assertFalse(restarted.cleanupBlocksLogin)
        } finally { restarted.closeAndJoin() }
    } }

    @Test
    fun fullWipeRecoveryKeepsTheOwnerInventoryAfterDatabaseDeletion() = withFixture { runBlocking {
        bindCleanup()
        val account = bind("fixture-a")
        cache.putMetadata("fixture-a", "physicslab.enabled", "true")
        cache.putMetadata("fixture-b", "physicslab.enabled", "true")
        failingVault = "lab:fixture-b"
        runtime.endAccount(account)
        assertFalse(runtime.cleanup(account, CleanupOperation.ALL_LOCAL_DATA).localDataCleared)
        assertTrue(cache.accountScopes().isEmpty())
        val record = VaultCleanupJournal(journalVault).load().single()
        assertEquals(setOf("fixture-a", "fixture-b"), record.inventory.accounts)
        failingVault = null
        clearedVaults.clear()
        val restarted = recoveryRuntime()
        try {
            assertTrue(restarted.ensureCleanupReady())
            assertTrue("lab:fixture-b" in clearedVaults)
            assertTrue("primary" !in clearedVaults)
            assertNull(journalPayload)
            assertNull(restarted.session.value)
        } finally { restarted.closeAndJoin() }
    } }

    @Test
    fun failedRecoveryBlocksAuthenticationAndCanBeRetriedThroughTheSameRuntime() = withFixture { runBlocking {
        bindCleanup()
        val account = bind("fixture-a")
        failingVault = "lab:fixture-a"
        runtime.endAccount(account)
        runtime.cleanup(account, CleanupOperation.SIGN_OUT)
        val restarted = recoveryRuntime()
        try {
            val networkBefore = networkCalls
            assertFalse(restarted.ensureCleanupReady())
            assertTrue(restarted.cleanupBlocksLogin)
            assertEquals(networkBefore, networkCalls)
            failingVault = null
            assertTrue(restarted.retryLastCleanup()!!.succeeded)
            assertTrue(restarted.ensureCleanupReady())
            assertNull(journalPayload)
        } finally { restarted.closeAndJoin() }
    } }

    @Test
    fun recoveryNeverReplaysOldDeletionAgainstAnAlreadyPublishedNewSession() = withFixture { runBlocking {
        bindCleanup()
        val old = bind("fixture-a")
        failingVault = "lab:fixture-a"
        runtime.endAccount(old)
        runtime.cleanup(old, CleanupOperation.SIGN_OUT)
        val fresh = bind("fixture-a")
        cache.putMetadata("fixture-a", "physicslab.enabled", "fresh")
        val before = clearedVaults.toList()
        assertTrue(runtime.recoverPendingCleanup().isEmpty())
        assertNull(runtime.retryLastCleanup())
        assertEquals(before, clearedVaults)
        assertEquals("fresh", cache.metadata("fixture-a", "physicslab.enabled"))
        assertSame(fresh, runtime.currentAccount)
    } }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun startupWithPendingCleanupCannotRestoreCredentialsOrPublishCachedAccount() = SwingUtilities.invokeAndWait {
        withFixture {
            bindCleanup()
            val account = bind("fixture-a")
            failingVault = "lab:fixture-a"
            runBlocking { runtime.endAccount(account); runtime.cleanup(account, CleanupOperation.SIGN_OUT) }
            cache.saveCachedProfile(profile("fixture-a"))
            val restarted = recoveryRuntime()
            var credentialReads = 0
            val restoreStore = securityStore.copy(credentialVault = object : CredentialVault {
                override suspend fun load(): Credentials { credentialReads++; return Credentials("fixture-a", "fixture-password") }
                override suspend fun save(credentials: Credentials) = Unit
                override suspend fun clear() = Unit
            }, preferences = object : AccountPreferences {
                override suspend fun shouldRememberCredentials() = true
                override suspend fun setShouldRememberCredentials(enabled: Boolean) = Unit
                override suspend fun clearRememberCredentialsSetting() = Unit
            })
            val beforeNetwork = networkCalls
            val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
                App(appRuntime = restarted, accountSecurityStore = restoreStore,
                    cacheStoreHandle = CacheStoreHandle(cache, CacheOpenState.OPENED),
                    fileGateway = fileGatewayForCleanupTest(), playbackRecovery = playbackRecovery, nativeTabBarEnabled = true)
            }
            try {
                repeat(8) { scene.render(it * 16_000_000L).close() }
                assertTrue(restarted.cleanupBlocksLogin)
                assertEquals(0, credentialReads)
                assertEquals(beforeNetwork, networkCalls)
                assertNull(restarted.session.value)
            } finally { scene.close(); runBlocking { restarted.closeAndJoin() } }
        }
    }

    @Test
    fun logoutIntentIsDurableBeforeTheOldTaskFinalizerFinishes() = withFixture { runBlocking {
        bindCleanup()
        var logoutCalls = 0
        val account = bind("fixture-a", onLogout = { logoutCalls++ })
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val task = launch(Dispatchers.Default) {
            account.graph.accountWork.run {
                try { entered.complete(Unit); awaitCancellation() }
                finally { withContext(NonCancellable) { finish.await() } }
            }
        }
        try {
            entered.await()
            runtime.requestLogout(account)
            val pending = VaultCleanupJournal(journalVault).load().single()
            assertEquals(CleanupOperation.SIGN_OUT, pending.request.operation)
            assertEquals("fixture-a", pending.request.accountScope)
            assertEquals(0, logoutCalls)
            assertTrue(pending.items.isEmpty())
            finish.complete(Unit)
            task.join()
            kotlinx.coroutines.withTimeout(5000) { while (logoutCalls == 0) kotlinx.coroutines.yield() }
            assertEquals(1, logoutCalls)
        } finally { finish.complete(Unit); task.join() }
    } }

    @Test
    fun unavailableOriginalDatabaseCannotBeMistakenForSuccessfulCleanupOfATemporaryDatabase() = withFixture { runBlocking {
        bindCleanup()
        val account = bind("fixture-a")
        failingVault = "lab:fixture-a"
        runtime.endAccount(account)
        runtime.cleanup(account, CleanupOperation.SIGN_OUT)
        val record = VaultCleanupJournal(journalVault).load().single()
        VaultCleanupJournal(journalVault).save(listOf(record.copy(items = record.items.filterNot { it.step == CleanupStep.DATABASE })))
        val restarted = recoveryRuntime(cacheAvailable = false)
        failingVault = null
        try {
            assertFalse(restarted.ensureCleanupReady())
            assertEquals(CleanupStatus.FAILED, restarted.cleanupCoordinator.lastReport.value!!.items.single { it.step == CleanupStep.DATABASE }.status)
            assertNotNull(journalPayload)
            assertFalse(restarted.retryLastCleanup()!!.localDataCleared)
            assertTrue(restarted.cleanupBlocksLogin)
        } finally { restarted.closeAndJoin() }
    } }

    @Test
    fun oldUnpairRecoveryCannotDeleteTheTokensOrRecordsOfANewerRemoteBinding() = withFixture { runBlocking {
        bindCleanup()
        val newBinding = RemoteAgentConfig(accountScope = "fixture-a", accountBindingId = "new-binding",
            siteUrl = "https://fixture.invalid", deviceId = "new-device")
        RemoteAgentStore(cache).saveConfig(newBinding)
        VaultCleanupJournal(journalVault).save(listOf(CleanupRecord("old-unpair",
            CleanupRequest(CleanupOperation.REMOTE_UNPAIR, "fixture-a"),
            CleanupInventory(ownsRemote = true, remoteBindingId = "old-binding"),
            listOf(CleanupItem(CleanupStep.REMOTE_TOKENS, CleanupStatus.FAILED)))))
        assertTrue(runtime.ensureCleanupReady())
        assertTrue(clearedVaults.none { it.startsWith("remote:") })
        assertEquals(newBinding, RemoteAgentStore(cache).loadConfig())
        assertNull(journalPayload)
    } }

    @Test
    fun pagePresentationIsAccountOwnedAndOldOutputCannotReachTheNextLogin() = withFixture { runBlocking {
        requestHandler = { awaitCancellation() }
        val account = bind("fixture-a", entryLoggingIn = false)
        val model = account.presentation.pageStateModel!!
        assertSame(model, account.presentation.pageStateModel)
        account.presentation.settingsModel.setDynamicColor(false)
        withTimeout(5000) { model.state.first { !it.inputs.settingsState.preferences.dynamicColor } }
        runtime.endAccount(account)
        account.graph.stopAndJoin()
        val frozen = model.state.value
        account.presentation.entryLoggingIn = true
        assertSame(frozen, model.state.value)
        val fresh = bind("fixture-a")
        val next = fresh.presentation.pageStateModel!!
        assertNotSame(model, next)
        assertTrue(next.state.value.inputs.entryLoggingIn)
        assertFalse(next.state.value.inputs.settingsState.preferences.isCitelEnabled)
        runtime.endAccount(fresh)
        fresh.graph.stopAndJoin()
    } }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun multiplePageAdaptersShareDerivedObjectsAndRemountKeepsTheProducer() = SwingUtilities.invokeAndWait {
        withFixture {
            val account = bind("fixture-a")
            val session = account.presentation
            val model = session.pageStateModel!!
            val visible = mutableStateOf(true)
            var first: ShellData? = null
            var second: ShellData? = null
            val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
                if (visible.value) {
                    first = rememberShellData(session, false)
                    second = rememberShellData(session, false)
                }
            }
            var frame = 0L
            fun settle() { repeat(6) { scene.render(++frame * 16_000_000L).close() } }
            try {
                settle()
                assertSame(first!!.aggregatedAssignments, second!!.aggregatedAssignments)
                assertSame(first!!.scheduleStateWithEvents, second!!.scheduleStateWithEvents)
                visible.value = false
                settle()
                session.settingsModel.setDynamicColor(false)
                runBlocking { withTimeout(5000) { model.state.first { !it.inputs.settingsState.preferences.dynamicColor } } }
                visible.value = true
                settle()
                assertSame(model, session.pageStateModel)
                assertFalse(first!!.settingsState.preferences.dynamicColor)
                assertFalse(second!!.settingsState.preferences.dynamicColor)
                assertEquals(0, networkCalls)
            } finally {
                scene.close()
                runBlocking { runtime.endAccount(account); account.graph.stopAndJoin() }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun nativeChromeAndTabNotificationsBelongToEachPresentationInsteadOfTheAccount() = SwingUtilities.invokeAndWait {
        withFixture {
            val account = bind("fixture-a")
            val session = account.presentation
            val model = session.pageStateModel!!
            // No account work is needed to exercise presentation layout and platform callbacks.
            runBlocking { account.graph.stopAndJoin() }
            val frozen = model.state.value
            val firstHost = team.bjtuss.bjtuselfservice.shared.feature.shell.NativeHostState()
            val secondHost = team.bjtuss.bjtuselfservice.shared.feature.shell.NativeHostState()
            var firstTitles = 0
            var secondTitles = 0
            val firstTabs = mutableListOf<List<String>>()
            val secondTabs = mutableListOf<List<String>>()
            val first = ImageComposeScene(390, 844, coroutineContext = Dispatchers.Unconfined) {
                team.bjtuss.bjtuselfservice.shared.AuthenticatedDestinationApp(session, "SCHEDULE",
                    nativeTabBarEnabled = true, useNativeTitleBar = true, nativeHostState = firstHost,
                    onOpenNativeRoute = {}, onCloseNativeRoute = {},
                    onNativeTitleChanged = { firstTitles++ }, onNativeTabItemsChanged = { firstTabs += it })
            }
            val second = ImageComposeScene(390, 844, coroutineContext = Dispatchers.Unconfined) {
                team.bjtuss.bjtuselfservice.shared.AuthenticatedDestinationApp(session, "SCHEDULE",
                    nativeTabBarEnabled = true, useNativeTitleBar = true, nativeHostState = secondHost,
                    onOpenNativeRoute = {}, onCloseNativeRoute = {},
                    onNativeTitleChanged = { secondTitles++ }, onNativeTabItemsChanged = { secondTabs += it })
            }
            var frame = 0L
            fun settle() { repeat(6) {
                first.render(++frame * 16_000_000L).close()
                second.render(++frame * 16_000_000L).close()
            } }
            try {
                settle()
                assertEquals(1, firstTabs.size)
                assertEquals(firstTabs, secondTabs)
                val oldFirst = firstTitles
                val oldSecond = secondTitles
                firstHost.glassTopBarInsetDp = 90f
                firstHost.systemBottomInsetDp = 34f
                firstHost.glassTabBarBottomInsetDp = 83f
                settle()
                assertTrue(firstTitles > oldFirst)
                assertEquals(oldSecond, secondTitles)
                assertEquals(0f, secondHost.glassTopBarInsetDp)
                assertEquals(0f, secondHost.systemBottomInsetDp)
                assertEquals(0f, secondHost.glassTabBarBottomInsetDp)
                assertSame(frozen, model.state.value)
                assertSame(session, runtime.session.value)
                assertEquals(0, networkCalls)
            } finally { first.close(); second.close() }
            assertFalse(account.isClosed)
            runtime.endAccount(account)
        }
    }

    @Test
    fun automaticStartupWaitsForLoginAndRunsWithoutAnyPageHost() = withFixture { runBlocking {
        val requested = CompletableDeferred<Unit>()
        var calls = 0
        var cancelled = 0
        requestHandler = {
            calls++
            requested.complete(Unit)
            try { awaitCancellation() } finally { cancelled++ }
        }
        val account = bind("fixture-a", entryLoggingIn = true)
        assertEquals(0, calls)
        account.presentation.entryLoggingIn = false
        withTimeout(5000) { requested.await() }
        val before = calls
        account.syncTasks.start()
        bind("fixture-a")
        account.presentation.settingsModel.setDynamicColor(false)
        assertEquals(before, calls)
        assertTrue(before > 0)
        runtime.endAccount(account)
        account.graph.stopAndJoin()
        assertEquals(calls, cancelled)
    } }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun removingAndRebuildingHostsDoesNotCancelOrRepeatAutomaticStartup() = SwingUtilities.invokeAndWait {
        withFixture {
            val visible = mutableStateOf(true)
            val requested = CompletableDeferred<Unit>()
            var calls = 0
            var cancelled = 0
            requestHandler = {
                calls++
                requested.complete(Unit)
                try { awaitCancellation() } finally { cancelled++ }
            }
            val account = bind("fixture-a", entryLoggingIn = true)
            val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
                if (visible.value) bindPresentation(profile("fixture-a"), AppPreferences(), false)
            }
            var frame = 0L
            fun settle() { repeat(6) { scene.render(++frame * 16_000_000L).close() } }
            try {
                settle()
                runBlocking { withTimeout(5000) { requested.await() } }
                val before = calls
                visible.value = false
                settle()
                assertEquals(0, cancelled)
                assertSame(account.presentation, runtime.session.value)
                visible.value = true
                settle()
                assertEquals(before, calls)
                assertEquals(0, cancelled)
                assertSame(account.syncTasks, runtime.currentAccount!!.syncTasks)
                assertSame(account.widgetPublisher, runtime.currentAccount!!.widgetPublisher)
            } finally {
                scene.close()
                runBlocking { runtime.endAccount(account); account.graph.stopAndJoin() }
            }
            assertEquals(calls, cancelled)
        }
    }

    @Test
    fun changingAccountsCancelsAutomaticWorkAndNewAccountStartsItsOwnBatch() = withFixture { runBlocking {
        var calls = 0
        var cancelled = 0
        requestHandler = {
            calls++
            try { awaitCancellation() } finally { cancelled++ }
        }
        val first = bind("fixture-a", entryLoggingIn = true)
        first.presentation.entryLoggingIn = false
        withTimeout(5000) { while (calls == 0) kotlinx.coroutines.yield() }
        val firstCalls = calls
        val next = bind("fixture-b", entryLoggingIn = true)
        first.graph.stopAndJoin()
        assertEquals(firstCalls, cancelled)
        assertNotSame(first.syncTasks, next.syncTasks)
        next.presentation.entryLoggingIn = false
        withTimeout(5000) { while (calls == firstCalls) kotlinx.coroutines.yield() }
        runtime.closeAndJoin()
        assertEquals(calls, cancelled)
    } }

    @Test
    fun resumeEventsAreConsumedOnceEvenWithNoComposeHost() = withFixture { runBlocking {
        val account = bind("fixture-a", entryLoggingIn = true)
        val session = account.presentation
        // Silent login consumes a resume but must not access the school server.
        session.notifyPageBecameActive()
        assertFalse(session.claimAppResume(session.appResumeGeneration.value))
        assertEquals(0, networkCalls)
        // Route reports are data only, including native push/pop and returning to a shared root.
        session.reportRootPage(team.bjtuss.bjtuselfservice.shared.feature.shell.AppSection.GRADES)
        session.reportActivePage("PHYVLAB_DETAIL")
        assertSame(team.bjtuss.bjtuselfservice.shared.feature.shell.PhyVlabDetailRoute, session.activeSyncRoute)
        session.reportRootPage(team.bjtuss.bjtuselfservice.shared.feature.shell.AppSection.GRADES)
        assertSame(team.bjtuss.bjtuselfservice.shared.feature.shell.PhyVlabDetailRoute, session.activeSyncRoute,
            "A hidden root recomposition must not overwrite the visible native detail")
        session.reportActivePage(null)
        assertSame(team.bjtuss.bjtuselfservice.shared.feature.shell.AppSection.GRADES, session.activeSyncRoute)
        assertEquals(0, networkCalls)
        runtime.endAccount(account)
        account.graph.stopAndJoin()
        val generation = session.appResumeGeneration.value
        session.notifyPageBecameActive()
        // Retired collectors cannot claim any new event.
        assertTrue(session.claimAppResume(generation + 1))
        assertEquals(0, networkCalls)
    } }

    @Test
    fun resumedExpiredPageRetriesOnceWithoutAnyPageHost() = withFixture { runBlocking {
        var gradeCalls = 0
        requestHandler = { request ->
            if (request.url.contains("/score/scores/stu/view/")) gradeCalls++
            SchoolHttpResponse(200, "https://mis.bjtu.edu.cn/auth/login/")
        }
        val account = bind("fixture-a", entryLoggingIn = true)
        val session = account.presentation
        session.gradeModel.initialize(refreshFromNetwork = true)
        assertEquals(team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure.SESSION_EXPIRED,
            session.gradeModel.state.value.failure)
        session.entryLoggingIn = false
        // Automatic initialization has already been attempted; only a resume of this expired page retries it.
        withTimeout(5000) { while (session.courseScheduleModel.state.value.isCalendarLoading) kotlinx.coroutines.yield() }
        val before = gradeCalls
        session.reportActivePage("GRADES")
        session.notifyPageBecameActive()
        withTimeout(5000) { while (gradeCalls == before) kotlinx.coroutines.yield() }
        assertEquals(before + 1, gradeCalls)
        assertFalse(session.claimAppResume(session.appResumeGeneration.value))
        account.syncTasks.start()
        assertEquals(before + 1, gradeCalls)
        runtime.endAccount(account)
        account.graph.stopAndJoin()
    } }

    @Test
    fun homeFirstSyncStartsAfterLoginWithoutAHomePage() = withFixture { runBlocking {
        requestHandler = { awaitCancellation() }
        val account = bind("fixture-a", entryLoggingIn = true)
        val home = account.presentation.homeModel
        assertFalse(home.state.value.isRefreshing)
        account.presentation.entryLoggingIn = false
        withTimeout(5000) { home.state.first { it.isRefreshing } }
        assertSame(home, bind("fixture-a").presentation.homeModel)
        runtime.endAccount(account)
        account.graph.stopAndJoin()
    } }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun completedLoginPublishesAndReadiesAccountAfterRootHasBeenRemoved() = SwingUtilities.invokeAndWait {
        withFixture {
            val fixture = this
            requestHandler = { awaitCancellation() }
            val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
                fixture.hostApp { }
            }
            repeat(6) { scene.render((it + 1) * 16_000_000L).close() }
            val login = runtime.loginContext(securityStore)
            val gate = CompletableDeferred<Unit>()
            var cachedEntry: AuthenticatedSession? = null
            var completed: AuthenticatedSession? = null
            val job = login.launch {
                gate.await()
                cachedEntry = login.publishAccount(profile("fixture-a"), true, Credentials("fixture-a", "fixture-password"))
                completed = login.publishAccount(profile("fixture-a"), false, Credentials("fixture-a", "fixture-password"))
            }
            scene.close()
            gate.complete(Unit)
            runBlocking { withTimeout(5000) { job.join() } }
            assertNotNull(completed)
            assertSame(cachedEntry, completed)
            assertSame(completed, runtime.session.value)
            assertFalse(completed!!.entryLoggingIn)
            runBlocking { runtime.closeAndJoin() }
        }
    }

    @Test
    fun cancellingManualRefreshCallerDoesNotCancelTheAccountRefresh() = withFixture { runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        requestHandler = {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val account = bind("fixture-a", entryLoggingIn = true)
        val page = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val caller = page.launch {
            account.refreshService.refreshAssignmentSource(
                team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource.COURSE_PLATFORM)
        }
        try {
            withTimeout(5000) { entered.await() }
            page.cancel()
            caller.join()
            assertFalse(cancelled.isCompleted)
            runtime.endAccount(account)
            account.graph.stopAndJoin()
            assertTrue(cancelled.isCompleted)
        } finally { page.cancel() }
    } }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun destinationReadersIgnoreUnrelatedBusinessStateButObserveTheirPreferences() = SwingUtilities.invokeAndWait {
        withFixture {
            val account = bind("fixture-a", entryLoggingIn = true)
            val session = account.presentation
            var grades = 0
            var home = 0
            @Composable fun GradeReader() {
                rememberShellData(session, false, route = team.bjtuss.bjtuselfservice.shared.feature.shell.AppSection.GRADES)
                SideEffect { grades++ }
            }
            @Composable fun HomeReader() {
                rememberShellData(session, false, route = team.bjtuss.bjtuselfservice.shared.feature.shell.AppSection.HOME)
                SideEffect { home++ }
            }
            // Separate hosts ensure a home recomposition cannot re-enter a local grade reader.
            val gradeScene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) { GradeReader() }
            val homeScene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) { HomeReader() }
            var frame = 0L
            fun settle() {
                repeat(6) {
                    val time = ++frame * 16_000_000L
                    gradeScene.render(time).close()
                    homeScene.render(time).close()
                }
            }
            try {
                settle()
                val beforeGrade = grades
                val beforeHome = home
                session.homeworkModel.updateTime()
                val instant = session.homeworkModel.state.value.nowInstant
                runBlocking { withTimeout(5000) { session.pageStateModel!!.state.first { it.inputs.homeworkState.nowInstant == instant } } }
                settle()
                assertEquals(beforeGrade, grades)
                assertTrue(home > beforeHome)
                session.settingsModel.setDynamicColor(false)
                runBlocking { withTimeout(5000) { session.pageStateModel!!.state.first { !it.inputs.settingsState.preferences.dynamicColor } } }
                settle()
                assertTrue(grades > beforeGrade)
            } finally {
                gradeScene.close()
                homeScene.close()
                runBlocking { runtime.endAccount(account); account.graph.stopAndJoin() }
            }
        }
    }

    private inline fun withFixture(block: Fixture.() -> Unit) {
        val fixture = Fixture()
        try { fixture.block() } finally { fixture.close() }
    }

    private class Fixture {
        val platformCalls = mutableListOf<String>()
        private val cleanupPlatform = object : CleanupPlatform {
            override suspend fun clearPresentation(allLocalData: Boolean): Boolean { platformCalls += "presentation:$allLocalData"; return true }
            override suspend fun clearManagedTemporaryFiles(): Boolean { platformCalls += "temp"; return true }
            override suspend fun clearDeviceSettings(removeAgent: Boolean): Boolean { platformCalls += "device:$removeAgent"; return true }
        }
        val runtime = AppRuntime(Dispatchers.Unconfined, cleanupPlatform) { transport }
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val cache = CacheStore(driver.also { CacheDatabaseSql.Schema.create(it).value })
        private val taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val workRoot = Files.createTempDirectory("account-runtime-fixture-")
        var transportCloses = 0
        var networkCalls = 0
        var requestHandler: (suspend (SchoolHttpRequest) -> SchoolHttpResponse)? = null
        private val transport = object : SchoolHttpTransport {
            override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
                networkCalls++
                requestHandler?.let { return it(request) }
                error("Runtime lifecycle fixtures must not access the network")
            }
            override fun close() { transportCloses++ }
            override fun clearSession() = Unit
        }
        private val protocol = SchoolLoginProtocol(transport)
        val clearedVaults = mutableListOf<String>()
        var failingVault: String? = null
        var journalPayload: Credentials? = null
        val journalVault = object : CredentialVault {
            override suspend fun load() = journalPayload
            override suspend fun save(credentials: Credentials) { journalPayload = credentials }
            override suspend fun clear() { journalPayload = null }
        }
        private fun vault(key: String) = object : CredentialVault {
            override suspend fun load(): Credentials? = null
            override suspend fun save(credentials: Credentials) = Unit
            override suspend fun clear() { clearedVaults += key; check(failingVault != key) { "Fixture cleanup failure" } }
        }
        val securityStore = AccountSecurityStore(vault("primary"), object : AccountPreferences {
            override suspend fun shouldRememberCredentials(): Boolean = false
            override suspend fun setShouldRememberCredentials(enabled: Boolean) = Unit
            override suspend fun clearRememberCredentialsSetting() = Unit
        }, physicsLabVault = { vault("lab:$it") }, physicsDemoVault = { vault("demo:$it") },
            phyVlabVault = { vault("vlab:$it") }, citelVault = { vault("citel:$it") }, remoteVault = { vault("remote:$it") },
            cleanupJournalVault = journalVault)
        private val security = AccountSecurityCoordinator(securityStore)
        private val fileGateway = UnavailableFileGateway
        val playbackRecovery = object : PlaybackRecoveryAccess {
            private val managers = mutableMapOf<String, PlaybackTaskManager>()
            override fun storeFor(accountScope: String): PlaybackTaskStore = object : PlaybackTaskStore {
                override val workRoot = Path(this@Fixture.workRoot.resolve(accountScope).toString())
                override suspend fun load(): List<StoredPlaybackTask> = emptyList()
                override suspend fun save(tasks: List<StoredPlaybackTask>) = Unit
            }
            override fun retainedManager(accountScope: String): PlaybackTaskManager = managers.getOrPut(accountScope) {
                PlaybackTaskManager(taskScope, HlsRecordingDownloader(transport),
                    { PlaybackPlatformHooks.effectiveCompositor }, { null }, storeFor(accountScope))
            }
            override suspend fun restoreDestination(token: String, displayName: String): StreamingDestinationResult =
                StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }

        fun cleanupService(account: AccountSession) = AccountCleanupService(
            runtime.cleanupCoordinator, cache, security, cleanupPlatform, account.graph, null,
            playbackRecovery, { account.graph.stopAndJoin() }, null,
            clearWeb = { platformCalls += "web"; true },
        )

        fun bindCleanup() { runtime.bindCleanupResources(cache, securityStore, playbackRecovery) }
        fun fileGatewayForCleanupTest(): FileGateway = fileGateway
        fun recoveryRuntime(cacheAvailable: Boolean = true) = AppRuntime(Dispatchers.Unconfined, cleanupPlatform) { transport }.also {
            it.bindCleanupResources(cache, securityStore, playbackRecovery, cacheAvailable)
        }

        fun profile(account: String) = StudentProfile("测试名称", account, "本科生", "测试院系")

        fun bind(
            account: String,
            entryLoggingIn: Boolean = true,
            preferences: AppPreferences = AppPreferences(),
            onLogout: (String) -> Unit = {},
        ): AccountSession = runtime.obtainAccountSession(
            profile = profile(account), entryLoggingIn = entryLoggingIn, transport = transport,
            loginProtocol = protocol, cacheStore = cache,
            platform = PlatformInfo(PlatformFamily.MacOS, "Fixture", isWindows = true),
            preferences = preferences, onPreferencesChanged = { true }, securityCoordinator = security,
            captchaRecognizer = UnavailableCaptchaRecognizer, credentials = Credentials(account, "fixture-password"),
            fileGateway = fileGateway, directoryGateway = UnavailableDirectoryGateway,
            systemCalendarGateway = UnavailableSystemCalendarGateway, onLogout = onLogout, onPurgeLogout = {},
            refreshDispatcher = Dispatchers.Unconfined,
            playbackRecovery = playbackRecovery,
        )

        @Composable
        fun hostApp(store: AccountSecurityStore = securityStore, publish: (AuthenticatedSession?) -> Unit) {
            App(appRuntime = runtime, accountSecurityStore = store,
                cacheStoreHandle = CacheStoreHandle(cache, CacheOpenState.OPENED),
                fileGateway = fileGateway, nativeTabBarEnabled = true,
                playbackRecovery = playbackRecovery,
                onAuthenticatedSessionChanged = publish)
        }

        @Composable
        fun bindPresentation(
            profile: StudentProfile,
            preferences: AppPreferences,
            entryLoggingIn: Boolean,
        ): AuthenticatedSession {
            val presentation = remember(runtime, profile.studentId) {
            bindAuthenticatedSession(
            runtime = runtime, profile = profile, entryLoggingIn = entryLoggingIn,
            transport = transport, loginProtocol = protocol, cacheStore = cache,
            platform = PlatformInfo(PlatformFamily.MacOS, "Fixture", isWindows = true),
            appPreferences = preferences, onPreferencesChanged = { true }, securityCoordinator = security,
            captchaRecognizer = UnavailableCaptchaRecognizer, username = profile.studentId, password = "fixture-password",
            fileGateway = fileGateway, directoryGateway = UnavailableDirectoryGateway,
            systemCalendarGateway = UnavailableSystemCalendarGateway, onLogout = {}, onPurgeLogout = {},
            playbackRecovery = playbackRecovery,
        )
            }
            SideEffect {
            bindAuthenticatedSession(
            runtime = runtime, profile = profile, entryLoggingIn = entryLoggingIn,
            transport = transport, loginProtocol = protocol, cacheStore = cache,
            platform = PlatformInfo(PlatformFamily.MacOS, "Fixture", isWindows = true),
            appPreferences = preferences, onPreferencesChanged = { true }, securityCoordinator = security,
            captchaRecognizer = UnavailableCaptchaRecognizer, username = profile.studentId, password = "fixture-password",
            fileGateway = fileGateway, directoryGateway = UnavailableDirectoryGateway,
            systemCalendarGateway = UnavailableSystemCalendarGateway, onLogout = {}, onPurgeLogout = {},
            playbackRecovery = playbackRecovery,
        )
            }
            return presentation
        }

        fun close() {
            try { runtime.close() } finally {
                taskScope.cancel()
                cache.close()
                // The in-memory task store never creates files or child directories.
                Files.delete(workRoot)
            }
        }
    }
}
