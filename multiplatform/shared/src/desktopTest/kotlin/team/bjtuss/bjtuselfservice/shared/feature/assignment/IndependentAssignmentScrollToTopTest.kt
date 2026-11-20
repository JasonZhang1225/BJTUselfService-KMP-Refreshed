package team.bjtuss.bjtuselfservice.shared.feature.assignment

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.SwingUtilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformAppTheme
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.cache.db.CacheDatabaseSql
import team.bjtuss.bjtuselfservice.shared.data.homework.*
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.*
import team.bjtuss.bjtuselfservice.shared.files.FileContent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.*
import team.bjtuss.bjtuselfservice.shared.feature.citel.*
import team.bjtuss.bjtuselfservice.shared.feature.homework.*
import team.bjtuss.bjtuselfservice.shared.feature.phyvlab.*
import team.bjtuss.bjtuselfservice.shared.feature.shell.*
import team.bjtuss.bjtuselfservice.shared.files.UnavailableFileGateway
import team.bjtuss.bjtuselfservice.shared.network.*

class IndependentAssignmentScrollToTopTest {
    @Test fun allIndependentProductionListsScrollBackInPhoneWideAndLandscapeLayouts() = SwingUtilities.invokeAndWait {
        val layouts = listOf(
            Layout(390, 844, false, 1f), Layout(390, 844, true, 1.6f),
            Layout(1000, 720, false, 1f), Layout(640, 360, true, 1.6f),
        )
        for (source in listOf("homework", "phyvlab", "citel")) for (layout in layouts) {
            Fixture(source, 60, layout).use { fixture ->
                fixture.settle()
                assertNull(fixture.button(), "初始顶部隐藏：$source $layout")
                fixture.scroll(2400f)
                fixture.settle(120)
                val button = assertNotNull(fixture.button(), "超过一屏出现：$source $layout")
                val bounds = button.boundsInRoot
                assertEquals(48f, bounds.width, 0.1f, "放大视觉后仍保留 48.dp 点击区域")
                assertEquals(48f, bounds.height, 0.1f)
                assertTrue(bounds.left >= 0f && bounds.top >= 0f)
                assertTrue(bounds.right <= layout.width && bounds.bottom <= layout.height - 84f - 12f + 1f,
                    "按钮位于视口内并避开底栏：$source $layout $bounds")
                if (source == "homework" && layout.width >= 600) {
                    assertTrue(bounds.right < layout.width * 0.45f, "宽屏按钮仅控制左侧作业列表")
                }
                fixture.capture("$source-${layout.width}x${layout.height}-${if (layout.dark) "dark" else "light"}-s${layout.scale}")
                fixture.scene.sendPointerEvent(PointerEventType.Press, bounds.center)
                fixture.scene.sendPointerEvent(PointerEventType.Release, bounds.center)
                fixture.settle(180)
                assertNull(fixture.button(), "点击回顶后隐藏：$source $layout")
                assertFalse(fixture.canScrollBackward(), "必须真正回到第一项，不能仅隐藏按钮")
                assertTrue(fixture.nodes().any {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> "未来 48 小时" in text.text } == true
                }, "回到顶部能看到原有筛选摘要")
            }
        }
    }

    @Test fun citelBatchActionStaysVisibleWhileDownloadingAndClearsTheScrollButton() =
        SwingUtilities.invokeAndWait {
            for (layout in listOf(Layout(390, 844, false, 1f), Layout(640, 360, true, 1.6f))) {
                Fixture("citel-cache", 60, layout).use { fixture ->
                    fixture.settle()
                    val action = assertNotNull(fixture.nodeWithText("一键拉取未缓存题面"))
                    assertNull(fixture.button())
                    fixture.scene.sendPointerEvent(PointerEventType.Press, action.boundsInRoot.center)
                    fixture.scene.sendPointerEvent(PointerEventType.Release, action.boundsInRoot.center)
                    fixture.scroll(10_000f)
                    fixture.settle(120)

                    val progressText = assertNotNull(fixture.nodeWithText("正在拉取题面…"))
                    val progress = assertNotNull(generateSequence(progressText) { it.parent }.firstOrNull {
                        it.config.getOrNull(SemanticsProperties.Role) == Role.Button
                    })
                    assertTrue(SemanticsProperties.Disabled in progress.config)
                    val button = assertNotNull(fixture.button())
                    val lastTask = assertNotNull(fixture.nodeWithText("课后练习 60"))
                    assertTrue(lastTask.boundsInRoot.bottom <= button.boundsInRoot.top)
                    assertTrue(button.boundsInRoot.bottom <= progress.boundsInRoot.top)
                    assertTrue(progress.boundsInRoot.bottom <= layout.height - 84f - 10f)
                    fixture.capture("citel-cache-${layout.width}x${layout.height}-busy")

                    fixture.scene.sendPointerEvent(PointerEventType.Press, button.boundsInRoot.center)
                    fixture.scene.sendPointerEvent(PointerEventType.Release, button.boundsInRoot.center)
                    fixture.settle(180)
                    assertFalse(fixture.canScrollBackward())
                    assertNull(fixture.button())
                    assertNotNull(fixture.nodeWithText("正在拉取题面…"))

                    fixture.finishCitelBatch()
                    fixture.settle()
                    assertNull(fixture.nodeWithText("正在拉取题面…"))
                    assertNull(fixture.nodeWithText("一键拉取未缓存题面"))
                    assertEquals(60, fixture.cachedCitelCount())
                }
            }
        }

    @Test fun compactHomeworkAndAggregateRetainPositionWhenReturningFromDetails() = SwingUtilities.invokeAndWait {
        for (source in listOf("homework", "aggregate")) {
            Fixture(source, 60, Layout(390, 844, false, 1f), navigate = true).use { fixture ->
                fixture.settle()
                fixture.scroll(2400f)
                fixture.settle()
                val position = fixture.scrollPosition()
                assertTrue(position > 0f, source)

                fixture.openVisibleHomework()
                fixture.settle()
                assertNotNull(fixture.nodeWithText("返回作业列表"), source)
                fixture.goBack()
                fixture.settle()
                assertEquals(position, fixture.scrollPosition(), 0.0001f, source)

                fixture.search.activate()
                fixture.search.update("阅读与思考")
                fixture.settle()
                assertEquals(0f, fixture.scrollPosition(), source)
            }
        }
    }

    @Test fun homeworkResetsScrollWhenChangingUntilEpochWithHideExpired() = SwingUtilities.invokeAndWait {
        Fixture("homework", 60, Layout(390, 844, false, 1f), homeworkEndTime = "2099-12-31 23:59").use { fixture ->
            fixture.setHomeworkDeadlineScope(hideExpired = true, untilEpoch = 4_102_500_000L)
            fixture.settle()
            fixture.scroll(2400f)
            fixture.settle()
            assertTrue(fixture.scrollPosition() > 0f)

            fixture.setHomeworkDeadlineScope(hideExpired = true, untilEpoch = 4_102_600_000L)
            fixture.settle()
            assertEquals(0f, fixture.scrollPosition(), 0.0001f)
        }
    }

    @Test fun emptyAndShortIndependentListsDoNotShowTheButton() = SwingUtilities.invokeAndWait {
        for (source in listOf("homework", "phyvlab", "citel")) for (count in listOf(0, 1)) {
            Fixture(source, count, Layout(390, 844, false, 1f)).use { fixture ->
                fixture.settle()
                fixture.scroll(120f)
                fixture.settle()
                assertNull(fixture.button(), "$source count=$count 不应显示回顶按钮")
            }
        }
    }

    private data class Layout(val width: Int, val height: Int, val dark: Boolean, val scale: Float)

    private class Fixture(
        val source: String,
        count: Int,
        val layout: Layout,
        private val navigate: Boolean = false,
        homeworkEndTime: String? = null,
    ) : AutoCloseable {
        private val routes = mutableStateListOf("list")
        val search = PageSearchState()
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CacheDatabaseSql.Schema.create(it) }
        private val cache = CacheStore(driver)
        private val homework = (1..count).map {
            val base = completionFixtureHomework().copy(upId = it, title = "阅读与思考 $it")
            if (homeworkEndTime != null) base.copy(endTime = homeworkEndTime) else base
        }
        private val aggregatedHomework = aggregateAssignments(homework, emptyList(), emptyList())
        private val activities = (1..count).map { completionFixturePhysical().copy(id = it, title = "实验准备 $it") }
        private val tasks = (1..count).map { completionFixtureCitel().copy(id = it, title = "课后练习 $it", programming = source == "citel-cache") }
        private val homeworkModel = HomeworkScreenModel(DefaultHomeworkRepository("scroll-fixture",
            CacheStoreHomeworkLocalDataSource(cache), SchoolHomeworkRemoteDataSource(NoNetwork)))
        private val course = PhyVlabCourse(10, "示例物理课程", "示例", 0, "https://phyvlab.bjtu.edu.cn/course/view.php?id=10")
        private val phyModel = PhyVlabScreenModel(object : PhyVlabRepository {
            override suspend fun fetchCourses() = PhyVlabCoursesResult.Success(if (count == 0) emptyList() else listOf(course))
            override suspend fun fetchCourseActivities(course: PhyVlabCourse) = PhyVlabActivitiesResult.Success(activities)
            override suspend fun fetchEvents(monthTimestampSeconds: Long) = PhyVlabEventsResult.Success(emptyList())
            override suspend fun fetchAssignmentDetail(activity: PhyVlabActivity): PhyVlabAssignmentDetailResult = error("No detail request expected")
            override suspend fun submitAssignment(activity: PhyVlabActivity, files: List<FileContent>): PhyVlabSubmissionResult = error("No submission allowed")
        }, PhyVlabSessionProtocol(object : SchoolHttpTransport {
            override suspend fun execute(request: SchoolHttpRequest) = SchoolHttpResponse(200,
                "https://phyvlab.bjtu.edu.cn/login/index.php", body = "<a href='/login/logout.php'>退出登录</a>".encodeToByteArray())
            override fun clearSession() = Unit
        }))
        private val finishBatch = CompletableDeferred<Unit>()
        private val citelModel = CitelModel("scroll-fixture", cache, null, object : CitelDataSource {
            override suspend fun fetch(credentials: Credentials): List<CitelTask> {
                check(source == "citel-cache")
                return tasks
            }

            override suspend fun problem(credentials: Credentials, task: CitelTask, withResults: Boolean): CitelProblem {
                check(source == "citel-cache")
                finishBatch.await()
                return CitelProblem(task.title, "", "<p>测试题面</p>")
            }
        })
        private var controller: NativeScrollController? = null
        private var frame = 0L
        val scene: ImageComposeScene

        init {
            cache.replaceHomework("scroll-fixture", homework)
            cache.putMetadata("scroll-fixture", "citel.tasks", Json.encodeToString(tasks))
            runBlocking {
                homeworkModel.initialize(refreshFromNetwork = false)
                if (source == "phyvlab") phyModel.initialize()
                if (source == "citel-cache") citelModel.configure("scroll-fixture", "fixture", remember = false, enabled = true)
                else citelModel.initialize()
            }
            val density = Density(1f, layout.scale)
            scene = ImageComposeScene(layout.width, layout.height, density = density, coroutineContext = Dispatchers.Unconfined) {
                PlatformAppTheme(layout.dark, false) {
                    CompositionLocalProvider(LocalDensity provides density,
                        LocalTopBarClearance provides 112.dp, LocalBottomBarClearance provides 84.dp,
                        LocalNativeScrollControllerReporter provides { controller = it }) {
                        Surface(Modifier.fillMaxSize()) { if (navigate) navigation() else content() }
                    }
                }
            }
        }

        @Composable private fun navigation() {
            NavDisplay(
                backStack = routes,
                onBack = ::goBack,
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                entryProvider = entryProvider {
                    entry("list") { content() }
                    entry("detail") { Button(onClick = ::goBack) { Text("返回作业列表") } }
                },
            )
        }

        fun finishCitelBatch() { finishBatch.complete(Unit) }

        fun cachedCitelCount() = citelModel.state.value.problems.size

        fun goBack() { routes.removeAt(routes.lastIndex) }

        fun setHomeworkDeadlineScope(hideExpired: Boolean, untilEpoch: Long?) {
            homeworkModel.setDeadlineScope(hideExpired, untilEpoch)
        }

        @Composable private fun content() {
            when (source) {
                "homework" -> {
                    val state by homeworkModel.state.collectAsState()
                    HomeworkWorkspace(state, layout.width >= 600, model = homeworkModel,
                        fileGateway = UnavailableFileGateway, onRefresh = {},
                        onOpenDetail = { if (navigate) routes.add("detail") }, modifier = Modifier.fillMaxSize(), search = search)
                }
                "aggregate" -> AggregatedAssignmentWorkspace(
                    aggregatedHomework, emptyList(), null, {}, { routes.add("detail") }, {}, false, {},
                    modifier = Modifier.fillMaxSize(), fileGateway = UnavailableFileGateway,
                    markdown = { "" }, detailContent = {}, search = search,
                )
                "phyvlab" -> PhyVlabWorkspace(phyModel, showDetailSheet = false)
                "citel" -> CitelWorkspace(citelModel, holdNetwork = true, onOpen = {}, showDetailSheet = false)
                "citel-cache" -> CitelBatchCacheScreen(citelModel, Modifier.fillMaxSize())
            }
        }

        fun settle(count: Int = 24) = repeat(count) { scene.render(++frame * 16_000_000L).close() }
        fun nodes(): List<SemanticsNode> {
            fun collect(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::collect)
            return scene.semanticsOwners.flatMap { collect(it.unmergedRootSemanticsNode) }
        }
        fun nodeWithText(text: String) = nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { text in it.text } == true
        }
        fun openVisibleHomework() {
            val title = assertNotNull(nodeWithText("阅读与思考"))
            val action = generateSequence(title) { it.parent }.firstNotNullOfOrNull {
                it.config.getOrNull(SemanticsActions.OnClick)?.action
            }
            assertTrue(assertNotNull(action).invoke())
        }
        fun scrollPosition() = nodes().maxOfOrNull {
            it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: 0f
        } ?: 0f
        fun button() = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("回到顶部") == true }
        fun scroll(amount: Float) {
            val native = controller
            if (native != null) native.consumeScroll(amount)
            else nodes().firstOrNull { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                ?.config?.get(SemanticsActions.ScrollBy)?.action?.invoke(0f, amount)
        }
        fun canScrollBackward(): Boolean = controller?.canScrollBackward ?: nodes().any {
            (it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke() ?: 0f) > 0f
        }
        fun capture(name: String) {
            val directory = System.getProperty("assignmentScrollTop.artifactDir") ?: return
            val file = File(directory, "$name.png").apply { parentFile.mkdirs() }
            scene.render(++frame * 16_000_000L).use { image ->
                image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) }
            }
        }
        override fun close() { scene.close(); cache.close() }
    }

    private object NoNetwork : SchoolHttpTransport {
        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = error("No real network requests allowed")
        override fun clearSession() = Unit
    }
}
