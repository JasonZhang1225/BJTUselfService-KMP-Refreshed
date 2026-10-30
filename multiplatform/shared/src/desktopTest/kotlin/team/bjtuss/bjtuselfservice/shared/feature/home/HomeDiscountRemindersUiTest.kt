package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.data.home.*
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.feature.citel.citelAgendaEvents

class HomeDiscountRemindersUiTest {
    private val f = HomeDiscountAgendaFixture()
    private val output = File(System.getenv("BJTU_HOME_DISCOUNT_UI_OUTPUT")
        ?: "../.artifacts/home-discount-reminders/ui")

    @Test fun actualHomeShowsDiscountAboveDeadlineAndOmitsEmptyCategoriesAcrossLayouts() = SwingUtilities.invokeAndWait {
        for (dark in listOf(false, true)) for ((width, scale) in listOf(360 to 1f, 1000 to 1f, 360 to 1.6f, 1000 to 1.6f)) {
            for (mode in listOf("both", "discount-only", "deadline-only", "none")) {
                val events = when (mode) {
                    "discount-only" -> f.events.filter { it.id == "citel-discount-20" }
                    "deadline-only" -> f.events.filter { it.id == "citel-due-21" }
                    "none" -> emptyList()
                    else -> f.events
                }
                val fixture = Fixture(events, width, scale, dark)
                try {
                    fixture.pump()
                    fixture.assertCategories(mode in listOf("both", "discount-only"), mode in listOf("both", "deadline-only"))
                    fixture.capture("$mode-w$width-s$scale-${if (dark) "dark" else "light"}")
                } finally { fixture.close() }
            }
        }
    }

    @Test fun bothViewHomeworkButtonsOpenTheExistingHomeworkDestination() = SwingUtilities.invokeAndWait {
        val fixture = Fixture(f.events, 440, 1f, false)
        try {
            fixture.pump()
            fixture.assertCategories(true, true)
            for (button in fixture.buttons()) {
                val center = button.node.boundsInRoot.center
                fixture.scene.sendPointerEvent(PointerEventType.Press, Offset(center.x, center.y))
                fixture.scene.sendPointerEvent(PointerEventType.Release, Offset(center.x, center.y))
                fixture.pump()
            }
            assertEquals(2, fixture.opened, "两类提醒都沿用查看作业入口")
        } finally { fixture.close() }
    }

    @Test fun eachCategoryKeepsItsOwnCountFirstThreeItemsAndOverflowText() = SwingUtilities.invokeAndWait {
        val discountTasks = (1..4).map { index -> f.discountTask.copy(
            id = 100 + index, title = "折扣示例$index", discountTime = f.seconds + index * 3600,
        ) }
        val deadlineTasks = (1..4).map { index -> f.deadlineTask.copy(
            id = 200 + index, title = "截止示例$index", dueTime = f.seconds + index * 7200,
        ) }
        val fixture = Fixture(citelAgendaEvents((discountTasks + deadlineTasks).reversed()), 360, 1.6f, true)
        try {
            fixture.pump()
            val texts = fixture.texts()
            val discountTitle = texts.single { it.text == "48 小时内有 4 项待完成作业开始折扣" }
            val deadlineTitle = texts.single { it.text == "48 小时内有 4 项待完成作业截止" }
            assertTrue(discountTitle.node.boundsInRoot.bottom < deadlineTitle.node.boundsInRoot.top)
            val buttons = fixture.buttons()
            assertEquals(2, buttons.size)
            fun summaryRows(title: TextNode, button: TextNode) = texts.filter {
                it.node.boundsInRoot.top >= title.node.boundsInRoot.bottom &&
                    it.node.boundsInRoot.bottom <= button.node.boundsInRoot.top
            }.map { it.text }
            val discounts = summaryRows(discountTitle, buttons[0])
            val deadlines = summaryRows(deadlineTitle, buttons[1])
            assertEquals(listOf(1, 2, 3), discounts.filter { it.startsWith("CITEL · 折扣示例") }
                .map { text -> (1..4).single { text.contains("折扣示例$it ·") } })
            assertEquals(listOf(1, 2, 3), deadlines.filter { it.startsWith("CITEL · 截止示例") }
                .map { text -> (1..4).single { text.contains("截止示例$it ·") } })
            assertTrue("另有 1 项" in discounts && "另有 1 项" in deadlines)
            assertTrue(discounts.none { it.contains("折扣示例4") || it.contains("截止示例") })
            assertTrue(deadlines.none { it.contains("截止示例4") || it.contains("折扣示例") })
            fixture.capture("independent-overflow-large-dark")
        } finally { fixture.close() }
    }

