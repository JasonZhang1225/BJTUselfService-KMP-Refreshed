package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.WindowClass
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences

class NativeNavigationBoundaryTest {
    @Test fun recompositionRebindsPlatformCallbacksWithoutReplacingTheStack() = SwingUtilities.invokeAndWait {
        val revision = mutableStateOf(1)
        val calls = mutableListOf<String>()
        lateinit var navigator: ShellNavigator
        val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
            val version = revision.value
            navigator = rememberShellNavigator(ios, rememberCoroutineScope(), "HOME",
                { calls += "$version:open:$it" }, { calls += "$version:tab:$it" }, { calls += "$version:close" },
                version == 2, true, if (version == 1) WindowClass.Compact else WindowClass.Expanded)
        }
        var frame = 0L
        fun settle() { repeat(5) { scene.render(++frame * 16_000_000L).close() } }
        try {
            settle()
            val first = navigator
            first.pushOrOpen(HomeworkDetailRoute, HOMEWORK_DETAIL_ROUTE_ID)
            assertEquals(listOf(AppSection.HOME, HomeworkDetailRoute), first.backStack.toList())
            revision.value = 2
            settle()
            assertSame(first, navigator)
            assertEquals(listOf(AppSection.HOME, HomeworkDetailRoute), navigator.backStack.toList())
            assertTrue(navigator.useNativeSecondaryRoutes) // Hosted destination stays hosted after expansion.
            navigator.openNativeRoute(EXAM_DETAIL_ROUTE_ID)
            navigator.navigateToSection(AppSection.SCHEDULE, AppPreferences(), bottomNavSections(AppPreferences()))
            settle()
            navigator.pop()
            assertEquals(listOf("2:open:EXAM_DETAIL", "2:tab:SCHEDULE", "2:close"), calls)
        } finally { scene.close() }
    }

    @Test fun removingOnePresentationDoesNotReplaceOrPopAnotherPresentationsStack() = SwingUtilities.invokeAndWait {
        val visible = mutableStateOf(true)
        lateinit var first: ShellNavigator
        lateinit var second: ShellNavigator
        val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
            key("first") {
                if (visible.value) first = rememberShellNavigator(ios, rememberCoroutineScope(), null,
                    {}, {}, {}, false, false, WindowClass.Expanded)
            }
            key("second") {
                second = rememberShellNavigator(ios, rememberCoroutineScope(), null,
                    {}, {}, {}, false, false, WindowClass.Expanded)
            }
        }
        var frame = 0L
        fun settle() { repeat(5) { scene.render(++frame * 16_000_000L).close() } }
        try {
            settle()
            val retained = second
            first.pushOrOpen(HomeworkDetailRoute, HOMEWORK_DETAIL_ROUTE_ID)
            second.pushOrOpen(ExamDetailRoute, EXAM_DETAIL_ROUTE_ID)
            first.pop()
            visible.value = false
            settle()
            assertSame(retained, second)
            assertEquals(listOf(AppSection.HOME, ExamDetailRoute), second.backStack.toList())
            second.pop()
            assertEquals(listOf(AppSection.HOME), second.backStack.toList())
        } finally { scene.close() }
    }

    @Test fun androidOpensSecondaryActivityImmediatelyWhileIosPreservesItsDeferredDispatch() = runBlocking {
        val calls = mutableListOf<String>()
        val preferences = AppPreferences()
        val sections = bottomNavSections(preferences)
        val android = ShellNavigator(PlatformInfo(PlatformFamily.Android, "fixture"), this, null,
            { calls += "android:$it" }, {}, {}, true, false, WindowClass.Compact)
        val apple = ShellNavigator(ios, this, null, { calls += "ios:$it" }, {}, {}, true, false, WindowClass.Compact)
        android.navigateToSection(AppSection.MAILBOX, preferences, sections)
        apple.navigateToSection(AppSection.MAILBOX, preferences, sections)
        assertEquals(listOf("android:MAILBOX"), calls)
        repeat(3) { yield() }
        assertEquals(listOf("android:MAILBOX", "ios:MAILBOX"), calls)
        assertEquals(listOf(AppSection.HOME), android.backStack.toList())
        assertEquals(listOf(AppSection.HOME), apple.backStack.toList())
    }

    @Test fun wideLayoutKeepsMoreSubpageAndInlineMailboxBackRules() = runBlocking {
        val preferences = AppPreferences(bottomNavigationItems = emptyList(), autoSyncPhyVlab = false, aggregateAssignments = false)
        val calls = mutableListOf<String>()
        val navigator = ShellNavigator(ios, this, null, { calls += it }, {}, {}, true, false, WindowClass.Expanded)
        navigator.navigateToSection(AppSection.MAILBOX, preferences, bottomNavSections(preferences))
        repeat(3) { yield() }
        assertEquals(listOf(AppSection.MORE, AppSection.MAILBOX), navigator.backStack.toList())
        var mailboxBack = 0
        navigator.back({ mailboxBack++ }, mailboxInlineDetail = true, mailboxInlineCompose = false)
        assertEquals(1, mailboxBack)
        assertEquals(AppSection.MAILBOX, navigator.currentRoute)
        navigator.back({}, mailboxInlineDetail = false, mailboxInlineCompose = false)
        assertEquals(AppSection.MORE, navigator.currentRoute)
        navigator.navigateToSection(AppSection.PHYVLAB, preferences, bottomNavSections(preferences))
        repeat(3) { yield() }
        assertEquals(AppSection.MORE, navigator.currentRoute)
        assertTrue(calls.isEmpty())
        val aggregated = preferences.copy(aggregateAssignments = true)
        navigator.navigateToSection(AppSection.PHYVLAB, aggregated, bottomNavSections(aggregated))
        repeat(3) { yield() }
        assertEquals(AppSection.ASSIGNMENTS, navigator.currentRoute)
    }

    private val ios = PlatformInfo(PlatformFamily.IOS, "fixture")
}
