package team.bjtuss.bjtuselfservice.shared.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.update.AppUpdateChecker

sealed interface OfflineCacheActionState {
    data object Idle : OfflineCacheActionState
    data object Clearing : OfflineCacheActionState
    data object Cleared : OfflineCacheActionState
    data object Failed : OfflineCacheActionState
}

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState

    /** 已拿到最新 Release（无论有无更新）；[hasUpdate] 供 UI 决定是否突出显示。 */
    data class Done(
        val release: AppUpdateChecker.Release,
        val hasUpdate: Boolean,
    ) : UpdateCheckState

    data object Failed : UpdateCheckState
}

data class SettingsUiState(
    val preferences: AppPreferences,
    val cacheAction: OfflineCacheActionState = OfflineCacheActionState.Idle,
    val dataWipeAction: OfflineCacheActionState = OfflineCacheActionState.Idle,
    val saveFailed: Boolean = false,
    val updateCheck: UpdateCheckState = UpdateCheckState.Idle,
)

/**
 * 设置状态只保存普通偏好；凭据与 Cookie 仍由现有安全退出路径管理。
 * 浅深色始终跟随系统；[AppPreferences.dynamicColor] 仅 Android 设置页暴露开关。
 */
class SettingsScreenModel(
    initialPreferences: AppPreferences,
    private val persistPreferences: (AppPreferences) -> Boolean,
    private val clearAccountCache: () -> Boolean,
    private val wipeAllLocalData: suspend () -> Boolean,
    private val checkLatestRelease: suspend () -> AppUpdateChecker.Result,
) {
    private val mutableState = MutableStateFlow(SettingsUiState(initialPreferences))
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    fun setAutoSyncGrades(enabled: Boolean) = updatePreferences {
        copy(autoSyncGrades = enabled)
    }

    fun setAutoSyncHomework(enabled: Boolean) = updatePreferences {
        copy(autoSyncHomework = enabled)
    }

    fun setAutoSyncSchedule(enabled: Boolean) = updatePreferences {
        copy(autoSyncSchedule = enabled)
    }

    fun setAutoSyncExams(enabled: Boolean) = updatePreferences {
        copy(autoSyncExams = enabled)
    }

    fun setAutoSyncPhyVlab(enabled: Boolean) = updatePreferences {
        copy(autoSyncPhyVlab = enabled)
    }

    fun setShowPhyVlabInBottomNav(enabled: Boolean) = updatePreferences {
        copy(showPhyVlabInBottomNav = enabled)
    }

    fun setPhyVlabEnabled(enabled: Boolean) = updatePreferences {
        copy(
            autoSyncPhyVlab = enabled,
            showPhyVlabInBottomNav = enabled,
        )
    }

    /** Android Material You 动态取色；其它平台设置页不展示，即使写入也无视觉效果。 */
    fun setDynamicColor(enabled: Boolean) = updatePreferences {
        copy(dynamicColor = enabled)
    }

    suspend fun clearOfflineCache() {
        if (mutableState.value.cacheAction == OfflineCacheActionState.Clearing) return
        mutableState.value = mutableState.value.copy(cacheAction = OfflineCacheActionState.Clearing)
        mutableState.value = mutableState.value.copy(
            cacheAction = if (runCatching(clearAccountCache).getOrDefault(false)) {
                OfflineCacheActionState.Cleared
            } else {
                OfflineCacheActionState.Failed
            },
        )
    }

    /**
     * 清除全部本地数据：所有账号的离线缓存、应用设置与系统安全存储中的登录信息。
     * 供桌面端卸载前的兜底清理（macOS 删除 .app 不会清 Application Support/Keychain）。
     */
    suspend fun clearAllLocalData() {
        if (mutableState.value.dataWipeAction == OfflineCacheActionState.Clearing) return
        mutableState.value = mutableState.value.copy(dataWipeAction = OfflineCacheActionState.Clearing)
        val wiped = try {
            wipeAllLocalData()
        } catch (_: Exception) {
            false
        }
        mutableState.value = mutableState.value.copy(
            dataWipeAction = if (wiped) OfflineCacheActionState.Cleared else OfflineCacheActionState.Failed,
            // 全量清除后持久化偏好已为空，回到默认值，避免界面继续显示旧设置。
            preferences = if (wiped) AppPreferences() else mutableState.value.preferences,
        )
    }

    fun dismissFeedback() {
        mutableState.value = mutableState.value.copy(
            cacheAction = OfflineCacheActionState.Idle,
            dataWipeAction = OfflineCacheActionState.Idle,
            saveFailed = false,
        )
    }

    /**
     * 触发更新检测；进行中不重复发起。结果写入 [SettingsUiState.updateCheck]。
     *
     * @param silentOnMiss 自动检测（进主界面后）为 true：无更新或失败时回到 Idle 不打扰用户；
     * 手动点「检查更新」为 false：始终弹结果（已最新/失败也明确告知）。
     */
    suspend fun checkForUpdate(silentOnMiss: Boolean = false) {
        if (mutableState.value.updateCheck == UpdateCheckState.Checking) return
        mutableState.value = mutableState.value.copy(updateCheck = UpdateCheckState.Checking)
        mutableState.value = mutableState.value.copy(
            updateCheck = when (val result = runCatching { checkLatestRelease() }.getOrNull()) {
                is AppUpdateChecker.Result.Success -> {
                    val hasUpdate = AppUpdateChecker.isNewer(result.release)
                    if (hasUpdate || !silentOnMiss) {
                        UpdateCheckState.Done(release = result.release, hasUpdate = hasUpdate)
                    } else {
                        UpdateCheckState.Idle
                    }
                }
                else -> if (silentOnMiss) UpdateCheckState.Idle else UpdateCheckState.Failed
            },
        )
    }

    /** 关闭更新结果（弹窗/提示），回到 Idle。 */
    fun dismissUpdateCheck() {
        mutableState.value = mutableState.value.copy(updateCheck = UpdateCheckState.Idle)
    }

    private fun updatePreferences(transform: AppPreferences.() -> AppPreferences) {
        val updated = mutableState.value.preferences.transform()
        val saved = runCatching { persistPreferences(updated) }.getOrDefault(false)
        mutableState.value = mutableState.value.copy(
            preferences = if (saved) updated else mutableState.value.preferences,
            saveFailed = !saved,
        )
    }
}
