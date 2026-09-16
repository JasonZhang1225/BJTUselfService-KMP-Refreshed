package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import com.fleeksoft.ksoup.Ksoup
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabSubmissionFile

sealed interface PhyVlabParseResult<out T> {
    data class Success<T>(val value: T) : PhyVlabParseResult<T>
    data class Failure(val field: String) : PhyVlabParseResult<Nothing>
}

private const val PHYVLAB_ORIGIN = "https://phyvlab.bjtu.edu.cn"

/**
 * 解析 Moodle“我的课程”页卡片。只读取登录后的课程名称、分类、完成百分比与课程链接。
 * 页面可能为空（尚未选课）；此时返回空列表而不是解析失败。
 */
fun parsePhyVlabCourses(html: String): PhyVlabParseResult<List<PhyVlabCourse>> {
    val document = Ksoup.parse(html)
    val cards = document.select("[data-region='course-content']")
    phyVlabDebug(
        "parse courses cards=${cards.size} " +
            "courseIdCards=${document.select("div[data-course-id]").size} " +
            "courseLinks=${document.select("a[href*='/course/view.php?id=']").size} " +
            "namedLinks=${document.select("a.aalink.coursename").size} " +
            "multiline=${document.select(".multiline").size}",
    )
    if (cards.isEmpty()) {
        val shapes = document.select("a[href*='/course/view.php?id=']")
            .take(12)
            .joinToString(";") { link ->
                val textLength = link.text().trim().length
                val titlePresent = link.hasAttr("title")
                val ariaPresent = link.hasAttr("aria-label")
                "class=${link.className().take(32)}|textLen=$textLength|title=$titlePresent|aria=$ariaPresent"
        }
        phyVlabDebug("course link shapes=$shapes")
        return parseLegacyPhyVlabCourses(document)
    }
    val courses = cards.mapNotNull { card ->
        val id = card.attr("data-course-id").toIntOrNull() ?: return@mapNotNull null
        val link = card.selectFirst("a.aalink.coursename")
            ?: card.selectFirst("[data-region='course-content'] > a")
        val name = link?.selectFirst(".multiline")?.text()?.trim().orEmpty()
        if (name.isBlank()) return@mapNotNull null
        val category = card.selectFirst(".categoryname")?.text()?.trim().orEmpty()
        val progressNode = card.selectFirst(".progress-text")
        val progressText = progressNode?.text()?.trim().orEmpty()
        val progressDigits = progressNode?.select("span")?.firstOrNull()?.text()
            ?.trim()
            ?.toIntOrNull()
            ?: progressText.filter(Char::isDigit).toIntOrNull()
        val progress = progressDigits?.coerceIn(0, 100) ?: 0
        val href = link?.attr("href").orEmpty()
        val courseUrl = when {
            href.startsWith("https://", ignoreCase = true) -> href
            href.startsWith("/") -> "$PHYVLAB_ORIGIN$href"
            else -> return@mapNotNull null
        }
        PhyVlabCourse(
            id = id,
            name = name,
            category = category,
            progressPercent = progress,
            courseUrl = courseUrl,
        )
    }
    if (courses.isEmpty()) return PhyVlabParseResult.Failure("courses")
    return PhyVlabParseResult.Success(courses.sortedBy { it.name })
}

/** 详情页解析出的短期提交上下文，仅供物理在线数据层消费。 */
internal data class PhyVlabAssignmentSubmissionContext(
    val formUrl: String,
    val formFields: Map<String, String>,
    val sesskey: String,
    val draftItemId: String?,
    val contextId: String?,
    val clientId: String?,
    val repositoryId: String?,
)

internal val PhyVlabAssignmentSubmissionContext.isUploadReady: Boolean
    get() = !draftItemId.isNullOrBlank() &&
        !contextId.isNullOrBlank() &&
        !clientId.isNullOrBlank()

private data class PhyVlabFileManagerMetadata(
    val itemId: String?,
    val contextId: String?,
    val clientId: String?,
    val repositoryId: String?,
)

internal data class PhyVlabParsedAssignmentPage(
    val detail: PhyVlabAssignmentDetail,
    val submissionContext: PhyVlabAssignmentSubmissionContext?,
    val editSubmissionUrl: String?,
)

