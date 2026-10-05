package team.bjtuss.bjtuselfservice.shared.feature.physicslab

import com.fleeksoft.ksoup.Ksoup
import kotlinx.datetime.LocalDate
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class PhysicsLabFailure(val userMessage: String) : Exception(userMessage)

fun parsePhysicsLabs(html: String): List<PhysicsLab> {
    val doc = Ksoup.parse(html)
    if (doc.selectFirst("input[name$=tbPassword]") != null || doc.selectFirst("input[id$=tbPassword_I]") != null) {
        throw PhysicsLabFailure("账号或密码错误，或登录已失效。")
    }
    val table = doc.selectFirst("table[id$=ASPxGridViewCourseList_DXMainTable]")
        ?: throw PhysicsLabFailure("选课结果结构发生变化，保留上次缓存。")
    return table.select("tr").mapNotNull { row ->
        if (row.className().contains("EmptyDataRow", ignoreCase = true)) return@mapNotNull null
        val cells = row.children().filter { it.tagName() == "td" || it.tagName() == "th" }.map { it.text().trim() }
        if (cells.size < 4) {
            if (row.className().contains("DataRow", ignoreCase = true)) throw PhysicsLabFailure("实验数据缺少字段，保留上次缓存。")
            return@mapNotNull null
        }
        val dateText = cells[1]
        if (!Regex("20\\d{2}[/\\-]\\d{1,2}[/\\-]\\d{1,2}").matches(dateText)) {
            if (row.className().contains("DataRow", ignoreCase = true) || dateText.any(Char::isDigit)) {
                throw PhysicsLabFailure("实验日期无法识别，保留上次缓存。")
            }
            return@mapNotNull null
        }
        val pieces = dateText.split('/', '-').map { it.toInt() }
        val date = runCatching { LocalDate(pieces[0], pieces[1], pieces[2]) }.getOrNull()
            ?: throw PhysicsLabFailure("实验日期无法识别，保留上次缓存。")
        val period = cells[2].toIntOrNull()?.takeIf { it in 1..6 }
            ?: throw PhysicsLabFailure("实验时段无法识别，保留上次缓存。")
        if (cells[3].isBlank()) throw PhysicsLabFailure("实验名称缺失，保留上次缓存。")
        PhysicsLab(date, period, cells[3], cells.getOrElse(4) { "" }, cells.getOrElse(5) { "" }, physicsLabWeekCount(cells[3]))
    }.distinct()
}

class PhysicsLabRemote(private val transport: SchoolHttpTransport) {
    suspend fun fetch(credentials: Credentials): List<PhysicsLab> {
        suspend fun request(method: SchoolHttpMethod, url: String, fields: Map<String, String> = emptyMap()): String {
            // 禁止自动跟随重定向，以免凭据或 Cookie 被发给其他系统。
            val response = transport.executeWithoutRedirects(SchoolHttpRequest(method, url,
                headers = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache", "Referer" to "$PHYSICS_LAB_ORIGIN/"),
                formFields = fields))
            if (response.statusCode in 300..399) {
                val location = response.header("Location").orEmpty()
                if (method != SchoolHttpMethod.POST ||
                    !(location.startsWith("/") && !location.startsWith("//") || location.startsWith("$PHYSICS_LAB_ORIGIN/"))) {
                    throw PhysicsLabFailure("实验系统登录失效或跳转地址异常。")
                }
                // 登录 POST 的同域跳转无需访问；随后 GET 受保护选课结果验证登录。
                return ""
            }
            if (response.statusCode !in 200..299) throw PhysicsLabFailure("实验系统暂时不可用，请稍后重试。")
            return response.bodyText()
        }
        val doc = Ksoup.parse(request(SchoolHttpMethod.GET, "$PHYSICS_LAB_ORIGIN/"))
        val form = doc.selectFirst("form") ?: throw PhysicsLabFailure("未找到实验系统登录表单。")
        val fields = mutableMapOf<String, String>()
        form.select("input[name]").forEach { input ->
            val type = input.attr("type").lowercase()
            if (type !in listOf("submit", "button", "image", "file", "reset") &&
                (type !in listOf("checkbox", "radio") || input.hasAttr("checked"))) {
                fields[input.attr("name")] = input.attr("value")
            }
        }
        fun field(suffix: String): String = fields.keys.singleOrNull { it.endsWith(suffix) }
            ?: throw PhysicsLabFailure("实验系统登录表单发生变化。")
        fields[field("tbUserId")] = credentials.username
        fields[field("tbPassword")] = credentials.password
        fields[field("ASPxRadioButtonListType")] = "0"
        repeat(3) { fields[field("RB$it")] = if (it == 0) "C" else "U" }
        fields.getOrPut("__EVENTTARGET") { "" }
        fields.getOrPut("__EVENTARGUMENT") { "" }
        val submit = form.selectFirst("input[type=submit][name$=btnLogin]")
            ?: throw PhysicsLabFailure("未找到登录按钮。")
        fields[submit.attr("name")] = submit.attr("value").ifBlank { "登录" }
        request(SchoolHttpMethod.POST, "$PHYSICS_LAB_ORIGIN/", fields)
        return parsePhysicsLabs(request(SchoolHttpMethod.GET, PHYSICS_LAB_RESULTS))
    }
}
