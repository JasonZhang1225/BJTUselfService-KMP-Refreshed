package team.bjtuss.bjtuselfservice.shared.feature.home

/** Immutable view data. All date/course rules and actions remain in the shared home workspace. */
class NativeHomeMark(val label: String, val count: Int, val colorRgb: Long)
class NativeHomeDay(
    val id: String,
    val weekday: String,
    val number: String,
    val selected: Boolean,
    val today: Boolean,
    val marks: List<NativeHomeMark>,
    val accessibilityLabel: String,
    val onSelect: () -> Unit,
)
class NativeHomeEvent(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
    val done: Boolean,
    val colorRgb: Long,
    val onOpen: () -> Unit,
)
class NativeHomeChangeGroup(val id: String, val title: String, val summary: String, val onOpen: () -> Unit)
class NativeHomeSummary(val title: String, val value: String, val detail: String, val onOpen: () -> Unit)
class NativeDesktopHome(
    val weekTitle: String,
    val dateRange: String,
    val days: List<NativeHomeDay>,
    val selectedDateTitle: String,
    val events: List<NativeHomeEvent>,
    val loading: Boolean,
    val errorMessage: String?,
    val dueSoon: List<String>,
    val nowEvents: List<NativeHomeEvent>,
    val summaries: List<NativeHomeSummary>,
    val changes: List<NativeHomeChangeGroup>,
    val onPrevious: (() -> Unit)?,
    val onNext: (() -> Unit)?,
    val onToday: () -> Unit,
    val todaySelected: Boolean,
    val todayRequest: Int,
    val onOpenHomework: () -> Unit,
    val onClearChanges: () -> Unit,
)

/** Non-agenda data/actions collected by HomeWorkspace, reused when the calendar is native. */
internal class NativeHomeContext(
    val errorMessage: String?,
    val nowEvents: List<NativeHomeEvent>,
    val summaries: List<NativeHomeSummary>,
    val changes: List<NativeHomeChangeGroup>,
    val onClearChanges: () -> Unit,
    val onOpenCourse: (team.bjtuss.bjtuselfservice.shared.domain.course.Course) -> Unit,
)
