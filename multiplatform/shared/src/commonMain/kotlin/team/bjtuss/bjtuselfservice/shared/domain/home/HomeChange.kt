package team.bjtuss.bjtuselfservice.shared.domain.home

import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeKind

enum class HomeChangeDomain(val title: String) {
    GRADES("成绩"),
    COURSES("课程表"),
    EXAMS("考试安排"),
    HOMEWORK("作业"),
    PHYVLAB("物理在线"),
}

data class HomeChangeRecord(
    val domain: HomeChangeDomain,
    val kind: DataChangeKind,
    val title: String,
    val beforeDetail: String = "",
    val afterDetail: String = "",
) {
    init {
        require(title.isNotBlank())
    }

    val stableKey: String
        get() = listOf(domain.name, kind.name, title, beforeDetail, afterDetail).joinToString("\u0000")
}

data class HomeChangeFeedSnapshot(
    val baselineDomains: Set<HomeChangeDomain> = emptySet(),
    val records: List<HomeChangeRecord> = emptyList(),
)
