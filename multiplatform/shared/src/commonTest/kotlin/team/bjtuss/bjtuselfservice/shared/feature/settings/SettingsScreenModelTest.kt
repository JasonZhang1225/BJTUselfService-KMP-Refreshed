package team.bjtuss.bjtuselfservice.shared.feature.settings

import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.update.AppUpdateChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SettingsScreenModelTest {
    private fun model(
        initialPreferences: AppPreferences = AppPreferences(),
        persistPreferences: (AppPreferences) -> Boolean = { true },
        clearAccountCache: () -> Boolean = { true },
        checkLatestRelease: suspend () -> AppUpdateChecker.Result = {
            AppUpdateChecker.Result.Unavailable
        },
    ) = SettingsScreenModel(
        initialPreferences = initialPreferences,
        persistPreferences = persistPreferences,
        clearAccountCache = clearAccountCache,
        checkLatestRelease = checkLatestRelease,
    )

    @Test
    fun freshPreferencesEnableEveryAutomaticSync() {
        val preferences = AppPreferences()

        assertTrue(preferences.autoSyncGrades)
        assertTrue(preferences.autoSyncHomework)
        assertTrue(preferences.autoSyncSchedule)
        assertTrue(preferences.autoSyncExams)
        assertTrue(preferences.autoSyncPhyVlab)
        assertTrue(preferences.showPhyVlabInBottomNav)
        assertTrue(preferences.isPhyVlabEnabled)
    }

    @Test
    fun autoSyncOptionsPersistAndUpdateVisiblePreferences() {
        val saved = mutableListOf<AppPreferences>()
        val model = model(persistPreferences = { saved += it; true })

        model.setAutoSyncGrades(true)
        model.setAutoSyncHomework(true)
        model.setAutoSyncSchedule(true)
        model.setAutoSyncExams(true)
        model.setAutoSyncPhyVlab(true)

        assertEquals(5, saved.size)
        assertTrue(model.state.value.preferences.autoSyncGrades)
        assertTrue(model.state.value.preferences.autoSyncHomework)
        assertTrue(model.state.value.preferences.autoSyncSchedule)
        assertTrue(model.state.value.preferences.autoSyncExams)
        assertTrue(model.state.value.preferences.autoSyncPhyVlab)
        assertFalse(model.state.value.saveFailed)
    }

    @Test
    fun dynamicColorTogglePersists() {
        val saved = mutableListOf<AppPreferences>()
        val model = model(
            initialPreferences = AppPreferences(dynamicColor = true),
            persistPreferences = { saved += it; true },
        )

        model.setDynamicColor(false)

        assertEquals(1, saved.size)
        assertFalse(model.state.value.preferences.dynamicColor)
        assertFalse(model.state.value.saveFailed)
    }

    @Test
    fun physicalOnlineMasterToggleControlsSyncAndBottomNav() {
        val saved = mutableListOf<AppPreferences>()
        val model = model(
            initialPreferences = AppPreferences(showPhyVlabInBottomNav = true),
            persistPreferences = { saved += it; true },
        )

        model.setPhyVlabEnabled(false)

        assertEquals(1, saved.size)
        assertFalse(model.state.value.preferences.autoSyncPhyVlab)
        assertFalse(model.state.value.preferences.showPhyVlabInBottomNav)
        assertFalse(model.state.value.preferences.isPhyVlabEnabled)
        assertFalse(model.state.value.saveFailed)
    }

    @Test
    fun failedAutoSyncSaveKeepsPreviousValueAndReportsFailure() {
        val model = model(persistPreferences = { false })

        model.setAutoSyncSchedule(false)

        assertTrue(model.state.value.preferences.autoSyncSchedule)
        assertTrue(model.state.value.saveFailed)
    }

    @Test
    fun successfulCacheClearReportsSuccessAndCanBeDismissed() {
        runBlocking {
            var calls = 0
            val model = model(clearAccountCache = {
                calls += 1
                true
            })

            model.clearOfflineCache()

            assertEquals(1, calls)
            assertIs<OfflineCacheActionState.Cleared>(model.state.value.cacheAction)
            model.dismissFeedback()
            assertIs<OfflineCacheActionState.Idle>(model.state.value.cacheAction)
        }
    }

    @Test
    fun cacheClearExceptionReportsFailure() {
        runBlocking {
            val model = model(clearAccountCache = { error("database") })

            model.clearOfflineCache()

            assertIs<OfflineCacheActionState.Failed>(model.state.value.cacheAction)
        }
    }

    @Test
    fun newerRemoteReleaseReportsUpdateAndCanBeDismissed() {
        runBlocking {
            val newer = AppUpdateChecker.Release(
                tagName = "v9.9.9-KMP",
                body = "更新说明",
                htmlUrl = "https://github.com/${AppUpdateChecker.REPO}/releases/tag/v9.9.9-KMP",
            )
            val model = model(
                checkLatestRelease = { AppUpdateChecker.Result.Success(newer) },
            )

            model.checkForUpdate()

            val done = assertIs<UpdateCheckState.Done>(model.state.value.updateCheck)
            assertTrue(done.hasUpdate)
            assertEquals(newer, done.release)

            model.dismissUpdateCheck()
            assertIs<UpdateCheckState.Idle>(model.state.value.updateCheck)
        }
    }

    @Test
    fun sameVersionRemoteReleaseReportsNoUpdate() {
        runBlocking {
            val model = model(
                checkLatestRelease = {
                    AppUpdateChecker.Result.Success(
                        AppUpdateChecker.Release(
                            tagName = AppUpdateChecker.CURRENT_VERSION,
                            htmlUrl = "https://github.com/${AppUpdateChecker.REPO}/releases",
                        ),
                    )
                },
            )

            model.checkForUpdate()

            val done = assertIs<UpdateCheckState.Done>(model.state.value.updateCheck)
            assertFalse(done.hasUpdate)
        }
    }

    @Test
    fun unavailableOrThrowingUpdateCheckReportsFailure() {
        runBlocking {
            val unavailable = model(
                checkLatestRelease = { AppUpdateChecker.Result.Unavailable },
            )
            unavailable.checkForUpdate()
            assertIs<UpdateCheckState.Failed>(unavailable.state.value.updateCheck)

            val throwing = model(
                checkLatestRelease = { error("network") },
            )
            throwing.checkForUpdate()
            assertIs<UpdateCheckState.Failed>(throwing.state.value.updateCheck)
        }
    }

    @Test
    fun silentAutoCheckStaysIdleWithoutUpdateButStillSurfacesNewerRelease() {
        runBlocking {
            // 自动检测（silentOnMiss=true）：无更新 → 回 Idle，不打扰用户。
            val upToDate = model(
                checkLatestRelease = {
                    AppUpdateChecker.Result.Success(
                        AppUpdateChecker.Release(
                            tagName = AppUpdateChecker.CURRENT_VERSION,
                            htmlUrl = "https://github.com/${AppUpdateChecker.REPO}/releases",
                        ),
                    )
                },
            )
            upToDate.checkForUpdate(silentOnMiss = true)
            assertIs<UpdateCheckState.Idle>(upToDate.state.value.updateCheck)

            // 失败同样静默。
            val failed = model(
                checkLatestRelease = { AppUpdateChecker.Result.Unavailable },
            )
            failed.checkForUpdate(silentOnMiss = true)
            assertIs<UpdateCheckState.Idle>(failed.state.value.updateCheck)

            // 但有新版本时仍然弹。
            val newer = model(
                checkLatestRelease = {
                    AppUpdateChecker.Result.Success(
                        AppUpdateChecker.Release(
                            tagName = "v9.9.9-KMP",
                            htmlUrl = "https://github.com/${AppUpdateChecker.REPO}/releases/tag/v9.9.9-KMP",
                        ),
                    )
                },
            )
            newer.checkForUpdate(silentOnMiss = true)
            val done = assertIs<UpdateCheckState.Done>(newer.state.value.updateCheck)
            assertTrue(done.hasUpdate)
        }
    }
}
