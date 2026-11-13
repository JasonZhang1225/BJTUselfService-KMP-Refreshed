package team.bjtuss.bjtuselfservice.shared.feature.home

/** 首页同步详情中单个数据源的状态；只描述状态，不携带账号或响应内容。 */
internal enum class HomeSyncItemState {
    WAITING,
    SYNCING,
    SUCCESS,
    FAILED,
}

internal data class HomeSyncItem(
    val title: String,
    val detail: String,
    val state: HomeSyncItemState,
    val requiresCaptcha: Boolean = false,
)

internal fun homeSyncDialogTitle(
    isLoggingIn: Boolean,
    isSyncing: Boolean,
    hasFailures: Boolean,
): String = when {
    isLoggingIn -> "登录中"
    isSyncing -> "同步中"
    hasFailures -> "同步失败"
    else -> "同步状态"
}
