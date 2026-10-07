package team.bjtuss.bjtuselfservice.shared.data.phyvlab

import kotlinx.coroutines.CancellationException
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabCourse
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabEvent
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

enum class PhyVlabSyncFailure {
    NETWORK,
    PARSE,
    SESSION_EXPIRED,
}

/** 物理在线列表/安排的本地快照；不包含 Cookie、sesskey 或其它短期会话字段。 */
data class PhyVlabCacheSnapshot(
    val courses: List<PhyVlabCourse>,
    val activities: List<PhyVlabActivity>,
    val events: List<PhyVlabEvent>,
    val assignmentDetails: List<PhyVlabCachedAssignmentDetail> = emptyList(),
    val savedAtEpochMillis: Long = 0L,
)

data class PhyVlabCachedAssignmentDetail(
    val courseId: Int,
    val activityId: Int,
    val detail: PhyVlabAssignmentDetail,
)

interface PhyVlabLocalDataSource {
    fun load(accountScope: String): PhyVlabCacheSnapshot?
    fun replace(accountScope: String, snapshot: PhyVlabCacheSnapshot)
}

class CacheStorePhyVlabLocalDataSource(
    private val cacheStore: CacheStore,
) : PhyVlabLocalDataSource {
    override fun load(accountScope: String): PhyVlabCacheSnapshot? =
        cacheStore.metadata(accountScope, PHYVLAB_CACHE_KEY)?.let(::decodePhyVlabCache)

    override fun replace(accountScope: String, snapshot: PhyVlabCacheSnapshot) {
        cacheStore.putMetadata(accountScope, PHYVLAB_CACHE_KEY, encodePhyVlabCache(snapshot))
    }
}

sealed interface PhyVlabCoursesResult {
    data class Success(val courses: List<PhyVlabCourse>) : PhyVlabCoursesResult
    data class Failure(val reason: PhyVlabSyncFailure) : PhyVlabCoursesResult
}

sealed interface PhyVlabActivitiesResult {
    data class Success(val activities: List<PhyVlabActivity>) : PhyVlabActivitiesResult
    data class Failure(val reason: PhyVlabSyncFailure) : PhyVlabActivitiesResult
}

sealed interface PhyVlabEventsResult {
    data class Success(val events: List<PhyVlabEvent>) : PhyVlabEventsResult
    data class Failure(val reason: PhyVlabSyncFailure) : PhyVlabEventsResult
}

sealed interface PhyVlabAssignmentDetailResult {
    data class Success(val detail: PhyVlabAssignmentDetail) : PhyVlabAssignmentDetailResult
    data class Failure(val reason: PhyVlabSyncFailure) : PhyVlabAssignmentDetailResult
}

sealed interface PhyVlabSubmissionResult {
    data object Success : PhyVlabSubmissionResult
    data class Failure(val reason: PhyVlabSyncFailure) : PhyVlabSubmissionResult
}

interface PhyVlabRepository {
    suspend fun fetchCourses(): PhyVlabCoursesResult
    suspend fun fetchCourseActivities(course: PhyVlabCourse): PhyVlabActivitiesResult
    suspend fun fetchEvents(monthTimestampSeconds: Long): PhyVlabEventsResult
    suspend fun fetchAssignmentDetail(activity: PhyVlabActivity): PhyVlabAssignmentDetailResult
    suspend fun submitAssignment(
        activity: PhyVlabActivity,
        files: List<HomeworkFileContent>,
    ): PhyVlabSubmissionResult
    suspend fun saveAssignment(activity: PhyVlabActivity, files: List<HomeworkFileContent>, removed: Set<String>): PhyVlabSubmissionResult =
        if (removed.isEmpty()) submitAssignment(activity, files) else PhyVlabSubmissionResult.Failure(PhyVlabSyncFailure.PARSE)
    suspend fun finalizationStatement(activity: PhyVlabActivity): String? = throw IllegalStateException("Finalization unavailable")
    suspend fun finalizeAssignment(activity: PhyVlabActivity, acceptedStatement: String?): PhyVlabSubmissionResult = PhyVlabSubmissionResult.Failure(PhyVlabSyncFailure.PARSE)
}

