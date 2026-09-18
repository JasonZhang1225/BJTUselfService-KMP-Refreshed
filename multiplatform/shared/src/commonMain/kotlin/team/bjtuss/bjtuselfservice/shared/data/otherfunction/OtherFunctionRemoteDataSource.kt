package team.bjtuss.bjtuselfservice.shared.data.otherfunction

import team.bjtuss.bjtuselfservice.shared.network.SchoolEndpoints

import kotlinx.coroutines.CancellationException
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.ReportCardLanguage
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport


enum class OtherFunctionRemoteFailure {
    NETWORK,
    PARSE,
    SESSION_EXPIRED,
}

class OtherFunctionRemoteException(
    val reason: OtherFunctionRemoteFailure,
) : Exception("Other function request failed: ${reason.name}")

/** 成绩单下载 URL 的固定构造；仅按语言切换 type 参数。 */
fun reportCardDownloadUrl(language: ReportCardLanguage): String {
    val type = when (language) {
        ReportCardLanguage.CHINESE -> "card_cn_sign"
        ReportCardLanguage.ENGLISH -> "card_en_sign"
    }
    return "${SchoolEndpoints.AA_ROOT}/score/scorecard/stu/5201314/download_pdf/?type=$type&has_advance_query="
}

interface OtherFunctionRemoteDataSource {
    suspend fun fetchReportCardFile(language: ReportCardLanguage): HomeworkFileContent
}

/**
 * 通过共享 transport 访问教务处公开页面与会话接口。
 *
 * 域名安全边界：成绩单必须位于 aa.bjtu.edu.cn，若被重定向回登录页则判为会话失效。
 */
class SchoolOtherFunctionRemoteDataSource(
    private val transport: SchoolHttpTransport,
) : OtherFunctionRemoteDataSource {

    override suspend fun fetchReportCardFile(language: ReportCardLanguage): HomeworkFileContent {
        val url = reportCardDownloadUrl(language)
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = url,
                headers = mapOf("Accept" to "application/pdf;q=0.9,*/*;q=0.5"),
            ),
        )
        if (response.statusCode !in 200..299) network()
        if (!response.finalUrl.isAllowedAaUrl()) sessionExpired()
        // 会话失效时服务器通常返回 HTML 登录页而不是 PDF。
        val contentType = response.contentTypeOrDefault()
        if (response.body.isEmpty() ||
            (!contentType.equals("application/pdf", ignoreCase = true) && !response.body.isPdfBytes())
        ) {
            sessionExpired()
        }
        val fileName = when (language) {
            ReportCardLanguage.CHINESE -> "中文成绩单.pdf"
            ReportCardLanguage.ENGLISH -> "英文成绩单.pdf"
        }
        return HomeworkFileContent(
            fileName = fileName,
            contentType = "application/pdf",
            bytes = response.body,
        )
    }

    private suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = try {
        transport.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        network()
    }
}

private fun String.isAllowedAaUrl(): Boolean {
    if (!startsWith("https://", ignoreCase = true)) return false
    val host = substringAfter("https://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').lowercase()
    return host == "aa.bjtu.edu.cn"
}

private fun ByteArray.isPdfBytes(): Boolean = size >= 5 &&
    this[0] == '%'.code.toByte() &&
    this[1] == 'P'.code.toByte() &&
    this[2] == 'D'.code.toByte() &&
    this[3] == 'F'.code.toByte() &&
    this[4] == '-'.code.toByte()

private fun SchoolHttpResponse.contentTypeOrDefault(): String = headers.entries
    .firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
    ?.value?.firstOrNull()?.substringBefore(';')?.trim()
    ?.takeIf(String::isNotBlank)
    ?: "application/octet-stream"

private fun network(): Nothing = throw OtherFunctionRemoteException(OtherFunctionRemoteFailure.NETWORK)
private fun sessionExpired(): Nothing = throw OtherFunctionRemoteException(OtherFunctionRemoteFailure.SESSION_EXPIRED)
