package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.Dispatchers
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercise the real AppleSheet effects with a host that completes native dismissal later. */
class NativeSheetOwnershipTest {
    @Test
    fun disposingCourseDetailsDoesNotDismissAnOverlappingWeekPicker() = onUiThread {
        val host = TestSheetHost()
        val courseVisible = mutableStateOf(true)
        val pickerVisible = mutableStateOf(true)
        withScene(host, content = {
            key("course") {
                if (courseVisible.value) AppleSheet(onDismissRequest = { courseVisible.value = false }) {}
            }
            key("picker") {
                if (pickerVisible.value) AppleSheet(onDismissRequest = { pickerVisible.value = false }) {}
            }
        }) { render ->
            render()
            assertEquals(2, host.presentations.size)
            val course = host.presentations[0]
            val picker = host.presentations[1]
            assertNotSame(course, picker)
            courseVisible.value = false
            render()
            assertEquals(1, course.dismissals.size)
            assertEquals(0, picker.dismissals.size)
            course.finishDismissal()
            assertTrue(picker.presented)
            assertNotNull(picker.content)
        }
    }

    @Test
    fun fastCloseAndReopenKeepsNewContentAfterOldNativeCompletion() = onUiThread {
        val host = TestSheetHost()
        val course = mutableStateOf<Int?>(1)
        withScene(host, content = {
            course.value?.let { id ->
                key(id) { AppleSheet(onDismissRequest = { course.value = null }, title = "course-$id") {} }
            }
        }) { render ->
            render()
            val first = host.presentations.single()
            // The close button changes Compose state before UIKit finishes its animation.
            first.requestClose()
            render()
            course.value = 2
            render()
            val second = host.presentations.last()
            assertNotSame(first, second)
            first.finishDismissal()
            assertEquals(2, course.value)
            assertEquals("course-2", second.title)
            assertTrue(second.presented)
            assertNotNull(second.content)
            second.requestClose()
            render()
            assertNull(course.value)
        }
    }

    @Test
    fun recompositionUpdatesCallbacksWithoutCreatingAnotherPresentation() = onUiThread {
        val host = TestSheetHost()
        val revision = mutableStateOf(1)
        var dismissedRevision = 0
        withScene(host, content = {
            val value = revision.value
            AppleSheet(onDismissRequest = { dismissedRevision = value }, title = "revision-$value") {}
        }) { render ->
            render()
            revision.value = 2
            render()
            val sheet = host.presentations.single()
            assertEquals(1, sheet.presentCalls)
            assertEquals("revision-2", sheet.title)
            sheet.requestClose()
            assertEquals(2, dismissedRevision)
        }
    }

    private fun onUiThread(block: () -> Unit) = SwingUtilities.invokeAndWait(block)

    private fun withScene(
        host: TestSheetHost,
        content: @Composable () -> Unit,
        assertions: (render: () -> Unit) -> Unit,
    ) {
        val scene = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalNativeSheetPresenter provides host) { content() }
            }
        }
        var frame = 0L
        try {
            assertions { repeat(4) { scene.render(++frame * 16_000_000L).close() } }
        } finally {
            scene.close()
        }
    }

    private class TestSheetHost : NativeSheetPresenter by TestPresentation() {
        val presentations = mutableListOf<TestPresentation>()
        override fun createPresentation(): NativeSheetPresenter = TestPresentation().also(presentations::add)
    }

    private class TestPresentation : NativeSheetPresenter {
        var content: (@Composable () -> Unit)? = null
        var title: String? = null
        var presented = false
        var presentCalls = 0
        private var onDismissRequest: () -> Unit = {}
        val dismissals = mutableListOf<() -> Unit>()

        override fun update(
            content: @Composable () -> Unit,
            onDismissRequest: () -> Unit,
            title: String?,
            confirmLabel: String?,
            confirmEnabled: Boolean,
            onConfirm: (() -> Unit)?,
            todayLabel: String?,
            todayEnabled: Boolean,
            onToday: (() -> Unit)?,
            dismissLabel: String?,
            dismissEnabled: Boolean,
            showDismissButton: Boolean,
        ) {
            this.content = content
            this.onDismissRequest = onDismissRequest
            this.title = title
        }

        override fun present(needsFullHeight: Boolean) {
            presentCalls++
            presented = true
        }

        override fun dismiss() {
            dismissals += { presented = false; content = null }
        }

        fun requestClose() = onDismissRequest()
        fun finishDismissal() = dismissals.removeAt(0).invoke()
    }
}
