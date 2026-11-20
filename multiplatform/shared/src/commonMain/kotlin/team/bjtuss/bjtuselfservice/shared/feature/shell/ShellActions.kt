package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.phyVlabDebug
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource

/**
 * 页面可以发起的动作。只暴露“做什么”，不暴露模型和状态；
 * 具体选择/压栈/刷新分别交给模型与 [ShellRefresher]，页面不需要知道实现。
 */
@Stable
internal class ShellActions(
    private val session: AuthenticatedSession,
    private val navigator: ShellNavigator,
    private val scope: CoroutineScope,
    private val refresher: ShellRefresher,
    private val view: ShellViewState,
    /** 当前页刷新（section/route 由壳层在调用时决定）。 */
    private val refreshAction: () -> Unit,
) {
    fun navigateToSection(target: AppSection) {
        val preferences = session.settingsModel.state.value.preferences
        navigator.navigateToSection(target, preferences, bottomNavSections(preferences))
    }

    fun openNativeRoute(routeId: String) {
        navigator.openNativeRoute(routeId)
    }

    fun refresh() {
        refreshAction()
    }

    fun refreshAssignmentSource(source: AssignmentSource) {
        refresher.refreshAssignmentSource(source)
    }

    fun retryPhyVlabDetail() {
        refresher.retryPhyVlabDetail()
    }

    fun openPhyVlabSettings() {
        navigator.openPhyVlabSettings()
    }

    fun openExamDetail(examId: Int) {
        session.examScheduleModel.showExamDetails(examId)
        navigator.pushOrOpen(ExamDetailRoute, EXAM_DETAIL_ROUTE_ID)
    }

    fun startMailboxCompose() {
        if (navigator.useNativeSecondaryRoutes) navigator.openNativeRoute(MAILBOX_COMPOSE_ROUTE_ID)
        scope.launch { session.mailboxModel.startCompose() }
    }

    fun logout() = session.onLogout()

    fun openPhyVlabCaptcha(fromAssignments: Boolean) {
        // The native sync sheet can cover the Compose host while this account lookup runs.
        phyVlabDebug("openPhyVlabCaptcha requested fromAssignments=$fromAssignments alreadyOpen=${view.phyVlabCaptchaAccount.value != null}")
        scope.launch(Dispatchers.Main.immediate) {
            if (view.phyVlabCaptchaAccount.value == null) {
                val account = session.phyVlabAccountModel?.credentials()
                phyVlabDebug("openPhyVlabCaptcha credentialsLoaded=${account != null}")
                if (account != null && view.phyVlabCaptchaAccount.value == null) {
                    view.phyVlabCaptchaFromAssignments.value = fromAssignments
                    view.phyVlabCaptchaAccount.value = account
                    view.homeSyncDialogVisible.value = false
                    view.aggregateSyncDialogVisible.value = false
                }
            }
        }
    }
    fun closePhyVlabCaptcha() {
        phyVlabDebug("closePhyVlabCaptcha returnToAssignments=${view.phyVlabCaptchaFromAssignments.value}")
        view.phyVlabCaptchaAccount.value = null
        if (view.phyVlabCaptchaFromAssignments.value) view.aggregateSyncDialogVisible.value = true
        else view.homeSyncDialogVisible.value = true
    }

    fun refreshPhyVlabAfterLogin() { session.refreshService?.refreshPhyVlabAfterAccountSaved() }
}
