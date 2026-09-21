package team.bjtuss.bjtuselfservice.shared.feature.course

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleRepository
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSnapshot
import team.bjtuss.bjtuselfservice.shared.data.course.CourseScheduleSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancyRepository
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancyResult
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.SemesterOptions
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancySemester
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.course.Course
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeRecorder
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo

class CourseScheduleScreenModelTest {
    @Test
    fun startupShowsCachedWeekThenCalendarWeekWithoutPassingThroughRemoteWeek() = runBlocking {
        // 2026-09-16 用户反馈：首页启动时周数会多次跳变。
        // 真实链路是“缓存 5 → 远端裸值 1 → 校历校准 2”，其中远端裸值必须永远不进 UI。
        val today = LocalDate(2026, 9, 16)
        val cachedWeek = 5
        val calendarWeek = 2
        val remoteBareWeek = 1
        val cached = CourseScheduleSnapshot(listOf(course(1, week = cachedWeek)), cachedWeek)
        val refreshed = CourseScheduleSnapshot(listOf(course(1, week = calendarWeek)), remoteBareWeek)
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(cached, refreshed),
            calendarRepository = FakeCalendarRepository(
                listOf(
                    week(1, LocalDate(2026, 9, 7)),
                    week(2, LocalDate(2026, 9, 14)),
                    week(3, LocalDate(2026, 9, 21)),
                ),
            ),
            todayProvider = { today },
        )

        // 阶段 1：只有缓存值，可以先显示，但还没被校历确认。
        model.initialize(refreshFromNetwork = false)
        assertEquals(cachedWeek, model.state.value.currentWeek)
        assertFalse(model.state.value.weekResolved)
        assertTrue(model.state.value.hasCachedWeek)

        // 阶段 2：远端刷新到达。裸周数 1 不得覆盖显示值。
        model.refresh()
        assertEquals(cachedWeek, model.state.value.currentWeek)
        assertFalse(model.state.value.weekResolved)

        // 阶段 3：校历按日期给出唯一权威值。
        model.ensureCalendarLoaded()
        assertEquals(calendarWeek, model.state.value.currentWeek)
        assertTrue(model.state.value.weekResolved)

