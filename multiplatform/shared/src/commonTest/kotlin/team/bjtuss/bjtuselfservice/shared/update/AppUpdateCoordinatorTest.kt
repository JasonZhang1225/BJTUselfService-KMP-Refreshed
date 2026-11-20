package team.bjtuss.bjtuselfservice.shared.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.feature.settings.SettingsScreenModel
import team.bjtuss.bjtuselfservice.shared.feature.settings.UpdateCheckState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppUpdateCoordinatorTest {
    @Test
    fun preLoginSilentCheckSurfacesNewerReleaseAndPassesDeduplicationToSettings(): Unit = runBlocking {
        var prefs = AppPreferences()
        var preLoginCalls = 0
        val coordinator = AppUpdateCoordinator(
            preferencesProvider = { prefs },
            updatePreferences = { transform -> prefs = prefs.transform(); true },
            fetchLatest = {
                preLoginCalls++
                AppUpdateChecker.Result.Success(
                    AppUpdateChecker.Release(
                        tagName = "v9.9.9-KMP",
                        htmlUrl = "https://github.com/${AppUpdateChecker.REPO}/releases/tag/v9.9.9-KMP",
                    ),
                )
            },
        )

        coordinator.checkForUpdate(silentOnMiss = true)
        assertEquals(1, preLoginCalls)
        assertTrue(coordinator.automaticCheckSucceeded)
        val done = assertIs<UpdateCheckState.Done>(coordinator.state.value)
        assertTrue(done.hasUpdate)

        // 登录前已成功完成本轮自动更新检查时，登录后不再重复静默请求或重复弹窗。
        var postLoginCalls = 0
        val settings = SettingsScreenModel(
            initialPreferences = prefs,
            persistPreferences = { prefs = it; true },
            clearAccountCache = { true },
            wipeAllLocalData = { true },
            checkLatestRelease = {
                postLoginCalls++
                AppUpdateChecker.Result.Unavailable
            },
            initialAutomaticUpdateChecked = coordinator.automaticCheckSucceeded,
        )
        settings.checkForUpdate(silentOnMiss = true)
        assertEquals(0, postLoginCalls)
        assertIs<UpdateCheckState.Idle>(settings.state.value.updateCheck)
    }

    @Test
    fun failedPreLoginSilentCheckLeavesPostLoginAutoCheckEnabled(): Unit = runBlocking {
        var prefs = AppPreferences()
        val coordinator = AppUpdateCoordinator(
            preferencesProvider = { prefs },
            updatePreferences = { transform -> prefs = prefs.transform(); true },
            fetchLatest = { AppUpdateChecker.Result.Unavailable },
        )

        coordinator.checkForUpdate(silentOnMiss = true)
        assertTrue(coordinator.automaticCheckAttempted)
        assertFalse(coordinator.automaticCheckSucceeded)
        assertIs<UpdateCheckState.Idle>(coordinator.state.value)

        var postLoginCalls = 0
        val settings = SettingsScreenModel(
            initialPreferences = prefs,
            persistPreferences = { prefs = it; true },
            clearAccountCache = { true },
            wipeAllLocalData = { true },
            checkLatestRelease = {
                postLoginCalls++
                AppUpdateChecker.Result.Success(
                    AppUpdateChecker.Release(
                        tagName = "v9.9.9-KMP",
                        htmlUrl = "https://github.com/${AppUpdateChecker.REPO}/releases/tag/v9.9.9-KMP",
                    ),
                )
            },
            initialAutomaticUpdateChecked = coordinator.automaticCheckSucceeded,
        )
        settings.checkForUpdate(silentOnMiss = true)
        assertEquals(1, postLoginCalls)
        assertIs<UpdateCheckState.Done>(settings.state.value.updateCheck)
    }

    @Test
    fun postponeAndCheckUpdatePreferenceGateSilentCheckButAllowManualCheck(): Unit = runBlocking {
        var now = 1_000L
        var prefs = AppPreferences(checkUpdate = false)
        var calls = 0
        var seenSource: UpdateSource? = null
        val coordinator = AppUpdateCoordinator(
            preferencesProvider = { prefs },
            updatePreferences = { transform -> prefs = prefs.transform(); true },
            fetchLatest = { source ->
                calls++
                seenSource = source
                AppUpdateChecker.Result.Success(
                    AppUpdateChecker.Release(
                        tagName = "v9.9.9-KMP",
                        htmlUrl = "https://github.com/${AppUpdateChecker.REPO}/releases/tag/v9.9.9-KMP",
                    ),
                )
            },
            nowMillis = { now },
        )

        coordinator.checkForUpdate(silentOnMiss = true)
        assertEquals(0, calls)

        prefs = prefs.copy(checkUpdate = true, updateSource = UpdateSource.GITEE)
        coordinator.checkForUpdate(silentOnMiss = true)
        assertEquals(1, calls)
        assertEquals(UpdateSource.GITEE, seenSource)
        assertIs<UpdateCheckState.Done>(coordinator.state.value)

        coordinator.postponeUpdate()
        assertEquals(1_000L + 24L * 60 * 60 * 1000, prefs.updatePostponedUntilMillis)
        assertIs<UpdateCheckState.Idle>(coordinator.state.value)

        // 手动检查绕过自动去重与推迟窗口，已最新与失败都明确展示。
        coordinator.checkForUpdate(silentOnMiss = false)
        assertEquals(2, calls)
        assertIs<UpdateCheckState.Done>(coordinator.state.value)
    }

    @Test
    fun cancelledSilentCheckResetsAttemptFlag(): Unit = runBlocking {
        var prefs = AppPreferences()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<AppUpdateChecker.Result>()
        val coordinator = AppUpdateCoordinator(
            preferencesProvider = { prefs },
            updatePreferences = { transform -> prefs = prefs.transform(); true },
            fetchLatest = {
                started.complete(Unit)
                gate.await()
            },
        )

        val job = async { coordinator.checkForUpdate(silentOnMiss = true) }
        started.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertFalse(coordinator.automaticCheckAttempted)
        assertIs<UpdateCheckState.Idle>(coordinator.state.value)
    }
}
