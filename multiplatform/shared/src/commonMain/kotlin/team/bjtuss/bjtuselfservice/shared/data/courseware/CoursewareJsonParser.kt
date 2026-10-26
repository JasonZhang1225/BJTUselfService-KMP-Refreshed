package team.bjtuss.bjtuselfservice.shared.data.courseware

import team.bjtuss.bjtuselfservice.shared.data.homework.StrictJsonValue
import team.bjtuss.bjtuselfservice.shared.data.homework.arrayOrBlank
import team.bjtuss.bjtuselfservice.shared.data.homework.asObject
import team.bjtuss.bjtuselfservice.shared.data.homework.boolean
import team.bjtuss.bjtuselfservice.shared.data.homework.hasSuccessStatus
import team.bjtuss.bjtuselfservice.shared.data.homework.int
import team.bjtuss.bjtuselfservice.shared.data.homework.long
import team.bjtuss.bjtuselfservice.shared.data.homework.parseStrictJsonObject
import team.bjtuss.bjtuselfservice.shared.data.homework.string
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNodeKind
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackDetail
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackLesson
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStreamKind

sealed interface CoursewareJsonParseResult<out T> {
    data class Success<T>(val value: T) : CoursewareJsonParseResult<T>
    data class Failure(val field: String) : CoursewareJsonParseResult<Nothing>
}

data class CoursewareDownloadTicket(
    val url: String,
    val downloadType: String,
)

fun parseCoursewareChildren(
    body: String,
    courseId: Int,
): CoursewareJsonParseResult<List<CoursewareNode>> = parseObject(body) { root ->
    // STATUS="2" 表示该课程当前没有课件资源（resList/bagList 均为空串），是合法空
    // 结果而非错误，与作业列表的“没有数据”容错对齐（原 Android 默认值容忍）。
    if (root.string("STATUS") == "2") return@parseObject CoursewareJsonParseResult.Success(emptyList())
    if (!root.hasSuccessStatus()) return@parseObject CoursewareJsonParseResult.Failure("STATUS")
    val folders = root.arrayOrBlank("bagList")
        ?: return@parseObject CoursewareJsonParseResult.Failure("bagList")
    val resources = root.arrayOrBlank("resList")
        ?: return@parseObject CoursewareJsonParseResult.Failure("resList")
    val nodes = mutableListOf<CoursewareNode>()
    for ((index, element) in folders.withIndex()) {
        val item = element.asObject()
            ?: return@parseObject CoursewareJsonParseResult.Failure("bagList[$index]")
        val id = item.int("id")
            ?: return@parseObject CoursewareJsonParseResult.Failure("bagList[$index].id")
        val name = item.string("bag_name").orEmpty()
        if (name.isBlank()) return@parseObject CoursewareJsonParseResult.Failure("bagList[$index].bag_name")
        nodes += CoursewareNode(
            id = id,
            courseId = courseId,
            name = name,
            kind = CoursewareNodeKind.FOLDER,
        )
    }
    for ((index, element) in resources.withIndex()) {
        val item = element.asObject()
            ?: return@parseObject CoursewareJsonParseResult.Failure("resList[$index]")
        val id = item.int("resId")
            ?: return@parseObject CoursewareJsonParseResult.Failure("resList[$index].resId")
        val name = item.string("rpName").orEmpty()
        val rpId = item.string("rpId").orEmpty()
        if (name.isBlank() || rpId.isBlank()) {
            return@parseObject CoursewareJsonParseResult.Failure("resList[$index].identity")
        }
        nodes += CoursewareNode(
            id = id,
            courseId = courseId,
            name = name,
            kind = CoursewareNodeKind.RESOURCE,
            rpId = rpId,
            extension = item.string("extName").orEmpty(),
            size = item.string("rpSize").orEmpty(),
            teacherName = item.string("teacherName").orEmpty(),
            inputTime = item.string("inputTime").orEmpty(),
            downloadCount = item.int("downloadNum") ?: 0,
            // 字段缺失时沿用旧行为（允许下载）；只有明确给出非 "2" 才视为未开放。
            downloadAllowed = item.string("stu_download")?.let { it.trim() == "2" } ?: true,
        )
    }
    CoursewareJsonParseResult.Success(nodes)
}

fun parseCoursewareDownloadTicket(body: String): CoursewareJsonParseResult<CoursewareDownloadTicket> =
    parseObject(body) { root ->
        val accepted = root.boolean("flag") ?: false
        val url = root.string("rpUrl").orEmpty()
        if (!accepted || url.isBlank()) {
            CoursewareJsonParseResult.Failure("downloadTicket")
        } else {
            CoursewareJsonParseResult.Success(
                CoursewareDownloadTicket(
                    url = url,
                    downloadType = root.string("download_type").orEmpty(),
                ),
            )
        }
    }

/**
 * `queryRpInfoById` 给出的服务器存储路径。老师未开放下载时 `download.shtml` 直接 500，
 * 但原文件（`RP_PATH_1`）和在线预览用的转换 PDF（`RP_PATH_2`）仍可从 `/ve/pdf/rp/` 读到。
 */
data class CoursewareStoredPaths(
    val originalPath: String,
    val previewPath: String,
)

