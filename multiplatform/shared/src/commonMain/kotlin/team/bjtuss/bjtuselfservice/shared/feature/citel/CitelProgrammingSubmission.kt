package team.bjtuss.bjtuselfservice.shared.feature.citel

import com.fleeksoft.ksoup.Ksoup
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.network.*

data class CitelProgrammingOptions(val languages: Map<String, String>, val maxBytes: Long)
private class ProgrammingForm(val url: String, val fields: Map<String, String>, val options: CitelProgrammingOptions)

class CitelProgrammingClient(
    private val read: suspend (String) -> SchoolHttpResponse,
    private val write: suspend (SchoolHttpRequest) -> SchoolHttpResponse,
) {
    private suspend fun form(task: CitelTask): ProgrammingForm {
        val page = Ksoup.parse(read(task.url).bodyText(), task.url)
        val submitUrl = page.select("a[href]").firstOrNull { it.absUrl("href").contains("/mod/programming/submit.php?") }?.absUrl("href")
            ?: throw CitelFailure("此编程作业没有代码提交入口。")
        if (!isCitelUrl(submitUrl)) throw CitelFailure("代码提交入口异常。")
        val doc = Ksoup.parse(read(submitUrl).bodyText(), submitUrl)
        val form = doc.select("#region-main form").firstOrNull { it.selectFirst("input[type=file][name=sourcefile]") != null }
            ?: throw CitelFailure("此编程作业暂不接受文件提交。")
        val action = form.absUrl("action")
        if (!isCitelUrl(action) || !action.substringBefore('?').endsWith("/mod/programming/submit.php")) throw CitelFailure("代码提交地址异常。")
        val fields = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }
        if (fields["a"].isNullOrBlank()) throw CitelFailure("编程作业标识缺失。")
        val languages = form.select("select[name=language] option").associate { it.attr("value") to it.text() }
        if (languages.isEmpty()) throw CitelFailure("没有可用的编程语言。")
        return ProgrammingForm(action, fields, CitelProgrammingOptions(languages, fields["MAX_FILE_SIZE"]?.toLongOrNull() ?: 65536))
    }
    suspend fun options(task: CitelTask) = form(task).options
    suspend fun submit(task: CitelTask, file: HomeworkFileContent, language: String): CitelProgrammingResult {
        val form = form(task)
        if (language !in form.options.languages) throw CitelFailure("请选择平台支持的编程语言。")
        if (file.bytes.size > form.options.maxBytes || file.bytes.isEmpty()) throw CitelFailure("代码文件为空或超过平台大小限制。")
        val index = "$CITEL_BASE/mod/programming/index.php?id=${task.courseId}"
        val before = parseCitelProgrammingResults(read(index).bodyText())[task.id] ?: throw CitelFailure("编程评测记录缺失。")
        val response = write(SchoolHttpRequest(SchoolHttpMethod.POST, form.url,
            formFields = form.fields + mapOf("language" to language, "code" to "", "action" to "Submit"),
            multipartFiles = listOf(SchoolMultipartFile("sourcefile", file.fileName, file.contentType, file.bytes))))
        if (!isCitelUrl(response.finalUrl) || response.looksLikeSessionExpired() || response.statusCode !in 200..299)
            throw CitelFailure("代码提交结果未确认，请刷新评测记录；不会自动重复提交。")
        val after = parseCitelProgrammingResults(read(index).bodyText())[task.id] ?: throw CitelFailure("无法核对代码提交记录。")
        if (after.submitCount <= before.submitCount) throw CitelFailure("代码提交次数尚未更新，请刷新或到网页核对。")
        return after
    }
}
