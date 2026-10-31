package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeAgenda
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AggregatedAssignment
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentCompletionKey
import team.bjtuss.bjtuselfservice.shared.feature.assignment.AssignmentSource
import team.bjtuss.bjtuselfservice.shared.feature.assignment.assignmentCompletionState
import team.bjtuss.bjtuselfservice.shared.feature.assignment.completionKey

/** 只移除临近截止与折扣提醒；原始周日程、提交状态、时间窗口与顺序均不改变。 */
internal fun filterHomeAssignmentReminders(
    agenda: HomeAgenda,
    assignments: List<AggregatedAssignment>,
    completedKeys: Set<AssignmentCompletionKey>,
): HomeAgenda {
    if (completedKeys.isEmpty()) return agenda
    val homework = agenda.dueSoonHomework.filter { it.completionKey() !in completedKeys }
    val events = agenda.dueSoonEvents.filter { it.reminderCompletionKey(assignments) !in completedKeys }
    val discounts = agenda.dueSoonDiscountEvents.filter {
        it.reminderCompletionKey(assignments) !in completedKeys
    }
    return if (
        homework.size == agenda.dueSoonHomework.size &&
        events.size == agenda.dueSoonEvents.size &&
        discounts.size == agenda.dueSoonDiscountEvents.size
    ) {
        agenda
    } else {
        agenda.copy(
            dueSoonHomework = homework,
            dueSoonEvents = events,
            dueSoonDiscountEvents = discounts,
        )
    }
}

/** 复用按 store 重建的订阅，切账号的第一帧也不会使用上一账号的标记。 */
@Composable
internal fun rememberHomeAssignmentReminders(
    agenda: HomeAgenda,
    assignments: List<AggregatedAssignment>,
): HomeAgenda {
    val completion = assignmentCompletionState()
    return remember(agenda, assignments, completion.completedKeys) {
        filterHomeAssignmentReminders(agenda, assignments, completion.completedKeys)
    }
}

private fun PhyVlabEvent.reminderCompletionKey(assignments: List<AggregatedAssignment>): AssignmentCompletionKey? {
    // 物理在线活动派生事件已有完整课程/活动身份，不受当前选中课程影响。
    val parts = id.split('-')
    if (parts.size == 4 && parts[0] == "activity" && parts[3] == "due") {
        val courseId = parts[1].toIntOrNull()
        val activityId = parts[2].toIntOrNull()
        if (courseId != null && activityId != null) {
            return AssignmentCompletionKey(AssignmentSource.PHYVLAB, courseId, activityId)
        }
    }
    val url = eventUrl?.takeIf { it.isNotBlank() }
    val matches = assignments.mapNotNull { assignment ->
        if (id.startsWith("citel-")) {
            // 截止与折扣事件共用同一作业完成键，手动完成后两类提醒一起消失。
            assignment.citel?.takeIf { task ->
                (id == "citel-due-${task.id}" || id == "citel-discount-${task.id}") &&
                    (url == null || task.url == url)
            }?.completionKey()
        } else {
            // 学校日历事件没有活动身份时，只用明确链接关联；不以标题猜测。
            assignment.physical?.takeIf { url != null && it.activityUrl == url }?.completionKey()
        }
    }.distinct()
    // 旧事件无法唯一关联（例如同 id 的不同课程或 CITEL 题型）时保留原提醒。
    return matches.singleOrNull()
}
