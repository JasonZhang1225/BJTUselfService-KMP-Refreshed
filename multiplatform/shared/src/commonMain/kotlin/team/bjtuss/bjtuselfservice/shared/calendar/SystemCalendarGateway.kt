package team.bjtuss.bjtuselfservice.shared.calendar

import team.bjtuss.bjtuselfservice.shared.domain.calendar.AcademicCalendarDateRange
import team.bjtuss.bjtuselfservice.shared.domain.calendar.AcademicCalendarEvent

data class SystemCalendarBatch(
    val name: String,
    /** `#RRGGBB`，平台不支持或颜色非法时可忽略。 */
    val colorHex: String,
    val events: List<AcademicCalendarEvent>,
    /**
     * Course export replaces all app-managed course occurrences inside this academic-term window,
     * then writes [events] as the exact selected-week result. Exam batches leave it null.
     */
    val managedCourseRange: AcademicCalendarDateRange? = null,
)

enum class SystemCalendarFailure {
    PERMISSION_DENIED,
    UNAVAILABLE,
    IO,
}

sealed interface SystemCalendarInstallResult {
    data class Installed(
        val calendarCount: Int,
        val insertedEventCount: Int,
        val updatedEventCount: Int,
    ) : SystemCalendarInstallResult

    data object Cancelled : SystemCalendarInstallResult
    data class Failed(val reason: SystemCalendarFailure) : SystemCalendarInstallResult
}

/** Apple 平台实现用 EventKit 创建/复用独立日历；Android 首版继续使用 ICS。 */
interface SystemCalendarGateway {
    val isAvailable: Boolean
    suspend fun install(calendars: List<SystemCalendarBatch>): SystemCalendarInstallResult
}

object UnavailableSystemCalendarGateway : SystemCalendarGateway {
    override val isAvailable: Boolean = false
    override suspend fun install(calendars: List<SystemCalendarBatch>): SystemCalendarInstallResult =
        SystemCalendarInstallResult.Failed(SystemCalendarFailure.UNAVAILABLE)
}
