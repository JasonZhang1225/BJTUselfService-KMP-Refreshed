package team.bjtuss.bjtuselfservice.kmp.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.SizeF
import android.appwidget.AppWidgetManager
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import team.bjtuss.bjtuselfservice.kmp.R
import team.bjtuss.bjtuselfservice.shared.feature.widget.*
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** 真正 inflate/apply RemoteViews，覆盖不支持的控件/方法、资源与尺寸分支。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35])
class CourseWidgetRendererTest {
    private val now = ZonedDateTime.of(2026, 10, 14, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
    private val session = CourseWidgetSession("脱敏测试课程", "第八教学楼 8202", "测试教师", 480, 590, color = 0xC9558A, kind = "limited")
    private val snapshot = CourseWidgetSnapshot(generatedAt = now.toEpochSecond(), days = listOf(
        CourseWidgetDay("2026-10-14", 5, listOf(session, session.copy(name = "第二节", startMinute = 610, endMinute = 720))),
        CourseWidgetDay("2026-10-15", 5, listOf(session)),
    ))

    private fun context(dark: Boolean = false, fontScale: Float = 1f): Context {
        val app = RuntimeEnvironment.getApplication()
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            uiMode = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            this.fontScale = fontScale
        })
    }

    private fun apply(context: Context, provider: CourseWidgetProvider, snapshot: CourseWidgetSnapshot?, width: Int, height: Int): View {
        val options = Bundle().apply {
            putInt("appWidgetMinWidth", width)
            putInt("appWidgetMaxWidth", width)
            putInt("appWidgetMinHeight", height)
            putInt("appWidgetMaxHeight", height)
        }
        val view = CourseWidgetRenderer.render(context, provider, 42, options, snapshot, now).apply(context, FrameLayout(context))
        val density = context.resources.displayMetrics.density
        view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    @Test fun allProviderLayoutsApplyInBothThemesAndLargeText() {
        for (dark in listOf(false, true)) for (scale in listOf(1f, 1.5f)) {
            val context = context(dark, scale)
            for (provider in CourseWidgetUpdates.providers) {
                val view = apply(context, provider, snapshot, provider.defaultWidth.toInt(), provider.defaultHeight.toInt())
                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_message).visibility)
                assertTrue(view.findViewById<LinearLayout>(R.id.widget_content).childCount > 0)
                assertTrue(view.findViewById<View>(R.id.widget_refresh).hasOnClickListeners())
            }
        }
    }

    @Test fun noSnapshotAndExpiredSnapshotsDisplayActionableMessages() {
        val context = context()
        for (provider in listOf(NextCourseWidget(), TodayCourseWidget(), WeekCourseWidget())) {
            for (data in listOf(null, snapshot.copy(days = emptyList()))) {
                val view = apply(context, provider, data, 300, 150)
                assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_message).visibility)
                assertEquals(View.GONE, view.findViewById<View>(R.id.widget_content).visibility)
                assertTrue(view.findViewById<TextView>(R.id.widget_message).text.isNotBlank())
            }
        }
    }

    @Test fun shortDailyWidgetReportsOverflowInsteadOfSilentlyDroppingClasses() {
        val context = context()
        val day = snapshot.days.first().copy(sessions = (0..9).map { session.copy(name = "课程 $it") })
        val view = apply(context, TodayCourseWidget(), snapshot.copy(days = listOf(day)), 280, 120)
        assertEquals(View.VISIBLE, view.findViewById<View>(R.id.widget_footer).visibility)
        assertTrue(view.findViewById<TextView>(R.id.widget_footer).text.contains("9"))
    }

    @Test fun snapshotReadIsFreshAcrossWritesDeletionAndCorruption() {
        val context = context()
        val file = File(context.noBackupFilesDir, "course-widget.json")
        file.parentFile!!.mkdirs()
        file.writeText(snapshot.encode())
        assertEquals(snapshot, AndroidCourseWidgetStore.read(context))
        file.writeText(snapshot.copy(days = emptyList()).encode())
        assertTrue(AndroidCourseWidgetStore.read(context)!!.days.isEmpty())
        file.writeText("broken")
        assertNull(AndroidCourseWidgetStore.read(context))
        file.delete()
        assertNull(AndroidCourseWidgetStore.read(context))
    }

    @Test @Config(sdk = [35]) fun Android12SizeMapAppliesBothCompactAndExpandedLayouts() {
        val context = context()
        for (provider in listOf(NextCourseWidget(), TodayCourseWidget(), WeekCourseWidget())) {
            val options = Bundle().apply {
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(150f, 150f), SizeF(320f, 320f)))
            }
            val view = CourseWidgetRenderer.render(context, provider, 42, options, snapshot, now).apply(context, FrameLayout(context))
            assertEquals(View.GONE, view.findViewById<View>(R.id.widget_message).visibility)
            assertTrue(view.findViewById<LinearLayout>(R.id.widget_content).childCount > 0)
        }
    }
}
