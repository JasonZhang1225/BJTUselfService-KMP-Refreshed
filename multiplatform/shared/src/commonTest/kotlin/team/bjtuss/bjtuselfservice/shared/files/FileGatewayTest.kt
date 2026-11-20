package team.bjtuss.bjtuselfservice.shared.files

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class FileGatewayTest {
    @Test
    fun selectedFilesDoNotExposeNamesOrBytesInStringForm() {
        val result = FilePickResult.Selected(
            listOf(
                FileContent(
                    fileName = "private-homework.pdf",
                    contentType = "application/pdf",
                    bytes = "private-file-body".encodeToByteArray(),
                ),
            ),
        )

        assertFalse("private-homework.pdf" in result.toString())
        assertFalse("private-file-body" in result.toString())
        assertFalse("private-homework.pdf" in result.files.single().toString())
    }

    @Test
    fun unavailableGatewayReturnsTypedFailureWithoutSideEffects() = runBlocking {
        val picked = UnavailableFileGateway.pickFiles()
        val saved = UnavailableFileGateway.saveFile(
            FileContent("file.bin", "application/octet-stream", byteArrayOf()),
        )

        assertEquals(
            FileGatewayFailure.UNAVAILABLE,
            assertIs<FilePickResult.Failed>(picked).reason,
        )
        assertEquals(
            FileGatewayFailure.UNAVAILABLE,
            assertIs<FileSaveResult.Failed>(saved).reason,
        )
    }
}
