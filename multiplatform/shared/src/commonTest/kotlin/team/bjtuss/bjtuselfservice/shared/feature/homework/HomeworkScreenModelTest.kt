package team.bjtuss.bjtuselfservice.shared.feature.homework

import kotlinx.coroutines.runBlocking
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
