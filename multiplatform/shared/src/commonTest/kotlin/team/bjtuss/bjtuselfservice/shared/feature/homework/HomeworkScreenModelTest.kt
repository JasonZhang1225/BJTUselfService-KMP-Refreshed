package team.bjtuss.bjtuselfservice.shared.feature.homework

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone
import team.bjtuss.bjtuselfservice.shared.domain.time.endOfDayEpochSeconds
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkDetailResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRepository
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkOperationResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSnapshot
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkDetail
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFilterPreferences
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFilterPreferencesStore
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkSortOrder
import team.bjtuss.bjtuselfservice.shared.domain.homework.SubmittedHomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.stableKey
import team.bjtuss.bjtuselfservice.shared.domain.change.DataChangeRecorder

class HomeworkScreenModelTest {
    @Test fun detailRecoveryRequiresConfirmedSessionExpiry() = runBlocking {
        for (reason in listOf(HomeworkSyncFailure.NETWORK, HomeworkSyncFailure.SESSION_EXPIRED)) {
            val item = homework(1, "fixture", "2099-01-01 00:00")
            val snapshot = HomeworkSnapshot(listOf(item))
            var reads = 0
            var recoveries = 0
            val repository = object : HomeworkRepository by FakeRepository(snapshot, snapshot) {
                override suspend fun loadDetail(homework: Homework): HomeworkDetailResult =
                    if (++reads == 1) HomeworkDetailResult.Failure(reason)
                    else HomeworkDetailResult.Success(HomeworkDetail("已恢复", emptyList()))
            }
            val model = HomeworkScreenModel(repository)
            model.initialize(refreshFromNetwork = false)
            loadHomeworkDetailsWithRecovery(model, item.stableKey()) { recoveries++; true }
            assertEquals(if (reason == HomeworkSyncFailure.SESSION_EXPIRED) 1 else 0, recoveries)
            assertEquals(if (reason == HomeworkSyncFailure.SESSION_EXPIRED) 2 else 1, reads)
        }
    }

    private val now = LocalDateTime(2026, 7, 30, 8, 0)

    @Test
    fun initializationFiltersCoursesAndCyclesDeadlineSort() = runBlocking {
        val later = homework(1, "程序设计", "2026-08-03 08:00")
        val sooner = homework(2, "高等数学", "2026-07-31 08:00")
        val snapshot = HomeworkSnapshot(listOf(later, sooner))
        val model = model(FakeRepository(snapshot, snapshot))

        model.initialize()
        model.toggleCourse("高等数学")
        model.cycleSortOrder()

        assertEquals(HomeworkContentSource.NETWORK, model.state.value.source)
        assertEquals(listOf(2), model.state.value.visibleHomework.map(Homework::id))
        assertEquals(HomeworkSortOrder.ASCENDING, model.state.value.sortOrder)
        assertEquals(1, model.state.value.dueSoonCount)
    }

    @Test
    fun hideExpiredAndDetailLifecyclePreserveFallbackContent() = runBlocking {
        val expired = homework(1, "程序设计", "2026-07-29 08:00")
        val active = homework(2, "程序设计", "2026-08-01 08:00")
        val snapshot = HomeworkSnapshot(listOf(expired, active))
        val detail = HomeworkDetail(
            content = "远端详情",
            attachments = listOf(HomeworkAttachment(8, "模板.docx", 100, "private")),
        )
        val model = model(FakeRepository(snapshot, snapshot, detail))

        model.initialize()
        model.setHideExpired(true)
        assertEquals(listOf(2), model.state.value.visibleHomework.map(Homework::id))

        model.showDetails(active.stableKey())
        assertEquals("远端详情", model.state.value.detail?.content)
        assertEquals("模板.docx", model.state.value.detail?.attachments?.single()?.fileName)
        assertFalse(model.state.value.isDetailLoading)
        model.dismissDetails()
        assertEquals(null, model.state.value.selectedHomework)
        assertTrue(model.state.value.detail == null)
    }

