package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancyRepository
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancyResult
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancySyncFailure
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.SemesterOptions
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.ClassroomOccupancy
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OCCUPANCY_BUILDINGS
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyKind
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancySemester
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.SLOT_TIME_RANGES

class ClassroomOccupancyScreenModelTest {
    @Test
    fun initializeSetsDefaultsWithoutQuerying() = runBlocking {
        val repository = FakeRepository(rooms = listOf(sampleRoom()))
        val model = ClassroomOccupancyScreenModel(
            repository = repository,
            currentWeekProvider = { 8 },
            todayWeekdayProvider = { 3 },
        )

        model.initialize()

        // 未选楼不查询（无“全部教学楼”模式）；周/星期默认值已写入。
        assertTrue(repository.calls.isEmpty())
        assertEquals(8, model.state.value.selectedWeek)
        assertEquals(3, model.state.value.selectedWeekday)
        assertEquals(ClassroomOccupancyQueryState.Idle, model.state.value.queryState)
    }

    @Test
    fun initializeDoesNotHitNetwork() = runBlocking {
        // initialize 只写默认周/星期，不拉学期、不拉校历，避免占会话锁拖慢首查。
        val repository = FakeRepository(
            semesterOptions = SemesterOptions(
                selected = OccupancySemester("2025-2026-2-2", "2025-2026-2"),
                all = listOf(
                    OccupancySemester("2026-2027-2-2", "2026-2027-2"),
                    OccupancySemester("2025-2026-2-2", "2025-2026-2"),
                ),
            ),
            weekDates = mapOf("2025-2026-2" to listOf(OccupancyWeekDate(1, "3/2", "3/8"))),
        )
        val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 8 })

        model.initialize()

        assertTrue(model.state.value.semesters.isEmpty())
        assertNull(model.state.value.currentSemesterLabel)
        assertTrue(model.state.value.weekDates.isEmpty())
        assertNull(model.state.value.selectedSemester)
        assertTrue(repository.calls.isEmpty())
        assertEquals(0, repository.weekDatesFetches)
        assertEquals(8, model.currentWeek)
        assertEquals(ClassroomOccupancyQueryState.Idle, model.state.value.queryState)

        model.ensureWeekDatesLoaded()
        assertEquals(1, model.state.value.weekDates["2025-2026-2"]?.size)
        model.ensureWeekDatesLoaded()
        assertEquals(1, repository.weekDatesFetches)

        model.ensureSemestersLoaded()
        assertEquals(2, model.state.value.semesters.size)
        assertEquals("2025-2026-2", model.state.value.currentSemesterLabel)
    }

    @Test
    fun initializeToleratesBackgroundPrefetchFailures() = runBlocking {
        val repository = FakeRepository(failSemesters = true, failWeekDates = true)
        val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 8 })

        model.initialize()

        assertTrue(model.state.value.semesters.isEmpty())
        assertNull(model.state.value.currentSemesterLabel)
        assertTrue(model.state.value.weekDates.isEmpty())

        // 懒加载失败也静默：不抛错、不阻塞后续查询。
        model.ensureWeekDatesLoaded()
        model.ensureSemestersLoaded()
        assertTrue(model.state.value.weekDates.isEmpty())
        assertTrue(model.state.value.semesters.isEmpty())
    }

    @Test
    fun occupancySuccessBackfillsSemestersWhenPrefetchFailed() {
        runBlocking {
            val options = SemesterOptions(
                selected = OccupancySemester("2025-2026-2-2", "2025-2026-2"),
                all = listOf(
                    OccupancySemester("2026-2027-2-2", "2026-2027-2"),
                    OccupancySemester("2025-2026-2-2", "2025-2026-2"),
                ),
            )
            val repository = FakeRepository(
                rooms = listOf(sampleRoom()),
                failSemesters = true,
                occupancySemesterOptions = options,
            )
            val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 8 })
            model.initialize()
            assertTrue(model.state.value.semesters.isEmpty())

            model.selectBuilding(OCCUPANCY_BUILDINGS[2])
            model.refresh()

            assertEquals(2, model.state.value.semesters.size)
            assertEquals("2025-2026-2", model.state.value.currentSemesterLabel)
            assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)
        }
    }

    @Test
    fun selectWeekKeepsPreviousRoomsWhileRefreshing() {
        runBlocking {
            val repository = FakeRepository(rooms = listOf(sampleRoom()))
            val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 8 })
            model.initialize()
            model.selectBuilding(OCCUPANCY_BUILDINGS[2])
            model.refresh()
            val loaded = assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)
            assertEquals(false, loaded.refreshing)
            assertEquals(1, loaded.rooms.size)

            // 同步 Fake 会立刻完成；验证至少不会把 state 打回整页 Loading。
            model.selectWeek(9)
            val after = assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)
            assertEquals(false, after.refreshing)
            assertEquals(Triple(9, "1", null), repository.calls.last())
        }
    }

    @Test
    fun selectingBuildingQueriesWithNumericId() = runBlocking {
        val repository = FakeRepository(rooms = listOf(sampleRoom()))
        val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 8 })
        model.initialize()

        // selectBuilding 只同步写状态（push 动画不等网络），查询由详情页/工作区触发。
        model.selectBuilding(OCCUPANCY_BUILDINGS[2]) // 思源楼，jxlh = "1"
        assertTrue(repository.calls.isEmpty())
        assertEquals(ClassroomOccupancyQueryState.Idle, model.state.value.queryState)

        model.refresh()

        assertEquals(listOf<Triple<Int, String, String?>>(Triple(8, "1", null)), repository.calls)
        assertEquals("思源楼", model.state.value.selectedBuilding?.name)
        assertEquals("1", model.state.value.selectedBuilding?.id)
        val loaded = assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)
        assertEquals("SY101", loaded.rooms.single().room)
    }

    @Test
    fun weekAndBuildingChangesRequeryButWeekdayDoesNot() = runBlocking {
        val repository = FakeRepository(rooms = listOf(sampleRoom()))
        val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 8 })
        model.initialize()

        model.selectBuilding(OCCUPANCY_BUILDINGS[2]) // 思源楼 → "1"
        model.refresh()
        assertEquals(listOf<Triple<Int, String, String?>>(Triple(8, "1", null)), repository.calls)

        model.selectWeek(9)
        assertEquals(Triple(9, "1", null), repository.calls.last())

        // 星期是客户端筛选，数据含整周，不应触发新请求。
        model.selectWeekday(5)
        assertEquals(2, repository.calls.size)
        assertEquals(5, model.state.value.selectedWeekday)

        // 周选择 clamp 在教务 zc 范围（1..30）内。
        model.selectWeek(99)
        assertEquals(MAX_WEEK, model.state.value.selectedWeek)
    }

    @Test
    fun switchingBuildingQueriesNewIdAndReloads() = runBlocking {
        val repository = FakeRepository(rooms = listOf(sampleRoom()))
        val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 8 })
        model.initialize()

        model.selectBuilding(OCCUPANCY_BUILDINGS[2]) // 思源楼 → "1"
        model.refresh()
        assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)

        // 切楼同样只写状态，由工作区 LaunchedEffect 触发 refresh。
        model.selectBuilding(OCCUPANCY_BUILDINGS[29]) // 思源楼A座 → "104"
        model.refresh()

        // 每次切楼都带新楼数字 id 重新查询；Fake 同步返回，切楼后立即回到 Loaded。
        assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)
        assertEquals(
            listOf<Triple<Int, String, String?>>(Triple(8, "1", null), Triple(8, "104", null)),
            repository.calls,
        )
        assertEquals("104", model.state.value.selectedBuilding?.id)
    }

    @Test
    fun switchingSemesterRequeriesWithSemesterIdAndBackWithNull() {
        runBlocking {
            val repository = FakeRepository(rooms = listOf(sampleRoom()))
            val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 8 })
            model.initialize()
            model.selectBuilding(OCCUPANCY_BUILDINGS[2])
            model.refresh()
            assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)

            val fall = OccupancySemester("2025-2026-1-2", "2025-2026-1")
            model.selectSemester(fall)

            assertEquals(fall, model.state.value.selectedSemester)
            assertEquals(Triple(8, "1", "2025-2026-1-2"), repository.calls.last())
            assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)

            // 切回当前学期：请求省略 zxjxjhh 参数。
            model.selectSemester(null)
            assertNull(model.state.value.selectedSemester)
            assertEquals(Triple(8, "1", null), repository.calls.last())
            assertIs<ClassroomOccupancyQueryState.Loaded>(model.state.value.queryState)
        }
    }

    @Test
    fun selectingSemesterClampsWeekToItsCalendarLength() = runBlocking {
        val repository = FakeRepository(
            weekDates = mapOf(
                "2025-2026-1" to (1..18).map { OccupancyWeekDate(it, "9/${it}", "9/${it + 6}") },
            ),
        )
        val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 20 })
        model.initialize()
        model.ensureWeekDatesLoaded()
        // 校历没有该学期数据时周上限仍是 1..30。
        assertEquals(20, model.state.value.selectedWeek)

        val fall = OccupancySemester("2025-2026-1-2", "2025-2026-1")
        model.selectSemester(fall)

        assertEquals(18, model.state.value.selectedWeek)
    }

    @Test
    fun weekDateOfResolvesByActiveSemesterLabel() = runBlocking {
        val repository = FakeRepository(
            semesterOptions = SemesterOptions(
                selected = OccupancySemester("2025-2026-2-2", "2025-2026-2"),
                all = listOf(OccupancySemester("2025-2026-2-2", "2025-2026-2")),
            ),
            weekDates = mapOf(
                "2025-2026-2" to listOf(OccupancyWeekDate(4, "10/6", "10/12")),
            ),
        )
        val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 4 })
        model.initialize()
        model.ensureSemestersLoaded()
        model.ensureWeekDatesLoaded()

        // 未显式选学期（null = 当前学期）时，按服务器回填的当前学期 label 查找。
        assertEquals(OccupancyWeekDate(4, "10/6", "10/12"), model.weekDateOf(4))
        assertNull(model.weekDateOf(5))

        // 显式切到其它学期后按该学期 label 查找，找不到就返回 null。
        model.selectSemester(OccupancySemester("2026-2027-2-2", "2026-2027-2"))
        assertNull(model.weekDateOf(4))
    }

    @Test
    fun calendarGapIsSelectableWithoutSendingAFakeTeachingWeek() = runBlocking {
        val repository = FakeRepository(
            semesterOptions = SemesterOptions(
                selected = OccupancySemester("2026-2027-1-2", "2026-2027-1"),
                all = listOf(OccupancySemester("2026-2027-1-2", "2026-2027-1")),
            ),
            weekDates = mapOf(
                "2026-2027-1" to listOf(
                    OccupancyWeekDate(3, "9/21", "9/27", LocalDate(2026, 9, 21)),
                    OccupancyWeekDate(4, "10/12", "10/18", LocalDate(2026, 10, 12)),
                ),
            ),
            rooms = listOf(sampleRoom()),
        )
        val model = ClassroomOccupancyScreenModel(repository, currentWeekProvider = { 3 })
        model.initialize()
        model.ensureSemestersLoaded()
        model.ensureWeekDatesLoaded()

        assertEquals(
            listOf(3, null, null, 4),
            model.weekSlots().map { it.teachingWeek },
        )
        model.selectBuilding(OCCUPANCY_BUILDINGS[2])
        model.refresh()
        val callsBeforeGap = repository.calls.size

        model.selectNonTeachingWeek(LocalDate(2026, 9, 28))

        assertTrue(model.state.value.isNonTeachingWeek)
        assertEquals(LocalDate(2026, 9, 28), model.state.value.selectedNonTeachingWeekStart)
        assertEquals(callsBeforeGap, repository.calls.size)

        model.moveWeekBy(1)
        assertEquals(LocalDate(2026, 10, 5), model.state.value.selectedNonTeachingWeekStart)
        model.moveWeekBy(1)
        assertEquals(4, model.state.value.selectedWeek)
        assertEquals(4, repository.calls.last().first)
    }

    @Test
    fun buildingListOrderAndIdsMatchOnline() {
        assertEquals(35, OCCUPANCY_BUILDINGS.size)
        assertEquals(
            listOf(
                "13", "100", "1", "2", "3", "4", "5", "6", "7", "11", "12", "91", "92", "93",
                "94", "9", "8", "10", "90", "14", "101", "102", "16", "15", "17", "18", "19",
                "20", "103", "104", "105", "106", "107", "108", "109",
            ),
            OCCUPANCY_BUILDINGS.map { it.id },
        )
        assertEquals(
            listOf(
                "第十七号教学楼", "学生活动服务中心", "思源楼", "思源西楼", "思源东楼", "第九教学楼",
                "第八教学楼", "第五教学楼", "第二教学楼", "逸夫教学楼", "机械楼", "天佑会堂", "工程素质",
                "综合实验楼", "机械实验馆", "东区二教", "东区一教", "东教三楼", "科技大厦", "电气工程楼",
                "综合体育馆", "新综合体育馆", "东校区计算机机房", "交通运输科学馆", "工程训练中心",
                "第七教学楼", "工程结构实验楼", "土木工程楼", "科技楼", "思源楼A座", "思源楼B座",
                "致远楼", "知行楼", "逸夫楼", "信息楼",
            ),
            OCCUPANCY_BUILDINGS.map { it.name },
        )
        assertEquals("思源楼", OCCUPANCY_BUILDINGS.first { it.id == "1" }.name)
        assertEquals("思源楼A座", OCCUPANCY_BUILDINGS.first { it.id == "104" }.name)
    }

    @Test
    fun slotTimeRangesMatchSchoolDefinition() {
        assertEquals(7, SLOT_TIME_RANGES.size)
        assertEquals(
            listOf(
                "08:00-09:50", "10:10-12:00", "12:10-14:00", "14:10-16:00",
                "16:20-18:10", "19:00-20:50", "21:00-21:50",
            ),
            SLOT_TIME_RANGES,
        )
    }

    @Test
    fun networkFailureAndSessionExpiryAreDistinct() = runBlocking {
        val repository = FakeRepository(failure = ClassroomOccupancySyncFailure.SESSION_EXPIRED)
        val model = ClassroomOccupancyScreenModel(repository)
        model.initialize()
        model.selectBuilding(OCCUPANCY_BUILDINGS[2])
        model.refresh()
        val failed = assertIs<ClassroomOccupancyQueryState.Failed>(model.state.value.queryState)
        assertEquals(ClassroomOccupancySyncFailure.SESSION_EXPIRED, failed.reason)

        repository.failure = ClassroomOccupancySyncFailure.NETWORK
        model.refresh()
        val retried = assertIs<ClassroomOccupancyQueryState.Failed>(model.state.value.queryState)
        assertEquals(ClassroomOccupancySyncFailure.NETWORK, retried.reason)
    }

    private class FakeRepository(
        private val rooms: List<ClassroomOccupancy> = emptyList(),
        var failure: ClassroomOccupancySyncFailure? = null,
        private val semesterOptions: SemesterOptions = SemesterOptions(selected = null, all = emptyList()),
        private val weekDates: Map<String, List<OccupancyWeekDate>> = emptyMap(),
        var failSemesters: Boolean = false,
        var failWeekDates: Boolean = false,
        private val occupancySemesterOptions: SemesterOptions? = null,
    ) : ClassroomOccupancyRepository {
        val calls = mutableListOf<Triple<Int, String, String?>>()
        var weekDatesFetches = 0

        override suspend fun fetchOccupancy(
            week: Int,
            buildingId: String,
            semesterId: String?,
        ): ClassroomOccupancyResult {
            calls += Triple(week, buildingId, semesterId)
            return failure?.let(ClassroomOccupancyResult::Failure)
                ?: ClassroomOccupancyResult.Success(rooms, occupancySemesterOptions)
        }

        override suspend fun fetchSemesters(): SemesterOptions {
            if (failSemesters) throw RuntimeException("semester fetch failed")
            return semesterOptions
        }

        override suspend fun fetchWeekDates(): Map<String, List<OccupancyWeekDate>> {
            weekDatesFetches += 1
            if (failWeekDates) throw RuntimeException("week date fetch failed")
            return weekDates
        }
    }

    private fun sampleRoom() = ClassroomOccupancy(
        room = "SY101",
        capacity = 90,
        cells = mapOf((1 to 1) to OccupancyKind.SCHEDULED),
    )
}
