package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.test.assertNull
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
        for ((name, dark, scale) in listOf(Triple("light", false, 1f), Triple("dark", true, 1f), Triple("large", false, 1.5f))) {
            var filters by mutableStateOf(AggregateAssignmentFilters(beforeDate = "2026-10-30"))
            val scene = ImageComposeScene(440, 1000, density = Density(1f, scale),
                coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(dark, false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(16.dp)) {
                            AggregateAssignmentFilterSheet(emptyList(), AssignmentSource.entries,
                                filters, { filters = it })
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
                if (name == "light") {
                    // Position verified against the rendered 440px fixture: "显示全部日期".
                    scene.sendPointerEvent(PointerEventType.Press, Offset(90f, 497f))
                    scene.sendPointerEvent(PointerEventType.Release, Offset(90f, 497f))
                    repeat(3) { scene.render(2_000_000_000L + it * 100_000_000L).close() }
                    assertNull(filters.beforeDate)
                }
            } finally { scene.close() }
        }
    }
}
