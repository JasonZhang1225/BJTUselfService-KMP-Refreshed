package team.bjtuss.bjtuselfservice.shared.feature.citel

internal enum class CitelProblemSubmission(val label: String) {
    ALL("全部"),
    SUBMITTED("已提交"),
    NOT_SUBMITTED("未提交");

    fun matches(task: CitelTask): Boolean = when (this) {
        ALL -> true
        SUBMITTED -> task.hasProgrammingSubmission
        NOT_SUBMITTED -> !task.hasProgrammingSubmission
    }
}

// 旧缓存只有评测状态。新数据还可区分已提交但尚未产生评测状态的题目。
internal val CitelTask.hasProgrammingSubmission: Boolean
    get() = isCitelAccepted(status) || (programmingSubmitCount?.let { it > 0 } ?: status.isNotBlank())

internal fun CitelState.filteredProgrammingTasks(courseId: Int?, submission: CitelProblemSubmission): List<CitelTask> =
    tasks.filter { it.programming && (courseId == null || it.courseId == courseId) && submission.matches(it) }

internal fun CitelState.missingProblems(
    courseId: Int? = null,
    submission: CitelProblemSubmission = CitelProblemSubmission.ALL,
): List<CitelTask> = filteredProgrammingTasks(courseId, submission).filterNot { it.id in problems }

internal data class CitelBatchCacheResult(
    val saved: Int = 0,
    val failed: Int = 0,
    val message: String? = null,
) {
    fun recordFailure(reason: String) = copy(failed = failed + 1, message = message ?: reason)
}
