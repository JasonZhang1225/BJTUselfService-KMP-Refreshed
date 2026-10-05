package team.bjtuss.bjtuselfservice.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarBatch
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarFailure
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarInstallResult

private const val HELPER_PROPERTY = "bjtu.calendar.helper"

class DesktopSystemCalendarGateway : SystemCalendarGateway {
    override val isAvailable: Boolean
        get() = locateHelper()?.canExecute() == true

    override suspend fun install(calendars: List<SystemCalendarBatch>): SystemCalendarInstallResult =
        withContext(Dispatchers.IO) {
            val helper = locateHelper()
                ?: return@withContext SystemCalendarInstallResult.Failed(SystemCalendarFailure.UNAVAILABLE)
            val input = kotlin.io.path.createTempFile("bjtu-calendar-", ".json").toFile()
            try {
                input.writeText(calendars.toJsonPayload())
                val process = ProcessBuilder(helper.absolutePath, input.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                if (!process.waitFor(120, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return@withContext SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)
                }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                if (process.exitValue() == 3 || "\"reason\":\"permission\"" in output) {
                    return@withContext SystemCalendarInstallResult.Failed(SystemCalendarFailure.PERMISSION_DENIED)
                }
                if (process.exitValue() != 0 || "\"ok\":true" !in output) {
                    return@withContext SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)
                }
                SystemCalendarInstallResult.Installed(
                    calendarCount = output.intField("calendars"),
                    insertedEventCount = output.intField("inserted"),
                    updatedEventCount = output.intField("updated"),
                )
            } catch (_: SecurityException) {
                SystemCalendarInstallResult.Failed(SystemCalendarFailure.PERMISSION_DENIED)
            } catch (_: Exception) {
                SystemCalendarInstallResult.Failed(SystemCalendarFailure.IO)
            } finally {
                input.delete()
            }
    }

    private fun locateHelper(): File? {
        val executable = ProcessHandle.current().info().command().orElse(null)?.let(::File) ?: return null
        val contents = executable.parentFile?.parentFile ?: return null
        // 发布包优先使用自身 Resources，不能加载开发机 build/generated 下的 helper。
        File(contents, "Resources/Calendar/BJTUCalendarHelper")
            .takeIf(File::canExecute)
            ?.let { return it }
        // `desktopApp:run` 没有 bundle Resources，开发运行才回退到 Gradle 注入路径。
        return System.getProperty(HELPER_PROPERTY)
            ?.let(::File)
            ?.takeIf(File::canExecute)
    }
}

private fun List<SystemCalendarBatch>.toJsonPayload(): String = buildString {
    append("{\"calendars\":[")
    this@toJsonPayload.forEachIndexed { calendarIndex, calendar ->
        if (calendarIndex > 0) append(',')
        append("{\"name\":\"").append(calendar.name.jsonEscape()).append("\",")
        append("\"colorHex\":\"").append(calendar.colorHex.jsonEscape()).append("\",")
        calendar.managedCourseRange?.let { range ->
            append("\"managedCourseRange\":{")
            append("\"startLocal\":\"").append(range.startLocal.jsonEscape()).append("\",")
            append("\"endLocal\":\"").append(range.endLocal.jsonEscape()).append("\"},")
        }
        append("\"events\":[")
        calendar.events.forEachIndexed { eventIndex, event ->
            if (eventIndex > 0) append(',')
            append("{\"stableId\":\"").append(event.stableId.jsonEscape()).append("\",")
            append("\"title\":\"").append(event.title.jsonEscape()).append("\",")
            append("\"startLocal\":\"").append(event.startLocal.jsonEscape()).append("\",")
            append("\"endLocal\":\"").append(event.endLocal.jsonEscape()).append("\",")
            append("\"location\":\"").append(event.location.jsonEscape()).append("\",")
            append("\"notes\":\"").append(event.notes.jsonEscape()).append("\",")
            val recurrence = event.recurrence
            if (recurrence == null) {
                append("\"recurrence\":null}")
            } else {
                append("\"recurrence\":{")
                append("\"occurrenceCount\":").append(recurrence.occurrenceCount).append(',')
                append("\"excludedStartLocals\":[")
                recurrence.excludedStartLocals.forEachIndexed { index, excluded ->
                    if (index > 0) append(',')
                    append('"').append(excluded.jsonEscape()).append('"')
                }
                append("],\"lastEndLocal\":\"")
                    .append(recurrence.lastEndLocal.jsonEscape())
                    .append("\"}}")
            }
        }
        append("]}")
    }
    append("]}")
}

private fun String.jsonEscape(): String = buildString(length + 8) {
    this@jsonEscape.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
}

private fun String.intField(name: String): Int =
    Regex("\\\"$name\\\":(\\d+)").find(this)?.groupValues?.get(1)?.toIntOrNull() ?: 0
