package team.bjtuss.bjtuselfservice.shared.data.mailbox

import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailboxPage
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachmentContent
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailComposeDraft
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailMessage
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

private const val MIS_MAILBOX_ENTRY_URL = "https://mis.bjtu.edu.cn/module/module/26/"
private const val COREMAIL_ORIGIN = "https://mail.bjtu.edu.cn"
private const val COREMAIL_HOST = "mail.bjtu.edu.cn"
private const val COREMAIL_JSON_PATH = "/coremail/s/json"
private const val COREMAIL_READ_MESSAGE_URL = "$COREMAIL_ORIGIN/coremail/XT/jsp/readMessage.jsp"
private const val COREMAIL_COMPOSE_URL = "$COREMAIL_ORIGIN/coremail/XT/jsp/compose.jsp"
private const val COREMAIL_INDEX_URL = "$COREMAIL_ORIGIN/coremail/XT/index.jsp"
/** 附件下载：GET 后 302 到 `/coremail/mbox-data/<文件名>?…`，200 返回 Content-Disposition: attachment。 */
private const val COREMAIL_ATTACHMENT_PATH = "/coremail/mbox-data"

enum class MailboxRemoteFailure {
    NETWORK,
    PARSE,
    SESSION_EXPIRED,
}

class MailboxRemoteException(
    val reason: MailboxRemoteFailure,
) : Exception("Mailbox request failed: ${reason.name}")

interface MailboxRemoteDataSource {
    suspend fun listMessages(folderId: Int, start: Int, limit: Int, descending: Boolean): MailboxPage
    suspend fun readMessage(messageId: String): MailMessage
    suspend fun beginCompose(replyToMessageId: String? = null): MailComposeDraft
    suspend fun sendMessage(draft: MailComposeDraft)
    suspend fun cancelCompose(composeId: String)
    suspend fun downloadAttachment(messageId: String, attachmentId: String): MailAttachmentContent
}

/**
 * Coremail 的只读 HTTP 适配器。
 * 先通过 MIS 入口建立 Coremail 会话，再调用已由 Chrome DevTools 取证的列表/详情接口。
 */
