package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEventKind
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabSubmissionFile

private const val PHYVLAB_CACHE_VERSION = 1
private const val MAX_COURSES = 200
private const val MAX_ACTIVITIES = 5_000
private const val MAX_EVENTS = 5_000
private const val MAX_DETAILS = 5_000

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
private data class PhyVlabCachePayload(
    val version: Int = PHYVLAB_CACHE_VERSION,
    val savedAtEpochMillis: Long = 0L,
    val courses: List<CoursePayload> = emptyList(),
    val activities: List<ActivityPayload> = emptyList(),
    val events: List<EventPayload> = emptyList(),
    val assignmentDetails: List<AssignmentDetailPayload> = emptyList(),
)

@Serializable
private data class CoursePayload(
    val id: Int,
    val name: String,
    val category: String,
    val progressPercent: Int,
    val courseUrl: String,
)

@Serializable
private data class ActivityPayload(
    val id: Int,
    val courseId: Int,
    val courseName: String,
    val title: String,
    val activityType: String,
    val activityUrl: String,
    val openText: String? = null,
    val openTimestamp: Long? = null,
    val dueText: String? = null,
    val dueTimestamp: Long? = null,
    val completed: Boolean = false,
)

@Serializable
private data class EventPayload(
    val id: String,
    val title: String,
    val dateText: String,
    val dayTimestamp: Long,
    val eventUrl: String? = null,
    val kind: String = PhyVlabEventKind.DEADLINE.name,
)

@Serializable
private data class AssignmentDetailPayload(
    val courseId: Int,
    val activityId: Int,
    val description: String = "",
    val submissionStatus: String = "",
    val submissionDateText: String? = null,
    val submissionDateTimestamp: Long? = null,
    val gradingStatus: String? = null,
    val gradeText: String? = null,
    val feedbackText: String? = null,
    val submittedFiles: List<String> = emptyList(),
)

internal fun encodePhyVlabCache(snapshot: PhyVlabCacheSnapshot): String = json.encodeToString(
    PhyVlabCachePayload(
        savedAtEpochMillis = snapshot.savedAtEpochMillis,
        courses = snapshot.courses.map { course ->
            CoursePayload(
                id = course.id,
                name = course.name,
                category = course.category,
                progressPercent = course.progressPercent,
                courseUrl = course.courseUrl,
            )
        },
        activities = snapshot.activities.map { activity ->
            ActivityPayload(
                id = activity.id,
                courseId = activity.courseId,
                courseName = activity.courseName,
                title = activity.title,
                activityType = activity.activityType,
                activityUrl = activity.activityUrl,
                openText = activity.openText,
                openTimestamp = activity.openTimestamp,
                dueText = activity.dueText,
                dueTimestamp = activity.dueTimestamp,
                completed = activity.completed,
            )
        },
        events = snapshot.events.map { event ->
            EventPayload(
                id = event.id,
                title = event.title,
                dateText = event.dateText,
                dayTimestamp = event.dayTimestamp,
                eventUrl = event.eventUrl,
                kind = event.kind.name,
            )
        },
        assignmentDetails = snapshot.assignmentDetails.map { cached ->
            AssignmentDetailPayload(
                courseId = cached.courseId,
                activityId = cached.activityId,
                description = cached.detail.description,
                submissionStatus = cached.detail.submissionStatus,
                submissionDateText = cached.detail.submissionDateText,
                submissionDateTimestamp = cached.detail.submissionDateTimestamp,
                gradingStatus = cached.detail.gradingStatus,
                gradeText = cached.detail.gradeText,
                feedbackText = cached.detail.feedbackText,
                submittedFiles = cached.detail.submittedFiles.map(PhyVlabSubmissionFile::fileName),
            )
        },
    ),
)

internal fun decodePhyVlabCache(value: String): PhyVlabCacheSnapshot? = runCatching {
    val payload = json.decodeFromString<PhyVlabCachePayload>(value)
    require(payload.version == PHYVLAB_CACHE_VERSION)
    require(payload.courses.size <= MAX_COURSES)
    require(payload.activities.size <= MAX_ACTIVITIES)
    require(payload.events.size <= MAX_EVENTS)
    require(payload.assignmentDetails.size <= MAX_DETAILS)

    val courses = payload.courses.map { course ->
        require(course.name.isNotBlank())
        require(course.courseUrl.isNotBlank())
        PhyVlabCourse(
            id = course.id,
            name = course.name,
            category = course.category,
            progressPercent = course.progressPercent.coerceIn(0, 100),
            courseUrl = course.courseUrl,
        )
    }
    require(courses.map(PhyVlabCourse::id).distinct().size == courses.size)

    val activities = payload.activities.map { activity ->
        require(activity.title.isNotBlank())
        require(activity.activityUrl.isNotBlank())
        PhyVlabActivity(
            id = activity.id,
            courseId = activity.courseId,
            courseName = activity.courseName,
            title = activity.title,
            activityType = activity.activityType,
            activityUrl = activity.activityUrl,
            openText = activity.openText,
            openTimestamp = activity.openTimestamp,
            dueText = activity.dueText,
            dueTimestamp = activity.dueTimestamp,
            completed = activity.completed,
        )
    }
    require(activities.map { it.courseId to it.id }.distinct().size == activities.size)

    val events = payload.events.map { event ->
        require(event.id.isNotBlank())
        require(event.title.isNotBlank())
        PhyVlabEvent(
            id = event.id,
            title = event.title,
            dateText = event.dateText,
            dayTimestamp = event.dayTimestamp,
            eventUrl = event.eventUrl,
            kind = runCatching { PhyVlabEventKind.valueOf(event.kind) }
                .getOrDefault(PhyVlabEventKind.DEADLINE),
        )
    }
    require(events.map(PhyVlabEvent::id).distinct().size == events.size)

    val assignmentDetails = payload.assignmentDetails.map { cached ->
        // 旧版本可能已经把开放时间误存成了“提交时间”。缓存不能凭一个孤立日期
        // 继续证明作业已提交；必须有提交文件或明确的已提交状态。
        val hasTrustedSubmission = cached.submittedFiles.isNotEmpty() ||
            cached.submissionStatusIndicatesSubmission()
        PhyVlabCachedAssignmentDetail(
            courseId = cached.courseId,
            activityId = cached.activityId,
            detail = PhyVlabAssignmentDetail(
                description = cached.description,
                submissionStatus = cached.submissionStatus,
                submissionDateText = cached.submissionDateText.takeIf { hasTrustedSubmission },
                submissionDateTimestamp = cached.submissionDateTimestamp.takeIf { hasTrustedSubmission },
                gradingStatus = cached.gradingStatus,
                gradeText = cached.gradeText,
                feedbackText = cached.feedbackText,
                submittedFiles = cached.submittedFiles.map(::PhyVlabSubmissionFile),
                // 上传所需的短期表单上下文从不进缓存；离线详情不能显示可提交按钮。
                canSubmit = false,
            ),
        )
    }
    require(assignmentDetails.map { it.courseId to it.activityId }.distinct().size == assignmentDetails.size)

    PhyVlabCacheSnapshot(
        courses = courses,
        activities = activities,
        events = events,
        assignmentDetails = assignmentDetails,
        savedAtEpochMillis = payload.savedAtEpochMillis,
    )
}.getOrNull()

private fun AssignmentDetailPayload.submissionStatusIndicatesSubmission(): Boolean {
    val status = submissionStatus.trim().lowercase()
    return status.contains("已提交") ||
        (status.contains("submitted") && !status.contains("not submitted"))
}
