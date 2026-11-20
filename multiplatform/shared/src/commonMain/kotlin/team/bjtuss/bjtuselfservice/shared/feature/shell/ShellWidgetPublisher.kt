package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.datetime.toLocalDateTime
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.widget.encode
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession

/** 同步期间课表、实验、校历、课程性质会接连到达，合并成一次小组件快照写入。 */
internal const val COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS = 1_500L

@Composable
internal fun ShellCourseWidgetPublisher(
    session: AuthenticatedSession,
    data: ShellData,
    forcedRouteId: String?,
    nativeTabBarEnabled: Boolean,
) {
    val homeworkState = data.homeworkState
    val courseState = data.courseState
    val homeScheduleEvents = data.homeScheduleEvents
    val settingsState = data.settingsState
    val gradeState = data.gradeState
    // iOS 课程小组件：课表、实验、校历或日期变化时重写快照；内容（不含生成时间）不变就不写，
    // 避免每次重组都让 WidgetKit 重新加载时间线。
    // iOS 每个原生 Tab 都有一个壳宿主，只让主宿主写（否则同步期间每个宿主各算一遍、各写一遍、
    // 各触发一次 WidgetKit 全量刷新）；同步时数据连续变化，先等一会儿合并成一次，计算放到后台线程。
    // 小组件快照按北京时间展开；从原始时刻取北京日期，避免重新推断回拨期间的当地墙钟。
    val widgetToday = homeworkState.nowInstant.toLocalDateTime(team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone).date
    var lastWidgetDays by remember(session) { mutableStateOf<List<team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetDay>?>(null) }
    val staggeredClassTimes = settingsState.preferences.staggeredSecondSlot
    LaunchedEffect(courseState.courses, homeScheduleEvents, courseState.homeAcademicWeeks, courseState.currentWeek, widgetToday,
        gradeState.courseTypesByCode, staggeredClassTimes) {
        if (!shouldStartPhyVlabAutoSync(forcedRouteId, nativeTabBarEnabled)) return@LaunchedEffect
        if (courseState.courses.isEmpty() && homeScheduleEvents.isEmpty()) return@LaunchedEffect
        if (courseState.homeAcademicWeeks.isEmpty() && courseState.currentWeek <= 0) return@LaunchedEffect
        delay(COURSE_WIDGET_WRITE_DEBOUNCE_MILLIS)
        val courses = courseState.courses
        val events = homeScheduleEvents
        val weeks = courseState.homeAcademicWeeks
        val currentWeek = courseState.currentWeek
        val types = gradeState.courseTypesByCode
        val previous = lastWidgetDays
        val written = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val (widgetStart, widgetDayCount) = team.bjtuss.bjtuselfservice.shared.feature.widget.courseWidgetRange(widgetToday)
            val snapshot = team.bjtuss.bjtuselfservice.shared.feature.widget.buildCourseWidgetSnapshot(
                schedule = team.bjtuss.bjtuselfservice.shared.feature.home.HomeSchedulePresentation(
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
            team.bjtuss.bjtuselfservice.shared.feature.widget.writeCourseWidgetSnapshot(snapshot.encode())
            snapshot.days
        }
        if (written != null) lastWidgetDays = written
    }
}
