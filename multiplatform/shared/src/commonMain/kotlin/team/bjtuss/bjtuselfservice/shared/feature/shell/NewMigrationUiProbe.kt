package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.data.home.*
import team.bjtuss.bjtuselfservice.shared.data.classroom.*
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.*
import team.bjtuss.bjtuselfservice.shared.domain.classroom.*
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.*
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.exam.ExamSchedule
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus
import team.bjtuss.bjtuselfservice.shared.feature.home.*
import team.bjtuss.bjtuselfservice.shared.feature.settings.*
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.*
import team.bjtuss.bjtuselfservice.shared.update.AppUpdateChecker

/** Explicit development entry point: synthetic data, no credentials, networking or persistent storage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewMigrationUiProbe() {
    var section by remember { mutableStateOf(AppSection.HOME) }
    var dark by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var largeText by remember { mutableStateOf(false) }
    var peopleFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val weeks = remember { listOf(OccupancyWeekDate(1, "11/23", "11/29", LocalDate(2026, 11, 23))) }
    val courses = remember { listOf(Course(1, "fixture", "数据科学测试课程", "测试教师", 28, "第1周", "思源楼，SY101", false)) }
    val settings = remember { SettingsScreenModel(AppPreferences(), { true }, { true }, { true }, { AppUpdateChecker.Result.Unavailable }) }
    val preferences = settings.state.collectAsState().value.preferences
    val tabs = bottomNavSections(preferences)
    val home = remember { HomeScreenModel(object : HomeStatusRepository {
        override fun load() = HomeStatus("0", "50", "30")
        override suspend fun refresh() = HomeStatusRefreshResult.Success(load())
    }) }
    val occupancy = remember { ClassroomOccupancyScreenModel(
        repository = object : ClassroomOccupancyRepository {
            override suspend fun fetchOccupancy(week: Int, buildingId: String, semesterId: String?) = ClassroomOccupancyResult.Success(
                listOf("SY101", "SY102").map { room -> ClassroomOccupancy(room, 90,
                    (1..7).flatMap { day -> (1..7).map { period -> (day to period) to if (period == 4) OccupancyKind.EXAM else OccupancyKind.FREE } }.toMap()) })
            override suspend fun fetchSemesters() = SemesterOptions(null, emptyList())
            override suspend fun fetchWeekDates() = emptyMap<String, List<OccupancyWeekDate>>()
        },
        peopleRepository = object : ClassroomRepository {
            override suspend fun fetchBuildingInfo(buildingName: String): ClassroomFetchResult = if (peopleFailed) ClassroomFetchResult.Failure(ClassroomFetchFailure.NETWORK)
                else ClassroomFetchResult.Success(ClassroomBuildingInfo(buildingName, "09:00", "09:05", listOf(ClassroomCapacity("思源楼101", 20.0, 18, 90), ClassroomCapacity("第三方额外999", 0.0, 0, 50))))
        }, currentWeekProvider = { 1 }, todayWeekdayProvider = { 4 },
    ) }
    LaunchedEffect(occupancy) { occupancy.selectBuilding(OccupancyBuilding("1", "思源楼")) }
    PlatformAppTheme(useDarkTheme = dark, dynamicColorEnabled = false) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeText) 1.5f else density.fontScale)) {
        Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AppSection.HOME, AppSection.MORE, AppSection.SETTINGS, AppSection.CLASSROOM_OCCUPANCY).forEach { item -> TextButton({ section = item }) { Text(item.title) } }
                TextButton({ dark = !dark }) { Text(if (dark) "切换浅色" else "切换深色") }
                TextButton({ largeText = !largeText }) { Text(if (largeText) "正常文字" else "放大文字") }
                TextButton({ expanded = !expanded }) { Text(if (expanded) "切换窄屏" else "切换宽屏") }
                TextButton({ peopleFailed = !peopleFailed; scope.launch { occupancy.refreshPeople() } }) { Text(if (peopleFailed) "恢复人数服务" else "模拟人数失败") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (section) {
                    AppSection.HOME -> CompositionLocalProvider(LocalHomeSchedule provides HomeSchedulePresentation(courses, weeks, 1, LocalDate(2026, 11, 26)) { section = AppSection.SCHEDULE }) {
                        HomeWorkspace(home, currentPlatform(), expanded, homework = emptyList(),
                            exams = listOf(ExamSchedule(examType = "期末", courseName = "测试考试", examTimeAndPlace = "2026-11-26 10:10 测试教室", examStatus = "", detail = "")),
                            currentWeek = 1, academicWeeks = weeks, now = LocalDateTime(2026, 11, 26, 9, 0), timeZone = TimeZone.of("Asia/Shanghai"),
                            isAgendaLoading = false, isRefreshing = false, onRefresh = {}, onOpenMailbox = {}, onOpenHomework = {}, onOpenExams = {},
                            changes = emptyList(), onClearAllChanges = {}, onClearChangeDomain = {}, onOpenChangeDomain = {}, modifier = Modifier.fillMaxSize())
                    }
                    AppSection.MORE -> MoreWorkspace(preferences, { section = it }, Modifier.fillMaxSize())
                    AppSection.SETTINGS -> SettingsWorkspace(settings, "测试账号", currentPlatform(), expanded, {}, Modifier.fillMaxSize())
                    AppSection.CLASSROOM_OCCUPANCY -> ClassroomOccupancyBuildingWorkspace(occupancy, modifier = Modifier.fillMaxSize())
                    else -> Text("已打开${section.title}", modifier = Modifier.padding(24.dp))
                }
            }
            CompactBottomNavigation(section, tabs, { section = it })
        }
        }
        }
    }
}
