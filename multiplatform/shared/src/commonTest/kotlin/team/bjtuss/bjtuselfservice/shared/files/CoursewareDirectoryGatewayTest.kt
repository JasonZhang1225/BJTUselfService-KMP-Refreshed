package team.bjtuss.bjtuselfservice.shared.files

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

class CoursewareDirectoryGatewayTest {
    @Test
    fun directoryContractsRejectBlankSegmentsAndRedactNames() {
        assertFailsWith<IllegalArgumentException> {
            CoursewareDirectoryFile(
                relativeFolders = listOf("第一章", " "),
                content = HomeworkFileContent("secret.pdf", "application/pdf", byteArrayOf(1)),
            )
        }

        val file = CoursewareDirectoryFile(
            relativeFolders = listOf("第一章"),
            content = HomeworkFileContent("secret.pdf", "application/pdf", byteArrayOf(1)),
        )

        assertFalse("secret.pdf" in file.toString())
    }

    @Test
    fun exportNamesCannotTraverseParentDirectories() {
        assertEquals("成绩.pdf", safeExportFileName("../../成绩.pdf"))
        assertEquals("attachment.bin", safeExportFileName(".."))
        assertEquals("attachment.bin", safeExportFileName("/"))
        assertEquals("courseware", safeExportPathSegment(".."))
        assertEquals("第一_章", safeExportPathSegment("第一/章"))
        assertFalse('/' in safeExportPathSegment("../第一章"))
        assertFalse('\\' in safeExportPathSegment("..\\第一章"))
    }

    @Test
    fun previewContentTypePrefersDeclaredTypeAndMapsOfficeExtensions() {
        assertEquals(
            "application/pdf",
            previewContentType("通知.pdf", "application/octet-stream"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            previewContentType("申请表.docx", "application/octet-stream"),
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            previewContentType("名单.xlsx", null),
        )
        assertEquals(
            "application/pdf",
            previewContentType("a.bin", "application/pdf"),
        )
        assertEquals("application/octet-stream", previewContentType("unknown", null))
    }

    @Test
    fun exportNamesReplaceInvisibleControlsAndPreserveShortExtensionWhenTruncated() {
        val controlled = safeExportFileName("成绩\u0000\u202E.pdf")
        val longName = safeExportFileName("课".repeat(120) + ".pdf")
        val surrogateBoundary = safeExportFileName("a".repeat(75) + "😀.pdf")

        assertEquals("成绩__.pdf", controlled)
        assertTrue(longName.length <= 80)
        assertTrue(longName.endsWith(".pdf"))
        assertFalse(longName.any { it.code < 0x20 || it.code in 0x202a..0x202e })
        assertEquals("a".repeat(75) + ".pdf", surrogateBoundary)
    }

    @Test
    fun exportAllocatorKeepsSanitizedFolderAndFileCollisionsDistinct() {
        val allocator = CoursewareExportNameAllocator()
        fun resolve(folder: String, fileName: String): CoursewareDirectoryFile = allocator.resolve(
            CoursewareDirectoryFile(
                relativeFolders = listOf(folder),
                content = HomeworkFileContent(fileName, "application/pdf", byteArrayOf(1)),
            ),
        )

        val first = resolve("第一/章", "成绩.pdf")
        val duplicateFile = resolve("第一/章", "成绩.pdf")
        val collidingFolder = resolve("第一\\章", "成绩.pdf")

        assertEquals(listOf("第一_章"), first.relativeFolders)
        assertEquals("成绩.pdf", first.content.fileName)
        assertEquals(listOf("第一_章"), duplicateFile.relativeFolders)
        assertEquals("成绩 (2).pdf", duplicateFile.content.fileName)
        assertEquals(listOf("第一_章 (2)"), collidingFolder.relativeFolders)
        assertEquals("成绩.pdf", collidingFolder.content.fileName)

        val crossTypeAllocator = CoursewareExportNameAllocator()
        val rootFile = crossTypeAllocator.resolve(
            CoursewareDirectoryFile(
                relativeFolders = emptyList(),
                content = HomeworkFileContent("第一章", "application/octet-stream", byteArrayOf(1)),
            ),
        )
        val folderAfterFile = crossTypeAllocator.resolve(
            CoursewareDirectoryFile(
                relativeFolders = listOf("第一章"),
                content = HomeworkFileContent("说明.pdf", "application/pdf", byteArrayOf(1)),
            ),
        )
        assertEquals("第一章", rootFile.content.fileName)
        assertEquals(listOf("第一章 (2)"), folderAfterFile.relativeFolders)

        val longExtensionAllocator = CoursewareExportNameAllocator()
        val longExtensionName = "a." + "x".repeat(77)
        longExtensionAllocator.resolve(
            CoursewareDirectoryFile(
                relativeFolders = emptyList(),
                content = HomeworkFileContent(longExtensionName, "application/octet-stream", byteArrayOf(1)),
            ),
        )
        val renamedLongExtension = longExtensionAllocator.resolve(
            CoursewareDirectoryFile(
                relativeFolders = emptyList(),
                content = HomeworkFileContent(longExtensionName, "application/octet-stream", byteArrayOf(1)),
            ),
        )
        assertTrue(renamedLongExtension.content.fileName.length <= 80)
    }
}
