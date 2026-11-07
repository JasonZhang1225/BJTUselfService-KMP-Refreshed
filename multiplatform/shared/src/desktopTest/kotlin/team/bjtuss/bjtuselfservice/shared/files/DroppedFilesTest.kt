package team.bjtuss.bjtuselfservice.shared.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.feature.assignment.dropFeedback
import team.bjtuss.bjtuselfservice.shared.feature.assignment.mergePickedFiles

class DroppedFilesTest {
    private val directory: File = Files.createTempDirectory("dropped-files").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun readsDroppedFilesLikeThePicker() = runBlocking {
        val first = File(directory, "报告.txt").apply { writeText("一") }
        val second = File(directory, "b.bin").apply { writeBytes(byteArrayOf(1, 2)) }
        val result = assertIs<HomeworkFilePickResult.Selected>(readLocalFiles(listOf(first, second)))
        assertEquals(listOf("报告.txt", "b.bin"), result.files.map { it.fileName })
        assertTrue(result.files[1].bytes.contentEquals(byteArrayOf(1, 2)))
        assertTrue(result.files.all { it.contentType.isNotBlank() })
    }

    @Test
    fun folderIsRejectedWithoutReadingAnything() = runBlocking {
        val file = File(directory, "a.txt").apply { writeText("x") }
        val folder = File(directory, "作业").apply { mkdir() }
        val result = assertIs<HomeworkFilePickResult.Failed>(readLocalFiles(listOf(file, folder)))
        assertTrue("压缩" in result.dropFeedback())
    }

    private fun sized(name: String, length: Long) = File(directory, name).apply {
        java.io.RandomAccessFile(this, "rw").use { it.setLength(length) }
    }

    @Test
    fun oversizedSingleFileIsRefusedBeforeReading(): Unit = runBlocking {
        // 稀疏文件：若真的去读会占满 300 MB 内存，这里必须在读取前就拒绝。
        assertIs<HomeworkFilePickResult.TooLarge>(readLocalFiles(listOf(sized("big.bin", 300L * 1024 * 1024))))
    }

    @Test
    fun totalSizeIsLimitedAcrossFiles(): Unit = runBlocking {
        val files = (1..3).map { sized("part$it.bin", 80L * 1024 * 1024) }
        assertIs<HomeworkFilePickResult.TooLarge>(readLocalFiles(files))
    }

    @Test
    fun filesAtTheLimitAreAccepted(): Unit = runBlocking {
        val file = File(directory, "ok.txt").apply { writeText("12345") }
        assertIs<HomeworkFilePickResult.Selected>(readLocalFiles(listOf(file), maxBytes = 5))
        assertIs<HomeworkFilePickResult.TooLarge>(readLocalFiles(listOf(file), maxBytes = 4))
    }

    @Test
    fun droppedFileWithSameNameReplacesPendingOne() {
        val old = HomeworkFileContent("a.txt", "text/plain", byteArrayOf(1))
        val new = HomeworkFileContent("a.txt", "text/plain", byteArrayOf(2))
        assertEquals(listOf(new), mergePickedFiles(listOf(old), listOf(new)))
    }
}
