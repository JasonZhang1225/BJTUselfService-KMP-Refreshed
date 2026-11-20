package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.grade.GradeChangeNoticeDialog
import team.bjtuss.bjtuselfservice.shared.feature.home.LiveActivityPermissionIntroDialog
import team.bjtuss.bjtuselfservice.shared.feature.home.homeSyncDialogTitle
import team.bjtuss.bjtuselfservice.shared.feature.home.rememberLiveActivityPermissionIntro
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabCaptchaSheet
import team.bjtuss.bjtuselfservice.shared.feature.settings.AppUpdateResultDialog

@Composable
internal fun ShellOverlays(
    ui: ShellUi,
    forcedRouteId: String?,
) {
    val session = ui.session
    val data = rememberShellData(session, ui.sessionRecoveryInProgress, overlays = true)
    val settingsModel = session.settingsModel
    val settingsState = data.settingsState
    val gradeModel = session.gradeModel
    val gradeState = data.gradeState

    val phyVlabModel = session.phyVlabModel
    val scope = ui.scope
    val citelState = data.citelState
    val entryLoggingIn = ui.entryLoggingIn
    val homeSyncInProgress = data.homeSyncInProgress
    val homeSyncFailureItems = data.homeSyncFailureItems
    val homeSyncItems = data.homeSyncItems
    val navigateToSection: (AppSection) -> Unit = ui.actions::navigateToSection
    val refresh: () -> Unit = ui.actions::refresh
    val openPhyVlabCaptcha: (Boolean) -> Unit = ui.actions::openPhyVlabCaptcha
    val closePhyVlabCaptcha: () -> Unit = ui.actions::closePhyVlabCaptcha
    val refreshPhyVlabAfterLogin: () -> Unit = ui.actions::refreshPhyVlabAfterLogin
    var phyVlabCaptchaAccount by ui.view.phyVlabCaptchaAccount
    var citelSyncDialogVisible by ui.view.citelSyncDialogVisible
    var homeSyncDialogVisible by ui.view.homeSyncDialogVisible
    var partialSyncFailureDialogItems by ui.view.partialSyncFailureDialogItems

    if (citelSyncDialogVisible) AppleAlert(
        title = "CITEL 同步状态",
        message = citelState.message ?: if (citelState.fromCache) "本轮尚未同步，当前显示上次同步的缓存。" else "尚未同步 CITEL 作业。",
        actions = listOf(
            AppleAlertAction("关闭", AppleAlertRole.Cancel) { citelSyncDialogVisible = false },
            AppleAlertAction("刷新") {
                citelSyncDialogVisible = false
                scope.launch { session.citelModel?.refresh() }
            },
        ),
        onDismissRequest = { citelSyncDialogVisible = false },
    )

    // 检查结果弹窗放在整个壳内容之后渲染：发现新版本时无论当前在哪个页面都能看到
    // 「前往下载」，不依赖用户停留在设置页（设置页内按钮触发的结果也走同一弹窗）。
    val showGuideOnThisHost = !entryLoggingIn &&
        (forcedRouteId == null || forcedRouteId == AppSection.HOME.name)
    // 启动弹窗最多两个：1.8.6 版本说明（新装与升级都只看一次）→ Android 通知权限说明，
    // 之后才是发现新版本时的更新提示。旧的 1.8.3/1.8.4/1.8.5/物理在线说明已并入 1.8.6 说明。
    val showRelease186Guide = showGuideOnThisHost && !settingsState.preferences.release186GuideAcknowledged
    val blockingGuideVisible = showRelease186Guide
    // 实时活动的通知权限说明（只有 Android 有）：版本说明都确认后再问，排在更新提示前，一次只弹一个。
    val liveActivityPermissionIntro = if (showGuideOnThisHost && !blockingGuideVisible) {
        rememberLiveActivityPermissionIntro()
    } else {
        null
    }
    when {
        showRelease186Guide -> Release186GuideDialog(
            phyVlabDisabledOnUpgrade = settingsState.preferences.phyVlabAccountNoticePending,
            saveFailed = settingsState.saveFailed,
            onConfirm = settingsModel::acknowledgeRelease186Guide,
        )
        liveActivityPermissionIntro != null -> LiveActivityPermissionIntroDialog(liveActivityPermissionIntro)
        else -> AppUpdateResultDialog(settingsState.updateCheck, settingsModel::dismissUpdateCheck, settingsModel::postponeUpdate)
    }
    partialSyncFailureDialogItems?.takeUnless { blockingGuideVisible }?.let { items ->
        PartialSyncFailureDialog(
            failedItems = items,
            onRetry = {
                partialSyncFailureDialogItems = null
                refresh()
            },
            onDismiss = { partialSyncFailureDialogItems = null },
        )
    }
    if (homeSyncDialogVisible && !blockingGuideVisible) {
        HomeSyncDetailsDialog(
            title = homeSyncDialogTitle(
                isLoggingIn = entryLoggingIn,
                isSyncing = homeSyncInProgress,
                hasFailures = homeSyncFailureItems.isNotEmpty(),
            ),
            items = homeSyncItems,
            canRetry = !entryLoggingIn && homeSyncFailureItems.isNotEmpty() && !homeSyncInProgress,
            onRetry = {
                homeSyncDialogVisible = false
                refresh()
            },
            onDismiss = { homeSyncDialogVisible = false },
            onEnterCaptcha = { openPhyVlabCaptcha(false) },
        )
    }
    if (!blockingGuideVisible) phyVlabCaptchaAccount?.let { account ->
        PhyVlabCaptchaSheet(
            account = account,
            prepare = phyVlabModel::prepareManualLogin,
            runInAccount = phyVlabModel::runManualLogin,
            submit = phyVlabModel::submitManualLogin,
            onSuccess = {
                phyVlabModel.rememberManualSession(account.username)
                phyVlabModel.sessionChanged()
                refreshPhyVlabAfterLogin()
                true
            },
            onRejected = phyVlabModel::manualCredentialsRejected,
            onCompleted = { phyVlabCaptchaAccount = null },
            onDismiss = closePhyVlabCaptcha,
        )
    }
    gradeState.pendingChangeNotice?.takeUnless { blockingGuideVisible }?.let { notice ->
        GradeChangeNoticeDialog(
            changes = notice,
            onDismiss = gradeModel::dismissChangeNotice,
            onOpenGrades = {
                gradeModel.dismissChangeNotice()
                navigateToSection(AppSection.GRADES)
            },
        )
    }

}
