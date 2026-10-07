package team.bjtuss.bjtuselfservice.shared.data.moodle

import com.fleeksoft.ksoup.Ksoup
import io.ktor.http.Url
import kotlinx.serialization.json.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.network.*

data class AssignmentFile(val name: String, val path: String = "/")
data class MoodleAssignmentStatus(
    val status: String,
    val files: List<AssignmentFile>,
    val editable: Boolean,
    val canFinalize: Boolean,
    val submitted: Boolean,
)
class MoodleAssignmentFailure(val detail: String, val uncertain: Boolean = false) : Exception(detail)

fun parseMoodleAssignmentStatus(html: String): MoodleAssignmentStatus {
    val doc = Ksoup.parse(html)
    val main = doc.selectFirst("#region-main") ?: throw MoodleAssignmentFailure("作业页面格式发生变化。")
    val status = main.select("tr").firstOrNull { row ->
        row.selectFirst("th")?.text()?.trim()?.lowercase() in listOf("submission status", "提交状态", "作业状态")
    }?.selectFirst("td")?.text().orEmpty()
    val actions = main.select("input[name=action]").map { it.attr("value") } +
        main.select("a[href]").map { it.attr("href").substringAfter("action=", "").substringBefore('&') }
    val draft = status.contains("draft", true) || status.contains("草稿")
    val submitted = !draft && (status.contains("submitted for grading", true) || status.contains("已提交"))
    val files = main.select(".submissionstatustable a[href*='/pluginfile.php']").mapNotNull { a ->
        a.text().trim().takeIf { it.isNotEmpty() }?.let { AssignmentFile(it) }
    }.distinctBy { it.name }
    return MoodleAssignmentStatus(status, files, "editsubmission" in actions,
        "submit" in actions, submitted)
}

/** 仅在一次操作内使用，禁止持久化、日志输出和跨作业复用。 */
internal class MoodleEditContext(
    val url: String, val fields: Map<String, String>, val key: String,
    val item: String, val context: String, val client: String, val repository: String,
    val manager: String, val files: List<AssignmentFile>, val maxFiles: Int, val maxBytes: Long,
    val acceptedTypes: List<String>,
) {
    override fun toString() = "MoodleEditContext(<redacted>)"
}

internal fun extractMoodleJson(text: String, start: Int): JsonObject? {
    val begin = text.indexOf('{', start)
    if (begin < 0) return null
    var depth = 0; var quoted = false; var escaped = false
    for (i in begin until text.length) {
        val ch = text[i]
        if (quoted) {
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
        } else when (ch) {
            '"' -> quoted = true
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return runCatching { Json.parseToJsonElement(text.substring(begin, i + 1)).jsonObject }.getOrNull() }
        }
    }
    return null
}

