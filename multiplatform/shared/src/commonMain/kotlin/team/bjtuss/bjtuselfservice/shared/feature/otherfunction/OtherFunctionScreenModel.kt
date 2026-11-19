package team.bjtuss.bjtuselfservice.shared.feature.otherfunction

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.bjtuss.bjtuselfservice.shared.data.otherfunction.OtherFunctionDownloadResult
import team.bjtuss.bjtuselfservice.shared.data.otherfunction.OtherFunctionRepository
import team.bjtuss.bjtuselfservice.shared.data.otherfunction.OtherFunctionSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.OtherFunctionFailure
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.OtherFunctionTask
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.OtherFunctionTaskState
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.ReportCardLanguage
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.files.FileSaveResult

data class OtherFunctionUiState(
    val reportCardLanguage: ReportCardLanguage = ReportCardLanguage.CHINESE,
    val reportCardState: OtherFunctionTaskState = OtherFunctionTaskState.Idle,
) {
    val isAnyTaskRunning: Boolean
        get() = reportCardState == OtherFunctionTaskState.Downloading
}

class OtherFunctionScreenModel(
    private val repository: OtherFunctionRepository,
    private val fileGateway: FileGateway,
) {
    private val mutableState = MutableStateFlow(OtherFunctionUiState())
    val state: StateFlow<OtherFunctionUiState> = mutableState.asStateFlow()

    private val taskMutex = Mutex()

    fun setReportCardLanguage(language: ReportCardLanguage) {
        mutableState.value = mutableState.value.copy(reportCardLanguage = language)
    }

    suspend fun downloadReportCard() {
        if (!taskMutex.tryLock()) return
        try {
            val language = mutableState.value.reportCardLanguage
            mutableState.value = mutableState.value.copy(
                reportCardState = OtherFunctionTaskState.Downloading,
            )
            when (val result = repository.downloadReportCard(language)) {
                is OtherFunctionDownloadResult.Failure ->
                    mutableState.value = mutableState.value.copy(
                        reportCardState = OtherFunctionTaskState.Failed(result.reason.toUiFailure()),
                    )
                is OtherFunctionDownloadResult.Success -> {
                    val next = when (val save = fileGateway.saveFile(result.file)) {
                        FileSaveResult.Saved -> OtherFunctionTaskState.Saved(result.file.fileName)
                        FileSaveResult.Cancelled -> OtherFunctionTaskState.SaveCancelled
                        is FileSaveResult.Failed ->
                            OtherFunctionTaskState.Failed(
                                if (save.reason == team.bjtuss.bjtuselfservice.shared.files.FileGatewayFailure.UNAVAILABLE) {
                                    OtherFunctionFailure.SAVE_UNAVAILABLE
                                } else {
                                    OtherFunctionFailure.SAVE_FAILED
                                },
                            )
                    }
                    mutableState.value = mutableState.value.copy(reportCardState = next)
                }
            }
        } finally {
            taskMutex.unlock()
        }
    }

    fun clearTaskState(task: OtherFunctionTask) {
        mutableState.value = when (task) {
            OtherFunctionTask.REPORT_CARD ->
                mutableState.value.copy(reportCardState = OtherFunctionTaskState.Idle)
        }
    }
}

private fun OtherFunctionSyncFailure.toUiFailure(): OtherFunctionFailure = when (this) {
    OtherFunctionSyncFailure.NETWORK -> OtherFunctionFailure.NETWORK
    OtherFunctionSyncFailure.PARSE -> OtherFunctionFailure.PARSE
    OtherFunctionSyncFailure.SESSION_EXPIRED -> OtherFunctionFailure.SESSION_EXPIRED
}
