package team.bjtuss.bjtuselfservice.shared.feature.otherfunction

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import team.bjtuss.bjtuselfservice.shared.data.otherfunction.OtherFunctionDownloadResult
import team.bjtuss.bjtuselfservice.shared.data.otherfunction.OtherFunctionRepository
import team.bjtuss.bjtuselfservice.shared.data.otherfunction.OtherFunctionSyncFailure
import team.bjtuss.bjtuselfservice.shared.files.FileContent
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.OtherFunctionFailure
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.OtherFunctionTaskState
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.ReportCardLanguage
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.files.FilePickResult
import team.bjtuss.bjtuselfservice.shared.files.FileSaveResult

class OtherFunctionScreenModelTest {

    @Test
    fun sessionExpiredFailureMapsToSessionExpired() = runBlocking {
        val repository = FakeRepository(
            reportCard = OtherFunctionDownloadResult.Failure(OtherFunctionSyncFailure.SESSION_EXPIRED),
        )
        val gateway = FakeFileGateway(FileSaveResult.Saved)
        val model = OtherFunctionScreenModel(repository, gateway)

        model.downloadReportCard()

        assertEquals(
            OtherFunctionTaskState.Failed(OtherFunctionFailure.SESSION_EXPIRED),
            model.state.value.reportCardState,
        )
    }

    @Test
    fun switchingLanguageAffectsRepositoryLanguage() = runBlocking {
        val repository = FakeRepository(reportCard = success("英文成绩单.pdf"))
        val gateway = FakeFileGateway(FileSaveResult.Saved)
        val model = OtherFunctionScreenModel(repository, gateway)

        model.setReportCardLanguage(ReportCardLanguage.ENGLISH)
        model.downloadReportCard()

        assertEquals(ReportCardLanguage.ENGLISH, repository.lastReportCardLanguage)
        assertEquals(
            OtherFunctionTaskState.Saved("英文成绩单.pdf"),
            model.state.value.reportCardState,
        )
    }

    private fun success(fileName: String) = OtherFunctionDownloadResult.Success(
        FileContent(fileName, "application/pdf", "pdf".encodeToByteArray()),
    )

    private class FakeRepository(
        private val reportCard: OtherFunctionDownloadResult? = null,
    ) : OtherFunctionRepository {
        var lastReportCardLanguage: ReportCardLanguage? = null

        override suspend fun downloadReportCard(language: ReportCardLanguage): OtherFunctionDownloadResult {
            lastReportCardLanguage = language
            return reportCard ?: error("reportCard not stubbed")
        }

    }

    private class FakeFileGateway(
        private val saveResult: FileSaveResult,
    ) : FileGateway {
        override val isAvailable: Boolean = true

        override suspend fun pickFiles(): FilePickResult = FilePickResult.Cancelled

        override suspend fun saveFile(file: FileContent): FileSaveResult {
            return saveResult
        }
    }
}
