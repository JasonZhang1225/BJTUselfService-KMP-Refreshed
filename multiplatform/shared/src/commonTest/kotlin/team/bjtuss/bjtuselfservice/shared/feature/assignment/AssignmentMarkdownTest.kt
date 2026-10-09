package team.bjtuss.bjtuselfservice.shared.feature.assignment

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkUiState
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabUiState
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.*
import team.bjtuss.bjtuselfservice.shared.feature.citel.*

class AssignmentMarkdownTest {
    @Test fun physicalExportIncludesSelectedRequirementsAndFiles() {
        val task = PhyVlabActivity(1, 2, "物理", "实验一", "assign", "https://example.test")
        val item = aggregateAssignments(emptyList(), listOf(task), emptyList()).single()
        val text = aggregateAssignmentMarkdown(item, HomeworkUiState(), PhyVlabUiState(selectedActivity = task,
            assignmentDetail = PhyVlabAssignmentDetail(description = "测量并分析误差", submittedFiles = listOf(PhyVlabSubmissionFile("报告.pdf")))), CitelState())
        assertTrue(text.contains("测量并分析误差"))
        assertTrue(text.contains("报告.pdf"))
        val unselected = aggregateAssignmentMarkdown(item, HomeworkUiState(), PhyVlabUiState(selectedActivity = task.copy(id = 3),
            assignmentDetail = PhyVlabAssignmentDetail(description = "另一个任务的正文")), CitelState())
        assertFalse(unselected.contains("另一个任务的正文"))
    }
    @Test fun citelExportIncludesSubmissionAndOriginalLink() {
        val task = CitelTask(1, 2, "程序设计", "作业一", "https://example.test/task", false, status = "已提交")
        val item = aggregateAssignments(emptyList(), emptyList(), listOf(task)).single()
        val text = aggregateAssignmentMarkdown(item, HomeworkUiState(), PhyVlabUiState(), CitelState(selectedTask = task))
        assertTrue(text.contains("# 作业一"))
        assertTrue(text.contains("CITEL"))
        assertTrue(text.contains(task.url))
    }
}
