package team.bjtuss.bjtuselfservice.shared.feature.citel

import com.fleeksoft.ksoup.Ksoup
import io.ktor.http.Url
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.network.*
import team.bjtuss.bjtuselfservice.shared.data.moodle.*
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

fun isCitelUrl(value: String): Boolean = runCatching {
    val url = Url(value)
    url.protocol.name == "https" && url.host == "citel.bjtu.edu.cn" && url.port == 443 &&
        url.encodedPath.startsWith("/mlsv3/") && url.user == null && url.password == null
}.getOrDefault(false)

interface CitelDataSource {
    suspend fun fetch(credentials: Credentials): List<CitelTask>
    suspend fun submission(credentials: Credentials, task: CitelTask): MoodleAssignmentStatus = throw CitelFailure("提交功能暂不可用。")
    suspend fun saveFiles(credentials: Credentials, task: CitelTask, added: List<HomeworkFileContent>, removed: Set<String>): MoodleAssignmentStatus = throw CitelFailure("提交功能暂不可用。")
    suspend fun programmingOptions(credentials: Credentials, task: CitelTask): CitelProgrammingOptions = throw CitelFailure("代码提交暂不可用。")
    suspend fun programmingStatus(credentials: Credentials, task: CitelTask): CitelProgrammingResult = throw CitelFailure("评测状态暂不可用。")
    suspend fun submitProgramming(credentials: Credentials, task: CitelTask, file: HomeworkFileContent, language: String): CitelProgrammingResult = throw CitelFailure("代码提交暂不可用。")
}

/** 串行快照读取。每个 GET 遇到掉线时重新取 logintoken 登录，并只重放该 GET 一次。 */
class CitelRemote(private val transport: SchoolHttpTransport) : CitelDataSource {
    private val mutex = Mutex()
    private fun assignmentClient(credentials: Credentials) = MoodleAssignmentClient(CITEL_BASE,
        read = { page(it, credentials) }, write = { request(it) })
    private fun programmingClient(credentials: Credentials) = CitelProgrammingClient(read = { page(it, credentials) }, write = { request(it) })
    override suspend fun programmingOptions(credentials: Credentials, task: CitelTask) = mutex.withLock { programmingClient(credentials).options(task) }
    override suspend fun programmingStatus(credentials: Credentials, task: CitelTask) = mutex.withLock { programmingClient(credentials).status(task) }
    override suspend fun submitProgramming(credentials: Credentials, task: CitelTask, file: HomeworkFileContent, language: String) = mutex.withLock {
        programmingClient(credentials).submit(task, file, language)
    }
    override suspend fun submission(credentials: Credentials, task: CitelTask): MoodleAssignmentStatus = mutex.withLock {
        require(!task.programming)
        assignmentClient(credentials).status(task.id)
    }
    override suspend fun saveFiles(credentials: Credentials, task: CitelTask, added: List<HomeworkFileContent>, removed: Set<String>): MoodleAssignmentStatus = mutex.withLock {
        require(!task.programming)
        assignmentClient(credentials).saveFiles(task.id, added, removed)
    }
    override suspend fun fetch(credentials: Credentials): List<CitelTask> = mutex.withLock {
        val courses = courses(credentials)
        val tasks = mutableListOf<CitelTask>()
        for (course in courses) {
            val courseTasks = parseCitelTasks(page("$CITEL_BASE/course/view.php?id=${course.id}", credentials).bodyText(), course)
            val results = if (courseTasks.any { it.programming }) {
                parseCitelProgrammingResults(page("$CITEL_BASE/mod/programming/index.php?id=${course.id}", credentials).bodyText())
            } else emptyMap()
            for (task in courseTasks) {
                val result = results[task.id]
                val indexed = if (result != null) task.copy(status = result.status, submitted = result.accepted,
                    openTime = result.times[0], discountTime = result.times[1], dueTime = result.times[2], allowLate = result.allowLate) else task
                tasks += parseCitelTaskDetail(page(task.url, credentials).bodyText(), indexed)
            }
        }
        tasks.distinctBy { it.id }
    }

    /** 侧栏会省略历史课程；使用网站自己的只读课程 AJAX，并处理分页。 */
    private suspend fun courses(credentials: Credentials): List<CitelCourse> {
        var key = sessionKey(credentials)
        val courses = mutableListOf<CitelCourse>()
        var offset = 0
        repeat(100) {
            var result = coursePage(key, offset)
            if (result == null) {
                login(credentials)
                key = sessionKey(credentials)
                result = coursePage(key, offset)
            }
            val batch = result ?: throw CitelFailure("CITEL 课程会话已失效，请重新刷新。", true)
            courses += batch.first
            if (batch.first.size < 50) return courses.distinctBy { it.id }
            if (batch.second <= offset) throw CitelFailure("CITEL 课程分页异常。")
            offset = batch.second
        }
        throw CitelFailure("CITEL 课程数量超过同步范围。")
    }

    private suspend fun sessionKey(credentials: Credentials): String {
        val dashboard = page("$CITEL_BASE/my/", credentials).bodyText()
        return Regex("\"sesskey\"\\s*:\\s*\"([A-Za-z0-9]+)\"").find(dashboard)?.groupValues?.get(1)
            ?: throw CitelFailure("CITEL 课程会话令牌缺失。")
    }