class DefaultPhyVlabRepository(
    private val remote: PhyVlabRemoteDataSource,
) : PhyVlabRepository {
    override suspend fun saveAssignment(activity: PhyVlabActivity, files: List<HomeworkFileContent>, removed: Set<String>): PhyVlabSubmissionResult =
        writeAssignment { remote.saveAssignment(activity, files, removed) }
    override suspend fun finalizationStatement(activity: PhyVlabActivity): String? = remote.finalizationStatement(activity)
    override suspend fun finalizeAssignment(activity: PhyVlabActivity, acceptedStatement: String?): PhyVlabSubmissionResult = writeAssignment { remote.finalizeAssignment(activity, acceptedStatement) }
    private suspend fun writeAssignment(operation: suspend () -> Unit): PhyVlabSubmissionResult = try {
        operation(); PhyVlabSubmissionResult.Success
    } catch (error: CancellationException) { throw error
    } catch (error: PhyVlabRemoteException) { PhyVlabSubmissionResult.Failure(error.reason.toSyncFailure())
    } catch (_: Exception) { PhyVlabSubmissionResult.Failure(PhyVlabSyncFailure.NETWORK) }
    override suspend fun fetchCourses(): PhyVlabCoursesResult = try {
        PhyVlabCoursesResult.Success(remote.fetchCourses())
    } catch (error: CancellationException) {
        throw error
    } catch (error: PhyVlabRemoteException) {
        PhyVlabCoursesResult.Failure(error.reason.toSyncFailure())
    } catch (_: Exception) {
        PhyVlabCoursesResult.Failure(PhyVlabSyncFailure.NETWORK)
    }

    override suspend fun fetchCourseActivities(course: PhyVlabCourse): PhyVlabActivitiesResult = try {
        PhyVlabActivitiesResult.Success(remote.fetchCourseActivities(course))
    } catch (error: CancellationException) {
        throw error
    } catch (error: PhyVlabRemoteException) {
        PhyVlabActivitiesResult.Failure(error.reason.toSyncFailure())
    } catch (_: Exception) {
        PhyVlabActivitiesResult.Failure(PhyVlabSyncFailure.NETWORK)
    }

    override suspend fun fetchEvents(monthTimestampSeconds: Long): PhyVlabEventsResult = try {
        PhyVlabEventsResult.Success(remote.fetchEvents(monthTimestampSeconds))
    } catch (error: CancellationException) {
        throw error
    } catch (error: PhyVlabRemoteException) {
        PhyVlabEventsResult.Failure(error.reason.toSyncFailure())
    } catch (_: Exception) {
        PhyVlabEventsResult.Failure(PhyVlabSyncFailure.NETWORK)
    }

    override suspend fun fetchAssignmentDetail(activity: PhyVlabActivity): PhyVlabAssignmentDetailResult = try {
        PhyVlabAssignmentDetailResult.Success(remote.fetchAssignmentDetail(activity))
    } catch (error: CancellationException) {
        throw error
    } catch (error: PhyVlabRemoteException) {
        PhyVlabAssignmentDetailResult.Failure(error.reason.toSyncFailure())
    } catch (_: Exception) {
        PhyVlabAssignmentDetailResult.Failure(PhyVlabSyncFailure.NETWORK)
    }

    override suspend fun submitAssignment(
        activity: PhyVlabActivity,
        files: List<HomeworkFileContent>,
    ): PhyVlabSubmissionResult = try {
        remote.submitAssignment(activity, files)
        PhyVlabSubmissionResult.Success
    } catch (error: CancellationException) {
        throw error
    } catch (error: PhyVlabRemoteException) {
        PhyVlabSubmissionResult.Failure(error.reason.toSyncFailure())
    } catch (_: Exception) {
        PhyVlabSubmissionResult.Failure(PhyVlabSyncFailure.NETWORK)
    }
}

private fun PhyVlabRemoteFailure.toSyncFailure(): PhyVlabSyncFailure = when (this) {
    PhyVlabRemoteFailure.NETWORK -> PhyVlabSyncFailure.NETWORK
    PhyVlabRemoteFailure.PARSE -> PhyVlabSyncFailure.PARSE
    PhyVlabRemoteFailure.SESSION_EXPIRED -> PhyVlabSyncFailure.SESSION_EXPIRED
}

private const val PHYVLAB_CACHE_KEY = "phyvlab_snapshot_v1"
