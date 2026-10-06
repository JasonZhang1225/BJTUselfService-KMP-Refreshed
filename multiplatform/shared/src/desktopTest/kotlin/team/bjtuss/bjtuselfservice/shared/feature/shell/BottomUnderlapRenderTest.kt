package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.feature.classroom.*
import team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy.*
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.*
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.*
import team.bjtuss.bjtuselfservice.shared.data.classroom.*
import team.bjtuss.bjtuselfservice.shared.feature.mailbox.*
import team.bjtuss.bjtuselfservice.shared.data.mailbox.MailboxRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.*
import team.bjtuss.bjtuselfservice.shared.network.*

/** Offscreen Compose only: no simulator, account store or network access. */
class BottomUnderlapRenderTest {
    @Test fun viewportUnderlapsBarButLastContentStopsAboveIt() {
        SwingUtilities.invokeAndWait {
            for (height in listOf(568, 844)) for (font in listOf(1f, 1.5f)) {
                for (hasBar in listOf(true, false)) for (layout in listOf("lazy", "scroll", "fixed", "empty")) {
                    val inset = destinationBottomClearance(false, hasBar, 114.dp, 34.dp)
                    val expectedBottom = height - inset.value
                    var viewportBottom = 0f
                    var lastBottom = 0f
                    var lastHeight = 0f
                    var lastTop = 0f
                    var contentUnderBar = false
                    val scrollToEnd = mutableStateOf(false)
                    val scene = ImageComposeScene(width = 390, height = height, density = Density(1f, font), coroutineContext = Dispatchers.Unconfined) {
                        Box(Modifier.fillMaxSize().onGloballyPositioned {
                            viewportBottom = it.boundsInRoot().bottom
                        }) {
                            val last = Modifier.fillMaxWidth().height(48.dp).background(Color.Green)
                                .onGloballyPositioned {
                                    val rect = it.boundsInRoot()
                                    lastTop = rect.top; lastBottom = rect.bottom; lastHeight = rect.height
                                }
                            when (layout) {
                                "lazy" -> {
                                    val state = rememberLazyListState()
                                    LaunchedEffect(scrollToEnd.value) { if (scrollToEnd.value) state.scrollToItem(29) }
                                    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp + inset)) {
                                        items(30) { index -> Box(if (index == 29) last else Modifier.fillMaxWidth().height(60.dp).onGloballyPositioned {
                                            if (it.boundsInRoot().bottom > expectedBottom) contentUnderBar = true
                                        }) }
                                    }
                                }
                                "scroll" -> {
                                    val state = rememberScrollState()
                                    LaunchedEffect(scrollToEnd.value, state.maxValue) { if (scrollToEnd.value) state.scrollTo(state.maxValue) }
                                    Column(Modifier.fillMaxSize().verticalScroll(state).padding(bottom = 16.dp + inset)) {
                                        repeat(29) { Box(Modifier.fillMaxWidth().height(60.dp).onGloballyPositioned {
                                            if (it.boundsInRoot().bottom > expectedBottom) contentUnderBar = true
                                        }) }; Box(last)
                                    }
                                }
                                "fixed" -> Column(Modifier.fillMaxSize().padding(bottom = inset)) { Spacer(Modifier.weight(1f)); Box(last) }
                                else -> Box(Modifier.fillMaxSize().padding(bottom = inset), contentAlignment = Alignment.BottomCenter) { Box(last) }
                            }
                        }
                    }
                    try {
                        repeat(3) { scene.render(500_000_000L + it * 16_000_000L).close() }
                        if (layout == "lazy" || layout == "scroll") assertTrue(contentUnderBar, "Content must draw behind the bar: $height $font $hasBar $layout")
                        scrollToEnd.value = true
                        repeat(12) { scene.render(1_000_000_000L + it * 16_000_000L).close() }
                        assertEquals(height.toFloat(), viewportBottom, 0.5f, "$height $font $hasBar $layout")
                        assertTrue(lastTop >= 0f && lastHeight >= 47f && lastBottom <= expectedBottom + 0.5f,
                            "Last item must remain fully visible: $height $font $hasBar $layout ($lastTop, $lastBottom)")
                    } finally { scene.close() }
                }
            }
        }
    }

    @Test fun renderActualMailboxAndClassroomsAtEndOfList() {
        val mailbox = MailboxScreenModel(object : SchoolHttpTransport {
            override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = error("No network in preview")
            override suspend fun sessionCookiesFor(url: String) = listOf(SchoolSessionCookie("preview", "synthetic"))
            override fun clearSession() = Unit
        }, object : MailboxRemoteDataSource {
            override suspend fun listMessages(folderId: Int, start: Int, limit: Int, descending: Boolean) = MailboxPage(20,
                List(20) { i -> MailSummary("$i", 1, "课程通知 <teacher@example.test>", "课程安排 ${i + 1}",
                    "请查阅本周的安排与附件。", "2026-10-06 13:00", "2026-10-06 13:00", 128, true, false) })
            override suspend fun readMessage(messageId: String): MailMessage = error("Unused")
            override suspend fun beginCompose(replyToMessageId: String?): MailComposeDraft = error("Unused")
            override suspend fun sendMessage(draft: MailComposeDraft) = error("Unused")
            override suspend fun cancelCompose(composeId: String) = Unit
        })
        runBlocking { mailbox.initialize() }
        val classrooms = ClassroomScreenModel(object : ClassroomRepository {
            override suspend fun fetchBuildingInfo(buildingName: String): ClassroomFetchResult = error("No network in preview")
        })
        val occupancy = ClassroomOccupancyScreenModel(object : ClassroomOccupancyRepository {
            override suspend fun fetchOccupancy(week: Int, buildingId: String, semesterId: String?): ClassroomOccupancyResult = error("No network in preview")
            override suspend fun fetchSemesters() = SemesterOptions(null, emptyList())
            override suspend fun fetchWeekDates(): Map<String, List<OccupancyWeekDate>> = emptyMap()
        })
        SwingUtilities.invokeAndWait {
            for (page in listOf(AppSection.MAILBOX, AppSection.CLASSROOMS, AppSection.CLASSROOM_OCCUPANCY)) {
                val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f), coroutineContext = Dispatchers.Unconfined) {
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            CompositionLocalProvider(LocalBottomBarClearance provides destinationBottomClearance(false, true, 114.dp, 34.dp)) {
                            Column(Modifier.fillMaxSize().padding(top = 44.dp)) {
                                CompactAppTopBar(page.title, isRefreshing = false, onRefresh = {}, action = if (page == AppSection.MAILBOX) {
                                    { TopBarCalendarAction("写信", {}) }
                                } else null)
                                if (page == AppSection.MAILBOX) MailboxWorkspace(mailbox, expanded = false, modifier = Modifier.weight(1f))
                                else if (page == AppSection.CLASSROOMS) ClassroomWorkspace(classrooms, expanded = false, modifier = Modifier.weight(1f))
                                else ClassroomOccupancyWorkspace(occupancy, expanded = false, modifier = Modifier.weight(1f))
                            }
                            }
                            // The local preview uses the real fallback bar; native liquid glass is left untouched.
                            Box(Modifier.align(Alignment.BottomCenter).height(114.dp)) {
                                CompactBottomNavigation(page, listOf(AppSection.HOME, AppSection.MAILBOX, if (page == AppSection.CLASSROOMS) AppSection.CLASSROOMS else AppSection.CLASSROOM_OCCUPANCY, AppSection.MORE), {})
                            }
                        }
                    }
                }
                try {
                    repeat(6) { scene.render(1_000_000_000L + it * 16_000_000L).close() }
                    repeat(20) {
                        scene.sendPointerEvent(PointerEventType.Scroll, Offset(180f, 400f), scrollDelta = Offset(0f, 120f))
                        scene.render(2_000_000_000L + it * 16_000_000L).close()
                    }
                    val rendered = scene.render(3_000_000_000L)
                    try {
                        val data = rendered.encodeToData(EncodedImageFormat.PNG)!!
                        val file = File("build/reports/bottom-underlap/${page.name.lowercase()}-bottom.png")
                        file.parentFile.mkdirs(); file.writeBytes(data.bytes); data.close()
                        assertTrue(file.length() > 1000)
                    } finally { rendered.close() }
                } finally { scene.close() }
            }
        }
    }
}
