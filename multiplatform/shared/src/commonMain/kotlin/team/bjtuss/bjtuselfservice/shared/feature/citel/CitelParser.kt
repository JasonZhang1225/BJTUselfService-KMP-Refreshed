package team.bjtuss.bjtuselfservice.shared.feature.citel

import com.fleeksoft.ksoup.Ksoup
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

class CitelFailure(val userMessage: String, val sessionExpired: Boolean = false) : Exception(userMessage)

/** 中文 Moodle 日期、英文 Moodle 日期和编程列表的两位年份均使用北京时间。 */
fun parseCitelTime(text: String): Long? = runCatching {
    val numeric = Regex("(\\d{2,4})[年/-](\\d{1,2})[月/-](\\d{1,2})日?.*?(\\d{1,2})[:：](\\d{2})").find(text)
    if (numeric != null) {
        val (y, m, d, h, min) = numeric.destructured
        var hour = h.toInt()
        if (listOf("下午", "晚上", "PM").any { text.contains(it, true) } && hour < 12) hour += 12
        if (listOf("上午", "AM").any { text.contains(it, true) } && hour == 12) hour = 0
        return@runCatching LocalDateTime(y.toInt().let { if (it < 100) 2000 + it else it }, m.toInt(), d.toInt(), hour, min.toInt()).toInstant(citelTimeZone).epochSeconds
    }
    val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    val match = Regex("(\\d{1,2})\\s+([A-Za-z]+)\\s+(\\d{4}),?\\s+(\\d{1,2}):(\\d{2})\\s*(AM|PM)?", RegexOption.IGNORE_CASE).find(text) ?: return@runCatching null
    val (d, m, y, h, min, ampm) = match.destructured
    var hour = h.toInt()
    if (ampm.equals("PM", true) && hour < 12) hour += 12
    if (ampm.equals("AM", true) && hour == 12) hour = 0
    LocalDateTime(y.toInt(), months.indexOfFirst { it.equals(m, true) } + 1, d.toInt(), hour, min.toInt()).toInstant(citelTimeZone).epochSeconds
}.getOrNull()

fun parseCitelCourses(html: String): List<CitelCourse> {
    val doc = Ksoup.parse(html, "$CITEL_BASE/my/")
    if (doc.selectFirst("#region-main, [role=main]") == null) throw CitelFailure("CITEL 课程页面格式发生变化。")
    return doc.select("a[href*='/course/view.php']").mapNotNull { link ->
        val id = Regex("[?&]id=(\\d+)").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val name = link.text().trim()
        if (name.isBlank()) null else CitelCourse(id, name)
    }.distinctBy { it.id }
}

fun parseCitelTasks(html: String, course: CitelCourse): List<CitelTask> {
    val doc = Ksoup.parse(html, "$CITEL_BASE/course/view.php?id=${course.id}")
    val main = doc.selectFirst("#region-main") ?: throw CitelFailure("CITEL 作业列表格式发生变化。")
    return main.select("li.activity a[href*='/mod/assign/view.php'], li.activity a[href*='/mod/programming/view.php']").mapNotNull { link ->
        val url = link.absUrl("href")
        if (!isCitelUrl(url)) return@mapNotNull null
        val id = Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val title = link.selectFirst(".instancename")?.clone()?.also { it.select(".accesshide").remove() }?.text()
            ?: link.text().replace(Regex("\\s+(Assignment|Programming Practice|作业|编程练习)$"), "")
        CitelTask(id, course.id, course.name, title.trim(), url, url.contains("/mod/programming/"))
    }.distinctBy { it.id }
}

fun parseCitelTaskDetail(html: String, task: CitelTask): CitelTask {
    val doc = Ksoup.parse(html, task.url)
    val main = doc.selectFirst("#region-main") ?: throw CitelFailure("CITEL 作业详情格式发生变化。")
    val rows = main.select("table tr").mapNotNull { row ->
        val label = row.selectFirst("th")?.text() ?: return@mapNotNull null
        val value = row.selectFirst("td")?.text() ?: return@mapNotNull null
        label.trim().lowercase() to value.trim()
    }.toMap()
    fun row(vararg keys: String) = keys.firstNotNullOfOrNull { rows[it.lowercase()] }
    val dateRows = main.select("[data-region=activity-dates] > div").associate { div ->
        val label = div.selectFirst("strong")?.text().orEmpty().trim().trimEnd(':', '：').lowercase()
        label to div.text()
    }
    fun date(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key ->
        (rows[key.lowercase()] ?: dateRows[key.lowercase()])?.let(::parseCitelTime)
    }
    if (task.programming && main.selectFirst("h1.name") == null) throw CitelFailure("CITEL 编程作业详情格式发生变化。")
    if (!task.programming && main.selectFirst(".submissionstatustable, [data-region=activity-information]") == null) throw CitelFailure("CITEL 书面作业详情格式发生变化。")
    val text = main.text()
    val status = row("Submission status", "提交状态") ?: task.status
    val factor = Regex("(?:Discount|折扣)\\s*[:：]\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    val late = Regex("(?:Allow late|允许迟交)\\s*[:：]\\s*(Yes|No|是|否)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
    return task.copy(
        openTime = date("Open Time", "Opened", "开放时间", "开始时间") ?: task.openTime,
        dueTime = date("Close Time", "Due", "截止时间", "到期时间") ?: task.dueTime,
        discountTime = date("Time Discount", "折扣时间") ?: task.discountTime,
        discount = factor,
        allowLate = late?.let { it.equals("Yes", true) || it == "是" } ?: task.allowLate,
        status = status,
        submitted = task.submitted || status.equals("Submitted for grading", true) || status.contains("已提交"),
        grade = row("Grade", "成绩") ?: Regex("Grade\\s*:\\s*([^/]+)\\s*/\\s*Discount", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim(),
    )
}

/** 编程练习的完成信号来自个人评测结果，题目的 Grade 是题目分值。 */
data class CitelProgrammingResult(val status: String, val accepted: Boolean, val times: List<Long>, val allowLate: Boolean, val submitCount: Int = 0)

fun parseCitelProgrammingResults(html: String): Map<Int, CitelProgrammingResult> {
    val doc = Ksoup.parse(html, "$CITEL_BASE/mod/programming/index.php")
    val table = doc.selectFirst("#region-main table") ?: throw CitelFailure("CITEL 编程评测列表格式发生变化。")
    return table.select("tbody tr").mapNotNull { row ->
        val link = row.selectFirst("a[href*='view.php?id=']") ?: return@mapNotNull null
        val id = Regex("[?&]id=(\\d+)").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val status = row.select("td").getOrNull(2)?.text().orEmpty()
        val timeCell = row.select("td").getOrNull(6) ?: throw CitelFailure("CITEL 编程时间列缺失。")
        val times = Regex("\\d{2}-\\d{2}-\\d{2} \\d{2}:\\d{2}").findAll(timeCell.text()).map { match ->
            parseCitelTime(match.value) ?: throw CitelFailure("CITEL 编程时间无法识别。")
        }.toList()
        if (times.size != 3) throw CitelFailure("CITEL 编程时间表格式发生变化。")
        id to CitelProgrammingResult(status, status.startsWith("AC:", true) || status == "AC", times, timeCell.text().trim().endsWith('+'),
            row.select("td").getOrNull(5)?.text()?.toIntOrNull() ?: 0)
    }.toMap()
}
