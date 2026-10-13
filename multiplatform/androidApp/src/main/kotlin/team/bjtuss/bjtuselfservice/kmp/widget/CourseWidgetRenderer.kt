package team.bjtuss.bjtuselfservice.kmp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import kotlinx.datetime.LocalDate as KmpDate
import team.bjtuss.bjtuselfservice.kmp.MainActivity
import team.bjtuss.bjtuselfservice.kmp.R
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetDay
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetPresentation
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetSession
import team.bjtuss.bjtuselfservice.shared.feature.widget.CourseWidgetSnapshot
import team.bjtuss.bjtuselfservice.shared.feature.widget.courseWidgetPresentation
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

internal object CourseWidgetRenderer {
    fun render(context: Context, provider: CourseWidgetProvider, id: Int, options: Bundle,
               snapshot: CourseWidgetSnapshot?, now: ZonedDateTime): RemoteViews {
        val sizes = if (Build.VERSION.SDK_INT >= 31) {
            options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width > 0 && it.height > 0 }?.distinct()?.take(8)
        } else null
        if (!sizes.isNullOrEmpty()) {
            return RemoteViews(sizes.associateWith { size ->
                layout(context, provider, id, size.width, size.height, snapshot, now)
            })
        }
        // Android 9–11 与未提供 sizes 的厂商桌面：分别准备竖屏/横屏 RemoteViews。
        val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 }?.toFloat() ?: provider.defaultWidth
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).takeIf { it > 0 }?.toFloat() ?: provider.defaultHeight
        val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH).takeIf { it > 0 }?.toFloat() ?: minWidth
        val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 }?.toFloat() ?: minHeight
        return RemoteViews(
            layout(context, provider, id, maxWidth, minHeight, snapshot, now),
            layout(context, provider, id, minWidth, maxHeight, snapshot, now),
        )
    }

    private fun layout(context: Context, provider: CourseWidgetProvider, id: Int, width: Float, height: Float,
                       snapshot: CourseWidgetSnapshot?, now: ZonedDateTime): RemoteViews {
        val wide = width >= 240
        val views = RemoteViews(context.packageName, when {
            provider.kind == WidgetKind.NEXT && wide -> R.layout.widget_next_wide
            provider.kind == WidgetKind.NEXT -> R.layout.widget_next_small
            else -> R.layout.widget_schedule
        })
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(CourseWidgetUpdates.OPEN_SCHEDULE, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        views.setOnClickPendingIntent(android.R.id.background, open)
        views.setOnClickPendingIntent(R.id.widget_refresh, PendingIntent.getBroadcast(context, id,
            Intent(context, CourseWidgetRefreshReceiver::class.java).setAction(CourseWidgetUpdates.REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val model = snapshot?.let { courseWidgetPresentation(it, KmpDate.parse(now.toLocalDate().toString()), now.hour * 60 + now.minute) }
        if (model == null) {
            views.setTextViewText(R.id.widget_date, context.getString(title(provider.kind)))
            views.setViewVisibility(R.id.widget_week, View.GONE)
            views.setViewVisibility(R.id.widget_content, View.GONE)
            views.setViewVisibility(R.id.widget_message, View.VISIBLE)
            views.setTextViewText(R.id.widget_message, context.getString(if (snapshot == null) R.string.widget_no_snapshot else R.string.widget_stale))
            return views
        }
        val day = if (provider.kind == WidgetKind.WEEK || model.isRestPeriod) model.today else model.displayDay
        val small = width < 200
        val largeText = context.resources.configuration.fontScale > 1.2f
        views.setTextViewText(R.id.widget_date, if (small)
            LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("MM.dd", Locale.ROOT)) else dateLabel(context, day.date))
        if (small) {
            views.setTextViewTextSize(R.id.widget_date, TypedValue.COMPLEX_UNIT_SP, if (width < 140) 11f else 13f)
            views.setTextViewTextSize(R.id.widget_week, TypedValue.COMPLEX_UNIT_SP, 9f)
            val padding = (4 * context.resources.displayMetrics.density).toInt()
            views.setViewPadding(R.id.widget_week, padding, padding, padding, padding)
            if (largeText || width < 140) views.setViewVisibility(R.id.widget_week, View.GONE)
        }
        views.setTextViewText(R.id.widget_week, day.week?.let { context.getString(R.string.widget_week_number, it) } ?: context.getString(R.string.widget_non_teaching))
        val status = when {
            model.isRestPeriod -> context.getString(R.string.widget_rest)
            day.date != model.today.date -> context.getString(if (model.today.sessions.isEmpty()) R.string.widget_today_empty else R.string.widget_today_finished)
            provider.kind == WidgetKind.TODAY -> context.getString(R.string.widget_session_count, day.sessions.size)
            else -> ""
        }
        views.setTextViewText(R.id.widget_status, status)
        views.setViewVisibility(R.id.widget_status, if (status.isBlank() || provider.kind == WidgetKind.NEXT && height < 140) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_message, View.GONE)
        when (provider.kind) {
            WidgetKind.NEXT -> next(context, views, model, wide, height)
            WidgetKind.TODAY -> today(context, views, model, width, height)
            WidgetKind.WEEK -> week(context, views, model, snapshot!!, height)
        }
        // 时间边界使用系统非精确刷新，内容明确展示生成日期，不能暗示已联网同步。
        val old = now.toEpochSecond() - snapshot!!.generatedAt > 24 * 3600
        if (old && provider.kind == WidgetKind.NEXT && wide) {
            views.setTextViewText(R.id.widget_footer, context.getString(R.string.widget_cached))
            views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
        }
        return views
    }

    private fun next(context: Context, views: RemoteViews, model: CourseWidgetPresentation, wide: Boolean, height: Float) {
        val upcoming = model.upcomingDay
        if (upcoming == null) {
            message(context, views, if (model.isRestPeriod) R.string.widget_rest else R.string.widget_no_more)
            return
        }
        views.removeAllViews(R.id.widget_content)
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val sessions = upcoming.sessions.take(if (!wide && height < 72 + 68 * fontScale || height < 150) 1 else 2)
        for ((index, session) in sessions.withIndex()) {
            val ongoing = upcoming.date == model.today.date && session.startMinute <= model.minute && model.minute < session.endMinute
            val label = context.getString(when {
                ongoing -> R.string.widget_ongoing
                index == 0 -> R.string.widget_next
                else -> R.string.widget_after_next
            })
            val row = course(context, session, detailed = height >= 170 && (wide || index == 0),
                column = true, stacked = !wide, compact = !wide && index > 0 || height < 170)
            row.setTextViewText(R.id.widget_course_label, label)
            row.setViewVisibility(R.id.widget_course_label, if (height < 150 || !wide && height < 170) View.GONE else View.VISIBLE)
            if (!wide && index > 0) row.setViewVisibility(R.id.widget_course_time, View.GONE)
            if (!wide && height < 140) row.setViewVisibility(R.id.widget_course_time, View.GONE)
            views.addView(R.id.widget_content, row)
        }
        if (sessions.size == 1 && upcoming.sessions.size > 1 && !wide) {
            views.setTextViewText(R.id.widget_footer, context.getString(R.string.widget_more_count, upcoming.sessions.size - 1))
            views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
        }
    }

    private fun today(context: Context, views: RemoteViews, model: CourseWidgetPresentation, width: Float, height: Float) {
        val day = model.displayDay
        if (day.sessions.isEmpty() || model.isRestPeriod) {
            message(context, views, R.string.widget_rest)
            return
        }
        views.removeAllViews(R.id.widget_content)
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val detailed = height >= 240 && width >= 240
        val rowHeight = (if (detailed) 54 else 28) * fontScale
        val capacity = max(1, ((height - 90) / rowHeight).toInt()).coerceAtMost(8)
        for (session in day.sessions.take(capacity)) views.addView(R.id.widget_content, course(context, session, detailed))
        val omitted = day.sessions.size - capacity
        if (omitted > 0) {
            views.setTextViewText(R.id.widget_footer, context.getString(R.string.widget_more_count, omitted))
            views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
        } else if (detailed && day.date == model.today.date && model.nextDay != null && capacity - day.sessions.size >= 2) {
            views.setTextViewText(R.id.widget_footer, context.getString(R.string.widget_next_day_hint,
                dateLabel(context, model.nextDay!!.date), model.nextDay!!.sessions.size))
            views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
        }
    }

    private fun week(context: Context, views: RemoteViews, model: CourseWidgetPresentation, snapshot: CourseWidgetSnapshot, height: Float) {
        if (model.week.all { it.sessions.isEmpty() }) {
            message(context, views, R.string.widget_week_empty)
            return
        }
        views.removeAllViews(R.id.widget_content)
        val header = RemoteViews(context.packageName, R.layout.widget_week_header)
        header.setTextViewText(R.id.widget_month, context.getString(R.string.widget_month, LocalDate.parse(model.today.date).monthValue))
        model.week.forEachIndexed { index, day ->
            header.setTextViewText(weekIds[index], weekday(context, LocalDate.parse(day.date)) + "\n" + LocalDate.parse(day.date).dayOfMonth)
        }
        views.addView(R.id.widget_content, header)
        val slots = snapshot.slotTimes.mapNotNull { range ->
            val parts = range.split('-').mapNotNull { hm ->
                val pair = hm.trim().split(':').mapNotNull(String::toIntOrNull)
                if (pair.size == 2) pair[0] * 60 + pair[1] else null
            }
            if (parts.size == 2 && parts[0] < parts[1]) parts[0] until parts[1] else null
        }
        // 无课的午间大节不占一整行；考试/实验按与大节的重叠区间纳入。
        val occupied = slots.withIndex().filter { (_, slot) -> model.week.any { day -> day.sessions.any {
            it.startMinute <= slot.last && it.endMinute > slot.first
        } } }
        val fontScale = context.resources.configuration.fontScale.coerceAtLeast(1f)
        val capacity = max(1, ((height - 98) / (42 * fontScale)).toInt()).coerceAtMost(7)
        val shown = occupied.take(capacity)
        var hidden = 0
        for ((slotIndex, slot) in shown) {
            val row = RemoteViews(context.packageName, R.layout.widget_week_row)
            row.setTextViewText(R.id.widget_slot, "${slotIndex + 1}\n${hm(slot.first)}")
            for ((index, day) in model.week.withIndex()) {
                val sessions = day.sessions.filter { it.startMinute <= slot.last && it.endMinute > slot.first }
                val session = sessions.firstOrNull()
                val cell = RemoteViews(context.packageName, R.layout.widget_week_cell)
                if (session != null) {
                    cell.setInt(R.id.widget_cell, "setBackgroundResource", background(session.kind))
                    cell.setTextViewText(R.id.widget_cell_title, session.name + if (sessions.size > 1) " +${sessions.size - 1}" else "")
                    cell.setContentDescription(R.id.widget_cell, sessions.joinToString("；") { "${it.name} ${it.place} ${hm(it.startMinute)}–${hm(it.endMinute)}" })
                }
                row.addView(weekIds[index], cell)
            }
            views.addView(R.id.widget_content, row)
        }
        // 非标准时段事件不得静默消失；脚注提示打开完整课表。
        for (day in model.week) hidden += day.sessions.count { session -> shown.none { (_, slot) ->
            session.startMinute <= slot.last && session.endMinute > slot.first
        } }
        if (hidden > 0) {
            views.setTextViewText(R.id.widget_footer, context.getString(R.string.widget_more_count, hidden))
            views.setViewVisibility(R.id.widget_footer, View.VISIBLE)
        }
    }

    private fun course(context: Context, session: CourseWidgetSession, detailed: Boolean,
                       column: Boolean = false, stacked: Boolean = false, compact: Boolean = false): RemoteViews {
        val views = RemoteViews(context.packageName, when {
            column && stacked -> R.layout.widget_course_stack
            column -> R.layout.widget_course_column
            detailed -> R.layout.widget_course_row
            else -> R.layout.widget_course_compact
        })
        views.setInt(R.id.widget_course, "setBackgroundResource", background(session.kind))
        views.setInt(R.id.widget_accent, "setBackgroundColor", (0xff000000L or session.color).toInt())
        views.setTextViewText(R.id.widget_course_name, session.name)
        views.setTextViewText(R.id.widget_course_place, listOf(session.place, session.teacher).filter(String::isNotBlank).joinToString(" "))
        views.setTextViewText(R.id.widget_course_time, if (detailed || column && !compact) "${hm(session.startMinute)} – ${hm(session.endMinute)}" else hm(session.startMinute))
        views.setViewVisibility(R.id.widget_course_place, if (detailed) View.VISIBLE else View.GONE)
        if (column && compact) {
            views.setTextViewTextSize(R.id.widget_course_name, TypedValue.COMPLEX_UNIT_SP, 13f)
            views.setInt(R.id.widget_course_name, "setMaxLines", 1)
        }
        views.setContentDescription(R.id.widget_course, "${session.name} ${session.place} ${session.teacher} ${hm(session.startMinute)}–${hm(session.endMinute)}")
        return views
    }

    private fun message(context: Context, views: RemoteViews, text: Int) {
        views.setViewVisibility(R.id.widget_content, View.GONE)
        views.setViewVisibility(R.id.widget_message, View.VISIBLE)
        views.setTextViewText(R.id.widget_message, context.getString(text))
    }
    private fun title(kind: WidgetKind) = when (kind) {
        WidgetKind.NEXT -> R.string.widget_next_title
        WidgetKind.TODAY -> R.string.widget_today_title
        WidgetKind.WEEK -> R.string.widget_week_title
    }
    private fun background(kind: String) = when (kind) {
        "required" -> R.drawable.widget_course_required
        "limited" -> R.drawable.widget_course_limited
        "elective" -> R.drawable.widget_course_elective
        "pe" -> R.drawable.widget_course_pe
        "lab" -> R.drawable.widget_course_lab
        "exam" -> R.drawable.widget_course_exam
        else -> R.drawable.widget_course_unknown
    }
    private fun hm(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
    private fun weekday(context: Context, date: LocalDate) = context.resources.getStringArray(R.array.widget_weekdays)[date.dayOfWeek.value - 1]
    private fun dateLabel(context: Context, key: String): String {
        val date = LocalDate.parse(key)
        return date.format(DateTimeFormatter.ofPattern("MM.dd", Locale.ROOT)) + " " + weekday(context, date)
    }
    private val weekIds = listOf(R.id.widget_day_1, R.id.widget_day_2, R.id.widget_day_3,
        R.id.widget_day_4, R.id.widget_day_5, R.id.widget_day_6, R.id.widget_day_7)
}
