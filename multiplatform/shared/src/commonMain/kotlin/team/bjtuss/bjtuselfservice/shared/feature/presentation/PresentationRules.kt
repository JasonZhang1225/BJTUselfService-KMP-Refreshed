package team.bjtuss.bjtuselfservice.shared.feature.presentation

import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.loginMessage

import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregatedAssignment
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSourceSync
import team.bjtuss.bjtuselfservice.shared.feature.assignment.aggregateAssignments
import team.bjtuss.bjtuselfservice.shared.feature.citel.CitelState
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.course.scheduleEventCourses
import team.bjtuss.bjtuselfservice.shared.feature.exam.ExamScheduleUiState
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkContentSource
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkUiState
import team.bjtuss.bjtuselfservice.shared.feature.phydemo.PhysicsDemoState
import team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabState
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabUiState

/**
 * 把多个业务状态合成壳层与首页需要的派生数据。
 *
 * 这里只有纯函数，不持有任务、不启动网络、也不订阅任何状态；由账号展示模型统一订阅输入并决定重算时机。实验/考试/演示实验同时影响课表、首页和小组件，
 * 合成规则集中在此，页面与壳层不再各自复制一份。
 */

/** 课表网格里的考试/实验/演示实验片段。 */
internal fun composeScheduleEvents(
    courseState: CourseScheduleUiState,
    examState: ExamScheduleUiState,
    physicsLabState: PhysicsLabState,
    physicsDemoState: PhysicsDemoState,
    physicsLabEnabled: Boolean,
): List<Course> = scheduleEventCourses(
    examState.exams,
    if (physicsLabEnabled) physicsLabState.labs else emptyList(),
    courseState.academicWeeks,
    if (physicsDemoState.enabled) physicsDemoState.bookings else emptyList(),
)

/** 课表页展示用的状态：普通课程 + 补充事件 + 实验/演示实验预约。 */
internal fun composeScheduleStateWithEvents(
    courseState: CourseScheduleUiState,
    events: List<Course>,
    physicsLabState: PhysicsLabState,
    physicsDemoState: PhysicsDemoState,
    physicsLabEnabled: Boolean,
): CourseScheduleUiState = courseState.copy(
    supplementalCourses = events,
    physicsLabs = if (physicsLabEnabled) physicsLabState.labs else emptyList(),
    physicsDemoBookings = if (physicsDemoState.enabled) physicsDemoState.bookings else emptyList(),
)

/** 首页周日程使用同一套事件，但按首页自己的教学周日历展开。 */
internal fun composeHomeScheduleEvents(
    courseState: CourseScheduleUiState,
    examState: ExamScheduleUiState,
    physicsLabState: PhysicsLabState,
    physicsDemoState: PhysicsDemoState,
    physicsLabEnabled: Boolean,
): List<Course> = scheduleEventCourses(
    examState.exams,
    if (physicsLabEnabled) physicsLabState.labs else emptyList(),
    courseState.homeAcademicWeeks,
    if (physicsDemoState.enabled) physicsDemoState.bookings else emptyList(),
)

/** 三来源作业聚合。按学校北京时间换算截止时刻，与日期筛选保持一致。 */
internal fun composeAggregatedAssignments(
    homeworkState: HomeworkUiState,
    phyVlabActivities: List<PhyVlabActivity>,
    citelState: CitelState,
    phyVlabEnabled: Boolean,
    citelEnabled: Boolean,
): List<AggregatedAssignment> = aggregateAssignments(
    homeworkState.homework,
    if (phyVlabEnabled) phyVlabActivities else emptyList(),
    if (citelEnabled) citelState.tasks else emptyList(),
)

/** 聚合作业页每个来源的同步胶囊状态。 */
internal fun composeAssignmentSync(
    homeworkState: HomeworkUiState,
    phyVlabState: PhyVlabUiState,
    citelState: CitelState,
    phyVlabEnabled: Boolean,
    citelEnabled: Boolean,
): List<AssignmentSourceSync> = buildList {
    add(
        AssignmentSourceSync(
            AssignmentSource.COURSE_PLATFORM,
            homeworkState.isLoading || homeworkState.isRefreshing,
            homeworkState.failure != null,
            homeworkState.source == HomeworkContentSource.NETWORK,
            homeworkState.source == HomeworkContentSource.CACHE,
            if (homeworkState.failure != null) "课程平台作业同步失败，请检查网络或登录状态。" else null,
        ),
    )
    if (phyVlabEnabled) {
        add(
            AssignmentSourceSync(
                AssignmentSource.PHYVLAB,
                phyVlabState.isLoading,
                phyVlabState.failure != null || phyVlabState.accountLoginRequired,
                phyVlabState.contentSource == team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabContentSource.NETWORK,
                phyVlabState.contentSource == team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabContentSource.CACHE,
                phyVlabState.loginMessage ?: phyVlabState.failureDetail
                    ?: "请检查校园网或重新登录物理在线。".takeIf { phyVlabState.failure != null },
                requiresCaptcha = phyVlabState.captchaRequired,
            ),
        )
    }
    if (citelEnabled) {
        add(
            AssignmentSourceSync(
                AssignmentSource.CITEL,
                citelState.refreshing,
                citelState.failed,
                citelState.lastSync != null && !citelState.fromCache,
                citelState.fromCache,
                citelState.message,
            ),
        )
    }
}