    @Test fun cachedRemindersRemainVisibleDuringLoadingAndHomeStatusErrors() = SwingUtilities.invokeAndWait {
        for (dark in listOf(false, true)) for (mode in listOf("loading", "error")) {
            val fixture = Fixture(f.events, 440, 1f, dark, mode)
            try {
                fixture.pump()
                fixture.assertCategories(true, true)
                fixture.capture("$mode-${if (dark) "dark" else "light"}")
            } finally { fixture.close() }
        }
    }

    private data class TextNode(val text: String, val node: SemanticsNode)

    private inner class Fixture(events: List<PhyVlabEvent>, val width: Int, scale: Float, dark: Boolean, mode: String = "normal") {
        var opened = 0
        private val model = HomeScreenModel(object : HomeStatusRepository {
            override fun load(): HomeStatus? = null
            override suspend fun refresh(): HomeStatusRefreshResult = if (mode == "error")
                HomeStatusRefreshResult.Failure(null, HomeStatusFailure.NETWORK)
                else HomeStatusRefreshResult.Success(HomeStatus("0", "100", "100"))
        }).also { runBlocking { it.refresh() } }
        private val density = Density(1f, scale)
        val scene = ImageComposeScene(width, if (scale > 1f) 2400 else 1700,
            density = density, coroutineContext = Dispatchers.Unconfined) {
            PlatformAppTheme(dark, false) {
                CompositionLocalProvider(LocalDensity provides density) {
                    Surface(Modifier.fillMaxSize()) {
                        HomeWorkspace(model, PlatformInfo(PlatformFamily.MacOS, "fixture", isWindows = true),
                            expanded = width >= 800, homework = emptyList(), exams = emptyList(),
                            phyVlabEvents = events, currentWeek = 1, now = f.now, timeZone = f.zone,
                            isAgendaLoading = mode == "loading", onOpenMailbox = {}, onOpenHomework = { opened++ },
                            onOpenExams = {}, changes = emptyList(), onClearAllChanges = {}, onClearChangeDomain = {},
                            onOpenChangeDomain = {}, holdNetwork = true)
                    }
                }
            }
        }
        fun pump() { repeat(5) { scene.render(System.nanoTime()).close(); Thread.sleep(10) } }
        fun texts(): List<TextNode> {
            fun collect(node: SemanticsNode): List<TextNode> {
                val own = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("\n") { it.text }
                return listOfNotNull(own?.let { TextNode(it, node) }) + node.children.flatMap(::collect)
            }
            return scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
        }
        fun buttons() = texts().filter { it.text == "查看作业" }.sortedBy { it.node.boundsInRoot.top }
        fun assertCategories(discount: Boolean, deadline: Boolean) {
            val texts = texts()
            val discounts = texts.filter { it.text == "48 小时内有 1 项待完成作业开始折扣" }
            val deadlines = texts.filter { it.text == "48 小时内有 1 项待完成作业截止" }
            assertEquals(if (discount) 1 else 0, discounts.size)
            assertEquals(if (deadline) 1 else 0, deadlines.size)
            assertEquals(listOf(discount, deadline).count { it }, buttons().size)
            if (discount && deadline) assertTrue(discounts.single().node.boundsInRoot.bottom < deadlines.single().node.boundsInRoot.top,
                "折扣必须显示在截止上方，宽窗口也不能并排")
            for (item in discounts + deadlines + buttons()) {
                val bounds = item.node.boundsInRoot
                assertTrue(bounds.left >= 0f && bounds.right <= width + 1f, "窄窗口与大字号不得横向溢出：$bounds")
            }
        }
        fun capture(name: String) {
            val image = scene.render(System.nanoTime())
            try {
                val data = image.encodeToData(EncodedImageFormat.PNG)!!
                try { output.mkdirs(); File(output, "$name.png").writeBytes(data.bytes) } finally { data.close() }
            } finally { image.close() }
        }
        fun close() = scene.close()
    }
}
