package team.bjtuss.bjtuselfservice.shared.feature.grade

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeRepository
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSnapshot
import team.bjtuss.bjtuselfservice.shared.data.grade.GradeSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.grade.CourseType
import team.bjtuss.bjtuselfservice.shared.domain.grade.Grade
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeInfoResult
import team.bjtuss.bjtuselfservice.shared.domain.grade.GradeSortOrder
import team.bjtuss.bjtuselfservice.shared.domain.grade.courseTypeOfGrade
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeRecorder

class GradeScreenModelTest {
    @Test
    fun initializationShowsCacheThenAppliesNetworkSnapshot() = runBlocking {
        val repository = FakeRepository(
            loaded = GradeSnapshot(listOf(grade(1, score = "B,79")), setOf(1)),
            refreshed = GradeRefreshResult.Success(
                GradeSnapshot(listOf(grade(101, score = "A,95")), setOf(101)),
            ),
        )
        val model = GradeScreenModel(repository)

        model.initialize()

        assertEquals(GradeContentSource.NETWORK, model.state.value.source)
        assertEquals(listOf(101), model.state.value.grades.map(Grade::id))
        assertEquals(setOf(101), model.state.value.selectedGradeIds)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun defaultAndReverseUseSchoolRowOrderAcrossSemesters() = runBlocking {
        val schoolRows = listOf(
            grade(7, semester = "2025-2026-2"),
            grade(3, semester = "2025-2026-1"),
            grade(8, semester = "2025-2026-2"),
        )
        val snapshot = GradeSnapshot(schoolRows, emptySet())
        val model = GradeScreenModel(
            FakeRepository(loaded = snapshot, refreshed = GradeRefreshResult.Success(snapshot)),
        )
        model.initialize(refreshFromNetwork = false)

        assertEquals(GradeSortOrder.ORIGINAL, model.state.value.sortOrder)
        assertEquals(listOf(7, 3, 8), model.state.value.visibleGrades.map(Grade::id))
        model.setSortOrder(GradeSortOrder.ORIGINAL_REVERSED)
        assertEquals(listOf(8, 3, 7), model.state.value.visibleGrades.map(Grade::id))
        model.setSortOrder(GradeSortOrder.ORIGINAL)
        assertEquals(listOf(7, 3, 8), model.state.value.visibleGrades.map(Grade::id))
        model.setSortOrder(GradeSortOrder.DESCENDING)
        model.selectSortCategory(byScore = false)
        assertEquals(GradeSortOrder.ORIGINAL, model.state.value.sortOrder)
    }

    @Test
    fun refreshFailureKeepsCacheAndExposesRetryState() = runBlocking {
        val cached = GradeSnapshot(listOf(grade(1)), emptySet())
        val model = GradeScreenModel(
            FakeRepository(
                loaded = cached,
                refreshed = GradeRefreshResult.Failure(cached, GradeSyncFailure.NETWORK),
            ),
        )

        model.initialize()

        assertEquals(GradeContentSource.CACHE, model.state.value.source)
        assertEquals(GradeSyncFailure.NETWORK, model.state.value.failure)
        assertEquals(1, model.state.value.grades.size)
    }

    @Test
    fun filteringSortingAndSelectionCalculationStayIndependent() = runBlocking {
        val first = grade(1, semester = "2025-2026-1", score = "A,95")
        val second = grade(2, semester = "2025-2026-2", score = "C,69")
        val snapshot = GradeSnapshot(listOf(first, second), emptySet())
        val repository = FakeRepository(
            loaded = snapshot,
            refreshed = GradeRefreshResult.Success(snapshot),
        )
        val model = GradeScreenModel(repository)
        model.initialize()

        // 加载后默认全选学期；取消 2025-2026-2 后只剩第一学期。
        assertEquals(setOf("2025-2026-1", "2025-2026-2"), model.state.value.selectedSemesters)
        model.toggleSemester("2025-2026-2")
        // 默认 ORIGINAL → 切到分数维度默认从高到低；再点从低到高。
        model.selectSortCategory(byScore = true)
        assertEquals(GradeSortOrder.DESCENDING, model.state.value.sortOrder)
        model.setSortOrder(GradeSortOrder.ASCENDING)
        assertEquals(GradeSortOrder.ASCENDING, model.state.value.sortOrder)
        assertEquals(listOf(1), model.state.value.visibleGrades.map(Grade::id))

        model.toggleSelectionMode()
        model.setGradeSelected(2, true)
        val calculated = assertIs<GradeInfoResult.Calculated>(model.state.value.gradeInfo)
        assertEquals(69.0, calculated.averageScore)
        assertEquals(setOf(2), model.state.value.selectedGradeIds)

        model.toggleSelectionMode()
        assertFalse(model.state.value.selectionMode)
        // 关闭自选不再清空学期筛选。
        assertEquals(setOf("2025-2026-1"), model.state.value.selectedSemesters)
    }

    @Test
    fun courseTypeCapsuleFiltersWithoutSelectionMode() = runBlocking {
        val required = grade(1, name = "C312009B必修课[01]", score = "A,90")
        val elective = grade(2, name = "X1000001任选课[01]", score = "B,80")
        val types = mapOf(
            "C312009B" to CourseType.REQUIRED,
            "X1000001" to CourseType.ELECTIVE,
        )
        val snapshot = GradeSnapshot(listOf(required, elective), emptySet(), types)
        val model = GradeScreenModel(
            FakeRepository(loaded = snapshot, refreshed = GradeRefreshResult.Success(snapshot)),
        )
        model.initialize()

        model.toggleCourseTypeIncluded(CourseType.ELECTIVE)
        assertEquals(listOf(1), model.state.value.visibleGrades.map(Grade::id))
        val calculated = assertIs<GradeInfoResult.Calculated>(model.state.value.gradeInfo)
        assertEquals(90.0, calculated.averageScore)
        assertFalse(model.state.value.selectionMode)
    }

    @Test
    fun successfulRefreshRecordsBeforeAndAfterSnapshots() = runBlocking {
        val old = grade(1)
        val updated = old.copy(id = 101, courseScore = "A,95")
        var captured: Pair<List<Grade>, List<Grade>>? = null
        val model = GradeScreenModel(
            repository = FakeRepository(
                loaded = GradeSnapshot(listOf(old), emptySet()),
                refreshed = GradeRefreshResult.Success(GradeSnapshot(listOf(updated), emptySet())),
            ),
            changeRecorder = DataChangeRecorder { before, after -> captured = before to after },
        )

        model.initialize()

        assertEquals(listOf(old), captured?.first)
        assertEquals(listOf(updated), captured?.second)
    }

    @Test
    fun unsyncedMappingStaysNullAndGradesHaveNoCategory() = runBlocking {
        val snapshot = GradeSnapshot(
            grades = listOf(grade(1, name = "C312009B高级英语视听说[04]")),
            selectedGradeIds = emptySet(),
            courseTypesByCode = null,
        )
        val model = GradeScreenModel(
            FakeRepository(loaded = snapshot, refreshed = GradeRefreshResult.Success(snapshot)),
        )

        model.initialize()

        val state = model.state.value
        assertEquals(null, state.courseTypesByCode)
        assertEquals(null, state.courseTypeOf(state.grades.single()))
        assertTrue(state.courseTypeCounts.isEmpty())
        assertEquals(CourseTypeSelectionState.NONE, state.selectionStateForType(CourseType.UNKNOWN))
    }

    @Test
    fun courseTypesFlowIntoStateAndUnknownGradesStayUnlabeled() = runBlocking {
        val required = grade(1, name = "C312009B高级英语视听说[04]")
        val unknown = grade(2, name = "英语认定")
        val snapshot = GradeSnapshot(
            grades = listOf(required, unknown),
            selectedGradeIds = emptySet(),
            courseTypesByCode = mapOf("C312009B" to CourseType.REQUIRED),
        )
        val model = GradeScreenModel(
            FakeRepository(loaded = snapshot, refreshed = GradeRefreshResult.Success(snapshot)),
        )

        model.initialize()

        val state = model.state.value
        assertEquals(mapOf("C312009B" to CourseType.REQUIRED), state.courseTypesByCode)
        assertEquals(CourseType.REQUIRED, state.courseTypeOf(required))
        assertEquals(CourseType.UNKNOWN, state.courseTypeOf(unknown))
        assertEquals(mapOf(CourseType.REQUIRED to 1, CourseType.UNKNOWN to 1), state.courseTypeCounts)
    }

    @Test
    fun typeChipsSelectAndDeselectWholeTypeAndSurviveRefresh() = runBlocking {
        val required = grade(1, name = "C312009B高级英语视听说[04]")
        val elective = grade(2, name = "S1100120A计算机导论[01]")
        val courseTypes = mapOf(
            "C312009B" to CourseType.REQUIRED,
            "S1100120A" to CourseType.ELECTIVE,
        )
        val cached = GradeSnapshot(
            grades = listOf(required, elective),
            selectedGradeIds = emptySet(),
            courseTypesByCode = courseTypes,
        )
        val refreshed = GradeSnapshot(
            grades = listOf(
                required.copy(id = 101),
                elective.copy(id = 102),
            ),
            selectedGradeIds = setOf(101),
            courseTypesByCode = courseTypes,
        )
        val model = GradeScreenModel(
            FakeRepository(loaded = cached, refreshed = GradeRefreshResult.Success(refreshed)),
        )
        model.initialize()
        model.toggleSelectionMode()

        // 刷新后选择记录恢复（101 即刷新前的 1），性质映射仍在。
        assertEquals(setOf(101), model.state.value.selectedGradeIds)
        assertTrue(model.state.value.allSelectedForType(CourseType.REQUIRED))

        // 取消必修 → 只剩任选可选；全选任选 → 只剩任选被选中。
        model.deselectByType(CourseType.REQUIRED)
        assertTrue(model.state.value.selectedGradeIds.isEmpty())

        model.selectAllByType(CourseType.ELECTIVE)
        assertEquals(setOf(102), model.state.value.selectedGradeIds)
        assertTrue(model.state.value.allSelectedForType(CourseType.ELECTIVE))

        model.deselectByType(CourseType.ELECTIVE)
        assertTrue(model.state.value.selectedGradeIds.isEmpty())
    }

    @Test
    fun enteringSelectionModeWithEmptySelectionLeavesAllTypesNone() = runBlocking {
        val required = grade(1, name = "C312009B高级英语视听说[04]")
        val elective = grade(2, name = "S1100120A计算机导论[01]")
        val snapshot = GradeSnapshot(
            grades = listOf(required, elective),
            selectedGradeIds = emptySet(),
            courseTypesByCode = mapOf(
                "C312009B" to CourseType.REQUIRED,
                "S1100120A" to CourseType.ELECTIVE,
            ),
        )
        val model = GradeScreenModel(
            FakeRepository(loaded = snapshot, refreshed = GradeRefreshResult.Success(snapshot)),
        )
        model.initialize()
        // 筛选态默认全选性质，但 selectedGradeIds 为空
        assertTrue(model.state.value.excludedCourseTypes.isEmpty())
        assertTrue(model.state.value.selectedGradeIds.isEmpty())

        model.setSelectionMode(true)
        assertTrue(model.state.value.selectionMode)
        assertTrue(model.state.value.selectedGradeIds.isEmpty())
        // 开启自选后性质绑定自选集合：0 门 → 全部 NONE（不是筛选满选）
        assertEquals(CourseTypeSelectionState.NONE, model.state.value.selectionStateForType(CourseType.REQUIRED))
        assertEquals(CourseTypeSelectionState.NONE, model.state.value.selectionStateForType(CourseType.ELECTIVE))
        assertEquals(0, model.state.value.selectedCountForType(CourseType.REQUIRED))
        assertEquals(0, model.state.value.selectedCountForType(CourseType.ELECTIVE))

        model.clearAllSelections()
        assertTrue(model.state.value.selectedGradeIds.isEmpty())
        assertEquals(CourseTypeSelectionState.NONE, model.state.value.selectionStateForType(CourseType.REQUIRED))
    }

    @Test
    fun typeChipSelectionStateCoversPartialAndUnknownCategory() = runBlocking {
        val requiredA = grade(1, name = "C312009B高级英语视听说[04]")
        val requiredB = grade(2, name = "M710033B大学物理[01]")
        val physical = grade(3, name = "P110011B体育Ⅰ[01]")
        val unknown = grade(4, name = "英语认定")
        val snapshot = GradeSnapshot(
            grades = listOf(requiredA, requiredB, physical, unknown),
            selectedGradeIds = emptySet(),
            courseTypesByCode = mapOf(
                "C312009B" to CourseType.REQUIRED,
                "M710033B" to CourseType.REQUIRED,
                "P110011B" to CourseType.PHYSICAL_EDUCATION,
            ),
        )
        val model = GradeScreenModel(
            FakeRepository(loaded = snapshot, refreshed = GradeRefreshResult.Success(snapshot)),
        )
        model.initialize()
        model.toggleSelectionMode()

        assertEquals(CourseTypeSelectionState.NONE, model.state.value.selectionStateForType(CourseType.REQUIRED))
        assertEquals(CourseTypeSelectionState.NONE, model.state.value.selectionStateForType(CourseType.UNKNOWN))
        assertEquals(1, model.state.value.courseTypeCounts[CourseType.PHYSICAL_EDUCATION])

        // 部分选中是独立状态，不能被误认为“全选”而被一键清空提示所掩盖。
        model.setGradeSelected(1, true)
        assertEquals(CourseTypeSelectionState.PARTIAL, model.state.value.selectionStateForType(CourseType.REQUIRED))

        model.selectAllByType(CourseType.REQUIRED)
        assertEquals(setOf(1, 2), model.state.value.selectedGradeIds)
        assertEquals(CourseTypeSelectionState.ALL, model.state.value.selectionStateForType(CourseType.REQUIRED))

        model.deselectByType(CourseType.REQUIRED)
        assertTrue(model.state.value.selectedGradeIds.isEmpty())
        assertEquals(CourseTypeSelectionState.NONE, model.state.value.selectionStateForType(CourseType.REQUIRED))

        // “体育” chip：独立类别的全选与取消与其余 chips 一致。
        model.selectAllByType(CourseType.PHYSICAL_EDUCATION)
        assertEquals(setOf(3), model.state.value.selectedGradeIds)
        assertEquals(CourseTypeSelectionState.ALL, model.state.value.selectionStateForType(CourseType.PHYSICAL_EDUCATION))
        model.deselectByType(CourseType.PHYSICAL_EDUCATION)
        assertTrue(model.state.value.selectedGradeIds.isEmpty())
        assertEquals(CourseTypeSelectionState.NONE, model.state.value.selectionStateForType(CourseType.PHYSICAL_EDUCATION))

        // “其他类别” chip：UNKNOWN 课程同样能全选与取消。
        model.selectAllByType(CourseType.UNKNOWN)
        assertEquals(setOf(4), model.state.value.selectedGradeIds)
        assertEquals(CourseTypeSelectionState.ALL, model.state.value.selectionStateForType(CourseType.UNKNOWN))

        model.deselectByType(CourseType.UNKNOWN)
        assertTrue(model.state.value.selectedGradeIds.isEmpty())
        assertEquals(CourseTypeSelectionState.NONE, model.state.value.selectionStateForType(CourseType.UNKNOWN))
    }

    @Test
    fun ensureProgramCourseTypesRetriesThenUpdatesMappingOnly() = runBlocking {
        val cached = GradeSnapshot(listOf(grade(1)), emptySet(), courseTypesByCode = null)
        val repository = FakeRepository(
            loaded = cached,
            refreshed = GradeRefreshResult.Success(cached),
            programTypes = mapOf("C312009B" to CourseType.REQUIRED),
            programFailuresBeforeSuccess = 2,
        )
        val model = GradeScreenModel(repository)
        model.initialize(refreshFromNetwork = false)

        model.ensureProgramCourseTypes(delayMillis = 0)

        assertEquals(3, repository.programAttempts)
        assertEquals(mapOf("C312009B" to CourseType.REQUIRED), model.state.value.courseTypesByCode)
        assertEquals(listOf(1), model.state.value.grades.map(Grade::id))
    }

    @Test
    fun ensureProgramCourseTypesSkipsWhenMappingAlreadyLoaded() = runBlocking {
        val cached = GradeSnapshot(
            listOf(grade(1)),
            emptySet(),
            courseTypesByCode = mapOf("C312009B" to CourseType.REQUIRED),
        )
        val repository = FakeRepository(
            loaded = cached,
            refreshed = GradeRefreshResult.Success(cached),
            programTypes = mapOf("X" to CourseType.ELECTIVE),
        )
        val model = GradeScreenModel(repository)
        model.initialize(refreshFromNetwork = false)

        model.ensureProgramCourseTypes()

        assertEquals(0, repository.programAttempts)
        assertEquals(mapOf("C312009B" to CourseType.REQUIRED), model.state.value.courseTypesByCode)
    }

    @Test
    fun refreshWithCachedGradesSetsChangeNoticeOnScoreUpdate() = runBlocking {
        val old = grade(1, score = "B,79")
        val updated = old.copy(id = 2, courseScore = "A,95")
        val model = GradeScreenModel(
            FakeRepository(
                loaded = GradeSnapshot(listOf(old), emptySet()),
                refreshed = GradeRefreshResult.Success(GradeSnapshot(listOf(updated), emptySet())),
            ),
        )
        model.initialize(refreshFromNetwork = false)
        model.refresh()

        val notice = model.state.value.pendingChangeNotice
        assertEquals(1, notice?.size)
        assertEquals(old.courseName, notice?.single()?.title)
        model.dismissChangeNotice()
        assertEquals(null, model.state.value.pendingChangeNotice)
    }

    @Test
    fun firstNetworkRefreshFromEmptyCacheDoesNotPopup() = runBlocking {
        val incoming = grade(1, score = "A,95")
        val model = GradeScreenModel(
            FakeRepository(
                loaded = GradeSnapshot(emptyList(), emptySet()),
                refreshed = GradeRefreshResult.Success(GradeSnapshot(listOf(incoming), emptySet())),
            ),
        )
        model.initialize()

        assertEquals(null, model.state.value.pendingChangeNotice)
        assertEquals(listOf(1), model.state.value.grades.map(Grade::id))
    }

    private class FakeRepository(
        private val loaded: GradeSnapshot,
        private val refreshed: GradeRefreshResult,
        private val programTypes: Map<String, CourseType>? = null,
        private val programFailuresBeforeSuccess: Int = 0,
    ) : GradeRepository {
        private var snapshot = loaded
        var programAttempts = 0
            private set

        override fun load(): GradeSnapshot = loaded

        override suspend fun refresh(): GradeRefreshResult = refreshed.also { result ->
            snapshot = when (result) {
                is GradeRefreshResult.Success -> result.snapshot
                is GradeRefreshResult.Failure -> result.snapshot
            }
        }

        override fun persistSelected(
            grades: List<Grade>,
            selectedGradeIds: Set<Int>,
        ): GradeSnapshot = snapshot.copy(
            grades = grades,
            selectedGradeIds = selectedGradeIds,
        ).also { snapshot = it }

        override fun clearSelectedSemesters(semesters: Set<String>): GradeSnapshot {
            val ids = snapshot.grades
                .filterNot { it.semester in semesters }
                .mapTo(mutableSetOf(), Grade::id)
            return snapshot.copy(selectedGradeIds = snapshot.selectedGradeIds intersect ids)
                .also { snapshot = it }
        }

        override fun clearSelectedCourseTypes(courseTypes: Set<CourseType>): GradeSnapshot {
            val ids = snapshot.grades
                .filterNot { grade ->
                    courseTypeOfGrade(grade, snapshot.courseTypesByCode.orEmpty()) in courseTypes
                }
                .mapTo(mutableSetOf(), Grade::id)
            return snapshot.copy(selectedGradeIds = snapshot.selectedGradeIds intersect ids)
                .also { snapshot = it }
        }

        override fun clearAllSelections(): GradeSnapshot = snapshot.copy(selectedGradeIds = emptySet())
            .also { snapshot = it }

        override suspend fun refreshProgramCourseTypes(): Map<String, CourseType>? {
            programAttempts += 1
            if (programAttempts <= programFailuresBeforeSuccess) return null
            val types = programTypes ?: return null
            snapshot = snapshot.copy(courseTypesByCode = types)
            return types
        }
    }

    private fun grade(
        id: Int,
        semester: String = "2025-2026-1",
        score: String = "B,79",
        name: String = "课程$id",
    ) = Grade(
        id = id,
        courseName = name,
        courseTeacher = "教师",
        courseScore = score,
        courseCredits = "2.0",
        courseYear = semester,
        semester = semester,
    )
}
