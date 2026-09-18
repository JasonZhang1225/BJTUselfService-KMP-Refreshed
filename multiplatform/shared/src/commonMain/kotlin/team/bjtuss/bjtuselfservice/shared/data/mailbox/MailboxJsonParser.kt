package team.bjtuss.bjtuselfservice.shared.data.mailbox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachment
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailComposeDraft
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailMessage
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailSummary
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailboxPage
import team.bjtuss.bjtuselfservice.shared.util.schoolRichTextToPlainMultiline

private val coremailJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

sealed interface MailboxJsonParseResult<out T> {
    data class Success<T>(val value: T) : MailboxJsonParseResult<T>
    data class Failure(val field: String) : MailboxJsonParseResult<Nothing>
}

/**
 * 解析 Coremail `mbox:listMessages` 的只读响应。
 * 只提取列表摘要，不把邮件内容放进异常消息。
 */
fun parseMailboxMessageList(body: String): MailboxJsonParseResult<MailboxPage> = try {
    val root = coremailJson.parseToJsonElement(body) as? JsonObject
        ?: return MailboxJsonParseResult.Failure("root")
    if (root.string("code") != "S_OK") return MailboxJsonParseResult.Failure("code")
    val items = root["var"] as? JsonArray
        ?: return MailboxJsonParseResult.Failure("var")
    val messages = buildList {
        items.forEachIndexed { index, item ->
            val summary = parseSummary(item as? JsonObject)
                ?: throw MailboxJsonParseException("var[$index]")
            add(summary)
        }
    }
    MailboxJsonParseResult.Success(
        MailboxPage(
            totalCount = root.int("total") ?: root.int("count") ?: messages.size,
            messages = messages,
        ),
    )
} catch (_: MailboxJsonParseException) {
    MailboxJsonParseResult.Failure("var")
} catch (_: Exception) {
    MailboxJsonParseResult.Failure("json")
}

/** 解析 Coremail `readMessage.jsp` 的只读响应。 */
fun parseMailboxMessage(
    body: String,
    fallbackMessageId: String,
): MailboxJsonParseResult<MailMessage> = try {
    val root = coremailJson.parseToJsonElement(body) as? JsonObject
        ?: return MailboxJsonParseResult.Failure("root")
    if (root.string("code") != "S_OK") return MailboxJsonParseResult.Failure("code")
    val payload = root["var"] as? JsonObject
        ?: return MailboxJsonParseResult.Failure("var")
    val mail = payload["mail"] as? JsonObject
        ?: return MailboxJsonParseResult.Failure("mail")
    val info = payload["mailInfo"] as? JsonObject
        ?: return MailboxJsonParseResult.Failure("mailInfo")
    val messageId = info.string("id")?.takeIf(String::isNotBlank) ?: fallbackMessageId
    val from = mail.strings("from")
    val to = mail.strings("to")
    val cc = mail.strings("cc")
    val bcc = mail.strings("bcc")
    val bodyHtml = (mail["mainPartData"] as? JsonObject)?.string("content").orEmpty()
    val attachments = parseAttachments(mail["attachments"] as? JsonArray)
    MailboxJsonParseResult.Success(
        MailMessage(
            id = messageId,
            folderId = info.int("fid") ?: 0,
            from = from,
            to = to,
            cc = cc,
            bcc = bcc,
            subject = mail.string("subject").orEmpty(),
            bodyHtml = bodyHtml,
            sentAt = info.string("sentDate").orEmpty(),
            attachments = attachments,
        ),
    )
} catch (_: Exception) {
    MailboxJsonParseResult.Failure("json")
}

