package team.bjtuss.bjtuselfservice.shared.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import team.bjtuss.bjtuselfservice.shared.feature.presentation.AccountPageState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.grade.CourseType
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSchedulePresentation
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetDay
import team.bjtuss.bjtuselfservice.shared.feature.widget.buildCourseWidgetSnapshot
import team.bjtuss.bjtuselfservice.shared.feature.widget.courseWidgetRange
import team.bjtuss.bjtuselfservice.shared.feature.widget.encode
import team.bjtuss.bjtuselfservice.shared.feature.widget.writeCourseWidgetSnapshot

/**
 * 当前账号唯一的小组件发布者，不依赖页面宿主或 Compose。
 *
 * 复用账号展示模型的原始课表与首页补充日程；只有原来的七项输入变化才重新防抖。
 * 快照仍在后台构建和写入，按不含生成时间的 days 去重，账号结束时由 AccountWork 取消。
 */
internal class AccountCourseWidgetPublisher(
    private val presentationState: () -> StateFlow<AccountPageState>?,
    private val accountWork: AccountWork?,
    private val scope: CoroutineScope,
    private val writeSnapshot: (String) -> Unit,
) {
    constructor(session: AuthenticatedSession, scope: CoroutineScope) : this(
        { session.pageStateModel?.state }, session.accountWork, scope, ::writeCourseWidgetSnapshot,
    )

    private var started = false
    private var lastWidgetDays: List<CourseWidgetDay>? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            val state = presentationState() ?: return@launch
            state.map { presentation ->
                val input = presentation.inputs
                WidgetInputs(
                    courses = input.courseState.courses,
                    homeScheduleEvents = presentation.schedule.homeEvents,
                    weeks = input.courseState.homeAcademicWeeks,
                    currentWeek = input.courseState.currentWeek,
                    // 仍按原始时刻取北京日期，不从当地墙钟反推。
                    widgetToday = input.homeworkState.nowInstant.toLocalDateTime(SchoolTimeZone).date,
                    types = input.gradeState.courseTypesByCode,
                    staggeredClassTimes = input.settingsState.preferences.staggeredSecondSlot,
                )
            }.distinctUntilChanged().collectLatest { input ->
                if (input.courses.isEmpty() && input.homeScheduleEvents.isEmpty()) return@collectLatest
                if (input.weeks.isEmpty() && input.currentWeek <= 0) return@collectLatest
                delay(COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS)
                val courses = input.courses
                val events = input.homeScheduleEvents
                val weeks = input.weeks
                val currentWeek = input.currentWeek
                val widgetToday = input.widgetToday
                val types = input.types
                val staggeredClassTimes = input.staggeredClassTimes
                val previous = lastWidgetDays
                val written = accountWork.inAccount { withContext(Dispatchers.Default) {
                    val (widgetStart, widgetDayCount) = courseWidgetRange(widgetToday)
                    val snapshot = buildCourseWidgetSnapshot(
                        schedule = HomeSchedulePresentation(
                            courses = courses,
                            supplementalCourses = events,
                            academicWeeks = weeks,
                            currentWeek = currentWeek,
                            today = widgetToday,
                            staggeredClassTimes = staggeredClassTimes,
                        ),
                        startDate = widgetStart,
                        generatedAtEpochSeconds = kotlin.time.Clock.System.now().epochSeconds,
                        dayCount = widgetDayCount,
                        courseTypesByCode = types,
                    )
                    if (snapshot.days == previous) return@withContext null
                    val encoded = snapshot.encode()
                    val work = accountWork
                    if (work == null) writeSnapshot(encoded) else work.mutate { writeSnapshot(encoded) }
                    snapshot.days
                }
                }
                if (written != null) lastWidgetDays = written
            }
        }
    }

    /** 与原 LaunchedEffect 的七个键一一对应，其他展示状态不重置防抖。 */
    private data class WidgetInputs(
        val courses: List<Course>,
        val homeScheduleEvents: List<Course>,
        val weeks: List<OccupancyWeekDate>,
        val currentWeek: Int,
        val widgetToday: LocalDate,
        val types: Map<String, CourseType>?,
        val staggeredClassTimes: Boolean,
    )
}

/** 同步期间课表、实验、校历、课程性质会接连到达，合并成一次小组件快照写入。 */
internal const val COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS = 1_500L