    @Test
    fun hideSubmittedFiltersVisibleHomework() = runBlocking {
        val unsubmitted = homework(1, "程序设计", "2026-08-01 08:00")
        val submitted = homework(2, "程序设计", "2026-08-01 08:00").copy(subStatus = "已提交")
        val model = model(FakeRepository(HomeworkSnapshot(listOf(unsubmitted, submitted)), HomeworkSnapshot(listOf(unsubmitted, submitted))))

        model.initialize()
        assertEquals(listOf(1, 2), model.state.value.visibleHomework.map(Homework::id))

        model.setHideSubmitted(true)
        assertTrue(model.state.value.hideSubmitted)
        assertEquals(listOf(1), model.state.value.visibleHomework.map(Homework::id))

        model.setHideSubmitted(false)
        assertEquals(listOf(1, 2), model.state.value.visibleHomework.map(Homework::id))
    }

    @Test
    fun filterPreferencesPersistAndRestoreAcrossModels() = runBlocking {
        val store = MemoryFilterStore()
        val snapshot = HomeworkSnapshot(
            listOf(
                homework(1, "程序设计", "2026-08-01 08:00"),
                homework(2, "高等数学", "2026-08-01 08:00"),
            ),
        )
        val first = HomeworkScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            timeZone = TimeZone.UTC,
            nowProvider = { now },
            filterStore = store,
        )

        first.initialize()
        first.toggleCourse("高等数学")
        first.setHideExpired(true)
        first.setHideSubmitted(true)
        first.setSortOrder(HomeworkSortOrder.ASCENDING)

        assertEquals(
            HomeworkFilterPreferences(
                selectedCourses = setOf("高等数学"),
                hideExpired = true,
                hideSubmitted = true,
                sortOrder = HomeworkSortOrder.ASCENDING,
            ),
            store.saved,
        )

