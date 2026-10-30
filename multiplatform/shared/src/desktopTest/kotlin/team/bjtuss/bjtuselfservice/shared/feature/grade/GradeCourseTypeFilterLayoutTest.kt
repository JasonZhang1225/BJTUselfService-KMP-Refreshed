package team.bjtuss.bjtuselfservice.shared.feature.grade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.math.ceil
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.domain.grade.CourseType

@OptIn(ExperimentalLayoutApi::class)
class GradeCourseTypeFilterLayoutTest {
    private val options = listOf(CourseType.REQUIRED to "必修", CourseType.LIMITED to "限选",
        CourseType.ELECTIVE to "任选", CourseType.PHYSICAL_EDUCATION to "体育")
    private val output = File("../../.artifacts/grade-course-type-layout/ui")

    @Test fun selectionDoesNotChangeChipBoundsOrRows() = SwingUtilities.invokeAndWait {
        for (dark in listOf(false, true)) for (scale in listOf(1f, 1.6f)) {
            for (width in listOf(220, 280, 320, 360, 420, 640)) {
                val fixture = Fixture(width, dark, scale)
                try {
                    fixture.render()
                    val initial = fixture.bounds.toMap()
                    assertEquals(4, initial.size)
                    for (mask in 0..15) {
                        fixture.excluded.value = mask
                        fixture.render()
                        assertEquals(initial, fixture.bounds.toMap(), "勾选不得改变尺寸或换行：width=$width scale=$scale dark=$dark mask=$mask")
                        if (mask in listOf(0, 1) && width in listOf(280, 360)) {
                            fixture.capture("w$width-s$scale-${if (dark) "dark" else "light"}-excluded$mask")
                        }
                    }
                } finally { fixture.close() }
            }
        }
    }

    @Test fun keepsOneRowWhenItFitsAndWrapsOnlyWhenSpaceRequiresIt() = SwingUtilities.invokeAndWait {
        for (scale in listOf(1f, 1.6f)) {
            val wide = Fixture(800, false, scale)
            val needed = try {
                wide.render()
                assertEquals(1, wide.bounds.values.map { it.top }.distinct().size)
                ceil(wide.bounds.values.sumOf { it.width.toDouble() } + 24).toInt()
            } finally { wide.close() }
            val fits = Fixture(needed, false, scale)
            val narrow = Fixture(needed - 20, false, scale)
            try {
                fits.render()
                narrow.render()
                assertEquals(1, fits.bounds.values.map { it.top }.distinct().size, "空间足够应保持一行")
                assertTrue(narrow.bounds.values.map { it.top }.distinct().size > 1, "空间不足应允许自然换行")
                val before = fits.bounds.toMap()
                val center = before.getValue(0).center
                fits.scene.sendPointerEvent(PointerEventType.Press, Offset(center.x, center.y))
                fits.scene.sendPointerEvent(PointerEventType.Release, Offset(center.x, center.y))
                fits.render()
                assertEquals(1, fits.excluded.value, "点击仍正常切换课程性质")
                assertEquals(before, fits.bounds.toMap())
            } finally { fits.close(); narrow.close() }
        }
    }

    private inner class Fixture(width: Int, dark: Boolean, scale: Float) {
        val excluded = mutableStateOf(0)
        val bounds = mutableMapOf<Int, Rect>()
        val density = Density(1f, scale)
        val scene = ImageComposeScene(width, 280, density = density, coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(dark, false) {
                CompositionLocalProvider(LocalDensity provides density) {
                    Surface {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            options.forEachIndexed { index, (type, label) ->
                                GradeCourseTypeFilterChip(type, label, if (index == 0) 22 else 2,
                                    included = excluded.value and (1 shl index) == 0,
                                    onClick = { excluded.value = excluded.value xor (1 shl index) },
                                    modifier = Modifier.onGloballyPositioned { bounds[index] = it.boundsInRoot() })
                            }
                        }
                    }
                }
            }
        }
        fun render() { repeat(3) { scene.render(System.nanoTime()).close() } }
        fun capture(name: String) {
            val image = scene.render(System.nanoTime())
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)!!
                try { output.mkdirs(); File(output, "$name.png").writeBytes(data.bytes) }
                finally { data.close() }
            } finally { image.close() }
        }
        fun close() = scene.close()
    }
}
