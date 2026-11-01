package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.test.assertEquals
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme

class DeadlineFilterVisualTest {
    @Test fun renderMidnightCard() = SwingUtilities.invokeAndWait {
        val midnight = kotlin.time.Instant.parse("2026-10-29T16:00:00Z").epochSeconds
        val scene = ImageComposeScene(440, 500, coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(false, false) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTaskCard(
                            team.bjtuss.bjtuselfservice.shared.feature.citel.CitelTask(
                                1, 1, "示例课程", "午夜截止与折扣", "", true,
                                openTime = midnight - 86400, dueTime = midnight, discountTime = midnight), {})
                    }
                }
            }
        }
        try {
            repeat(5) { scene.render((it + 1) * 100_000_000L).close() }
            val image = scene.render(1_000_000_000L)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)!!
                try { File("build/reports/deadline-filter/midnight.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) } }
                finally { data.close() }
            } finally { image.close() }
        } finally { scene.close() }
    }

    @Test fun renderDateFilters() = SwingUtilities.invokeAndWait {
        val until = AggregateAssignmentFilters(hideSubmitted = true).withDeadlineScope(true,
            team.bjtuss.bjtuselfservice.shared.domain.time.endOfDayEpochSeconds(
                kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date.plus(7, DateTimeUnit.DAY)))
        val cases = listOf(
            Triple("all-light", false, 1f) to AggregateAssignmentFilters(),
            Triple("upcoming-dark", true, 1f) to AggregateAssignmentFilters(hideExpired = true, hideSubmitted = true),
            Triple("until-light", false, 1f) to until,
            Triple("until-dark", true, 1f) to until,
            Triple("until-large", false, 1.6f) to until,
        )
        for ((spec, initial) in cases) {
            val (name, dark, scale) = spec
            var filters by mutableStateOf(initial)
            val scene = ImageComposeScene(440, 1000, density = Density(1f, scale),
                coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(dark, false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            AggregateAssignmentFilterSheet(emptyList(), AssignmentSource.entries,
                                filters, 3, { filters = it })
                        }
                    }
                }
            }
            try {
                repeat(5) { scene.render((it + 1) * 100_000_000L).close() }
                val image = scene.render(1_000_000_000L)
                try {
                    val data = image.encodeToData(EncodedImageFormat.PNG)!!
                    try { File("build/reports/deadline-filter/$name.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) } }
                    finally { data.close() }
                } finally { image.close() }
            } finally { scene.close() }
        }
    }

    /** 单独布局的切换器：宽 300、三段各 100，点中间段与右段，验证互斥选择与已选段不重复回调。 */
    @Test fun segmentedControlSelectsByTap() = SwingUtilities.invokeAndWait {
        var selected by mutableStateOf(0)
        var calls = 0
        val scene = ImageComposeScene(304, 60, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(false, false) {
                Surface(Modifier.fillMaxSize()) {
                    AppleSegmentedControl(listOf("全部", "未过期", "指定日期"), selected, { calls++; selected = it })
                }
            }
        }
        try {
            fun tap(x: Float) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x, 18f))
                scene.sendPointerEvent(PointerEventType.Release, Offset(x, 18f))
                repeat(3) { scene.render(it * 100_000_000L).close() }
            }
            scene.render().close()
            tap(150f)
            assertEquals(1, selected)
            tap(260f)
            assertEquals(2, selected)
            tap(260f)
            assertEquals(2, calls, "点已选中的段不应再回调")
        } finally { scene.close() }
    }
}
