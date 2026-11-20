package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.Composable
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import androidx.compose.runtime.Stable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.files.DirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentCompletionStore
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway

/**
 * 一次登录态下页面可以读取的全部上下文。
 *
 * 目的地分别读取展示数据，动作 [actions] 和页面局部交互 [view] 由当前宿主装配。
 * 原生壳/窗口信息 [chrome]。壳层只负责装配这几组对象，不再直接理解业务状态。
 */
@Stable
internal class ShellUi(
    val session: AuthenticatedSession,
    val platform: PlatformInfo,
    val navigator: ShellNavigator,
    val actions: ShellActions,
    val view: ShellViewState,
    val chrome: ShellChrome,
    val scope: kotlinx.coroutines.CoroutineScope,
) {
    val entryLoggingIn: Boolean get() = chrome.entryLoggingIn
    val sessionRecoveryInProgress: Boolean get() = chrome.sessionRecoveryInProgress
    val compactBottomBarOverlayPadding: Dp get() = chrome.compactBottomBarOverlayPadding
}

/** 页面骨架/顶栏依赖但不属于业务的数据。 */
@Stable
internal class ShellChrome(
    val entryLoggingIn: Boolean,
    val sessionRecoveryInProgress: Boolean,
    val compactBottomBarOverlayPadding: Dp,
    val nativeNavigationEnabled: Boolean,
    val useNativeTitleBar: Boolean,
    val popBackStack: () -> Unit,
    val onNativeTitleChanged: (String) -> Unit,
    val onNativeActionChanged: (NativeBarAction?) -> Unit,
    val onOpenExternalUrl: (String) -> Unit,
    val fileGateway: FileGateway,
    val directoryGateway: DirectoryGateway,
    val nativeHostState: NativeHostState,
    val systemCalendarGateway: SystemCalendarGateway,
    val assignmentCompletionStore: AssignmentCompletionStore?,
)

/** 页面级交互状态：跨页面导航时保持原生命周期（与上一个版本的壳层状态一致）。 */
@Stable
internal class ShellViewState(
    session: AuthenticatedSession,
    val homeTodayRequest: MutableState<Int>,
    val homeTodaySelected: MutableState<Boolean>,
    val homeSyncDialogVisible: MutableState<Boolean>,
    val citelSyncDialogVisible: MutableState<Boolean>,
    val aggregateSyncDialogVisible: MutableState<Boolean>,
    val partialSyncFailureDialogItems: MutableState<List<String>?>,
    val assignmentSearch: PageSearchState,
) {
    val phyVlabCaptchaAccount = mutableStateOf<Credentials?>(null)
    val phyVlabCaptchaFromAssignments = mutableStateOf(false)
    val showCourseCalendarExport = mutableStateOf(false)
    val legacyWarningDismissed = mutableStateOf(session.legacyHttpWarningDismissed)
    val classroomIntroBannerDismissed = mutableStateOf(session.classroomIntroBannerDismissed)
}

internal val LocalShellUi = staticCompositionLocalOf<ShellUi> {
    error("ShellUi is not provided; AuthenticatedAppShell must wrap its destinations.")
}

@Composable
internal fun rememberShellViewState(session: AuthenticatedSession): ShellViewState {
    val homeTodayRequest = remember { mutableStateOf(0) }
    val homeTodaySelected = remember { mutableStateOf(true) }
    val homeSyncDialogVisible = remember { mutableStateOf(false) }
    val citelSyncDialogVisible = remember { mutableStateOf(false) }
    val aggregateSyncDialogVisible = remember { mutableStateOf(false) }
    val partialSyncFailureDialogItems = remember { mutableStateOf<List<String>?>(null) }
    val assignmentSearch = remember { PageSearchState() }
    return remember(session) {
        ShellViewState(
            session,
            homeTodayRequest,
            homeTodaySelected,
            homeSyncDialogVisible,
            citelSyncDialogVisible,
            aggregateSyncDialogVisible,
            partialSyncFailureDialogItems,
            assignmentSearch,
        )
    }
}
