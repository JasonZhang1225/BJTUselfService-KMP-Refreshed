package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class AppCommand {
    NAVIGATE_HOME,
    NAVIGATE_GRADES,
    NAVIGATE_SCHEDULE,
    NAVIGATE_EXAMS,
    NAVIGATE_HOMEWORK,
    NAVIGATE_COURSEWARE,
    NAVIGATE_PLAYBACK,
    NAVIGATE_CLASSROOMS,
    NAVIGATE_CLASSROOM_OCCUPANCY,
    NAVIGATE_MAILBOX,
    NAVIGATE_SETTINGS,
    REFRESH_CURRENT,
}

/**
 * Carries commands from platform-native menus and shortcuts into the shared app shell.
 * Commands are consumed once and never persisted across launches or login sessions.
 */
class AppCommandBus {
    private val shared = MutableSharedFlow<AppCommand>(extraBufferCapacity = 16)

    val commands: Flow<AppCommand> = shared.asSharedFlow()
    val subscriptionCount: StateFlow<Int> = shared.subscriptionCount

    fun send(command: AppCommand): Boolean = shared.tryEmit(command)
}