        // 新 Model 初始化时恢复上次状态。
        val second = HomeworkScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            timeZone = TimeZone.UTC,
            nowProvider = { now },
            filterStore = store,
        )
        second.initialize()

        assertEquals(setOf("高等数学"), second.state.value.selectedCourses)
        assertTrue(second.state.value.hideExpired)
        assertTrue(second.state.value.hideSubmitted)
        assertEquals(HomeworkSortOrder.ASCENDING, second.state.value.sortOrder)

        // 缓存里不存在的课程名在恢复时丢掉，不参与筛选。
        store.save(
            HomeworkFilterPreferences(selectedCourses = setOf("高等数学", "不存在课程")),
        )
        val third = HomeworkScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            timeZone = TimeZone.UTC,
            nowProvider = { now },
            filterStore = store,
        )
        third.initialize()

        assertEquals(setOf("高等数学"), third.state.value.selectedCourses)
    }

    @Test
    fun filtersSurviveHomeworkAddedAndRemovedByRefresh() = runBlocking {
        val store = MemoryFilterStore()
        val cached = HomeworkSnapshot(
            listOf(
                homework(1, "程序设计", "2026-08-01 08:00"),
                homework(2, "高等数学", "2026-08-01 08:00"),
            ),
        )
        // 刷新后：程序设计被删，新增一门课程；高等数学一直在。
        val refreshed = HomeworkSnapshot(
            listOf(
                homework(2, "高等数学", "2026-08-01 08:00"),
                homework(3, "新课程", "2026-08-01 08:00"),
            ),
        )
        val model = HomeworkScreenModel(
            repository = FakeRepository(cached, refreshed),
            timeZone = TimeZone.UTC,
            nowProvider = { now },
            filterStore = store,
        )

        model.initialize(refreshFromNetwork = false)
        model.toggleCourse("程序设计")
        model.setHideExpired(true)
        model.setSortOrder(HomeworkSortOrder.ASCENDING)
        assertEquals(listOf(1), model.state.value.visibleHomework.map(Homework::id))

        // 刷新删掉了已选课程：选中被裁到空，列表回到全量，其它开关和排序不动。
        model.refresh()
        assertEquals(emptySet(), model.state.value.selectedCourses)
        assertEquals(listOf(2, 3), model.state.value.visibleHomework.map(Homework::id))
        assertTrue(model.state.value.hideExpired)
        assertEquals(HomeworkSortOrder.ASCENDING, model.state.value.sortOrder)

        // 新增课程直接可见、可选、可存。
        model.toggleCourse("新课程")
        assertEquals(setOf("新课程"), model.state.value.selectedCourses)
        assertEquals(listOf(3), model.state.value.visibleHomework.map(Homework::id))
        assertEquals(setOf("新课程"), store.saved.selectedCourses)

        // 重启恢复：只保留仍然存在的课程。
        val relaunched = HomeworkScreenModel(
            repository = FakeRepository(refreshed, refreshed),
            timeZone = TimeZone.UTC,
            nowProvider = { now },
            filterStore = store,
        )
        relaunched.initialize()
        assertEquals(setOf("新课程"), relaunched.state.value.selectedCourses)
        assertTrue(relaunched.state.value.hideExpired)
        assertEquals(HomeworkSortOrder.ASCENDING, relaunched.state.value.sortOrder)
    }

    @Test
    fun submittedAttachmentsAndDownloadUseTypedOperationState() = runBlocking {
        val submittedHomework = homework(3, "程序设计", "2026-08-01 08:00").copy(
            idSnId = 31,
            subStatus = "已提交",
        )
        val snapshot = HomeworkSnapshot(listOf(submittedHomework))
        val submitted = SubmittedHomeworkAttachment("81", "我的作业.pdf", "/private/submitted")
        val file = HomeworkFileContent("我的作业.pdf", "application/pdf", "body".encodeToByteArray())
        val model = model(FakeRepository(snapshot, snapshot, submitted = listOf(submitted), file = file))

        model.initialize()
        model.showDetails(submittedHomework.stableKey())
        val result = model.downloadSubmittedAttachment("81")

        assertEquals(listOf(submitted), model.state.value.submittedAttachments)
        assertEquals(file, assertIs<HomeworkOperationResult.Success<HomeworkFileContent>>(result).value)
        assertFalse(model.state.value.isSubmittedAttachmentsLoading)
        assertFalse(model.state.value.isFileTransferInProgress)
    }

    @Test
    fun successfulSubmissionRefreshesSnapshotBeforeReturning() = runBlocking {
        val item = homework(4, "程序设计", "2026-08-01 08:00")
        val snapshot = HomeworkSnapshot(listOf(item))
        val repository = FakeRepository(snapshot, snapshot)
        val model = model(repository)

        model.initialize()
        model.showDetails(item.stableKey())
        val result = model.submitHomework(
            content = "提交说明",
            files = listOf(HomeworkFileContent("answer.txt", "text/plain", "answer".encodeToByteArray())),
        )

        assertIs<HomeworkOperationResult.Success<Unit>>(result)
        assertEquals(1, repository.submitCount)
        assertEquals(2, repository.refreshCount)
        assertEquals(item.stableKey(), model.state.value.selectedHomeworkKey)
        assertFalse(model.state.value.isSubmitting)
    }

    @Test
    fun successfulRefreshRecordsBeforeAndAfterSnapshots() = runBlocking {
        val old = homework(5, "程序设计", "2026-08-01 08:00")
        val updated = old.copy(id = 105, subStatus = "已提交")
        var captured: Pair<List<Homework>, List<Homework>>? = null
        val model = HomeworkScreenModel(
            repository = FakeRepository(HomeworkSnapshot(listOf(old)), HomeworkSnapshot(listOf(updated))),
            changeRecorder = DataChangeRecorder { before, after -> captured = before to after },
            timeZone = TimeZone.UTC,
            nowProvider = { now },
        )

        model.initialize()

        assertEquals(listOf(old), captured?.first)
        assertEquals(listOf(updated), captured?.second)
    }

    @Test
    fun autoSyncInitializeRetriesTransientNetworkFailure() = runBlocking {
        val cached = HomeworkSnapshot(listOf(homework(1, "程序设计", "2026-08-01 08:00")))
        val fresh = HomeworkSnapshot(listOf(homework(9, "程序设计", "2026-08-02 08:00")))
        val repository = FakeRepository(cached, fresh, failFirstN = 2)
        val model = model(repository)

        model.initialize()

        assertEquals(3, repository.refreshCount)
        assertEquals(HomeworkContentSource.NETWORK, model.state.value.source)
        assertEquals(null, model.state.value.failure)
        assertEquals(listOf(9), model.state.value.homework.map(Homework::id))
    }

    @Test
    fun autoSyncInitializeStopsAfterMaxFailedAttempts() = runBlocking {
        val cached = HomeworkSnapshot(listOf(homework(1, "程序设计", "2026-08-01 08:00")))
        val repository = FakeRepository(cached, cached, failFirstN = 10)
        val model = model(repository)

        model.initialize()

        assertEquals(HOMEWORK_AUTO_SYNC_MAX_ATTEMPTS, repository.refreshCount)
        assertEquals(HomeworkSyncFailure.NETWORK, model.state.value.failure)
        assertEquals(HomeworkContentSource.CACHE, model.state.value.source)
    }

    @Test
    fun deadlineScopeIsExclusiveAndUntilDateSurvivesRestart() = runBlocking {
        val store = MemoryFilterStore()
        val snapshot = HomeworkSnapshot(
            listOf(
                homework(1, "程序设计", "2026-07-29 08:00"), // 已过期
                homework(2, "程序设计", "2026-08-01 00:00"), // 显示为 7 月 31 日 24:00
                homework(3, "程序设计", "2026-08-01 00:01"),
                homework(4, "程序设计", ""), // 没有截止时间
            ),
        )
        val model = HomeworkScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            timeZone = TimeZone.UTC,
            nowProvider = { now },
            filterStore = store,
        )
        model.initialize(refreshFromNetwork = false)

        // 截止点：北京时间 7 月 31 日 24:00。
        val until = endOfDayEpochSeconds(LocalDate(2026, 7, 31), SchoolTimeZone)
        model.setDeadlineScope(hideExpired = false, untilEpoch = until)
        assertEquals(listOf(2), model.state.value.visibleHomework.map(Homework::id))
        assertTrue(model.state.value.hideExpired, "指定日期写回 hideExpired，旧版本读到时退回“未过期”")
        assertEquals(until, store.saved.untilEpoch)

        val restored = HomeworkScreenModel(
            repository = FakeRepository(snapshot, snapshot),
            timeZone = TimeZone.UTC,
            nowProvider = { now },
            filterStore = store,
        )
        restored.initialize(refreshFromNetwork = false)
        assertEquals(until, restored.state.value.untilEpoch)
        assertEquals(listOf(2), restored.state.value.visibleHomework.map(Homework::id))

        // 切回“未过期”清掉日期；没有截止时间的作业重新出现。
        restored.setHideExpired(true)
        assertEquals(null, restored.state.value.untilEpoch)
        assertEquals(listOf(2, 3, 4), restored.state.value.visibleHomework.map(Homework::id))
        restored.setDeadlineScope(hideExpired = false, untilEpoch = null)
        assertEquals(4, restored.state.value.visibleHomework.size)
        assertEquals(HomeworkFilterPreferences(), store.saved)
    }

    @Test
    fun nowUsesPhoneTimeZoneWhileBeijingDeadlinesCompareByMoment() = runBlocking {
        // 北京时间 10-29 23:59 = 纽约 10-29 11:59。
        val clock = object : kotlin.time.Clock {
            override fun now() = kotlin.time.Instant.parse("2026-10-29T15:59:00Z")
        }
        val newYork = TimeZone.of("America/New_York")
        val snapshot = HomeworkSnapshot(listOf(
            homework(1, "程序设计", "2026-10-30 00:00"), // 北京零点 = 纽约 10-29 12:00，还有 1 分钟
            homework(2, "程序设计", "2026-10-29 23:58"), // 已过 1 分钟
        ))
        val model = HomeworkScreenModel(repository = FakeRepository(snapshot, snapshot), timeZone = newYork, clock = clock)
        model.initialize(refreshFromNetwork = false)
        assertEquals(LocalDateTime(2026, 10, 29, 11, 59), model.state.value.now)
        model.setHideExpired(true)
        assertEquals(listOf(1), model.state.value.visibleHomework.map(Homework::id))
        // 在纽约选“截至 10 月 29 日”：截止点是纽约 10-29 24:00，北京零点那项在内。
        model.setDeadlineScope(hideExpired = true, untilEpoch = endOfDayEpochSeconds(LocalDate(2026, 10, 29), newYork))
        assertEquals(listOf(1), model.state.value.visibleHomework.map(Homework::id))
        assertEquals(1, model.state.value.dueSoonCount)
    }

    @Test
    fun runningSessionRefreshesZoneAndNowTogetherWithoutMovingSavedCutoff() = runBlocking {
        val clock = object : kotlin.time.Clock {
            override fun now() = kotlin.time.Instant.parse("2026-10-29T15:59:00Z")
        }
        var zone = SchoolTimeZone
        val snapshot = HomeworkSnapshot(listOf(
            homework(1, "程序设计", "2026-10-30 00:00"),
            homework(2, "程序设计", "2026-10-29 23:58"),
        ))
        val model = HomeworkScreenModel(
            repository = FakeRepository(snapshot, snapshot), clock = clock, timeZoneProvider = { zone },
        )
        model.initialize(refreshFromNetwork = false)
        model.setDeadlineScope(true, endOfDayEpochSeconds(LocalDate(2026, 10, 30), zone))
        val savedCutoff = model.state.value.untilEpoch
        assertEquals(listOf(1), model.state.value.visibleHomework.map(Homework::id))
        assertEquals(LocalDateTime(2026, 10, 29, 23, 59), model.state.value.now)

        zone = TimeZone.of("America/New_York")
        model.updateTime() // 会话计时器，无网络刷新或筛选操作。
        assertEquals(zone, model.state.value.timeZone)
        assertEquals(LocalDateTime(2026, 10, 29, 11, 59), model.state.value.now)
        assertEquals(savedCutoff, model.state.value.untilEpoch)
        assertEquals(listOf(1), model.state.value.visibleHomework.map(Homework::id))
        assertEquals(1, model.state.value.dueSoonCount)

        zone = TimeZone.of("Pacific/Auckland")
        model.setHideSubmitted(true) // 普通状态更新也必须重取时区。
        assertEquals(zone, model.state.value.timeZone)
        assertEquals(LocalDateTime(2026, 10, 30, 4, 59), model.state.value.now)
        assertEquals(savedCutoff, model.state.value.untilEpoch)
        assertEquals(listOf(1), model.state.value.visibleHomework.map(Homework::id))
        assertEquals(1, model.state.value.dueSoonCount)
    }

    private class MemoryFilterStore(
        var saved: HomeworkFilterPreferences = HomeworkFilterPreferences(),
    ) : HomeworkFilterPreferencesStore {
        override fun load(): HomeworkFilterPreferences = saved
        override fun save(preferences: HomeworkFilterPreferences) {
            saved = preferences
        }
    }

    private fun model(repository: HomeworkRepository) = HomeworkScreenModel(
        repository = repository,
        timeZone = TimeZone.UTC,
        nowProvider = { now },
    )

    private class FakeRepository(
        private val loaded: HomeworkSnapshot,
        private val refreshed: HomeworkSnapshot,
        private val detail: HomeworkDetail = HomeworkDetail("要求", emptyList()),
        private val submitted: List<SubmittedHomeworkAttachment> = emptyList(),
        private val file: HomeworkFileContent = HomeworkFileContent("file.bin", "application/octet-stream", byteArrayOf(1)),
        private val failFirstN: Int = 0,
    ) : HomeworkRepository {
        var refreshCount = 0
        var submitCount = 0

        override fun load(): HomeworkSnapshot = loaded

        override suspend fun refresh(): HomeworkRefreshResult {
            refreshCount++
            if (refreshCount <= failFirstN) {
                return HomeworkRefreshResult.Failure(loaded, HomeworkSyncFailure.NETWORK)
            }
            return HomeworkRefreshResult.Success(refreshed)
        }

        override suspend fun loadDetail(homework: Homework): HomeworkDetailResult =
            HomeworkDetailResult.Success(detail)

        override suspend fun loadSubmittedAttachments(
            homework: Homework,
        ): HomeworkOperationResult<List<SubmittedHomeworkAttachment>> =
            HomeworkOperationResult.Success(submitted)

        override suspend fun downloadTeacherAttachment(
            homeworkId: Int,
            attachment: HomeworkAttachment,
        ): HomeworkOperationResult<HomeworkFileContent> = HomeworkOperationResult.Success(file)

        override suspend fun downloadSubmittedAttachment(
            attachment: SubmittedHomeworkAttachment,
        ): HomeworkOperationResult<HomeworkFileContent> = HomeworkOperationResult.Success(file)

        override suspend fun submitHomework(
            homework: Homework,
            content: String,
            files: List<HomeworkFileContent>,
        ): HomeworkOperationResult<Unit> {
            submitCount++
            return HomeworkOperationResult.Success(Unit)
        }

        override fun attachmentDownloadUrl(homeworkId: Int, attachmentId: Int): String =
            "https://example.invalid/download"
    }

    private fun homework(id: Int, course: String, deadline: String) = Homework(
        id = id,
        upId = 100 + id,
        idSnId = null,
        score = "",
        userId = 0,
        courseId = id,
        courseName = course,
        title = "作业$id",
        content = "列表要求",
        createDate = "2026-07-01 08:00",
        endTime = deadline,
        openDate = "2026-07-01 09:00",
        status = 0,
        submitCount = 0,
        allCount = 30,
        subStatus = "未提交",
        scoreId = 0,
        homeworkType = 0,
    )
}
