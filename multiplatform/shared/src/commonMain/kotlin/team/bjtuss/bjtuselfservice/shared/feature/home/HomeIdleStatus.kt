package team.bjtuss.bjtuselfservice.shared.feature.home

/**
 * 首页顶栏同步文案。邮件/校园卡自身失败才是「同步失败」；
 * 作业/考试/课表/物理在线失败单独写成「部分同步失败」，避免一条切片拖垮整页语义。
 */
internal fun homeIdleStatusText(
    homeFailed: Boolean,
    homeworkFailed: Boolean,
    examFailed: Boolean,
    courseFailed: Boolean,
    phyVlabFailed: Boolean = false,
    hasAnySource: Boolean,
    hasWaitingSource: Boolean = false,
): String {
    val childFailed = homeworkFailed || examFailed || courseFailed || phyVlabFailed
    return when {
        homeFailed -> "同步失败"
        childFailed -> "部分同步失败"
        hasWaitingSource -> "等待同步"
        hasAnySource -> "已同步"
        else -> "未同步"
    }
}
