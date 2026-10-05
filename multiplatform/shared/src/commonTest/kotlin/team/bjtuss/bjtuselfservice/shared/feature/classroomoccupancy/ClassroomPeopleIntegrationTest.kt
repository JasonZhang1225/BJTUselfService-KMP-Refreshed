package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.*
import team.bjtuss.bjtuselfservice.shared.data.classroom.*
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.*
import team.bjtuss.bjtuselfservice.shared.domain.classroom.*
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.*

class ClassroomPeopleIntegrationTest {
    private val official = object : ClassroomOccupancyRepository {
        override suspend fun fetchOccupancy(week: Int, buildingId: String, semesterId: String?) =
            ClassroomOccupancyResult.Success(listOf(ClassroomOccupancy("SY101", 90, mapOf((1 to 1) to OccupancyKind.OTHER))))
        override suspend fun fetchSemesters() = SemesterOptions(null, emptyList())
        override suspend fun fetchWeekDates() = emptyMap<String, List<OccupancyWeekDate>>()
    }
    private fun result() = ClassroomFetchResult.Success(ClassroomBuildingInfo("思源楼", "09:00", "09:05", listOf(ClassroomCapacity("SY101", 20.0, 18, 90))))

    @Test fun supplementalFailureKeepsOfficialRowsAndClassification(): Unit = runBlocking {
        var fail = false
        val model = ClassroomOccupancyScreenModel(official, object : ClassroomRepository {
            override suspend fun fetchBuildingInfo(buildingName: String): ClassroomFetchResult =
                if (fail) ClassroomFetchResult.Failure(ClassroomFetchFailure.NETWORK) else result()
        })
        model.selectBuilding(OccupancyBuilding("1", "思源楼"))
        model.refresh()
        val query = model.state.value.queryState
        model.refreshPeople()
        assertEquals(18, model.state.value.people.single().used)
        fail = true
        model.refreshPeople()
        assertEquals(query, model.state.value.queryState)
        assertTrue(model.state.value.people.isEmpty())
        assertFalse(model.state.value.peopleLoading)
    }

    @Test fun responseForOldBuildingDoesNotLeakIntoNewBuilding(): Unit = runBlocking {
        val response = CompletableDeferred<ClassroomFetchResult>()
        val model = ClassroomOccupancyScreenModel(official, object : ClassroomRepository {
            override suspend fun fetchBuildingInfo(buildingName: String) = response.await()
        })
        model.selectBuilding(OccupancyBuilding("1", "思源楼"))
        val request = launch { model.refreshPeople() }
        yield()
        model.selectBuilding(OccupancyBuilding("2", "逸夫楼"))
        response.complete(result())
        request.join()
        assertEquals("逸夫楼", model.state.value.selectedBuilding?.name)
        assertTrue(model.state.value.people.isEmpty())
        assertFalse(model.state.value.peopleLoading)
    }
}