internal fun parseMoodleEditContext(html: String, base: String, activityId: Int): MoodleEditContext {
    val doc = Ksoup.parse(html, "$base/mod/assign/view.php?id=$activityId")
    val form = doc.select("form").firstOrNull { it.selectFirst("input[name*='filemanager']") != null }
        ?: throw MoodleAssignmentFailure("此作业已锁定或暂不允许上传。")
    val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
    if (fields["id"] != activityId.toString() || fields["action"] != "savesubmission") throw MoodleAssignmentFailure("作业提交上下文不匹配。")
    val manager = form.selectFirst("input[name*='filemanager']") ?: throw MoodleAssignmentFailure("文件区信息缺失。")
    val script = doc.select("script").map { it.html() }.firstOrNull { it.contains("M.form_filemanager.init") }
        ?: throw MoodleAssignmentFailure("文件区配置缺失。")
    val config = extractMoodleJson(script, script.indexOf("M.form_filemanager.init")) ?: throw MoodleAssignmentFailure("文件区配置无法识别。")
    fun scalar(name: String) = config[name]?.jsonPrimitive?.contentOrNull
    val repositoryConfigs = doc.select("script").map { it.html() }.joinToString("\n")
    val repo = Regex("""["'](\d+)["']\s*:\s*\{[^{}]{0,1200}?["']type["']\s*:\s*["']upload["']""").find(repositoryConfigs)?.groupValues?.get(1)
        ?: Regex("""["']id["']\s*:\s*["']?(\d+)["']?[^{}]{0,1200}?["']type["']\s*:\s*["']upload["']""").find(repositoryConfigs)?.groupValues?.get(1)
        ?: throw MoodleAssignmentFailure("没有找到上传文件仓库。")
    val files = config["list"]?.jsonArray.orEmpty().map { raw ->
        val file = raw.jsonObject
        AssignmentFile(file["fullname"]?.jsonPrimitive?.content ?: file.getValue("filename").jsonPrimitive.content,
            file["filepath"]?.jsonPrimitive?.content ?: "/")
    }
    return MoodleEditContext(form.absUrl("action"), fields,
        fields["sesskey"]?.takeIf { it.isNotBlank() } ?: throw MoodleAssignmentFailure("作业会话令牌缺失。"),
        manager.attr("value"), config["context"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: throw MoodleAssignmentFailure("文件区上下文缺失。"),
        scalar("client_id") ?: throw MoodleAssignmentFailure("文件区客户端信息缺失。"), repo,
        manager.attr("name"), files, scalar("maxfiles")?.toIntOrNull() ?: -1, scalar("maxbytes")?.toLongOrNull() ?: -1,
        config["accepted_types"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content })
}

/** 两站共用的 Moodle 文件保存和最终提交协议。读取可恢复会话，写入绝不自动重放。 */
class MoodleAssignmentClient(
    private val base: String,
    private val read: suspend (String) -> SchoolHttpResponse,
    private val write: suspend (SchoolHttpRequest) -> SchoolHttpResponse,
) {
    private fun url(id: Int) = "$base/mod/assign/view.php?id=$id"
    private fun trusted(value: String): Boolean = runCatching {
        val root = Url(base); val target = Url(value)
        target.protocol.name == "https" && target.host == root.host && target.port == root.port &&
            target.user == null && target.password == null && target.encodedPath.startsWith(root.encodedPath.trimEnd('/') + "/")
    }.getOrDefault(false)
    suspend fun status(id: Int): MoodleAssignmentStatus = parseMoodleAssignmentStatus(read(url(id)).bodyText())

    suspend fun saveFiles(id: Int, added: List<HomeworkFileContent>, removed: Set<String> = emptySet()): MoodleAssignmentStatus {
        require(added.isNotEmpty() || removed.isNotEmpty())
        val before = status(id)
        if (!before.editable) throw MoodleAssignmentFailure("此作业已锁定或不允许修改。")
        val edit = parseMoodleEditContext(read("${url(id)}&action=editsubmission").bodyText(), base, id)
        if (!trusted(edit.url)) throw MoodleAssignmentFailure("文件保存地址异常。")
        val replacementNames = added.map { it.fileName }.toSet()
        if (replacementNames.size != added.size) throw MoodleAssignmentFailure("新文件中存在重名，请先移除重复文件。")
        if (!edit.files.map { it.name }.containsAll(removed)) throw MoodleAssignmentFailure("原附件已变化，请刷新文件列表。")
        val deleting = edit.files.filter { it.name in removed || it.name in replacementNames }
        val count = edit.files.size - deleting.size + added.size
        if (edit.maxFiles >= 0 && count > edit.maxFiles) throw MoodleAssignmentFailure("此作业最多允许 ${edit.maxFiles} 个文件。")
        if (edit.maxBytes > 0 && added.any { it.bytes.size > edit.maxBytes }) throw MoodleAssignmentFailure("所选文件超过此作业的大小限制。")
        if (added.any { it.fileName.contains('/') || it.fileName.contains('\\') }) throw MoodleAssignmentFailure("文件名包含不允许的路径字符。")
        if (edit.acceptedTypes.isNotEmpty() && edit.acceptedTypes.all { it.startsWith('.') } &&
            added.any { file -> edit.acceptedTypes.none { file.fileName.endsWith(it, ignoreCase = true) } })
            throw MoodleAssignmentFailure("此作业仅接受：${edit.acceptedTypes.joinToString()}。")
        for (file in deleting) {
            jsonWrite(SchoolHttpRequest(SchoolHttpMethod.POST, "$base/repository/draftfiles_ajax.php?action=delete",
                formFields = mapOf("sesskey" to edit.key, "client_id" to edit.client, "itemid" to edit.item,
                    "filepath" to file.path, "filename" to file.name)))
        }
        for (file in added) {
            jsonWrite(SchoolHttpRequest(SchoolHttpMethod.POST, "$base/repository/repository_ajax.php",
                headers = mapOf("Referer" to url(id)), formFields = mapOf("action" to "upload", "repo_id" to edit.repository,
                    "itemid" to edit.item, "ctx_id" to edit.context, "client_id" to edit.client, "sesskey" to edit.key,
                    "env" to "filepicker", "savepath" to "/", "filepath" to "/", "title" to file.fileName,
                    "maxbytes" to edit.maxBytes.toString(), "areamaxbytes" to "-1", "license" to "unknown", "author" to ""),
                multipartFiles = listOf(SchoolMultipartFile("repo_upload_file", file.fileName, file.contentType, file.bytes))))
        }
        checkedWrite(SchoolHttpRequest(SchoolHttpMethod.POST, edit.url,
            headers = mapOf("Referer" to url(id)), formFields = edit.fields + (edit.manager to edit.item) + ("submitbutton" to "保存更改")))
        val after = status(id)
        val expected = (edit.files.map { it.name }.toSet() - deleting.map { it.name }.toSet()) + replacementNames
        if (after.files.map { it.name }.toSet() != expected) throw MoodleAssignmentFailure("文件保存结果未确认，请刷新核对后再操作。", true)
        return after
    }

    suspend fun finalizationStatement(id: Int): String? {
        val doc = Ksoup.parse(read("${url(id)}&action=submit").bodyText())
        if (doc.selectFirst("input[name=action][value=confirmsubmit]") == null) throw MoodleAssignmentFailure("最终提交确认页面缺失。")
        val checkbox = doc.selectFirst("input[name=submissionstatement]") ?: return null
        return checkbox.parent()?.text()?.takeIf { it.isNotBlank() }
            ?: throw MoodleAssignmentFailure("平台提交声明缺失，请到网页核对。")
    }

    suspend fun finalize(id: Int, acceptedStatement: String? = null): MoodleAssignmentStatus {
        if (!status(id).canFinalize) throw MoodleAssignmentFailure("当前作业没有待最终提交的草稿。")
        val doc = Ksoup.parse(read("${url(id)}&action=submit").bodyText(), url(id))
        val form = doc.select("form").firstOrNull { it.selectFirst("input[name=action][value=confirmsubmit]") != null }
            ?: throw MoodleAssignmentFailure("最终提交确认页面缺失，请到网页核对。")
        val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }.toMutableMap()
        if (fields["id"] != id.toString() || fields["sesskey"].isNullOrBlank()) throw MoodleAssignmentFailure("最终提交上下文不匹配。")
        form.select("input[type=checkbox][name]").forEach {
            if (it.attr("name") != "submissionstatement" || it.parent()?.text() != acceptedStatement) throw MoodleAssignmentFailure("请先阅读并同意平台提交声明。")
            fields[it.attr("name")] = it.attr("value").ifBlank { "1" }
        }
        form.selectFirst("input[type=submit][name]:not([name=cancel])")?.let { fields[it.attr("name")] = it.attr("value") }
        checkedWrite(SchoolHttpRequest(SchoolHttpMethod.POST, form.absUrl("action"), formFields = fields))
        return status(id).also { if (!it.submitted || it.canFinalize) throw MoodleAssignmentFailure("最终提交结果未确认，请刷新核对。", true) }
    }

    private suspend fun checkedWrite(request: SchoolHttpRequest): SchoolHttpResponse {
        if (!trusted(request.url)) throw MoodleAssignmentFailure("作业操作地址异常。")
        var response = write(request)
        if (response.statusCode in listOf(301, 302, 303)) {
            val location = response.header("Location") ?: throw MoodleAssignmentFailure("操作跳转结果未确认。", true)
            val target = Ksoup.parse("", request.url).createElement("a").attr("href", location).absUrl("href")
            if (!trusted(target)) throw MoodleAssignmentFailure("平台返回了异常跳转地址。", true)
            response = read(target)
        }
        if (!trusted(response.finalUrl) || response.looksLikeSessionExpired() || response.statusCode !in 200..299)
            throw MoodleAssignmentFailure("操作结果未确认，请刷新核对；不会自动重复提交。", true)
        val doc = Ksoup.parse(response.bodyText())
        if (doc.selectFirst(".error, .errormessage, .alert-danger, .invalid-feedback")?.text()?.isNotBlank() == true)
            throw MoodleAssignmentFailure("平台拒绝了操作，请刷新查看状态。")
        return response
    }
    private suspend fun jsonWrite(request: SchoolHttpRequest) {
        val body = checkedWrite(request).bodyText()
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw MoodleAssignmentFailure("文件操作响应无法确认，请刷新核对。", true)
        if (obj["error"]?.let { it !is JsonNull && it.toString() !in listOf("false", "0", "\"\"") } == true)
            throw MoodleAssignmentFailure("文件操作失败，原提交未保存。")
        if (obj.containsKey("event")) throw MoodleAssignmentFailure("存在同名文件冲突，请刷新后重试。")
    }
}
