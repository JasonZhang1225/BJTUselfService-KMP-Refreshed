package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlin.math.abs

/**
 * 回放任务在系统表面上显示的内容：iOS 的实时活动/灵动岛、Android 的前台服务通知（实时动态）都用它。
 *
 * 之所以走同一个接口而不是各端自己拼字符串：两边的文案与进度必须一致，
 * 共享层算一次，宿主只负责渲染。
 */
data class PlaybackBackgroundState(
    /** 课节标题，如“2026-10-20 16:20-18:10”。 */
    val title: String,
    /** 阶段文案：下载中 / 拼接中 / 保存中 / 已完成 / 失败 / 已取消。 */
    val phaseLabel: String,
    /** 当前步骤，如“下载教师画面（第 1/2 路）”。 */
    val detail: String,
    /** 0..1；未知时为 null（界面显示不确定进度条）。 */
    val progress: Float?,
    /** “1.2 MB / 约 5.4 GB”这类体积文案。 */
    val sizeLabel: String? = null,
    /** “9.1 MB/s”这类速度文案。 */
    val speedLabel: String? = null,
)

/** 体积文案：按大小选 KB / MB / GB，带一位小数。 */
internal fun formatSize(bytes: Long): String {
    val value = abs(bytes)
    val megabyte = 1024L * 1024
    val gigabyte = megabyte * 1024
    return when {
        value < megabyte -> "${value / 1024} KB"
        value < gigabyte -> {
            val tenths = value * 10 / megabyte
            "${tenths / 10}.${tenths % 10} MB"
        }
        else -> {
            val tenths = value * 10 / gigabyte
            "${tenths / 10}.${tenths % 10} GB"
        }
    }
}

/** 速度文案：如“9.1 MB/s”。 */
internal fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond < 0) "—" else "${formatSize(bytesPerSecond)}/s"
