package team.bjtuss.bjtuselfservice.shared.update

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.feature.settings.UpdateCheckState

private const val POSTPONE_DURATION_MILLIS = 24L * 60 * 60 * 1000

/**
 * 在账号会话之外共享更新检查流程，使登录前与登录页手动操作都能独立触发并持久化推迟状态。
 */
internal class AppUpdateCoordinator(
    private val preferencesProvider: () -> AppPreferences,
    private val updatePreferences: (AppPreferences.() -> AppPreferences) -> Boolean,
    private val fetchLatest: suspend (UpdateSource) -> AppUpdateChecker.Result,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutableState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val state: StateFlow<UpdateCheckState> = mutableState.asStateFlow()

    var automaticCheckAttempted: Boolean = false
        private set

    var automaticCheckSucceeded: Boolean = false
        private set

    suspend fun checkForUpdate(silentOnMiss: Boolean = false) {
        if (mutableState.value == UpdateCheckState.Checking) return
        val currentPreferences = preferencesProvider()
        if (silentOnMiss) {
            if (
                automaticCheckAttempted ||
                !currentPreferences.checkUpdate ||
                nowMillis() < currentPreferences.updatePostponedUntilMillis
            ) {
                return
            }
            automaticCheckAttempted = true
        }
        mutableState.value = UpdateCheckState.Checking
        val result = try {
            fetchLatest(currentPreferences.updateSource)
        } catch (cancelled: CancellationException) {
            if (silentOnMiss) automaticCheckAttempted = false
            mutableState.value = UpdateCheckState.Idle
            throw cancelled
        } catch (_: Exception) {
            AppUpdateChecker.Result.Unavailable
        }
        val latestPreferences = preferencesProvider()
        mutableState.value = when (result) {
            is AppUpdateChecker.Result.Success -> {
                automaticCheckSucceeded = true
                val hasUpdate = AppUpdateChecker.isNewer(result.release)
                if (
                    (!silentOnMiss || nowMillis() >= latestPreferences.updatePostponedUntilMillis) &&
                    (hasUpdate || !silentOnMiss)
                ) {
                    UpdateCheckState.Done(release = result.release, hasUpdate = hasUpdate)
                } else {
                    UpdateCheckState.Idle
                }
            }
            AppUpdateChecker.Result.Unavailable -> {
                if (silentOnMiss) UpdateCheckState.Idle else UpdateCheckState.Failed
            }
        }
    }

    fun postponeUpdate() {
        if ((mutableState.value as? UpdateCheckState.Done)?.hasUpdate == true) {
            val until = nowMillis() + POSTPONE_DURATION_MILLIS
            updatePreferences { copy(updatePostponedUntilMillis = until) }
        }
        dismissUpdateCheck()
    }

    fun dismissUpdateCheck() {
        mutableState.value = UpdateCheckState.Idle
    }
}
