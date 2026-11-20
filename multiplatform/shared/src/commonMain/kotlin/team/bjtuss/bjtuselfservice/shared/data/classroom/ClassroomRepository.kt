package team.bjtuss.bjtuselfservice.shared.data.classroom

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomBuildingInfo
import team.bjtuss.bjtuselfservice.shared.runtime.AccountMutationLock

enum class ClassroomFetchFailure {
    NETWORK,
    PARSE,
    SECURE_CHANNEL_UNAVAILABLE,
}

sealed interface ClassroomFetchResult {
    data class Success(val info: ClassroomBuildingInfo) : ClassroomFetchResult
    data class Failure(val reason: ClassroomFetchFailure) : ClassroomFetchResult
}

interface ClassroomRepository {
    suspend fun fetchBuildingInfo(buildingName: String): ClassroomFetchResult
}

/**
 * 教室人数评估仓库。接口是公开第三方快照，不做本地缓存：
 * 数据是服务器最近一次轮询窗口的实时人数，缓存旧快照没有意义，
 * 与原 App 每次进入页面直接请求的行为一致。
 */
class DefaultClassroomRepository(
    private val remote: ClassroomRemoteDataSource,
    private val refreshScope: CoroutineScope? = null,
) : ClassroomRepository {

    private val requestsLock = AccountMutationLock()
    private val inFlight = mutableMapOf<String, Deferred<ClassroomFetchResult>>()
    private val requestMutex = Mutex()

    override suspend fun fetchBuildingInfo(buildingName: String): ClassroomFetchResult = coroutineScope {
        val request = requestsLock.withLock {
            val active = inFlight[buildingName]?.takeUnless { it.isCompleted }
            active ?: (refreshScope ?: this).async(start = CoroutineStart.LAZY) {
                requestMutex.withLock { loadBuildingInfo(buildingName) }
            }.also { pending ->
                inFlight[buildingName] = pending
                pending.invokeOnCompletion {
                    requestsLock.withLock {
                        if (inFlight[buildingName] === pending) inFlight.remove(buildingName)
                    }
                }
            }
        }
        request.await()
    }

    private suspend fun loadBuildingInfo(buildingName: String): ClassroomFetchResult = try {
        ClassroomFetchResult.Success(remote.fetchBuildingInfo(buildingName))
    } catch (error: CancellationException) {
        throw error
    } catch (error: ClassroomRemoteException) {
        ClassroomFetchResult.Failure(error.reason.toFetchFailure())
    } catch (_: Exception) {
        ClassroomFetchResult.Failure(ClassroomFetchFailure.NETWORK)
    }
}

private fun ClassroomRemoteFailure.toFetchFailure(): ClassroomFetchFailure = when (this) {
    ClassroomRemoteFailure.NETWORK -> ClassroomFetchFailure.NETWORK
    ClassroomRemoteFailure.PARSE -> ClassroomFetchFailure.PARSE
    ClassroomRemoteFailure.SECURE_CHANNEL_UNAVAILABLE ->
        ClassroomFetchFailure.SECURE_CHANNEL_UNAVAILABLE
}