/**
 * Moodle 的课程概览卡片由前端脚本异步渲染，Ktor 只拿到服务端骨架时没有
 * `data-region="course-content"`。此时侧栏仍会输出课程链接，按课程 id 去重后
 * 作为只读课程摘要；不把收藏/归档菜单文本当成课程名。
 */
private fun parseLegacyPhyVlabCourses(document: com.fleeksoft.ksoup.nodes.Document): PhyVlabParseResult<List<PhyVlabCourse>> {
    val genericLabels = setOf("课程图片", "课程名称")
    val actionMarkers = listOf("收藏此课程", "取消收藏", "在视图中恢复", "归档本课程")
    val candidates = document.select("a[href*='/course/view.php?id=']")
        .mapNotNull { link ->
            val id = Regex("[?&]id=(\\d+)").find(link.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: return@mapNotNull null
            val visible = link.text().replace(Regex("\\s+"), " ").trim()
            val title = link.attr("title").replace(Regex("\\s+"), " ").trim()
            val name = when {
                visible.isBlank() || visible in genericLabels || actionMarkers.any(visible::contains) -> title
                else -> visible
            }
            if (name.isBlank() || name in genericLabels || actionMarkers.any(name::contains)) {
                return@mapNotNull null
            }
            val href = link.attr("href").trim()
            val courseUrl = when {
                href.startsWith("https://", ignoreCase = true) -> href
                href.startsWith("/") -> "$PHYVLAB_ORIGIN$href"
                else -> return@mapNotNull null
            }
            Triple(id, name, courseUrl)
        }
        .groupBy { it.first }
        .values
        .mapNotNull { sameCourse ->
            val selected = sameCourse
                .sortedWith(compareBy<Triple<Int, String, String>> { it.second.length }.thenBy { it.second })
                .firstOrNull() ?: return@mapNotNull null
            PhyVlabCourse(
                id = selected.first,
                name = selected.second,
                category = "",
                progressPercent = 0,
                courseUrl = selected.third,
            )
        }
        .sortedBy { it.name }
    if (candidates.isEmpty()) return PhyVlabParseResult.Success(emptyList())
    phyVlabDebug("legacy courses parsed count=${candidates.size}")
    return PhyVlabParseResult.Success(candidates)
}

/**
 * 解析课程页的“作业”活动卡片。
 * 以 Moodle 的 `li.activity.modtype_assign` 为锚点，读取名称、链接、打开时间与完成状态。
 */
fun parsePhyVlabActivities(
    html: String,
    courseId: Int,
    courseName: String,
): PhyVlabParseResult<List<PhyVlabActivity>> {
    val document = Ksoup.parse(html)
    val activities = document.select("li.activity.modtype_assign")
    phyVlabDebug(
        "parse activities anchors=${activities.size} " +
            "assignLinks=${document.select("a[href*='/mod/assign/view.php']").size} " +
            "dateRegions=${document.select("[data-region='activity-dates']").size}",
    )
    if (activities.isEmpty()) return PhyVlabParseResult.Success(emptyList())
    val parsed = activities.mapNotNull { activity ->
        val id = (activity.attr("id").substringAfter("module-").toIntOrNull()
            ?: activity.attr("data-id").toIntOrNull())
            ?: return@mapNotNull null
        val link = activity.selectFirst("a.aalink[href*='/mod/assign/view.php']")
        val rawTitle = link?.selectFirst(".instancename")?.text()?.trim().orEmpty()
        val title = rawTitle.removeSuffix("作业").trim()
        if (title.isBlank()) return@mapNotNull null
        val href = link?.attr("href").orEmpty()
        val activityUrl = when {
            href.startsWith("https://", ignoreCase = true) -> href
            href.startsWith("/") -> "$PHYVLAB_ORIGIN$href"
            else -> return@mapNotNull null
        }
        val completion = activity.selectFirst("button[data-action='toggle-manual-completion']")
        val completionType = completion?.attr("data-toggletype").orEmpty()
        val completed = completionType.contains("undo") ||
            (completion != null && completionType.startsWith("manual:") &&
                !completionType.contains("mark-done"))
        val dateRegion = activity.selectFirst("[data-region='activity-dates']")
        val openText = activityDateText(dateRegion, "打开")
        val dueText = activityDateText(dateRegion, "到期日")
        PhyVlabActivity(
            id = id,
            courseId = courseId,
            courseName = courseName,
            title = title,
            activityType = "作业",
            activityUrl = activityUrl,
            openText = openText,
            openTimestamp = openText?.let(::parsePhyVlabDateTimestamp),
            dueText = dueText,
            dueTimestamp = dueText?.let(::parsePhyVlabDateTimestamp),
            completed = completed,
        )
    }
    if (parsed.isEmpty()) return PhyVlabParseResult.Failure("activities")
    return PhyVlabParseResult.Success(parsed.sortedBy { it.id })
}

/**
 * 解析 Moodle 单项作业详情。页面结构在 Moodle 小版本/主题间会变化，
 * 所以状态优先按表格行的标签提取，正文与成绩再按多个稳定 class 兜底。
 * 该函数只读 HTML，不执行表单动作。
 */
internal fun parsePhyVlabAssignmentPage(
    html: String,
    activity: PhyVlabActivity,
): PhyVlabParseResult<PhyVlabParsedAssignmentPage> {
    val document = Ksoup.parse(html)
    val rows = document.select(
        "div.submissionstatustable tr, table.submissionstatustable tr, " +
            "div.gradingsummarytable tr, table.gradingsummarytable tr, " +
            "div.feedbacktable tr, table.feedbacktable tr",
    )
    val labeledValues = rows.mapNotNull { row ->
        val cells = row.select("th, td").map { cleanPhyVlabText(it.text()) }
        if (cells.size < 2) return@mapNotNull null
        cells.first() to cells.drop(1).joinToString(" ").trim()
    }

    fun rowValue(vararg labels: String): String? = labeledValues
        .firstOrNull { (label, value) ->
            labels.any { wanted -> label.contains(wanted, ignoreCase = true) } && value.isNotBlank()
        }
        ?.second

    fun exactRowValue(vararg labels: String): String? = labeledValues
        .firstOrNull { (label, value) ->
            val normalizedLabel = label.removeSuffix(":").removeSuffix("：").trim()
            labels.any { wanted -> normalizedLabel.equals(wanted, ignoreCase = true) } && value.isNotBlank()
        }
        ?.second

    val description = listOf(
        "#intro",
        ".activity-description",
        ".mod_introbox",
        "[data-region='activity-description']",
        ".box.generalbox",
    ).asSequence()
        .mapNotNull { selector -> document.selectFirst(selector)?.let { cleanPhyVlabText(it.text()) } }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()

    val bodyText = cleanPhyVlabText(document.body().text())
    val submissionStatus = rowValue(
        "提交状态",
        "作业状态",
        "Submission status",
        "Assignment status",
    )
        ?: document.selectFirst(".submissionstatus, [data-region='submission-status']")
            ?.let { cleanPhyVlabText(it.text()) }
        ?: when {
            bodyText.contains("未提交") -> "未提交"
            bodyText.contains("已提交") -> "已提交"
            else -> null
        }
        ?: ""
    // 只能从带有明确提交语义的表格行读取提交时间。
    // 不能用正文里的第一个日期兜底：详情页正文通常先出现开放时间，
    // 这会把“开放”误显示成“提交”，并进一步把未提交作业判成已完成。
    val submissionDateCandidate = rowValue("最后修改", "Last modified", "提交时间", "Submitted")
    val numericGradePattern = Regex(
        "成绩\\s*([0-9]+(?:\\.[0-9]+)?\\s*/\\s*[0-9]+(?:\\.[0-9]+)?)",
        RegexOption.IGNORE_CASE,
    )
    val numericGrade = labeledValues.asSequence()
        .map { it.second }
        .mapNotNull { value -> numericGradePattern.find(value)?.groupValues?.getOrNull(1) }
        .firstOrNull()
        ?: numericGradePattern.find(bodyText)?.groupValues?.getOrNull(1)
    val gradingStatus = rowValue("批改状态", "Grading status", "评分状态")
        ?: rowValue("批改成绩", "Graded")?.takeIf { numericGrade == null }
    val grade = numericGrade
        ?: rowValue("成绩", "Grade", "评分")?.takeIf { Regex("\\d").containsMatchIn(it) }
        ?: firstDetailText(document, ".gradefordisplay, [data-region='grade']")
    val feedback = exactRowValue(
        "教师评语",
        "教师反馈",
        "Feedback comments",
        "评语",
        "反馈",
        "Comments",
        "Comment",
    )
        ?.takeUnless { numericGradePattern.containsMatchIn(it) }
        ?: firstDetailText(
            document,
            ".assignfeedback_comments, .feedbackcomments, .feedback-comments, " +
                "[data-region='feedback-comments'], .feedback .comments, .feedback .feedbacktext",
        )
            ?.takeUnless { numericGradePattern.containsMatchIn(it) }

    val submittedFiles = document.select(
        ".submissionstatussubmitted .files a, " +
            ".assignsubmission_file .files a, " +
            ".submissionstatustable a[href*='/pluginfile.php']",
    ).mapNotNull { link ->
        cleanPhyVlabText(link.text()).takeIf(String::isNotBlank)?.let(::PhyVlabSubmissionFile)
    }.distinctBy(PhyVlabSubmissionFile::fileName)
    val submissionDate = submissionDateCandidate?.takeIf {
        activity.completed ||
            submittedFiles.isNotEmpty() ||
            submissionStatusIndicatesSubmission(submissionStatus)
    }

    val form = document.select("form").firstOrNull { form ->
        val action = form.attr("action")
        action.contains("/mod/assign/editsubmission.php") ||
            form.attr("id").contains("submission", ignoreCase = true) ||
            (action.contains("/mod/assign/view.php") && form.selectFirst("input[name*='filemanager']") != null)
    }
    val formFields = form?.select("input[type='hidden'][name]")
        ?.mapNotNull { input ->
            val name = input.attr("name").trim()
            name.takeIf(String::isNotBlank)?.let { it to input.attr("value") }
        }
        ?.toMap()
        .orEmpty()
    val fileManager = form?.selectFirst("input[name*='filemanager']")
        ?: document.selectFirst("input[name*='filemanager']")
    val fileManagerNode = form?.selectFirst(".filemanager")
        ?: document.selectFirst(".filemanager")
    val fileManagerFieldName = fileManager?.attr("name")
        ?.trim()
        ?.takeIf(String::isNotBlank)
    val fileManagerMetadata = parsePhyVlabFileManagerMetadata(document, fileManagerFieldName)
    val sesskey = formFields["sesskey"]
        ?.takeIf(String::isNotBlank)
        ?: parsePhyVlabSesskeyFromAssignment(html)
    val fallbackEditUrl = "$PHYVLAB_ORIGIN/mod/assign/view.php?id=${activity.id}&action=editsubmission"
    val formUrl = form?.attr("action")
        ?.takeIf(String::isNotBlank)
        ?.let(::resolvePhyVlabUrl)
        ?: fallbackEditUrl
    val context = if (!sesskey.isNullOrBlank() &&
        (fileManager != null || fileManagerNode != null)
    ) {
        PhyVlabAssignmentSubmissionContext(
            formUrl = formUrl,
            formFields = formFields,
            sesskey = sesskey,
            draftItemId = fileManager?.attr("value")?.trim()?.takeIf(String::isNotBlank)
                ?: fileManagerNode?.attr("data-itemid")?.trim()?.takeIf(String::isNotBlank)
                ?: fileManagerMetadata.itemId,
            contextId = fileManagerNode?.attr("data-contextid")?.trim()?.takeIf(String::isNotBlank)
                ?: fileManagerMetadata.contextId,
            clientId = fileManagerNode?.attr("data-clientid")?.trim()?.takeIf(String::isNotBlank)
                ?: fileManagerMetadata.clientId,
            repositoryId = fileManagerNode?.attr("data-repositoryid")?.trim()?.takeIf(String::isNotBlank)
                ?: fileManagerNode?.attr("data-repo-id")?.trim()?.takeIf(String::isNotBlank)
                ?: fileManagerMetadata.repositoryId,
        )
    } else {
        null
    }

    val editSubmissionUrl = document.select("a[href]")
        .mapNotNull { resolvePhyVlabUrl(it.attr("href")) }
        .firstOrNull { it.contains("/mod/assign/editsubmission.php") || it.contains("action=editsubmission") }
    phyVlabDebug(
        "assignment structure forms=${document.select("form").size} " +
            "fileManagers=${document.select(".filemanager").size} " +
            "fileManagerInputs=${document.select("input[name*='filemanager']").size} " +
            "editLinks=${document.select("a[href*='editsubmission']").size} " +
            "labels=${labeledValues.size}",
    )

    val detail = PhyVlabAssignmentDetail(
        description = normalizePhyVlabDatesInText(description),
        submissionStatus = normalizePhyVlabDatesInText(submissionStatus),
        submissionDateText = submissionDate?.let(::normalizePhyVlabDateText),
        submissionDateTimestamp = submissionDate?.let(::parsePhyVlabDateTimestamp),
        gradingStatus = gradingStatus?.let(::normalizePhyVlabDatesInText),
        gradeText = grade?.let(::normalizePhyVlabDatesInText),
        feedbackText = feedback?.let(::normalizePhyVlabDatesInText),
        submittedFiles = submittedFiles,
        // 没有 context/client 时上传接口无法可靠定位草稿，只显示网页备用入口，
        // 不让 UI 暴露一个注定失败的原生上传按钮。
        canSubmit = context?.isUploadReady == true,
    )
    // 活动标题由课程页解析结果提供；详情页只要有主体/状态/表单之一即可视为成功。
    if (detail.description.isBlank() && detail.submissionStatus.isBlank() &&
        detail.gradeText.isNullOrBlank() && context == null && document.body().text().isBlank()
    ) {
        return PhyVlabParseResult.Failure("assignment-detail")
    }
    phyVlabDebug(
        "parse assignment detail hasDescription=${detail.description.isNotBlank()} " +
            "hasStatus=${detail.submissionStatus.isNotBlank()} hasGrade=${!detail.gradeText.isNullOrBlank()} " +
            "submittedFiles=${detail.submittedFiles.size} canSubmit=${detail.canSubmit}",
    )
    return PhyVlabParseResult.Success(PhyVlabParsedAssignmentPage(detail, context, editSubmissionUrl))
}

/**
 * Moodle 主题通常把 filemanager 的上下文参数放进初始化脚本，而不是放在
 * `.filemanager` 节点的 `data-*` 属性上。这里只读取标量参数和上传仓库 id；
 * 不执行脚本，也不把页面里的短期凭据写入日志或领域模型。
 */
private fun parsePhyVlabFileManagerMetadata(
    document: com.fleeksoft.ksoup.nodes.Document,
    fieldName: String?,
): PhyVlabFileManagerMetadata {
    val scripts = document.select("script")
        .map { it.html() }
        .filter { script ->
            fieldName.isNullOrBlank() ||
                script.contains(fieldName, ignoreCase = true) ||
                script.contains("filemanager", ignoreCase = true)
        }
    if (scripts.isEmpty()) return PhyVlabFileManagerMetadata(null, null, null, null)

    val scriptText = scripts.joinToString("\n")
    return PhyVlabFileManagerMetadata(
        itemId = parsePhyVlabJsScalar(scriptText, "itemid", "itemId"),
        contextId = parsePhyVlabJsScalar(scriptText, "contextid", "context_id", "contextId", "context"),
        clientId = parsePhyVlabJsScalar(scriptText, "client_id", "clientId"),
        repositoryId = parsePhyVlabRepositoryId(scriptText),
    )
}

/**
 * 读取 Moodle 初始化对象中的简单字符串/数字字段。页面脚本可能使用 JSON、
 * 单引号 JavaScript 或不带引号的对象键，因此这里使用受限正则而不是执行脚本。
 */
private fun parsePhyVlabJsScalar(text: String, vararg keys: String): String? {
    keys.forEach { key ->
        val quotedKey = Regex.escape(key)
        val pattern = Regex(
            """(?i)(?:[\"']$quotedKey[\"']|$quotedKey)\s*[:=]\s*(?:[\"']([^\"']*)[\"']|([A-Za-z0-9_.:/-]+))""",
        )
        val match = pattern.find(text) ?: return@forEach
        val value = match.groupValues.getOrNull(1)?.ifBlank {
            match.groupValues.getOrNull(2).orEmpty()
        }.orEmpty().trim()
        if (value.isNotBlank()) return value
    }
    return null
}

/**
 * 从 `repositories` 初始化数据中选择 upload 仓库。Moodle 既可能把仓库 id
 * 作为对象键，也可能放在仓库对象的 `id` 字段里；若现场没有明确的 upload
 * 项则不猜测其他仓库，交给上传层现有的默认值兜底。
 */
private fun parsePhyVlabRepositoryId(text: String): String? {
    parsePhyVlabJsScalar(text, "repositoryid", "repository_id", "repo_id")?.let { return it }
    val repositoriesStart = Regex(
        """(?i)(?:[\"']?repositories[\"']?)\s*[:=]""",
    ).find(text)?.range?.last ?: return null
    val repositoriesText = text.substring(repositoriesStart).take(12000)

    val objectIdBeforeType = Regex(
        """(?is)\{[^{}]{0,900}?(?:[\"']?id[\"']?\s*[:=]\s*[\"']?(\d+)[\"']?)[^{}]{0,900}?(?:[\"']?type[\"']?\s*[:=]\s*[\"']?upload[\"']?)""",
    ).find(repositoriesText)?.groupValues?.getOrNull(1)
    if (!objectIdBeforeType.isNullOrBlank()) return objectIdBeforeType

    val objectTypeBeforeId = Regex(
        """(?is)\{[^{}]{0,900}?(?:[\"']?type[\"']?\s*[:=]\s*[\"']?upload[\"']?)[^{}]{0,900}?(?:[\"']?id[\"']?\s*[:=]\s*[\"']?(\d+)[\"']?)""",
    ).find(repositoriesText)?.groupValues?.getOrNull(1)
    if (!objectTypeBeforeId.isNullOrBlank()) return objectTypeBeforeId

    val keyedUpload = Regex(
        """(?is)(?:[\"'](\d+)[\"']|\b(\d+))\s*[:=]\s*\{[^{}]{0,900}?(?:[\"']?type[\"']?\s*[:=]\s*[\"']?upload[\"']?)""",
    ).find(repositoriesText)
    return keyedUpload?.groupValues?.drop(1)?.firstOrNull(String::isNotBlank)
}

private fun firstDetailText(
    document: com.fleeksoft.ksoup.nodes.Document,
    selector: String,
): String? = document.select(selector)
    .asSequence()
    .map { cleanPhyVlabText(it.text()) }
    .firstOrNull { it.isNotBlank() }

private fun cleanPhyVlabText(value: String): String = value
    .replace("\u00a0", " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun parsePhyVlabSesskeyFromAssignment(html: String): String? = Regex(
    "[\\\"']sesskey[\\\"']\\s*[:=]\\s*[\\\"']([^\\\"']+)[\\\"']",
)
    .find(html)
    ?.groupValues
    ?.getOrNull(1)
    ?.takeIf(String::isNotBlank)

private fun resolvePhyVlabUrl(href: String): String? {
    val value = href.trim()
    return when {
        value.startsWith("https://", ignoreCase = true) -> value
        value.startsWith("/") -> "$PHYVLAB_ORIGIN$value"
        else -> null
    }
}

private fun activityDateText(
    dateRegion: com.fleeksoft.ksoup.nodes.Element?,
    label: String,
): String? = dateRegion
    ?.select("strong")
    ?.firstOrNull { it.text().trim().removeSuffix(":").removeSuffix("：") == label }
    ?.parent()
    ?.text()
    ?.replace("\u00a0", " ")
    ?.trim()
    ?.let { text ->
        when {
            text.startsWith("$label：") -> text.removePrefix("$label：").trim()
            text.startsWith("$label:") -> text.removePrefix("$label:").trim()
            else -> text
        }
            .takeIf(String::isNotBlank)
            ?.let(::normalizePhyVlabDateText)
    }

private val phyVlabDatePattern = Regex(
    "(\\d{4})年(\\d{1,2})月(\\d{1,2})日(?:\\s+[^\\d\\s]+)?\\s+(\\d{1,2}):(\\d{2})",
)

private val phyVlabWeekdayPattern = Regex(
    "\\b(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b",
    RegexOption.IGNORE_CASE,
)

/**
 * 统一 Moodle 页面日期：去掉服务器附带的英文星期，并固定为北京时间的
 * `yyyy年MM月dd日 HH:mm`。无法识别的非日期文本原样保留，避免把作业要求
 * 误改为空字符串。
 */
private fun normalizePhyVlabDateText(value: String): String {
    val match = phyVlabDatePattern.find(value) ?: return value
    val (year, month, day, hour, minute) = match.destructured
    return buildString {
        append(year)
        append("年")
        append(month.padStart(2, '0'))
        append("月")
        append(day.padStart(2, '0'))
        append("日 ")
        append(hour.padStart(2, '0'))
        append(":")
        append(minute.padStart(2, '0'))
    }
}

private fun normalizePhyVlabDayText(value: String): String = value
    .replace(phyVlabWeekdayPattern, "")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun normalizePhyVlabDatesInText(value: String): String =
    phyVlabDatePattern.replace(value) { match -> normalizePhyVlabDateText(match.value) }

private fun submissionStatusIndicatesSubmission(value: String): Boolean {
    val status = value.trim().lowercase()
    return status.contains("已提交") ||
        (status.contains("submitted") && !status.contains("not submitted"))
}

private fun parsePhyVlabDateTimestamp(value: String): Long? = runCatching {
    val match = phyVlabDatePattern.find(value) ?: return null
    val (year, month, day, hour, minute) = match.destructured
    LocalDateTime(
        year.toInt(),
        month.toInt(),
        day.toInt(),
        hour.toInt(),
        minute.toInt(),
    ).toInstant(TimeZone.of("Asia/Shanghai")).epochSeconds
}.getOrNull()

/**
 * 解析日历月视图中的事件。事件名称、日期与链接都来自 `td.day.hasevent [data-region='event-item']`。
 */
fun parsePhyVlabEvents(html: String): PhyVlabParseResult<List<PhyVlabEvent>> {
    val document = Ksoup.parse(html)
    val cells = document.select("td.day.hasevent")
    phyVlabDebug(
        "parse events cells=${cells.size} eventItems=${document.select("[data-region='event-item']").size} " +
            "eventLinks=${document.select("a[data-action='view-event']").size} " +
            "assignLinks=${document.select("a[href*='/mod/assign/view.php']").size}",
    )
    if (cells.isEmpty()) return PhyVlabParseResult.Success(emptyList())
    val events = cells.flatMap { cell ->
        val dayTimestamp = cell.attr("data-day-timestamp").toLongOrNull() ?: return@flatMap emptyList()
        val dayText = normalizePhyVlabDayText(cell.attr("data-title").substringBefore(" 事件").trim())
        cell.select("[data-region='event-item']").mapNotNull { event ->
            val link = event.selectFirst("a[data-action='view-event']")
            if (link == null) return@mapNotNull null
            val href = link.attr("href").orEmpty()
            val eventUrl = when {
                href.startsWith("https://", ignoreCase = true) -> href
                href.startsWith("/") -> "$PHYVLAB_ORIGIN$href"
                else -> null
            }
            PhyVlabEvent(
                id = link.attr("data-event-id").ifBlank {
                    event.attr("data-event-id").ifBlank { "$dayTimestamp-${event.hashCode()}" }
                },
                title = link.selectFirst(".eventname")?.text()?.trim().orEmpty(),
                dateText = dayText,
                dayTimestamp = dayTimestamp,
                eventUrl = eventUrl,
            )
        }
    }.filter { it.title.isNotBlank() }.distinctBy { it.id }
    return PhyVlabParseResult.Success(
        events.sortedWith(compareBy<PhyVlabEvent> { it.dayTimestamp }.thenBy { it.title }),
    )
}
