package team.bjtuss.bjtuselfservice.shared.data.otherfunction

import kotlinx.coroutines.CancellationException
import team.bjtuss.bjtuselfservice.shared.files.FileContent
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.ReportCardLanguage

enum class OtherFunctionSyncFailure {
    NETWORK,
    PARSE,
    SESSION_EXPIRED,
}

sealed interface OtherFunctionDownloadResult {
    data class Success(val file: FileContent) : OtherFunctionDownloadResult
    data class Failure(val reason: OtherFunctionSyncFailure) : OtherFunctionDownloadResult
}

interface OtherFunctionRepository {
    suspend fun downloadReportCard(language: ReportCardLanguage): OtherFunctionDownloadResult
}

class DefaultOtherFunctionRepository(
    private val remote: OtherFunctionRemoteDataSource,
) : OtherFunctionRepository {

    override suspend fun downloadReportCard(
        language: ReportCardLanguage,
    ): OtherFunctionDownloadResult = try {
        OtherFunctionDownloadResult.Success(remote.fetchReportCardFile(language))
    } catch (error: CancellationException) {
        throw error
    } catch (error: OtherFunctionRemoteException) {
        OtherFunctionDownloadResult.Failure(error.reason.toSyncFailure())
    } catch (_: Exception) {
        OtherFunctionDownloadResult.Failure(OtherFunctionSyncFailure.NETWORK)
    }
}

private fun OtherFunctionRemoteFailure.toSyncFailure(): OtherFunctionSyncFailure = when (this) {
    OtherFunctionRemoteFailure.NETWORK -> OtherFunctionSyncFailure.NETWORK
    OtherFunctionRemoteFailure.PARSE -> OtherFunctionSyncFailure.PARSE
    OtherFunctionRemoteFailure.SESSION_EXPIRED -> OtherFunctionSyncFailure.SESSION_EXPIRED
}
