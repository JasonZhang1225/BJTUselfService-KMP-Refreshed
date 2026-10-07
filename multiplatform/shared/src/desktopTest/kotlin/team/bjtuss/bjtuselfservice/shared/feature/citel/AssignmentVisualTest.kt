package team.bjtuss.bjtuselfservice.shared.feature.citel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkCard
import team.bjtuss.bjtuselfservice.shared.feature.homework.HomeworkSummaryBanner
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.PhyVlabActivityRow
import team.bjtuss.bjtuselfservice.shared.feature.settings.FeatureSwitchesCard

/** One composite image of the three production card implementations. */
class AssignmentVisualTest {
    @Test fun renderThreePlatformsOnce() = SwingUtilities.invokeAndWait {
        val scene = ImageComposeScene(width = 1600, height = 850, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(useDarkTheme = false, dynamicColorEnabled = false) {
                Surface(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("设置 · 已配置")
                            FeatureSwitchesCard(true, true, true, true, true, {}, {}, {}, {}, {})
                            Text("设置 · 尚未配置")
                            FeatureSwitchesCard(true, false, false, false, false, {}, {}, {}, {}, {})
                        }
                        Panel("作业", Modifier.weight(1f)) {
                            listOf(false, true).forEachIndexed { i, submitted ->
                                HomeworkCard(Homework(id = i, upId = i, idSnId = if (submitted) 1 else null,
                                    score = "", userId = 0, courseId = 1, courseName = "测试课程 · 数据结构", title = "第 ${i + 1} 章 · 实验报告",
                                    content = "", createDate = "", endTime = "2026-10-18 23:59", openDate = "2026-10-07 00:00",
                                    status = 0, submitCount = 30, allCount = 60, subStatus = if (submitted) "已提交" else "未提交",
                                    scoreId = 0, homeworkType = 2), false, {})
                            }
                        }
                        Panel("物理在线", Modifier.weight(1f)) {
                            listOf(false, true).forEachIndexed { i, submitted ->
                                PhyVlabActivityRow(PhyVlabActivity(i, 1, "测试课程 · 大学物理", "第 ${i + 1} 章 · 课后作业", "作业", "",
                                    openText = "2026-10-07 00:00", dueText = "2026-10-18 23:59", completed = submitted), 0, {})
                            }
                        }
                        Panel("CITEL", Modifier.weight(1f)) {
                            listOf(false, true).forEachIndexed { i, submitted ->
                                CitelTaskCard(CitelTask(i, 1, "测试课程 · 数据结构", "第 ${i + 1} 章 · 编程练习", "", true,
                                    openTime = 1791302400, discountTime = 1791907200, dueTime = 1792339140, discount = 0.8, submitted = submitted), {})
                            }
                        }
                    }
                }
            }
        }
        try {
            repeat(5) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
            val image = scene.render(2_000_000_000L)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)!!
                File("build/reports/assignment-ui/three-platforms.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                data.close()
            } finally { image.close() }
        } finally { scene.close() }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title)
        HomeworkSummaryBanner(2, "未来 48 小时内暂无临近截止项", {})
        content()
    }
}
