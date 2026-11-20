package team.bjtuss.bjtuselfservice.shared.feature.classroom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomFetchFailure
import team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomFetchResult
import team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomRepository
import team.bjtuss.bjtuselfservice.shared.data.classroom.DefaultClassroomRepository
import team.bjtuss.bjtuselfservice.shared.domain.classroom.CLASSROOM_BUILDINGS
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomBuildingInfo
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomCapacity
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomSortDirection
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomSortField
import team.bjtuss.bjtuselfservice.shared.runtime.AccountWork

class ClassroomScreenModelTest {
    @Test
    fun selectsBuildingAndLoadsRooms() = runBlocking {
        val model = ClassroomScreenModel(FakeRepository(successInfo()))
        model.selectBuilding("思源楼")
        val loaded = assertIs<ClassroomBuildingState.Loaded>(model.state.value.buildingState)
        assertEquals(3, loaded.info.classrooms.size)
    }

    @Test
    fun filtersFreeSeatsAndCapacity() = runBlocking {
        val model = ClassroomScreenModel(FakeRepository(successInfo()))
        model.selectBuilding("思源楼")
        model.setOnlyWithFreeSeats(true)
        model.setCapacityRange(100, null)
        assertEquals(listOf("SY103"), model.state.value.visibleClassrooms.map { it.name })
    }

    @Test
    fun sortsAndTogglesDirection() = runBlocking {
        val model = ClassroomScreenModel(FakeRepository(successInfo()))
        model.selectBuilding("思源楼")
        model.setSortField(ClassroomSortField.CAPACITY)
        assertEquals(listOf("SY102", "SY101", "SY103"), model.state.value.visibleClassrooms.map { it.name })
        model.setSortField(ClassroomSortField.CAPACITY)
        assertEquals(ClassroomSortDirection.DESCENDING, model.state.value.sortDirection)
        assertEquals(listOf("SY103", "SY101", "SY102"), model.state.value.visibleClassrooms.map { it.name })
    }

    @Test
    fun refreshFailureKeepsPreviousSnapshot() = runBlocking {
        val repository = SequenceRepository(
            ClassroomFetchResult.Success(successInfo()),
            ClassroomFetchResult.Failure(ClassroomFetchFailure.NETWORK),
        )
        val model = ClassroomScreenModel(repository)
        model.selectBuilding("思源楼")
        model.refresh()
        val failed = assertIs<ClassroomBuildingState.Failed>(model.state.value.buildingState)
        assertEquals(3, failed.cached?.classrooms?.size)
        assertEquals(3, model.state.value.visibleClassrooms.size)
    }

    @Test
    fun rejectsUnknownBuilding() {
        runBlocking {
            val model = ClassroomScreenModel(FakeRepository(successInfo()))
            assertFailsWith<IllegalArgumentException> { model.selectBuilding("不存在的楼") }
        }
    }

    @Test
    fun nameQueryUpdatesCachedVisibleListWithoutRecomputeGetter() = runBlocking {
        val model = ClassroomScreenModel(FakeRepository(successInfo()))
        model.selectBuilding("思源楼")
        model.setNameQuery("SY10")
        assertEquals(listOf("SY101", "SY102", "SY103"), model.state.value.visibleClassrooms.map { it.name })
        model.setNameQuery("SY102")
        assertEquals(listOf("SY102"), model.state.value.visibleClassrooms.map { it.name })
        // 相同 query 应是 no-op（引用可不变）
        val before = model.state.value
        model.setNameQuery("SY102")
        assertEquals(before, model.state.value)
    }

    @Test
    fun slowBuildingRequestDoesNotBlockAnotherBuildingInRepository() = runBlocking {
        val account = AccountWork()
        val syEntered = CompletableDeferred<Unit>()
        val releaseSy = CompletableDeferred<Unit>()
        try {
            val remote = object : ClassroomRemoteDataSource {
                override suspend fun fetchBuildingInfo(buildingName: String): ClassroomBuildingInfo {
                    if (buildingName == "思源楼") {
                        syEntered.complete(Unit)
                        releaseSy.await()
                    }
                    return successInfo().copy(buildingName = buildingName)
                }
            }
            val repository = DefaultClassroomRepository(
                remote = remote,
                refreshScope = account.scope(Dispatchers.Default),
            )
            val slowCaller = launch { repository.fetchBuildingInfo("思源楼") }
            withTimeout(5_000) { syEntered.await() }
            slowCaller.cancelAndJoin()

            val fastResult = withTimeout(5_000) { repository.fetchBuildingInfo("逸夫楼") }
            val loaded = assertIs<ClassroomFetchResult.Success>(fastResult)
            assertEquals("逸夫楼", loaded.info.buildingName)
        } finally {
            releaseSy.complete(Unit)
            account.stop()
            account.join()
        }
    }

    @Test
    fun olderCompletedResultCannotOverwriteNewerSnapshotOrCache() = runBlocking {
        val oldInfo = successInfo().copy(
            classrooms = listOf(ClassroomCapacity("SY101", 10.0, 9, 90)),
        )
        val newInfo = successInfo().copy(
            classrooms = listOf(ClassroomCapacity("SY999", 90.0, 81, 90)),
        )
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val repository = object : ClassroomRepository {
            override suspend fun fetchBuildingInfo(buildingName: String): ClassroomFetchResult {
                return if (++calls == 1) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                    ClassroomFetchResult.Success(oldInfo)
                } else {
                    ClassroomFetchResult.Success(newInfo)
                }
            }
        }
        val model = ClassroomScreenModel(repository)
        val first = launch { model.selectBuilding("思源楼") }
        withTimeout(5_000) { firstEntered.await() }

        model.refresh()
        val loadedAfterSecond = assertIs<ClassroomBuildingState.Loaded>(model.state.value.buildingState)
        assertEquals("SY999", loadedAfterSecond.info.classrooms.single().name)

        releaseFirst.complete(Unit)
        first.join()

        val loadedFinal = assertIs<ClassroomBuildingState.Loaded>(model.state.value.buildingState)
        assertEquals("SY999", loadedFinal.info.classrooms.single().name)
        assertEquals(listOf("SY999"), model.state.value.visibleClassrooms.map { it.name })

        model.clearSelection()
        model.selectBuilding("思源楼")
        val cachedLoaded = assertIs<ClassroomBuildingState.Loaded>(model.state.value.buildingState)
        assertEquals("SY999", cachedLoaded.info.classrooms.single().name)
    }
}

private class FakeRepository(private val info: ClassroomBuildingInfo) : ClassroomRepository {
    override suspend fun fetchBuildingInfo(buildingName: String) = ClassroomFetchResult.Success(
        info.copy(buildingName = buildingName),
    )
}

private class SequenceRepository(vararg results: ClassroomFetchResult) : ClassroomRepository {
    private val queue = results.toMutableList()
    override suspend fun fetchBuildingInfo(buildingName: String): ClassroomFetchResult = queue.removeAt(0)
}

private fun successInfo() = ClassroomBuildingInfo(
    buildingName = "思源楼",
    effectiveStart = "a",
    effectiveEnd = "b",
    classrooms = listOf(
        ClassroomCapacity("SY101", 50.0, 45, 90),
        ClassroomCapacity("SY102", 100.0, 32, 32),
        ClassroomCapacity("SY103", 25.0, 30, 120),
    ),
)
