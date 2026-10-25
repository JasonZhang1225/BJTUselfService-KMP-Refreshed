package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusRepository
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus
import team.bjtuss.bjtuselfservice.shared.feature.home.*
import javax.swing.SwingUtilities
import kotlin.test.*

class NativeHomePresentationTest {
    @Test fun nativeCalendarKeepsUserSelectedWeekWhenCurrentWeekArrives() {
        SwingUtilities.invokeAndWait {
            val presenter = Recorder()
            val week = mutableIntStateOf(6)
            val now = LocalDateTime(2026, 10, 19, 9, 15)
            var mailboxOpens = 0
            val scene = fixture(presenter, now, week = { week.intValue }, onMailbox = { mailboxOpens++ })
            try {
                repeat(3) { scene.render() }
                assertEquals("第 6 教学周", presenter.home!!.weekTitle)
                assertEquals("2026-10-19", presenter.home!!.days.single { it.selected }.id)
                assertEquals("周一课程 - 示例教师", presenter.home!!.events.single().title)
                assertEquals(1, presenter.home!!.days.first().marks.single().count)
                presenter.home!!.days[1].onSelect()
                repeat(3) { scene.render() }
                assertEquals("周二课程 - 示例教师", presenter.home!!.events.single().title)
                assertFalse(presenter.home!!.todaySelected)
                presenter.home!!.summaries.first().onOpen()
                assertEquals(1, mailboxOpens)
                presenter.home!!.onNext!!()
                repeat(3) { scene.render() }
                assertEquals("第 7 教学周", presenter.home!!.weekTitle)
                assertEquals("10月26日 — 11月1日", presenter.home!!.dateRange)
                assertEquals("2026-10-26", presenter.home!!.days.single { it.selected }.id)
                week.intValue = 8
                repeat(4) { scene.render() }
                assertEquals("第 7 教学周", presenter.home!!.weekTitle, "Async calendar updates must not override a manual choice")
                presenter.home!!.onToday()
                repeat(3) { scene.render() }
                assertEquals("2026-10-19", presenter.home!!.days.single { it.selected }.id)
                assertTrue(presenter.home!!.todaySelected)
            } finally { scene.close() }
            assertNull(presenter.home)
        }
    }

    @Test fun loadingToEmptyUpdatesNativeSnapshotWithoutLosingThePage() {
        SwingUtilities.invokeAndWait {
            val presenter = Recorder()
            val loading = mutableStateOf(true)
            val scene = fixture(presenter, LocalDateTime(2026, 10, 25, 9, 15), loading = { loading.value })
            try {
                repeat(3) { scene.render() }
                assertTrue(presenter.home!!.loading)
                assertEquals("日程加载中", presenter.home!!.weekTitle)
                assertTrue(presenter.home!!.events.isEmpty())
                loading.value = false
                repeat(4) { scene.render() }
                assertFalse(presenter.home!!.loading)
                assertEquals("第 6 教学周", presenter.home!!.weekTitle)
                assertTrue(presenter.home!!.events.isEmpty())
            } finally { scene.close() }
        }
    }

    private class Recorder : NativeDesktopPresentation {
        var home: NativeDesktopHome? = null
        override fun showHome(value: NativeDesktopHome?) { home = value }
        override fun showLogin(value: NativeDesktopLogin?) = Unit
        override fun showGuide(value: NativeDesktopGuide?) = Unit
        override fun updateNavigation(title: String, canGoBack: Boolean, onBack: () -> Unit) = Unit
        override fun updateAction(value: NativeBarAction?) = Unit
    }

    private fun fixture(presenter: Recorder, now: LocalDateTime, week: () -> Int = { 6 }, loading: () -> Boolean = { false }, onMailbox: () -> Unit = {}): ImageComposeScene {
        val model = HomeScreenModel(object : HomeStatusRepository {
            override fun load(): HomeStatus? = error("Native calendar fixture must not access storage")
            override suspend fun refresh(): HomeStatusRefreshResult = error("Native calendar fixture must not access the network")
        })
        val courses = listOf(
            Course(1, "preview-mon", "周一课程", "示例教师", 1, "第6-10周", "示例教室", false),
            Course(2, "preview-tue", "周二课程", "示例教师", 2, "第6-10周", "示例教室", false),
        )
        return ImageComposeScene(960, 620, coroutineContext = Dispatchers.Unconfined) {
            CompositionLocalProvider(
                LocalNativeDesktopPresentation provides presenter,
                LocalHomeSchedule provides HomeSchedulePresentation(courses = courses, currentWeek = week(), today = now.date),
            ) {
                HomeWorkspace(
                    model, PlatformInfo(PlatformFamily.MacOS, "macOS"), expanded = true,
                    homework = emptyList(), exams = emptyList(), currentWeek = week(), now = now,
                    isWeekResolved = !loading(), timeZone = TimeZone.of("Asia/Shanghai"), isAgendaLoading = loading(),
                    onOpenMailbox = onMailbox, onOpenHomework = {}, onOpenExams = {}, changes = emptyList(),
                    onClearAllChanges = {}, onClearChangeDomain = {}, onOpenChangeDomain = {}, holdNetwork = true, realtimeClock = false,
                )
            }
        }
    }
}
