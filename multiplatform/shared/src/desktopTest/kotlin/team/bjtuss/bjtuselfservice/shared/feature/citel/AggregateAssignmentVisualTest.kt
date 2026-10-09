package team.bjtuss.bjtuselfservice.shared.feature.citel

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import kotlin.test.assertEquals
import team.bjtuss.bjtuselfservice.shared.files.*
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
import team.bjtuss.bjtuselfservice.shared.feature.assignment.*
import team.bjtuss.bjtuselfservice.shared.feature.settings.AssignmentAggregationCard
import team.bjtuss.bjtuselfservice.shared.feature.settings.FeatureSwitchesCard

class AggregateAssignmentVisualTest {
    @Test fun desktopSelectionKeepsListAndDetailInOneWorkspace() = SwingUtilities.invokeAndWait {
        val task = Homework(upId = 1, idSnId = null, score = "", userId = 0, courseId = 1, courseName = "数据结构", title = "离线作业要求",
            content = "", createDate = "", endTime = "2099-10-18 23:59", openDate = "2026-10-07 00:00",
            status = 0, submitCount = 3, allCount = 60, subStatus = "未提交", scoreId = 0, homeworkType = 0)
        val items = aggregateAssignments(listOf(task),
            listOf(PhyVlabActivity(2, 2, "大学物理", "物理在线实验", "作业", "")),
            listOf(CitelTask(3, 3, "程序设计", "CITEL 编程练习", "", true)))
        var opened: AggregatedAssignment? = null
        val gateway = object : HomeworkFileGateway {
            override val isAvailable = true
            override suspend fun pickFiles() = HomeworkFilePickResult.Cancelled
            override suspend fun saveFile(file: team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent) = HomeworkFileSaveResult.Cancelled
        }
        val scene = ImageComposeScene(1200, 840, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(false, false) {
                Surface(Modifier.fillMaxSize()) {
                    AggregatedAssignmentWorkspace(items, emptyList(), null, {}, { opened = it }, {}, false, {},
                        Modifier.fillMaxSize(), expanded = true, fileGateway = gateway, markdown = { "# 离线作业要求" },
                        detailContent = { item -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            AssignmentSourceBadge(item.source)
                            Text(task.title, style = MaterialTheme.typography.headlineSmall)
                            Text("课程：${item.courseName}")
                            Text("作业要求", style = MaterialTheme.typography.titleMedium)
                            Text("这是离线布局检查。左侧列表持续保留，右侧展示所选来源的详情；三个来源共用复制和保存入口。")
                        } })
                }
            }
        }
        try {
            repeat(5) { scene.render((it + 1) * 100_000_000L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(160f, 190f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(160f, 190f))
            repeat(5) { scene.render(1_000_000_000L + it * 100_000_000L).close() }
            assertEquals(items.first().key, opened?.key)
            val image = scene.render(2_000_000_000L)
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)!!
                File("build/reports/assignment-ui/desktop-split.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                data.close()
            } finally { image.close() }
        } finally { scene.close() }
    }

    @Test fun renderAggregatedListFiltersAndIndependentSyncOnce() = SwingUtilities.invokeAndWait {
        val homework = Homework(upId = 1, idSnId = null, score = "", userId = 0, courseId = 1, courseName = "数据结构", title = "课后练习",
            content = "", createDate = "", endTime = "2026-10-18 23:59", openDate = "2026-10-07 00:00",
            status = 0, submitCount = 3, allCount = 60, subStatus = "未提交", scoreId = 0, homeworkType = 0)
        val physical = PhyVlabActivity(1, 1, "大学物理", "第 3 章 · 课后作业", "作业", "", openText = "2026-10-07 00:00",
            dueText = "2026-10-18 23:59", dueTimestamp = 1792339140, completed = true)
        val citel = CitelTask(1, 1, "数据结构", "顺序表 · 编程练习", "", true, openTime = 1791302400,
            dueTime = 1792339140, discountTime = 1791907200, discount = 0.8)
        val items = aggregateAssignments(listOf(homework), listOf(physical), listOf(citel))
        val sync = listOf(AssignmentSourceSync(AssignmentSource.COURSE_PLATFORM, false, false, true),
            AssignmentSourceSync(AssignmentSource.PHYVLAB, false, true, false, true, "请连接校园网后重试。"),
            AssignmentSourceSync(AssignmentSource.CITEL, false, false, false, true))
        val scene = ImageComposeScene(width = 1200, height = 1050, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(useDarkTheme = false, dynamicColorEnabled = false) {
                Surface(Modifier.fillMaxSize()) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Panel("作业聚合", Modifier.weight(1f)) {
                            AggregatedAssignmentWorkspace(items, sync, null, {}, {}, {}, false, {}, Modifier.fillMaxSize(),
                                fileGateway = team.bjtuss.bjtuselfservice.shared.files.UnavailableHomeworkFileGateway, markdown = { "" }, detailContent = {})
                        }
                        Panel("按平台筛选课程", Modifier.weight(1f)) {
                            AggregateAssignmentFilterSheet(items, AssignmentSource.entries,
                                AggregateAssignmentFilters().withSource(AssignmentSource.PHYVLAB, AggregateCourseFilter(false)), {})
                        }
                        Panel("设置与独立同步状态", Modifier.weight(1f)) {
                            AssignmentAggregationCard(true, {})
                            FeatureSwitchesCard(true, true, true, true, true, {}, {}, {}, {}, {})
                            AssignmentSyncDetails(sync, {})
                            FilledTonalButton({}, Modifier.fillMaxWidth()) { Text("在网页中打开") }
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
                File("build/reports/assignment-ui/aggregate.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
                data.close()
            } finally { image.close() }
        } finally { scene.close() }
    }
}

@Composable
private fun Panel(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}
