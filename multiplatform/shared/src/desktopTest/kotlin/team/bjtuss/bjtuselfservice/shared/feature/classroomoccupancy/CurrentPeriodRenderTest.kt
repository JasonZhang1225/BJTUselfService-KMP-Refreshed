package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.ClassroomOccupancy
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyKind
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSearchHeader
import team.bjtuss.bjtuselfservice.shared.feature.shell.MaterialSearchHeader
import team.bjtuss.bjtuselfservice.shared.feature.shell.PageSearchState

/** 当前节次光晕、空闲/占用标签与两种搜索头的浅/深色离屏截图，供肉眼核对。 */
class CurrentPeriodRenderTest {
    @Test fun renderLightAndDark() = SwingUtilities.invokeAndWait {
        listOf(false, true).forEach { dark ->
            val scene = ImageComposeScene(width = 820, height = 1500, density = Density(2f), coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(useDarkTheme = dark, dynamicColorEnabled = false) {
                    Column(
                        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val typed = PageSearchState().apply { activate(); consumeFocus(); update("数据") }
                        MaterialSearchHeader(typed, "搜索课程、作业或来源")
                        AppleSearchHeader(PageSearchState().apply { activate(); consumeFocus() }, "搜索课程或作业")
                        Text("当前第3节", style = MaterialTheme.typography.labelMedium)
                        rooms.forEach { room ->
                            ClassroomOccupancyCard(room = room, weekday = 3, currentPeriod = 3, supportsPeople = true)
                        }
                    }
                }
            }
            try {
                repeat(5) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
                val image = scene.render(2_000_000_000L)
                try {
                    val data = image.encodeToData(EncodedImageFormat.PNG)!!
                    File("build/reports/classroom-ui/current-period-${if (dark) "dark" else "light"}.png")
                        .apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                    data.close()
                } finally { image.close() }
            } finally { scene.close() }
        }
    }

    // 每种占用类型各做一间，当前节次放在第 3 节，两侧邻格都能看到染色。
    private val rooms = OccupancyKind.entries.map { kind ->
        room("SY${OccupancyKind.entries.indexOf(kind) + 1}01 ${kind.name}", OccupancyKind.SCHEDULED, OccupancyKind.FREE, kind)
    }

    private fun room(name: String, vararg kinds: OccupancyKind) = ClassroomOccupancy(
        room = name,
        capacity = 90,
        cells = kinds.mapIndexed { index, kind -> (3 to index + 1) to kind }.toMap() +
            mapOf((3 to 5) to OccupancyKind.SCHEDULED, (3 to 6) to OccupancyKind.RESCHEDULED),
    )
}