/** 解析 Coremail compose.jsp 返回的写信/回复草稿。 */
fun parseMailboxComposeDraft(
    body: String,
    replyToMessageId: String?,
): MailboxJsonParseResult<MailComposeDraft> = try {
    val root = coremailJson.parseToJsonElement(body) as? JsonObject
        ?: return MailboxJsonParseResult.Failure("root")
    if (root.string("code") != "S_OK") return MailboxJsonParseResult.Failure("code")
    val payload = root["var"] as? JsonObject
        ?: return MailboxJsonParseResult.Failure("var")
    val id = payload.string("id")?.takeIf(String::isNotBlank)
        ?: return MailboxJsonParseResult.Failure("id")
    MailboxJsonParseResult.Success(
        MailComposeDraft(
            id = id,
            to = payload.strings("to").joinToString(", "),
            cc = payload.strings("cc").joinToString(", "),
            bcc = payload.strings("bcc").joinToString(", "),
            subject = payload.string("subject").orEmpty(),
            bodyText = schoolRichTextToPlainMultiline(payload.string("content")).trim(),
            replyToMessageId = replyToMessageId,
            isReply = replyToMessageId != null,
        ),
    )
} catch (_: Exception) {
    MailboxJsonParseResult.Failure("json")
}

/** 解析仅需确认成功/失败的 Coremail 写操作响应。 */
fun parseMailboxCommandSuccess(body: String): MailboxJsonParseResult<Unit> = try {
    val root = coremailJson.parseToJsonElement(body) as? JsonObject
        ?: return MailboxJsonParseResult.Failure("root")
    if (root.string("code") != "S_OK") {
        MailboxJsonParseResult.Failure("code")
    } else {
        MailboxJsonParseResult.Success(Unit)
    }
} catch (_: Exception) {
    MailboxJsonParseResult.Failure("json")
}

private fun parseSummary(item: JsonObject?): MailSummary? {
    if (item == null) return null
    val id = item.string("id")?.takeIf(String::isNotBlank) ?: return null
    val flags = item["flags"] as? JsonObject
    // Coremail 只对「已读」返回 read=true；未读邮件整个 read 键缺失。
    // 因此必须显式等于 true 才算已读，缺失/null 一律视为未读。
    val isRead = flags?.boolean("read") == true
    val hasAttachments = flags?.boolean("attached") == true ||
        flags?.boolean("inlineAttached") == true
    val folderId = item.int("fid") ?: 0
    val from = item.strings("from")
    val to = item.strings("to")
    // 发件箱列表有些 Coremail 版本只返回 to，不返回 from；列表仍应显示往来对象。
    val party = from.ifEmpty { to }
    return MailSummary(
        id = id,
        folderId = folderId,
        sender = party.joinToString(", "),
        subject = item.string("subject").orEmpty(),
        preview = item.string("summary").orEmpty(),
        sentAt = item.string("sentDate").orEmpty(),
        receivedAt = item.string("receivedDate").orEmpty(),
        sizeBytes = item.int("size") ?: 0,
        isRead = isRead,
        hasAttachments = hasAttachments,
        recipients = to,
    )
}

private fun parseAttachments(items: JsonArray?): List<MailAttachment> = items?.mapNotNull { item ->
    val objectValue = item as? JsonObject ?: return@mapNotNull null
    val name = objectValue.string("name")
        ?: objectValue.string("fileName")
        ?: return@mapNotNull null
    MailAttachment(
        id = objectValue.string("id") ?: objectValue.string("attachmentId"),
        name = name,
        sizeBytes = objectValue.int("size") ?: objectValue.int("sizeBytes"),
        contentType = objectValue.string("contentType") ?: objectValue.string("type"),
    )
}.orEmpty()

private fun JsonObject.string(name: String): String? = this[name]?.asString()

private fun JsonObject.int(name: String): Int? = this[name]?.asInt()

private fun JsonObject.boolean(name: String): Boolean? = this[name]?.asBoolean()

private fun JsonObject.strings(name: String): List<String> {
    val value = this[name]
    return when (value) {
        is JsonArray -> value.mapNotNull(JsonElement::asString)
        null -> emptyList()
        else -> value.asString()?.let(::listOf).orEmpty()
    }
}

private fun JsonElement.asString(): String? = when (this) {
    is JsonPrimitive -> jsonPrimitive.contentOrNull
    else -> null
}

private fun JsonElement.asInt(): Int? = when (this) {
    is JsonPrimitive -> jsonPrimitive.intOrNull ?: jsonPrimitive.content.toIntOrNull()
    else -> null
}

private fun JsonElement.asBoolean(): Boolean? = when (this) {
    is JsonPrimitive -> jsonPrimitive.booleanOrNull ?: jsonPrimitive.content.toBooleanStrictOrNull()
    else -> null
}

private class MailboxJsonParseException(message: String) : Exception(message)