fun parseCoursewareStoredPaths(body: String): CoursewareJsonParseResult<CoursewareStoredPaths> =
    parseObject(body) { root ->
        if (!root.hasSuccessStatus()) return@parseObject CoursewareJsonParseResult.Failure("STATUS")
        val info = root["resMap"].asObject()
            ?: return@parseObject CoursewareJsonParseResult.Failure("resMap")
        val original = info.string("RP_PATH_1").orEmpty().trim()
        val preview = info.string("RP_PATH_2").orEmpty().trim()
        if (original.isBlank() && preview.isBlank()) {
            CoursewareJsonParseResult.Failure("resMap.RP_PATH")
        } else {
            CoursewareJsonParseResult.Success(CoursewareStoredPaths(original, preview))
        }
    }

/** 课程平台页内联脚本里的 `var uId = "…"`；回放详情接口必须带上它，否则返回“出现异常”。 */
fun parseCoursePlatformUserId(html: String): String? =
    Regex("""var\s+uId\s*=\s*["'](\d{1,18})["']""").find(html)?.groupValues?.get(1)

fun parsePlaybackLessons(body: String, courseId: Int): CoursewareJsonParseResult<List<PlaybackLesson>> =
    parseObject(body) { root ->
        // STATUS="2"：这门课没有录播课节。
        if (root.string("STATUS") == "2") return@parseObject CoursewareJsonParseResult.Success(emptyList())
        if (!root.hasSuccessStatus()) return@parseObject CoursewareJsonParseResult.Failure("STATUS")
        val items = root.arrayOrBlank("courseSchedList")
            ?: return@parseObject CoursewareJsonParseResult.Failure("courseSchedList")
        val lessons = mutableListOf<PlaybackLesson>()
        for ((index, element) in items.withIndex()) {
            val item = element.asObject()
                ?: return@parseObject CoursewareJsonParseResult.Failure("courseSchedList[$index]")
            val id = item.long("id")
                ?: return@parseObject CoursewareJsonParseResult.Failure("courseSchedList[$index].id")
            val videoId = item.string("videoId")
                ?: item["params"].asObject()?.string("videoId")
            lessons += PlaybackLesson(
                id = id,
                courseId = courseId,
                name = item.string("courseScheName").orEmpty().trim(),
                timeRange = item.string("courseBetween").orEmpty().trim(),
                classroom = item.string("classRoom").orEmpty().trim(),
                summary = item.string("content").orEmpty().trim(),
                hasRecording = !videoId.isNullOrBlank() && videoId != "null",
            )
        }
        // 学校按时间正序返回；回放列表最近的一节在最上面。
        CoursewareJsonParseResult.Success(lessons.sortedByDescending { it.timeRange })
    }

fun parsePlaybackDetail(
    body: String,
    lesson: PlaybackLesson,
    acceptsStreamUrl: (String) -> Boolean,
): CoursewareJsonParseResult<PlaybackDetail> = parseObject(body) { root ->
    if (!root.hasSuccessStatus()) return@parseObject CoursewareJsonParseResult.Failure("STATUS")
    val result = root["res"].asObject() ?: return@parseObject CoursewareJsonParseResult.Failure("res")
    val streamMap = result["streamMap"].asObject()
        ?: return@parseObject CoursewareJsonParseResult.Failure("res.streamMap")
    val permitted = streamMap.string("haveStream") != "0"
    val streams = buildList {
        for ((kind, field) in PLAYBACK_STREAM_FIELDS) {
            val url = streamMap.string(field).orEmpty().trim()
            if (url.isBlank()) continue
            // 播放与下载都只走学校 HTTPS 录播服务器；地址不合规就当没有这一路。
            if (acceptsStreamUrl(url)) add(PlaybackStream(kind, url))
        }
    }
    val requested = streamMap.string("publicRpType").orEmpty()
        .split(',').map(String::trim).filter(String::isNotEmpty)
    val defaults = requested
        .mapNotNull { code -> PlaybackStreamKind.entries.firstOrNull { it.publicTypeCode == code } }
        .filter { kind -> streams.any { it.kind == kind } }
        .ifEmpty { streams.map { it.kind }.filter { it == PlaybackStreamKind.TEACHER || it == PlaybackStreamKind.SCREEN } }
        .ifEmpty { streams.take(1).map { it.kind } }
        .distinct()
        .take(2)
    CoursewareJsonParseResult.Success(
        PlaybackDetail(
            lesson = lesson,
            permitted = permitted,
            streams = if (permitted) streams else emptyList(),
            defaultKinds = if (permitted) defaults else emptyList(),
            sizeMegabytes = streamMap.string("rpSize").orEmpty().trim(),
        ),
    )
}

private val PLAYBACK_STREAM_FIELDS = listOf(
    PlaybackStreamKind.TEACHER to "teaStreamHlsUrl",
    PlaybackStreamKind.SCREEN to "vgaStreamHlsUrl",
    PlaybackStreamKind.STUDENT to "stuStreamHlsUrl",
    PlaybackStreamKind.TEACHER_CLOSE_UP to "teaCloseUpStreamHlsUrl",
    PlaybackStreamKind.STUDENT_CLOSE_UP to "stuCloseUpStreamHlsUrl",
    PlaybackStreamKind.MOVIE to "movieStreamHlsUrl",
)

private inline fun <T> parseObject(
    body: String,
    transform: (Map<String, StrictJsonValue>) -> CoursewareJsonParseResult<T>,
): CoursewareJsonParseResult<T> = try {
    val root = parseStrictJsonObject(body)
        ?: return CoursewareJsonParseResult.Failure("root")
    transform(root)
} catch (_: Exception) {
    CoursewareJsonParseResult.Failure("json")
}
