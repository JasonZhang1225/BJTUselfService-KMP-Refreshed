package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.datetime.LocalDate
import team.bjtuss.bjtuselfservice.shared.domain.course.displayScheduleCourseName
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeNowSession
import team.bjtuss.bjtuselfservice.shared.feature.course.CourseColorCategory
import team.bjtuss.bjtuselfservice.shared.feature.course.tones
import team.bjtuss.bjtuselfservice.shared.feature.widget.compactWidgetPlace

/**
 * 首页“正在上/下一节”卡片对应的一项日程，交给系统实时活动（iOS 灵动岛）显示。
 * 字段与课程小组件快照一致，iOS 宿主按 [id] 在快照里找到同一项；找不到时用这里的内容兜底。
 */
data class LiveActivityTarget(
    val id: String,
    val date: LocalDate,
    val name: String,
    val place: String,
    val teacher: String,
    val startMinute: Int,
    val endMinute: Int,
    /** 课程类别强调色 0xRRGGBB。 */
    val color: Long,
    /** 课程类别（CourseColorCategory.widgetKind）。 */
    val kind: String,
)

/**
 * 实时活动里一项日程的标识：`yyyy-MM-dd|开始分钟|结束分钟|课名`。
 * 与 iOS `courseLiveActivityOccurrenceId`（CourseLiveActivityAttributes.swift）同一格式，改一边要同步另一边。
 */
fun liveActivityOccurrenceId(date: LocalDate, startMinute: Int, endMinute: Int, name: String): String =
    "$date|$startMinute|$endMinute|$name"

/** 课名、地点与课程小组件快照（buildCourseWidgetSnapshot）同一规则，保证两边的 [LiveActivityTarget.id] 相同。 */
internal fun HomeNowSession.liveActivityTarget(date: LocalDate, category: CourseColorCategory): LiveActivityTarget {
    val name = displayScheduleCourseName(course.courseName.trim())
    return LiveActivityTarget(
        id = liveActivityOccurrenceId(date, startMinutes, endMinutes, name),
        date = date,
        name = name,
        place = compactWidgetPlace(course.coursePlace),
        teacher = course.courseTeacher.trim(),
        startMinute = startMinutes,
        endMinute = endMinutes,
        color = category.tones.accent,
        kind = category.widgetKind,
    )
}

/**
 * 卡片状态胶囊下方的 Live 按钮：点开菜单可重新开启或关闭这一项的实时活动；课前 10 或 20 分钟（菜单里选）会自动出现。
 * 只有 iOS 有实时活动，其余平台不占位置。
 */
@Composable
internal expect fun LiveActivityButton(target: LiveActivityTarget, modifier: Modifier = Modifier)

/**
 * Live 胶囊的外观：与卡片上的“下一节/正在上”状态胶囊同高同形，图标为无线电波。
 * 开启时用课程类别色（同“正在上”），未开启时用中性灰（同“下一节”）。按下时整体变淡。
 */
@Composable
internal fun LiveActivityChip(
    active: Boolean,
    accent: Color,
    pressed: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val label = MaterialTheme.typography.labelSmall
    // 与状态胶囊里一行 labelSmall 文字等高，随系统字号一起缩放。
    val lineHeight = with(LocalDensity.current) {
        (if (label.lineHeight.isSpecified) label.lineHeight else 16.sp).toDp()
    }
    val tint = if (active) accent else scheme.onSurfaceVariant
    Surface(
        modifier = modifier.alpha(if (pressed) 0.55f else 1f),
        shape = RoundedCornerShape(999.dp),
        color = if (active) accent.copy(alpha = 0.16f) else scheme.surfaceVariant,
    ) {
        Box(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
            RadioWavesIcon(tint, Modifier.size(width = lineHeight * 1.5f, height = lineHeight))
        }
    }
}

/** 中心圆点 + 左右各两道弧，对应 SF Symbol `dot.radiowaves.left.and.right`。 */
@Composable
private fun RadioWavesIcon(tint: Color, modifier: Modifier) {
    Canvas(modifier) {
        val h = size.height
        val center = Offset(size.width / 2f, h / 2f)
        val stroke = Stroke(width = h * 0.1f, cap = StrokeCap.Round)
        drawCircle(tint, radius = h * 0.12f, center = center)
        listOf(h * 0.3f, h * 0.48f).forEach { radius ->
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawArc(tint, startAngle = -40f, sweepAngle = 80f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
            drawArc(tint, startAngle = 140f, sweepAngle = 80f, useCenter = false, topLeft = topLeft, size = arcSize, style = stroke)
        }
    }
}
