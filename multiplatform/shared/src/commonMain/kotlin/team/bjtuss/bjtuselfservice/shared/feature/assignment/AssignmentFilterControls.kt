package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlinx.coroutines.delay
import team.bjtuss.bjtuselfservice.shared.LocalReduceMotion
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import team.bjtuss.bjtuselfservice.shared.domain.homework.DeadlineScope
import team.bjtuss.bjtuselfservice.shared.domain.homework.SchoolTimeZone
import team.bjtuss.bjtuselfservice.shared.domain.homework.deadlineScope
import team.bjtuss.bjtuselfservice.shared.feature.course.IosCompactDatePicker

private const val MILLIS_PER_DAY = 86_400_000L

/** 面板打开期间重新读取当前日期的间隔。 */
private const val TODAY_REFRESH_MILLIS = 60_000L

// 按北京时间取“今天”，与列表判断过期用的“现在”和截止日期归日一致，不随手机时区变化。
private fun currentLocalDate(): LocalDate = Clock.System.now().toLocalDateTime(SchoolTimeZone).date

/** 切到“指定日期”且之前没选过日期时，默认看一周内。 */
private const val DEFAULT_UNTIL_DAYS = 7

/**
 * 三个作业列表（聚合、课程平台、物理在线/CITEL）共用的“截止日期”切换器。
 * 全部 / 未过期 / 指定日期三档互斥；选指定日期时下方展开系统日期选择入口。
 */
@Composable
internal fun AssignmentDeadlineFilter(
    hideExpired: Boolean,
    untilDate: LocalDate?,
    onChange: (hideExpired: Boolean, untilDate: LocalDate?) -> Unit,
) {
    // 面板开着跨过零点时“今天”也要跟着变，否则日期下限和过期提示停在前一天。
    val today by produceState(currentLocalDate()) {
        while (true) {
            delay(TODAY_REFRESH_MILLIS)
            value = currentLocalDate()
        }
    }
    val scope = deadlineScope(hideExpired, untilDate)
    // 切走再切回“指定日期”时沿用上次的日期，而不是每次重置。
    var lastUntil by remember { mutableStateOf(untilDate ?: today.plus(DEFAULT_UNTIL_DAYS, DateTimeUnit.DAY)) }
    if (untilDate != null && untilDate != lastUntil) lastUntil = untilDate
    // 展开日期行时整体高度平滑变化。
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterSectionTitle("截止日期")
        AppleSegmentedControl(
            options = listOf("全部", "未过期", "指定日期"),
            selectedIndex = scope.ordinal,
            onSelect = { index ->
                when (DeadlineScope.entries[index]) {
                    DeadlineScope.ALL -> onChange(false, null)
                    DeadlineScope.UPCOMING -> onChange(true, null)
                    DeadlineScope.UNTIL_DATE -> onChange(true, lastUntil)
                }
            },
        )
        if (scope == DeadlineScope.UNTIL_DATE) {
            UntilDateRow(date = untilDate ?: lastUntil, today = today, onPick = { picked ->
                lastUntil = picked
                onChange(true, picked)
            })
        }
        val stale = scope == DeadlineScope.UNTIL_DATE && untilDate != null && untilDate < today
        FilterFootnote(
            when (scope) {
                DeadlineScope.ALL -> "显示全部作业，包括已过截止时间的。"
                DeadlineScope.UPCOMING -> "只显示还没到截止时间的作业。"
                DeadlineScope.UNTIL_DATE -> if (stale) "所选日期已经过去，请重新选择。"
                else "显示现在到 ${untilDate?.let { shortDateText(it, today) }.orEmpty()} 24:00 之间截止的作业。"
            },
            isError = stale,
        )
    }
}

/**
 * “提交状态”切换器：全部 / 未提交，与截止日期互相独立。
 * 忽略不是筛选条件：未提交但已忽略的作业照常参与筛选，收在列表末尾的“已忽略”分组里；[ignoredCount] 提醒用户它们还在。
 */
