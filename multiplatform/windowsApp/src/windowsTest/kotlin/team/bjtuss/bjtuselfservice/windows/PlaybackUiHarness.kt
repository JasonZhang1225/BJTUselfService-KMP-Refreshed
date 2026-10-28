package team.bjtuss.bjtuselfservice.windows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackEnqueueResult
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackTasksPage
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult

/** 人工 UI 验收入口，只使用传入的隔离目录和公开媒体；不加载真实账号或应用缓存。 */
fun main(args: Array<String>) {
    require(args.size == 2) { "需要测试目录和媒体地址 JSON" }
    val root = File(args[0]).canonicalFile
    require(root.name in setOf("ui-harness", "ui-harness-progressive", "ui-harness-progressive-timed",
        "ui-harness-device", "ui-harness-device-cpu") &&
        root.parentFile.name == "windows-gpu-playback")
    check(root.isDirectory || root.mkdirs())
    val streams = Json.parseToJsonElement(File(args[1]).readText()).jsonArray.map { item ->
        val data = item.jsonObject
        PlaybackStream(PlaybackStreamKind.valueOf(data.getValue("kind").jsonPrimitive.content),
            data.getValue("url").jsonPrimitive.content)
    }
    val detail = PlaybackDetail(PlaybackLesson(1, 1, "测试课节", "测试时间", "", "", true),
        true, streams, streams.map { it.kind }, "")
    val recovery = WindowsPlaybackRecovery(File(root, "state"))
    val manager = recovery.retainedManager("ui-test-account")
    val gateway = object : HomeworkFileGateway by WindowsHomeworkFileGateway(recoveryRoot = File(root, "state")) {
        override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult {
            val target = File(root, "visual-result.mp4")
            val temporary = Files.createTempFile(root.toPath(), ".bjtu-save-", ".tmp")
            return StreamingDestinationResult.Opened(FileStreamingDestination(target, temporary))
        }
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "课程回放 Windows 隔离验收") {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val tasks by manager.tasks.collectAsState()
                var generation by remember { mutableIntStateOf(0) }
                Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch {
                            val result = manager.enqueue(detail, streams, "测试课程", true, gateway)
                            if (result is PlaybackEnqueueResult.Failed) System.err.println(result.message)
                        } }, enabled = tasks.isEmpty()) { Text("开始测试下载") }
                        Button(onClick = { generation++ }) { Text("模拟页面刷新 $generation") }
                    }
                    PlaybackTasksPage(tasks, manager, Modifier.weight(1f))
                }
            }
        }
    }
}