class SchoolMailboxRemoteDataSource(
    private val transport: SchoolHttpTransport,
) : MailboxRemoteDataSource {
    private var coremailSessionId: String? = null

    override suspend fun listMessages(
        folderId: Int,
        start: Int,
        limit: Int,
        descending: Boolean,
    ): MailboxPage {
        val sid = ensureCoremailSession()
        val body = buildJsonObject {
            put("start", start)
            put("limit", limit)
            put("mode", "count")
            put("order", "date")
            put("desc", descending)
            put("returnTotal", true)
            put("returnTag", false)
            put("summaryWindowSize", limit)
            put("fid", folderId)
            put("mboxa", "")
            put("topFirst", true)
        }.toString()
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = buildJsonEndpoint(sid, "mbox:listMessages"),
                headers = coremailJsonHeaders,
                rawBody = body.encodeToByteArray(),
                rawBodyContentType = "text/x-json; tz=\"Asia/Shanghai\"",
            ),
        )
        return when (val parsed = parseMailboxMessageList(response.bodyText())) {
            is MailboxJsonParseResult.Success -> parsed.value
            is MailboxJsonParseResult.Failure -> {
                if (parsed.field == "code") sessionExpired()
                parse(parsed.field)
            }
        }
    }

    override suspend fun readMessage(messageId: String): MailMessage {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        // 详情页也可被独立调用；不要假设列表请求一定已经成功建立了 sid。
        ensureCoremailSession()
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = COREMAIL_READ_MESSAGE_URL,
                headers = coremailFormHeaders,
                formFields = mapOf(
                    "mid" to messageId,
                    "mboxa" to "",
                    "part" to "",
                    "mailCipherPassword" to "",
                ),
            ),
        )
        return when (val parsed = parseMailboxMessage(response.bodyText(), messageId)) {
            is MailboxJsonParseResult.Success -> parsed.value
            is MailboxJsonParseResult.Failure -> {
                if (parsed.field == "code") sessionExpired()
                parse(parsed.field)
            }
        }
    }

    override suspend fun beginCompose(replyToMessageId: String?): MailComposeDraft {
        val sid = ensureCoremailSession()
        val fields = buildMap {
            put("ctype", if (replyToMessageId == null) "normal" else "reply")
            replyToMessageId?.let { put("mid", it) }
            put("mboxa", "")
        }
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = "$COREMAIL_COMPOSE_URL?sid=${sid.encodeURLParameter()}",
                headers = coremailFormHeaders,
                formFields = fields,
            ),
        )
        return when (val parsed = parseMailboxComposeDraft(response.bodyText(), replyToMessageId)) {
            is MailboxJsonParseResult.Success -> parsed.value
            is MailboxJsonParseResult.Failure -> {
                if (parsed.field == "code") sessionExpired()
                parse(parsed.field)
            }
        }
    }

    override suspend fun sendMessage(draft: MailComposeDraft) {
        require(draft.id.isNotBlank()) { "compose id must not be blank" }
        val sid = ensureCoremailSession()
        val attrs = buildJsonObject {
            put("to", draft.to.mailRecipientsJson())
            put("cc", draft.cc.mailRecipientsJson())
            put("bcc", draft.bcc.mailRecipientsJson())
            put("subject", draft.subject.trim())
            put("isHtml", true)
            put("content", draft.bodyText.toComposeHtml())
            put("attachments", buildJsonArray { })
            put("priority", 1)
            put("requestReadReceipt", false)
            put("saveSentCopy", true)
        }
        val body = buildJsonObject {
            put("id", draft.id)
            put("attrs", attrs)
            put("returnInfo", true)
            put("action", "deliver")
            if (draft.isReply) {
                put("ctype", "reply")
                put("mid", draft.replyToMessageId.orEmpty())
                put("mboxa", "")
            }
        }.toString()
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = buildJsonEndpoint(sid, "mbox:compose"),
                headers = coremailJsonHeaders,
                rawBody = body.encodeToByteArray(),
                rawBodyContentType = "text/x-json; tz=\"Asia/Shanghai\"",
            ),
        )
        when (val parsed = parseMailboxCommandSuccess(response.bodyText())) {
            is MailboxJsonParseResult.Success -> Unit
            is MailboxJsonParseResult.Failure -> {
                if (parsed.field == "code") sessionExpired()
                parse(parsed.field)
            }
        }
    }

    override suspend fun cancelCompose(composeId: String) {
        if (composeId.isBlank()) return
        val sid = ensureCoremailSession()
        val body = buildJsonObject { put("ids", composeId) }.toString()
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.POST,
                url = buildJsonEndpoint(sid, "mbox:cancelComposes"),
                headers = coremailJsonHeaders,
                rawBody = body.encodeToByteArray(),
                rawBodyContentType = "text/x-json; tz=\"Asia/Shanghai\"",
            ),
        )
        when (val parsed = parseMailboxCommandSuccess(response.bodyText())) {
            is MailboxJsonParseResult.Success -> Unit
            is MailboxJsonParseResult.Failure -> {
                if (parsed.field == "code") sessionExpired()
                parse(parsed.field)
            }
        }
    }

    override suspend fun downloadAttachment(messageId: String, attachmentId: String): MailAttachmentContent {
        require(messageId.isNotBlank()) { "messageId must not be blank" }
        require(attachmentId.isNotBlank()) { "attachmentId must not be blank" }
        val sid = ensureCoremailSession()
        val response = execute(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = "$COREMAIL_ORIGIN$COREMAIL_ATTACHMENT_PATH?mode=download" +
                    "&sid=${sid.encodeURLParameter()}" +
                    "&mid=${messageId.encodeURLParameter()}" +
                    "&part=${attachmentId.encodeURLParameter()}",
                headers = mapOf("Referer" to COREMAIL_INDEX_URL),
            ),
        )
        // sid 失效时 Coremail 会落到登录/错误页而不是 mbox-data 下的文件地址。
        val path = runCatching { Url(response.finalUrl).encodedPath }.getOrNull().orEmpty()
        if (!path.startsWith(COREMAIL_ATTACHMENT_PATH)) sessionExpired()
        val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.ifBlank { null }
        val disposition = response.header("Content-Disposition").orEmpty()
        if (contentType == "text/html" && !disposition.contains("attachment", ignoreCase = true)) sessionExpired()
        return MailAttachmentContent(bytes = response.body, contentType = contentType)
    }

    private suspend fun ensureCoremailSession(): String {
        coremailSessionId?.let { return it }
        val response = executeRaw(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = MIS_MAILBOX_ENTRY_URL,
                headers = mapOf("Accept" to "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"),
            ),
        )
        if (response.statusCode !in 200..299) network()
        val finalUrl = runCatching { Url(response.finalUrl) }.getOrNull()
        if (finalUrl?.host?.lowercase() != COREMAIL_HOST) sessionExpired()
        val sid = finalUrl.parameters["sid"]?.takeIf(String::isNotBlank) ?: sessionExpired()
        coremailSessionId = sid
        return sid
    }

    private suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
        val response = executeRaw(request)
        if (response.statusCode == 401 || response.statusCode == 403) sessionExpired()
        if (response.statusCode !in 200..299) network()
        val host = runCatching { Url(response.finalUrl).host.lowercase() }.getOrNull()
        if (host != COREMAIL_HOST) sessionExpired()
        return response
    }

    private suspend fun executeRaw(request: SchoolHttpRequest): SchoolHttpResponse = try {
        transport.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        network()
    }

    private fun buildJsonEndpoint(sessionId: String, function: String): String =
        "$COREMAIL_ORIGIN$COREMAIL_JSON_PATH?sid=${sessionId.encodeURLParameter()}&func=${function.encodeURLParameter()}"

    private fun network(): Nothing = throw MailboxRemoteException(MailboxRemoteFailure.NETWORK)
    private fun parse(field: String): Nothing = throw MailboxRemoteException(
        MailboxRemoteFailure.PARSE,
    )
    private fun sessionExpired(): Nothing {
        coremailSessionId = null
        throw MailboxRemoteException(MailboxRemoteFailure.SESSION_EXPIRED)
    }

    private fun String.mailRecipientsJson(): JsonArray = buildJsonArray {
        split(Regex("[,;，；\\n]"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { add(JsonPrimitive(it)) }
    }

    private fun String.toComposeHtml(): String = replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace("\n", "<br>")

    private companion object {
        val coremailJsonHeaders = mapOf(
            "Accept" to "text/x-json",
            "Content-Type" to "text/x-json; tz=\"Asia/Shanghai\"",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to COREMAIL_INDEX_URL,
        )
        val coremailFormHeaders = mapOf(
            "Accept" to "text/x-json",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to COREMAIL_INDEX_URL,
        )
    }
}
