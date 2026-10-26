package team.bjtuss.bjtuselfservice.shared.feature.courseware

import java.io.File
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import team.bjtuss.bjtuselfservice.shared.data.courseware.HlsSegmentCheckpoint
import team.bjtuss.bjtuselfservice.shared.media.MediaWorkDirectory

class HlsSegmentCheckpointTest {
    @Test fun changedPlaylistInvalidatesDiskCheckpointWithoutPersistingTokens() = runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("hls-checkpoint-test").toFile()
        val work = MediaWorkDirectory(Path(directory.absolutePath))
        try {
            val urls = listOf("https://lmt2.bjtu.edu.cn:8880/one.ts?token=test-secret")
            HlsSegmentCheckpoint(work).apply { prepare(urls); append(0, byteArrayOf(1, 2, 3)) }
            val restored = HlsSegmentCheckpoint(work)
            restored.prepare(urls)
            assertEquals(1, restored.completed)
            assertContentEquals(byteArrayOf(1, 2, 3), restored.read(0))
            assertFalse(File(directory, "playlist.id").readText().contains("test-secret"))
            restored.prepare(listOf("https://lmt2.bjtu.edu.cn:8880/another.ts"))
            assertEquals(0, restored.completed)
            assertFalse(File(directory, "0.ts").exists(), "不能将旧录像分片用于新列表")
        } finally { directory.deleteRecursively() }
    }
}
