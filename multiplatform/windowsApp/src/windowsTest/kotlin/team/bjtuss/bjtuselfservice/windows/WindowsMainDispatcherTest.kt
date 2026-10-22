package team.bjtuss.bjtuselfservice.windows

import java.awt.EventQueue
import kotlinx.coroutines.*
import kotlin.test.*

class WindowsMainDispatcherTest {
    @Test fun mainDispatcherRunsOnTheWindowsUiEventThread() = runBlocking {
        withTimeout(5_000) {
            withContext(Dispatchers.Main.immediate) {
                assertTrue(EventQueue.isDispatchThread(), "App 刷新协调器需要真实的 UI 主线程调度器")
            }
        }
    }
}