        // 全程只允许出现 cachedWeek -> calendarWeek 一次跳变，绝不出现 remoteBareWeek。
        val seenWeeks = mutableListOf<Int>()
        seenWeeks += cachedWeek
        seenWeeks += model.state.value.currentWeek
        assertFalse(remoteBareWeek in seenWeeks)
    }

    @Test
    fun startupWithoutCachedWeekStaysPendingUntilCalendarConfirms() = runBlocking {
        val today = LocalDate(2026, 9, 16)
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(
                CourseScheduleSnapshot(emptyList(), 0),
                CourseScheduleSnapshot(emptyList(), 0),
            ),
            calendarRepository = FakeCalendarRepository(
                listOf(week(1, LocalDate(2026, 9, 7)), week(2, LocalDate(2026, 9, 14))),
            ),
            todayProvider = { today },
        )

        model.initialize(refreshFromNetwork = false)
        assertFalse(model.state.value.weekResolved)
        assertFalse(model.state.value.hasCachedWeek)

        model.ensureCalendarLoaded()
        assertEquals(2, model.state.value.currentWeek)
        assertTrue(model.state.value.weekResolved)
    }

    @Test
    fun scheduleModelWithoutCalendarSourceIsNeverPending() {
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(
                CourseScheduleSnapshot(listOf(course(1, week = 4)), 4),
                CourseScheduleSnapshot(listOf(course(1, week = 4)), 4),
            ),
            todayProvider = { LocalDate(2026, 9, 16) },
        )

        assertTrue(model.state.value.weekResolved)
    }

    @Test
    fun cachedTeachingWeekIsKeptAsSelectedWeekBeforeCalendarConfirms() = runBlocking {
        // 2026-09-16 实机反馈：缓存是第 2 周，进入首页却先显示「非教学周」。
        // 未确认的 0 不能覆盖缓存下来的真实教学周，否则会多出一次无意义的跳变。
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(
                CourseScheduleSnapshot(listOf(course(1, week = 2)), 2),
                CourseScheduleSnapshot(listOf(course(1, week = 2)), 2),
            ),
            calendarRepository = FakeCalendarRepository(
                listOf(week(1, LocalDate(2026, 9, 7)), week(2, LocalDate(2026, 9, 14))),
            ),
            todayProvider = { LocalDate(2026, 9, 16) },
        )

        model.initialize(refreshFromNetwork = false)

        assertEquals(2, model.state.value.currentWeek)
        assertEquals(2, model.state.value.selectedWeek)
        assertFalse(model.state.value.weekResolved)

        // 校历到达后按日期确认为第 2 周，不产生额外跳变。
        model.ensureCalendarLoaded()
        assertEquals(2, model.state.value.currentWeek)
        assertEquals(2, model.state.value.selectedWeek)
        assertTrue(model.state.value.weekResolved)
    }

    @Test
    fun confirmedHolidayGapShowsNonTeachingWeek() = runBlocking {
        // 校历确认当天不在教学周时，允许显示 0（非教学周）。
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(
                CourseScheduleSnapshot(listOf(course(1, week = 2)), 2),
                CourseScheduleSnapshot(listOf(course(1, week = 2)), 2),
            ),
            calendarRepository = FakeCalendarRepository(
                listOf(week(1, LocalDate(2026, 9, 7)), week(2, LocalDate(2026, 9, 14))),
            ),
            todayProvider = { LocalDate(2026, 10, 5) },
        )

        model.initialize(refreshFromNetwork = false)
        assertEquals(2, model.state.value.selectedWeek)

        model.ensureCalendarLoaded()
        assertEquals(0, model.state.value.currentWeek)
        assertEquals(0, model.state.value.selectedWeek)
        assertTrue(model.state.value.weekResolved)
    }

    @Test
    fun stateExposesTodayForCompactListContext() {
        val today = LocalDate(2026, 8, 11)
        val snapshot = CourseScheduleSnapshot(listOf(course(1, week = 24)), 24)
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            todayProvider = { today },
        )

        assertEquals(today, model.state.value.todayDate)
    }

    @Test
    fun horizontalTrackpadGestureTurnsExactlyOneWeekUntilMomentumEnds() {
        val accumulator = CourseWeekScrollAccumulator(threshold = 30f, quietGapMillis = 180L)

        assertEquals(null, accumulator.add(deltaX = 12f, deltaY = 1f, eventTimeMillis = 1_000L))
        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(20f, 1f, 1_016L))
        assertEquals(null, accumulator.add(60f, 0f, 1_032L))
        assertEquals(null, accumulator.add(1f, 20f, 1_048L))
        assertEquals(null, accumulator.add(40f, 0f, 1_140L))
        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_400L))
    }

    @Test
    fun explicitGestureResetAllowsImmediateSecondSwipe() {
        val accumulator = CourseWeekScrollAccumulator(threshold = 30f)

        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_000L))
        assertEquals(null, accumulator.add(31f, 0f, 1_050L))
        accumulator.resetGesture()
        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_060L))
    }

    @Test
    fun reverseSwipeUnlocksImmediatelyWithoutWaitingForQuietGap() {
        val accumulator = CourseWeekScrollAccumulator(
            threshold = 30f,
            quietGapMillis = 1_000L,
            minTurnIntervalMillis = 1_000L,
        )

        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_000L))
        assertEquals(CourseWeekScrollDirection.PREVIOUS, accumulator.add(-31f, 0f, 1_016L))
    }

    @Test
    fun distancePagerHasNoPersistentGestureLock() {
        val accumulator = CourseWeekScrollAccumulator(
            threshold = 30f,
            quietGapMillis = 80L,
            minTurnIntervalMillis = 180L,
        )

        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_000L))
        assertEquals(null, accumulator.add(80f, 0f, 1_050L))
        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_200L))
        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_400L))
    }

    @Test
    fun continuousInertiaAfterATurnDoesNotSkipExtraWeeks() {
        val accumulator = CourseWeekScrollAccumulator(
            threshold = 30f,
            quietGapMillis = 80L,
            minTurnIntervalMillis = 180L,
        )

        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_000L))
        var t = 1_016L
        while (t <= 1_600L) {
            assertEquals(
                null,
                accumulator.add(40f, 0f, t),
                "inertia at $t should not turn another week",
            )
            t += 16L
        }
        assertEquals(CourseWeekScrollDirection.NEXT, accumulator.add(31f, 0f, 1_700L))
    }

    @Test
    fun verticalScrollDoesNotTurnWeekAndNegativeHorizontalScrollGoesBack() {
        val accumulator = CourseWeekScrollAccumulator(threshold = 30f)

        assertEquals(null, accumulator.add(deltaX = 20f, deltaY = 40f, eventTimeMillis = 1_000L))
        assertEquals(null, accumulator.add(deltaX = -16f, deltaY = 1f, eventTimeMillis = 1_300L))
        assertEquals(CourseWeekScrollDirection.PREVIOUS, accumulator.add(-16f, 1f, 1_316L))
    }

    @Test
    fun fingerSwipeLeftTurnsToNextWeekWhenDistanceIsEnough() {
        assertEquals(
            CourseWeekScrollDirection.NEXT,
            weekSwipeDirection(
                displacementX = -80f,
                displacementY = 8f,
                velocityX = -10f,
                distanceThreshold = 48f,
                velocityThreshold = 700f,
            ),
        )
    }

    @Test
    fun fingerSwipeRightTurnsToPreviousWeekWhenFlickIsFast() {
        assertEquals(
            CourseWeekScrollDirection.PREVIOUS,
            weekSwipeDirection(
                displacementX = 20f,
                displacementY = 4f,
                velocityX = 900f,
                distanceThreshold = 48f,
                velocityThreshold = 700f,
            ),
        )
    }

    @Test
    fun fingerWeekPagerIsOnlyForTouchPlatforms() {
        assertTrue(
            shouldUseFingerWeekPager(PlatformInfo(PlatformFamily.Android, "Android")),
        )
        assertTrue(
            shouldUseFingerWeekPager(PlatformInfo(PlatformFamily.IOS, "iOS")),
        )
        assertFalse(
            shouldUseFingerWeekPager(PlatformInfo(PlatformFamily.MacOS, "macOS")),
        )
        assertFalse(
            shouldUseFingerWeekPager(
                PlatformInfo(PlatformFamily.MacOS, "Windows", isWindows = true),
            ),
        )
    }

    @Test
    fun verticalDominantFingerSwipeDoesNotTurnWeek() {
        assertEquals(
            null,
            weekSwipeDirection(
                displacementX = -90f,
                displacementY = -120f,
                velocityX = -800f,
                distanceThreshold = 48f,
                velocityThreshold = 700f,
            ),
        )
    }

    @Test
    fun overviewPagerPlacesAllBeforeWeekOne() {
        assertEquals(31, COURSE_OVERVIEW_PAGE_COUNT)
        assertEquals(0, overviewPageForWeek(0))
        assertEquals(0, weekForOverviewPage(0))
        assertEquals(1, overviewPageForWeek(1))
        assertEquals(1, weekForOverviewPage(1))
        assertEquals(30, overviewPageForWeek(30))
        assertEquals(30, weekForOverviewPage(30))
    }

    @Test
    fun coursePagerKeepsHolidayWeeksBetweenTeachingWeeks() {
        val state = CourseScheduleUiState(
            academicWeeks = listOf(
                week(3, LocalDate(2026, 9, 21)),
                week(4, LocalDate(2026, 10, 12)),
            ),
        )

        val pages = courseScheduleWeekPages(state)
        assertEquals(
            listOf(null, 3, null, null, 4),
            pages.map { if (it.isOverview) null else it.teachingWeek },
        )
        assertEquals(
            listOf(
                null,
                LocalDate(2026, 9, 21),
                LocalDate(2026, 9, 28),
                LocalDate(2026, 10, 5),
                LocalDate(2026, 10, 12),
            ),
            pages.map { it.startDate },
        )
    }

    @Test
    fun firstNetworkSnapshotFollowsCurrentWeekOnce() = runBlocking {
        val repository = FakeRepository(
            loaded = CourseScheduleSnapshot(listOf(course(1, week = 2)), 0),
            refreshed = CourseScheduleSnapshot(listOf(course(101, week = 8)), 8),
        )
        val model = CourseScheduleScreenModel(repository)

        model.initialize()

        assertEquals(8, model.state.value.selectedWeek)
        assertTrue(model.state.value.followCurrentWeek)
        assertEquals(CourseScheduleContentSource.NETWORK, model.state.value.source)
    }

    @Test
    fun cachedCurrentWeekDoesNotFreezeLaterNetworkCurrentWeek() = runBlocking {
        val repository = FakeRepository(
            loaded = CourseScheduleSnapshot(listOf(course(1, week = 23)), 23),
            refreshed = CourseScheduleSnapshot(listOf(course(2, week = 24)), 24),
        )
        val model = CourseScheduleScreenModel(repository)

        model.initialize()

        assertEquals(24, model.state.value.currentWeek)
        assertEquals(24, model.state.value.selectedWeek)
        assertTrue(model.state.value.followCurrentWeek)
    }

    @Test
    fun calendarCorrectsWeekOneReturnedAtCurrentSemesterSummerBoundary() = runBlocking {
        val snapshot = CourseScheduleSnapshot(listOf(course(1, week = 26)), 1)
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            calendarRepository = FakeCalendarRepository(
                weeks = listOf(
                    week(25, LocalDate(2026, 8, 17)),
                    week(26, LocalDate(2026, 8, 24)),
                    week(27, LocalDate(2026, 8, 31)),
                ),
                selectedLabel = "2025-2026-2",
            ),
            todayProvider = { LocalDate(2026, 8, 29) },
        )

        model.initialize()
        model.ensureCalendarLoaded()

        assertEquals(26, model.state.value.currentWeek)
        assertEquals(26, model.state.value.selectedWeek)
        assertTrue(model.state.value.followCurrentWeek)
    }

    @Test
    fun laterNetworkRefreshDoesNotReintroduceWeekOneAfterCalendarCorrection() = runBlocking {
        val snapshot = CourseScheduleSnapshot(listOf(course(1, week = 26)), 1)
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            calendarRepository = FakeCalendarRepository(
                weeks = listOf(week(26, LocalDate(2026, 8, 24))),
                selectedLabel = "2025-2026-2",
            ),
            todayProvider = { LocalDate(2026, 8, 29) },
        )

        model.initialize()
        model.ensureCalendarLoaded()
        model.refresh()

        assertEquals(26, model.state.value.currentWeek)
        assertEquals(26, model.state.value.selectedWeek)
    }

    @Test
    fun remoteWeekCannotOverwriteCachedWeekWhenCalendarValidationIsAvailable() = runBlocking {
        val cached = CourseScheduleSnapshot(listOf(course(1, week = 26)), 26)
        val remote = CourseScheduleSnapshot(listOf(course(2, week = 26)), 1)
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(cached, remote),
            calendarRepository = FakeCalendarRepository(
                weeks = emptyList(),
                selectedLabel = "2025-2026-2",
            ),
            todayProvider = { LocalDate(2026, 8, 29) },
        )

        model.initialize(refreshFromNetwork = false)
        assertEquals(26, model.state.value.currentWeek)
        model.ensureCalendarLoaded()
        model.initialize(refreshFromNetwork = true)

        assertEquals(26, model.state.value.currentWeek)
        assertEquals(26, model.state.value.selectedWeek)
    }

    @Test
    fun remoteWeekOneIsNotDisplayedWhenThereIsNoValidatedWeek() = runBlocking {
        val empty = CourseScheduleSnapshot(emptyList(), 0)
        val remote = CourseScheduleSnapshot(listOf(course(2, week = 1)), 1)
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(empty, remote),
            calendarRepository = FakeCalendarRepository(
                weeks = emptyList(),
                selectedLabel = "2025-2026-2",
            ),
            todayProvider = { LocalDate(2026, 8, 29) },
        )

        model.initialize(refreshFromNetwork = false)
        model.ensureCalendarLoaded()
        model.initialize(refreshFromNetwork = true)

        assertEquals(0, model.state.value.currentWeek)
        assertEquals(0, model.state.value.selectedWeek)
    }

    @Test
    fun summerContinuationWeekStillFollowsCurrentWeek() = runBlocking {
        val repository = FakeRepository(
            loaded = CourseScheduleSnapshot(listOf(course(1, week = 26)), 26),
            refreshed = CourseScheduleSnapshot(listOf(course(2, week = 27)), 27),
        )
        val model = CourseScheduleScreenModel(repository)

        model.initialize()

        assertEquals(27, model.state.value.currentWeek)
        assertEquals(27, model.state.value.selectedWeek)
        assertTrue(model.state.value.followCurrentWeek)
    }

    @Test
    fun manualWeekStillWinsAgainstLaterNetworkRefresh() = runBlocking {
        val repository = FakeRepository(
            loaded = CourseScheduleSnapshot(listOf(course(1, week = 23)), 23),
            refreshed = CourseScheduleSnapshot(listOf(course(2, week = 24)), 24),
        )
        val model = CourseScheduleScreenModel(repository)
        model.initialize(refreshFromNetwork = false)
        model.selectWeek(20)

        model.initialize(refreshFromNetwork = true)

        assertEquals(24, model.state.value.currentWeek)
        assertEquals(20, model.state.value.selectedWeek)
        assertFalse(model.state.value.followCurrentWeek)
    }

    @Test
    fun disabledLoginSyncLoadsCacheWithoutNetworkRequest() = runBlocking {
        val cached = CourseScheduleSnapshot(listOf(course(1, week = 2)), 2)
        val repository = FakeRepository(cached, CourseScheduleSnapshot(emptyList(), 0))
        val model = CourseScheduleScreenModel(repository)

        model.initialize(refreshFromNetwork = false)

        assertEquals(0, repository.refreshCount)
        assertEquals(CourseScheduleContentSource.CACHE, model.state.value.source)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun autoSyncInitializeRetriesUntilSuccess() = runBlocking {
        // 失败回落快照的 currentWeek 置 0，避免中途失败先“锁定”教学周导致成功后不再跟随。
        val cached = CourseScheduleSnapshot(listOf(course(1, week = 2)), 0)
        val success = CourseScheduleSnapshot(listOf(course(9, week = 5)), 5)
        val repository = FakeRepository(
            loaded = cached,
            refreshed = success,
            failFirstN = 2,
        )
        val model = CourseScheduleScreenModel(repository)

        model.initialize()

        assertEquals(3, repository.refreshCount)
        assertEquals(CourseScheduleContentSource.NETWORK, model.state.value.source)
        assertNull(model.state.value.failure)
        assertEquals(5, model.state.value.selectedWeek)
    }

    @Test
    fun autoSyncInitializeStopsAfterMaxFailedAttempts() = runBlocking {
        val cached = CourseScheduleSnapshot(listOf(course(1, week = 2)), 2)
        val repository = FakeRepository(
            loaded = cached,
            refreshed = cached,
            failFirstN = 10,
        )
        val model = CourseScheduleScreenModel(repository)

        model.initialize()

        assertEquals(AUTO_SYNC_MAX_ATTEMPTS, repository.refreshCount)
        assertEquals(CourseScheduleSyncFailure.NETWORK, model.state.value.failure)
        assertEquals(CourseScheduleContentSource.CACHE, model.state.value.source)
    }

    @Test
    fun manualWeekAndScheduleSwitchRemainPredictable() = runBlocking {
        val current = course(1, week = 3, selection = false)
        val selection = course(2, week = 5, selection = true)
        val snapshot = CourseScheduleSnapshot(listOf(current, selection), 6)
        val model = CourseScheduleScreenModel(FakeRepository(snapshot, snapshot))
        model.initialize()

        model.selectWeek(3)
        assertEquals(listOf(1), model.state.value.visibleCourses.map(Course::id))
        model.selectScheduleType(CourseScheduleType.SELECTION)
        assertEquals(0, model.state.value.selectedWeek)
        assertEquals(listOf(2), model.state.value.visibleCourses.map(Course::id))
        model.selectScheduleType(CourseScheduleType.CURRENT)
        assertEquals(6, model.state.value.selectedWeek)
        assertTrue(model.state.value.visibleCourses.isEmpty())
    }

    @Test
    fun dayAndDetailSelectionAreValidated() = runBlocking {
        val item = course(1, week = 3)
        val snapshot = CourseScheduleSnapshot(listOf(item), 3)
        val model = CourseScheduleScreenModel(FakeRepository(snapshot, snapshot))
        model.initialize()

        model.selectDay(4)
        model.selectDay(9)
        model.showCourseDetails(1)

        assertEquals(4, model.state.value.selectedDay)
        assertEquals(item, model.state.value.selectedCourse)
        model.dismissCourseDetails()
        assertEquals(null, model.state.value.selectedCourse)
    }

    @Test
    fun calendarDateSelectionUpdatesWeekAndDayAtomicallyAndMarksHoliday() = runBlocking {
        val snapshot = CourseScheduleSnapshot(listOf(course(1, week = 2)), 2)
        val calendar = FakeCalendarRepository(
            weeks = listOf(
                week(1, LocalDate(2026, 9, 7)),
                week(2, LocalDate(2026, 9, 14)),
            ),
        )
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            calendarRepository = calendar,
            todayProvider = { LocalDate(2026, 9, 15) },
        )
        model.initialize()
        model.ensureCalendarLoaded()

        model.selectDate(LocalDate(2026, 9, 16))
        assertEquals(2, model.state.value.selectedWeek)
        assertEquals(2, model.state.value.selectedDay)
        assertFalse(model.state.value.dateOutsideTeachingWeeks)
        assertFalse(model.state.value.followCurrentWeek)

        model.selectDate(LocalDate(2026, 10, 1))
        assertEquals(0, model.state.value.selectedWeek)
        assertTrue(model.state.value.dateOutsideTeachingWeeks)
        assertTrue(model.state.value.visibleCourses.isEmpty())
    }

    @Test
    fun manualWeekSelectionGetsConcreteDateAfterCalendarArrives() = runBlocking {
        val snapshot = CourseScheduleSnapshot(listOf(course(1, week = 2)), 2)
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            calendarRepository = FakeCalendarRepository(listOf(week(2, LocalDate(2026, 9, 14)))),
        )
        model.initialize()
        model.selectWeek(2)
        model.selectDay(4)
        model.ensureCalendarLoaded()

        assertEquals(LocalDate(2026, 9, 18), model.state.value.selectedDate)
        assertEquals("2026-2027-1", model.state.value.calendarSemesterLabel)
    }

    @Test
    fun selectionScheduleUsesExactNextSemesterCalendarInsteadOfCurrentSummerDates() = runBlocking {
        val springWeeks = listOf(
            week(24, LocalDate(2026, 8, 10)),
            week(27, LocalDate(2026, 8, 31)),
        )
        val fallWeeks = listOf(
            week(1, LocalDate(2026, 9, 7)),
            week(2, LocalDate(2026, 9, 14)),
        )
        val snapshot = CourseScheduleSnapshot(
            courses = listOf(course(1, week = 1, selection = true)),
            currentWeek = 24,
        )
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            calendarRepository = FakeCalendarRepository(
                weeks = springWeeks,
                selectedLabel = "2025-2026-2",
                allWeekDates = mapOf(
                    "2025-2026-2" to springWeeks,
                    "2026-2027-1" to fallWeeks,
                ),
            ),
            todayProvider = { LocalDate(2026, 8, 11) },
        )

        model.initialize()
        model.ensureCalendarLoaded()
        assertEquals("2025-2026-2", model.state.value.calendarSemesterLabel)
        assertEquals(LocalDate(2026, 8, 10), model.state.value.academicWeeks.first().startDate)

        model.selectScheduleType(CourseScheduleType.SELECTION)
        assertEquals("2026-2027-1", model.state.value.calendarSemesterLabel)
        assertEquals(LocalDate(2026, 9, 7), model.state.value.academicWeeks.first().startDate)
    }

    @Test
    fun dateSelectionCrossesBetweenCurrentAndSelectionCalendars() = runBlocking {
        val springWeeks = listOf(week(24, LocalDate(2026, 8, 10)))
        val fallWeeks = listOf(week(1, LocalDate(2026, 9, 7)))
        val snapshot = CourseScheduleSnapshot(
            courses = listOf(
                course(1, week = 24, selection = false),
                course(2, week = 1, selection = true),
            ),
            currentWeek = 24,
        )
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            calendarRepository = FakeCalendarRepository(
                weeks = springWeeks,
                selectedLabel = "2025-2026-2",
                allWeekDates = mapOf(
                    "2025-2026-2" to springWeeks,
                    "2026-2027-1" to fallWeeks,
                ),
            ),
            todayProvider = { LocalDate(2026, 8, 11) },
        )

        model.initialize()
        model.ensureCalendarLoaded()

        model.selectDate(LocalDate(2026, 9, 9))
        assertEquals(CourseScheduleType.SELECTION, model.state.value.scheduleType)
        assertEquals("2026-2027-1", model.state.value.calendarSemesterLabel)
        assertEquals(1, model.state.value.selectedWeek)
        assertEquals(2, model.state.value.selectedDay)
        assertEquals(listOf(2), model.state.value.visibleCourses.map(Course::id))

        model.selectDate(LocalDate(2026, 8, 12))
        assertEquals(CourseScheduleType.CURRENT, model.state.value.scheduleType)
        assertEquals("2025-2026-2", model.state.value.calendarSemesterLabel)
        assertEquals(24, model.state.value.selectedWeek)
        assertEquals(2, model.state.value.selectedDay)
        assertEquals(listOf(1), model.state.value.visibleCourses.map(Course::id))
    }

    @Test
    fun selectionScheduleDoesNotReuseCurrentCalendarWhenNextSemesterIsMissing() {
        val springWeeks = listOf(week(24, LocalDate(2026, 8, 10)))
        val mappings = resolveCourseScheduleCalendarMappings(
            selectedSemesterLabel = "2025-2026-2",
            weekDates = mapOf("2025-2026-2" to springWeeks),
            today = LocalDate(2026, 8, 11),
        )

        assertEquals("2025-2026-2", mappings[CourseScheduleType.CURRENT]?.semesterLabel)
        assertEquals(null, mappings[CourseScheduleType.SELECTION])
    }

    @Test
    fun selectionSubtitleUsesSelectionCalendarInsteadOfSnapshotCurrentWeek() {
        val state = CourseScheduleUiState(
            currentWeek = 24,
            scheduleType = CourseScheduleType.SELECTION,
            selectedWeek = 1,
            todayDate = LocalDate(2026, 8, 11),
            academicWeeks = listOf(week(1, LocalDate(2026, 9, 7))),
        )

        assertEquals("学期尚未开始", state.semesterStatusSubtitle())
    }

    @Test
    fun currentSubtitleStillUsesSnapshotCurrentWeek() {
        val state = CourseScheduleUiState(
            currentWeek = 24,
            scheduleType = CourseScheduleType.CURRENT,
            todayDate = LocalDate(2026, 8, 11),
        )

        assertEquals("当前第 24 周", state.semesterStatusSubtitle())
    }

    @Test
    fun startedSelectionSubtitleComputesWeekFromItsOwnCalendar() {
        val state = CourseScheduleUiState(
            currentWeek = 24,
            scheduleType = CourseScheduleType.SELECTION,
            selectedWeek = 2,
            todayDate = LocalDate(2026, 9, 16),
            academicWeeks = listOf(
                week(1, LocalDate(2026, 9, 7)),
                week(2, LocalDate(2026, 9, 14)),
            ),
        )

        assertEquals("当前第 2 周", state.semesterStatusSubtitle())
    }

    @Test
    fun selectionWithoutCalendarDoesNotGuessSemesterStatus() {
        val state = CourseScheduleUiState(
            currentWeek = 24,
            scheduleType = CourseScheduleType.SELECTION,
            todayDate = LocalDate(2026, 8, 11),
        )

        assertEquals(null, state.semesterStatusSubtitle())
    }

    @Test
    fun compactViewModeDoesNotLoseWeekOrDaySelection() = runBlocking {
        val snapshot = CourseScheduleSnapshot(listOf(course(1, week = 3)), 3)
        val model = CourseScheduleScreenModel(FakeRepository(snapshot, snapshot))
        model.initialize()
        assertEquals(CourseCompactViewMode.WEEK, model.state.value.compactViewMode)
        model.selectDay(5)
        model.selectCompactViewMode(CourseCompactViewMode.DAY)

        assertEquals(CourseCompactViewMode.DAY, model.state.value.compactViewMode)
        assertEquals(3, model.state.value.selectedWeek)
        assertEquals(5, model.state.value.selectedDay)
    }

    @Test
    fun repeatedDesktopWeekMovesReadLatestState() = runBlocking {
        val snapshot = CourseScheduleSnapshot(listOf(course(1, week = 3)), 3)
        val model = CourseScheduleScreenModel(FakeRepository(snapshot, snapshot))
        model.initialize()

        model.moveWeekBy(1)
        model.moveWeekBy(1)

        assertEquals(5, model.state.value.selectedWeek)
    }

    @Test
    fun successfulRefreshRecordsBeforeAndAfterSnapshots() = runBlocking {
        val old = course(1, week = 3)
        val updated = old.copy(id = 101, coursePlace = "新教室")
        var captured: Pair<List<Course>, List<Course>>? = null
        val model = CourseScheduleScreenModel(
            repository = FakeRepository(
                CourseScheduleSnapshot(listOf(old), 3),
                CourseScheduleSnapshot(listOf(updated), 3),
            ),
            changeRecorder = DataChangeRecorder { before, after -> captured = before to after },
        )

        model.initialize()

        assertEquals(listOf(old), captured?.first)
        assertEquals(listOf(updated), captured?.second)
    }

    private class FakeRepository(
        private val loaded: CourseScheduleSnapshot,
        private var refreshed: CourseScheduleSnapshot,
        private val failFirstN: Int = 0,
    ) : CourseScheduleRepository {
        var refreshCount = 0
        override fun load(): CourseScheduleSnapshot = loaded
        override suspend fun refresh(): CourseScheduleRefreshResult {
            refreshCount += 1
            return if (refreshCount <= failFirstN) {
                CourseScheduleRefreshResult.Failure(
                    snapshot = loaded,
                    reason = CourseScheduleSyncFailure.NETWORK,
                )
            } else {
                CourseScheduleRefreshResult.Success(refreshed)
            }
        }
    }

    private class FakeCalendarRepository(
        private val weeks: List<OccupancyWeekDate>,
        private val selectedLabel: String = "2026-2027-1",
        private val allWeekDates: Map<String, List<OccupancyWeekDate>> = mapOf(selectedLabel to weeks),
    ) : ClassroomOccupancyRepository {
        private val semester = OccupancySemester("$selectedLabel-1", selectedLabel)

        override suspend fun fetchOccupancy(
            week: Int,
            buildingId: String,
            semesterId: String?,
        ): ClassroomOccupancyResult = error("not used")

        override suspend fun fetchSemesters(): SemesterOptions = SemesterOptions(
            selected = semester,
            all = listOf(semester),
        )

        override suspend fun fetchWeekDates(): Map<String, List<OccupancyWeekDate>> =
            allWeekDates
    }

    private fun week(number: Int, start: LocalDate) = OccupancyWeekDate(
        week = number,
        startMonthDay = "${start.month.ordinal + 1}/${start.day}",
        endMonthDay = "",
        startDate = start,
    )

    private fun course(id: Int, week: Int, selection: Boolean = false) = Course(
        id = id,
        courseId = "course-$id",
        courseName = "课程$id",
        courseTeacher = "教师",
        courseLocationIndex = 1,
        courseTime = "第${week}周",
        coursePlace = "教室",
        isCurrentSemester = selection,
    )
}
