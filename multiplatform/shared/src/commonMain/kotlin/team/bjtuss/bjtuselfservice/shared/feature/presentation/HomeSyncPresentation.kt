package team.bjtuss.bjtuselfservice.shared.feature.presentation

import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabContentSource
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.loginMessage

import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItem
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState

internal fun buildHomeSyncItems(
    isLoggingIn: Boolean,
    homeBusy: Boolean,
    homeFailed: Boolean,
    homeReady: Boolean,
    gradeBusy: Boolean,
    gradeFailed: Boolean,
    gradeReady: Boolean,
    homeworkBusy: Boolean,
    homeworkFailed: Boolean,
    homeworkReady: Boolean,
    examBusy: Boolean,
    examFailed: Boolean,
    examReady: Boolean,
    courseBusy: Boolean,
    courseFailed: Boolean,
    courseReady: Boolean,
    phyVlabEnabled: Boolean,
    phyVlabBusy: Boolean,
    phyVlabFailed: Boolean,
    phyVlabReady: Boolean,
    phyVlabRequiresCaptcha: Boolean = false,
    phyVlabFailureMessage: String? = null,
    physicsLabEnabled: Boolean = false,
    physicsLabBusy: Boolean = false,
    physicsLabFailed: Boolean = false,
    physicsLabReady: Boolean = false,
    citelEnabled: Boolean = false,
    citelBusy: Boolean = false,
    citelFailed: Boolean = false,
    citelReady: Boolean = false,
    physicsDemoEnabled: Boolean = false,
    physicsDemoBusy: Boolean = false,
    physicsDemoFailed: Boolean = false,
    physicsDemoReady: Boolean = false,
): List<HomeSyncItem> = buildList {
    add(
        homeSyncItem(
            title = "统一身份认证",
            busy = isLoggingIn,
            failed = false,
            ready = !isLoggingIn,
            busyDetail = "正在检查登录状态",
        ),
    )
    add(
        homeSyncItem(
            title = "首页账户状态",
            busy = homeBusy,
            failed = homeFailed,
            ready = homeReady,
            busyDetail = "正在读取邮箱与校园账户状态",
        ),
    )
    add(
        homeSyncItem(
            title = "成绩",
            busy = gradeBusy,
            failed = gradeFailed,
            ready = gradeReady,
            busyDetail = "正在同步成绩",
        ),
    )
    add(
        homeSyncItem(
            title = "课程表与校历周数",
            busy = courseBusy,
            failed = courseFailed,
            ready = courseReady,
            busyDetail = "正在同步课表并校准教学周",
        ),
    )
    add(
        homeSyncItem(
            title = "课程平台作业",
            busy = homeworkBusy,
            failed = homeworkFailed,
            ready = homeworkReady,
            busyDetail = "正在同步作业",
        ),
    )
    add(
        homeSyncItem(
            title = "考试安排",
            busy = examBusy,
            failed = examFailed,
            ready = examReady,
            busyDetail = "正在同步考试安排",
        ),
    )
    if (physicsLabEnabled) {
        add(homeSyncItem("物理实验", physicsLabBusy, physicsLabFailed, physicsLabReady,
            busyDetail = "正在读取已选实验（需要校园网）"))
    }
    if (physicsDemoEnabled) {
        add(homeSyncItem("演示实验", physicsDemoBusy, physicsDemoFailed, physicsDemoReady,
            busyDetail = "正在读取已预约演示实验（需要校园网）"))
    }
    // 物理相关的三项（都需要校园网）排在一起。
    if (phyVlabEnabled) {
        add(
            homeSyncItem(
                title = "物理在线",
                busy = phyVlabBusy,
                failed = phyVlabFailed,
                ready = phyVlabReady,
                busyDetail = "正在同步物理在线安排（需要校园网）",
                failureDetail = phyVlabFailureMessage ?: "本轮同步失败",
            ).copy(requiresCaptcha = phyVlabFailed && !phyVlabBusy && phyVlabRequiresCaptcha),
        )
    }
    if (citelEnabled) {
        add(homeSyncItem("CITEL 作业", citelBusy, citelFailed, citelReady,
            busyDetail = "正在读取作业折扣与截止时间"))
    }
}

