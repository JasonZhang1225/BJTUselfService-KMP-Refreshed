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
        val result = assertIs<HomeworkFilePickResult.Selected>(readDroppedFiles(listOf(first, second)))
        assertEquals(listOf("报告.txt", "b.bin"), result.files.map { it.fileName })
        assertTrue(result.files[1].bytes.contentEquals(byteArrayOf(1, 2)))
        assertTrue(result.files.all { it.contentType.isNotBlank() })
    }

    @Test
    fun folderIsRejectedWithoutReadingAnything() = runBlocking {
        val file = File(directory, "a.txt").apply { writeText("x") }
        val folder = File(directory, "作业").apply { mkdir() }
        val result = assertIs<HomeworkFilePickResult.Failed>(readDroppedFiles(listOf(file, folder)))
        assertTrue("压缩" in result.dropFeedback())
    }

    @Test
    fun droppedFileWithSameNameReplacesPendingOne() {
        val old = HomeworkFileContent("a.txt", "text/plain", byteArrayOf(1))
        val new = HomeworkFileContent("a.txt", "text/plain", byteArrayOf(2))
        assertEquals(listOf(new), mergePickedFiles(listOf(old), listOf(new)))
    }
}