@Composable
internal fun AssignmentSubmissionFilter(hideSubmitted: Boolean, ignoredCount: Int, onChange: (hideSubmitted: Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterSectionTitle("提交状态")
        AppleSegmentedControl(
            options = listOf("全部", "未提交"),
            selectedIndex = if (hideSubmitted) 1 else 0,
            onSelect = { onChange(it == 1) },
        )
        FilterFootnote(
            (if (hideSubmitted) "已提交的作业不显示。" else "已提交和未提交的作业都显示。") +
                "左滑可忽略作业，忽略后收纳在列表末尾的“已忽略”分组里。",
        )
        if (ignoredCount > 0) {
            Text(
                "您当前忽略了 $ignoredCount 项作业",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** 筛选面板里的说明数字：学校仍未提交、且本人忽略过的作业有几项（不看当前筛选）。 */
internal fun <T> ignoredAssignmentCount(items: List<T>, ignoredKeys: Set<AssignmentCompletionKey>,
    submitted: (T) -> Boolean, key: (T) -> AssignmentCompletionKey?): Int =
    items.count { isAssignmentIgnored(submitted(it), key(it), ignoredKeys) }

@Composable
private fun FilterSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun FilterFootnote(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun UntilDateRow(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("截止不晚于", style = MaterialTheme.typography.bodyLarge, maxLines = 1, modifier = Modifier.weight(1f))
        if (currentPlatform().family == PlatformFamily.IOS) {
            // 已过去的保存日期不能被下限夹到今天：setDate 不回调，按钮会显示今天而列表仍按旧日期筛选。
            // 下限放宽到该日期，按钮如实显示旧日期，配合下方红字提示，点今天即可生效。
            IosCompactDatePicker(selectedDate = date, minimumDate = minOf(date, today), onDateSelected = onPick)
        } else {
            MaterialDateButton(date = date, today = today, onPick = onPick)
        }
    }
}

/** Android / 桌面：外观对齐 iOS 紧凑日期按钮，点按打开系统 Material 日期选择对话框。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialDateButton(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val label = shortDateText(date, today)
    Surface(
        onClick = { showDialog = true },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        modifier = Modifier.semantics { contentDescription = "截止不晚于 $label，点按更改" },
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
    if (!showDialog) return
    val todayEpochDay = today.toEpochDays()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = maxOf(date.toEpochDays(), todayEpochDay) * MILLIS_PER_DAY,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis.floorDiv(MILLIS_PER_DAY) >= todayEpochDay
            override fun isSelectableYear(year: Int) = year >= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = { showDialog = false },
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPick(LocalDate.fromEpochDays(it.floorDiv(MILLIS_PER_DAY))) }
                    showDialog = false
                },
            ) { Text("完成") }
        },
        dismissButton = { TextButton(onClick = { showDialog = false }) { Text("取消") } },
    ) {
        DatePicker(
            state = state,
            title = { Text("截止不晚于", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
        )
    }
}

/**
 * iOS `UISegmentedControl` 式的分段切换：浅色轨道上一块滑动的选中块，三档以内用。
 * 选中块按临界阻尼弹簧滑到新位置（无过冲，可被下一次点按中途打断）；减少动态效果时直接跳到位。
 */
@Composable
internal fun AppleSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val track = colors.onSurface.copy(alpha = if (dark) 0.12f else 0.07f)
    val thumb = if (dark) colors.onSurface.copy(alpha = 0.24f) else colors.surface
    val thumbEdge = colors.onSurface.copy(alpha = if (dark) 0f else 0.08f)
    val reduceMotion = LocalReduceMotion.current
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "segmentThumb",
    )
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(track, shape)
            .padding(2.dp)
            .drawBehind {
                val segment = size.width / options.size
                val radius = CornerRadius(8.dp.toPx())
                val topLeft = Offset(segment * position, 0f)
                val thumbSize = Size(segment, size.height)
                drawRoundRect(thumb, topLeft, thumbSize, radius)
                if (thumbEdge.alpha > 0f) drawRoundRect(thumbEdge, topLeft, thumbSize, radius, style = Stroke(1.dp.toPx() / 2))
            }
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, label ->
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = 36.dp)
                    .selectable(
                        selected = selected,
                        onClick = { if (!selected) onSelect(index) },
                        role = Role.RadioButton,
                        interactionSource = interaction,
                        indication = null,
                    )
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                    // 按下即变淡，不等切换完成。
                    modifier = Modifier.alpha(if (pressed && !selected) 0.45f else 1f),
                )
            }
        }
    }
}

private fun weekdayText(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> "周一"
    DayOfWeek.TUESDAY -> "周二"
    DayOfWeek.WEDNESDAY -> "周三"
    DayOfWeek.THURSDAY -> "周四"
    DayOfWeek.FRIDAY -> "周五"
    DayOfWeek.SATURDAY -> "周六"
    DayOfWeek.SUNDAY -> "周日"
}

/** `11月8日 周日`；不是今年时带上年份。 */
internal fun shortDateText(date: LocalDate, today: LocalDate): String =
    (if (date.year == today.year) "" else "${date.year}年") +
        "${date.month.ordinal + 1}月${date.day}日 ${weekdayText(date.dayOfWeek)}"