internal fun homeSyncItem(
    title: String,
    busy: Boolean,
    failed: Boolean,
    ready: Boolean,
    busyDetail: String,
    failureDetail: String = "本轮同步失败",
): HomeSyncItem {
    val state = when {
        failed -> HomeSyncItemState.FAILED
        busy -> HomeSyncItemState.SYNCING
        ready -> HomeSyncItemState.SUCCESS
        else -> HomeSyncItemState.WAITING
    }
    val detail = when (state) {
        HomeSyncItemState.FAILED -> failureDetail
        HomeSyncItemState.SYNCING -> busyDetail
        HomeSyncItemState.SUCCESS -> "已完成"
        HomeSyncItemState.WAITING -> "等待同步"
    }
    return HomeSyncItem(title = title, detail = detail, state = state)
}

internal fun composeHomeSyncItems(input: PresentationInputs): List<HomeSyncItem> {
    val gradeState = input.gradeState
    val courseState = input.courseState
    val examState = input.examState
    val homeworkState = input.homeworkState
    val phyVlabState = input.phyVlabState
    val citelState = input.citelState
    val physicsLabState = input.physicsLabState
    val physicsDemoState = input.physicsDemoState
    val homeState = input.homeState
    val isLoggingIn = input.entryLoggingIn
    val physicsLabEnabled = input.settingsState.preferences.isPhysicsLabEnabled
    val phyVlabEnabled = input.settingsState.preferences.isPhyVlabEnabled
    val citelEnabled = input.settingsState.preferences.isCitelEnabled
    return buildHomeSyncItems(
        isLoggingIn = isLoggingIn,
        homeBusy = homeState.isRefreshing,
        homeFailed = homeState.failure != null,
        homeReady = homeState.status != null,
        gradeBusy = gradeState.isLoading || gradeState.isRefreshing,
        gradeFailed = gradeState.failure != null,
        gradeReady = gradeState.source != null,
        homeworkBusy = homeworkState.isLoading || homeworkState.isRefreshing,
        homeworkFailed = homeworkState.failure != null,
        homeworkReady = homeworkState.source != null,
        examBusy = examState.isLoading || examState.isRefreshing,
        examFailed = examState.failure != null,
        examReady = examState.source != null,
        courseBusy = courseState.isLoading || courseState.isRefreshing || courseState.isCalendarLoading,
        courseFailed = courseState.failure != null || courseState.calendarFailed,
        courseReady = courseState.source != null && courseState.weekResolved,
        physicsLabEnabled = physicsLabEnabled,
        physicsLabBusy = physicsLabState.refreshing,
        physicsLabFailed = physicsLabState.failed,
        physicsLabReady = physicsLabState.synced,
        physicsDemoEnabled = physicsDemoState.enabled,
        physicsDemoBusy = physicsDemoState.refreshing,
        physicsDemoFailed = physicsDemoState.failed,
        physicsDemoReady = physicsDemoState.synced,
        citelEnabled = citelEnabled,
        citelBusy = citelState.refreshing,
        citelFailed = citelState.failed,
        citelReady = citelState.lastSync != null && !citelState.fromCache,
        phyVlabEnabled = phyVlabEnabled,
        phyVlabBusy = phyVlabEnabled && phyVlabState.isLoading,
        phyVlabFailed = phyVlabEnabled && (phyVlabState.failure != null || phyVlabState.accountLoginRequired),
        phyVlabReady = phyVlabEnabled &&
            (phyVlabState.contentSource == PhyVlabContentSource.NETWORK ||
                phyVlabState.courses.isNotEmpty() || phyVlabState.agendaEvents.isNotEmpty()),
        phyVlabRequiresCaptcha = phyVlabState.captchaRequired,
        phyVlabFailureMessage = phyVlabState.loginMessage,
    )

}