    /** 只读 AJAX 虽使用 POST，也允许会话恢复后重试一次；绝不调用作业写入接口。 */
    private suspend fun coursePage(key: String, offset: Int): Pair<List<CitelCourse>, Int>? {
        val method = "core_course_get_enrolled_courses_by_timeline_classification"
        val response = request(SchoolHttpRequest(SchoolHttpMethod.POST,
            "$CITEL_BASE/lib/ajax/service.php?sesskey=$key&info=$method",
            rawBodyContentType = "application/json",
            rawBody = """[{"index":0,"methodname":"$method","args":{"offset":$offset,"limit":50,"classification":"all","sort":"fullname","customfieldname":"","customfieldvalue":""}}]""".encodeToByteArray()))
        if (response.looksLikeSessionExpired()) return null
        validate(response)
        val item = try { Json.parseToJsonElement(response.bodyText()).jsonArray.single().jsonObject }
            catch (_: Exception) { throw CitelFailure("CITEL 课程接口格式发生变化。") }
        if (item["error"]?.jsonPrimitive?.booleanOrNull == true) {
            val code = item["exception"]?.jsonObject?.get("errorcode")?.jsonPrimitive?.contentOrNull
            if (code in listOf("invalidsesskey", "requireloginerror", "notloggedin", "servicerequireslogin")) return null
            throw CitelFailure("CITEL 课程接口暂不可用。")
        }
        try {
            val data = item.getValue("data").jsonObject
            val courses = data.getValue("courses").jsonArray.map { raw ->
                val course = raw.jsonObject
                CitelCourse(course.getValue("id").jsonPrimitive.int, course.getValue("fullname").jsonPrimitive.content)
            }
            return courses to data.getValue("nextoffset").jsonPrimitive.int
        } catch (_: Exception) { throw CitelFailure("CITEL 课程数据格式发生变化。") }
    }

    private suspend fun page(url: String, credentials: Credentials): SchoolHttpResponse {
        var response = request(SchoolHttpRequest(SchoolHttpMethod.GET, url))
        if (response.looksLikeSessionExpired()) {
            login(credentials)
            response = request(SchoolHttpRequest(SchoolHttpMethod.GET, url))
        }
        if (response.looksLikeSessionExpired()) throw CitelFailure("CITEL 登录已失效，请检查账号密码后重试。", true)
        validate(response)
        return response
    }

    private suspend fun login(credentials: Credentials) {
        val loginUrl = "$CITEL_BASE/login/index.php"
        val entry = request(SchoolHttpRequest(SchoolHttpMethod.GET, loginUrl))
        validate(entry)
        val doc = Ksoup.parse(entry.bodyText(), loginUrl)
        val form = doc.select("form").firstOrNull { it.selectFirst("input[type=password][name=password]") != null }
            ?: throw CitelFailure("CITEL 登录页面格式发生变化。")
        val token = form.selectFirst("input[name=logintoken]")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: throw CitelFailure("CITEL 登录令牌缺失。")
        val action = form.absUrl("action").ifBlank { loginUrl }
        if (action != loginUrl) throw CitelFailure("CITEL 登录地址异常。")
        val result = request(SchoolHttpRequest(SchoolHttpMethod.POST, action,
            headers = mapOf("Referer" to loginUrl),
            formFields = mapOf("username" to credentials.username, "password" to credentials.password, "logintoken" to token, "anchor" to "")))
        validate(result)
        if (result.looksLikeSessionExpired()) throw CitelFailure("CITEL 登录失败，请检查 CITEL 专用账号密码。", true)
    }

    /** 禁止把凭据发送到跨域跳转，也禁止 307/308 自动重放登录 POST。 */
    private suspend fun request(initial: SchoolHttpRequest): SchoolHttpResponse {
        var current = initial
        repeat(8) {
            if (!isCitelUrl(current.url)) throw CitelFailure("CITEL 返回了不受信任的地址。")
            val response = transport.executeWithoutRedirects(current)
            if (!isCitelUrl(response.finalUrl)) throw CitelFailure("CITEL 返回了不受信任的地址。")
            if (response.statusCode !in listOf(301, 302, 303, 307, 308)) return response
            val location = response.header("Location") ?: throw CitelFailure("CITEL 重定向地址缺失。")
            val next = Ksoup.parse("", current.url).let { doc ->
                doc.createElement("a").attr("href", location).absUrl("href")
            }
            if (!isCitelUrl(next)) throw CitelFailure("CITEL 返回了不受信任的地址。")
            if (current.method == SchoolHttpMethod.POST && response.statusCode in listOf(307, 308)) throw CitelFailure("CITEL 登录重定向异常，请重试。")
            current = SchoolHttpRequest(SchoolHttpMethod.GET, next)
        }
        throw CitelFailure("CITEL 重定向次数过多。")
    }

    private fun validate(response: SchoolHttpResponse) {
        if (response.statusCode !in 200..299) throw CitelFailure("CITEL 暂时无法连接，请稍后刷新。")
    }
}
