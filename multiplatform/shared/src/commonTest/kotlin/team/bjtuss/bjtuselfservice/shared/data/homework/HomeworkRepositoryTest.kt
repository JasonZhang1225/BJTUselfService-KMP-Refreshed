package team.bjtuss.bjtuselfservice.shared.data.homework

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkDetail
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.homework.SubmittedHomeworkAttachment

class HomeworkRepositoryTest {
    @Test
    fun refreshReplacesOnlyCurrentAccountSnapshot() = runBlocking {
        val local = FakeLocal(HomeworkSnapshot(listOf(homework(7, 70, "旧作业"))))
        val repository = DefaultHomeworkRepository(
            "student-a",
            local,
            FakeRemote(listOf(homework(0, 80, "新作业"))),
        )

        val result = assertIs<HomeworkRefreshResult.Success>(repository.refresh())

        assertEquals("新作业", result.snapshot.homework.single().title)
        assertEquals(100, result.snapshot.homework.single().id)
        assertEquals(listOf("student-a"), local.replacedAccounts)
    }

    @Test
    fun remoteAndCacheFailurePreserveOldSnapshot() = runBlocking {
        val cached = HomeworkSnapshot(listOf(homework(7, 70, "完整缓存")))
        val firstLocal = FakeLocal(cached)
        val remoteFailure = DefaultHomeworkRepository(
            "student-a",
            firstLocal,
            FakeRemote(error = HomeworkRemoteException(HomeworkRemoteFailure.NETWORK)),
        )
        val first = assertIs<HomeworkRefreshResult.Failure>(remoteFailure.refresh())
        assertEquals(cached, first.snapshot)
        assertTrue(firstLocal.replacedAccounts.isEmpty())

        val cacheFailure = DefaultHomeworkRepository(
            "student-a",
            FakeLocal(cached, failReplace = true),
            FakeRemote(listOf(homework(0, 80, "新作业"))),
        )
        val second = assertIs<HomeworkRefreshResult.Failure>(cacheFailure.refresh())
        assertEquals(HomeworkSyncFailure.CACHE, second.reason)
        assertEquals(cached, second.snapshot)
    }

    @Test
    fun detailFailureIsTypedAndDoesNotExposeRemoteException() = runBlocking {
        val repository = DefaultHomeworkRepository(
            "student-a",
            FakeLocal(HomeworkSnapshot(emptyList())),
            FakeRemote(detailError = HomeworkRemoteException(HomeworkRemoteFailure.SESSION_EXPIRED)),
        )

        val result = assertIs<HomeworkDetailResult.Failure>(repository.loadDetail(homework(1, 1, "作业")))

        assertEquals(HomeworkSyncFailure.SESSION_EXPIRED, result.reason)
    }

    @Test
    fun submitRejectionKeepsServerMessageAndIsTyped() = runBlocking {
        val repository = DefaultHomeworkRepository(
            "student-a",
            FakeLocal(HomeworkSnapshot(emptyList())),
            FakeRemote(
                submitError = HomeworkRemoteException(
                    HomeworkRemoteFailure.SUBMIT_REJECTED,
                    "上传文件类型不支持，请更换文件！",
                ),
            ),
        )

        val result = assertIs<HomeworkOperationResult.Failure>(
            repository.submitHomework(
                homework(1, 1, "作业"),
                content = "提交说明",
                files = listOf(HomeworkFileContent("答案.pdf", "application/pdf", byteArrayOf(1))),
            ),
        )

        assertEquals(HomeworkSyncFailure.SUBMIT_REJECTED, result.reason)
        assertEquals("上传文件类型不支持，请更换文件！", result.serverMessage)
    }

    @Test
    fun networkSubmitFailureDoesNotExposeInternalMessage() = runBlocking {
        val repository = DefaultHomeworkRepository(
            "student-a",
            FakeLocal(HomeworkSnapshot(emptyList())),
            FakeRemote(submitError = HomeworkRemoteException(HomeworkRemoteFailure.NETWORK)),
        )

        val result = assertIs<HomeworkOperationResult.Failure>(
            repository.submitHomework(
                homework(1, 1, "作业"),
                content = "提交说明",
                files = listOf(HomeworkFileContent("答案.pdf", "application/pdf", byteArrayOf(1))),
            ),
        )

        assertEquals(HomeworkSyncFailure.NETWORK, result.reason)
        assertEquals(null, result.serverMessage)
    }

    private class FakeRemote(
        private val homework: List<Homework> = emptyList(),
        private val error: Exception? = null,
        private val detailError: Exception? = null,
        private val submitError: Exception? = null,
    ) : HomeworkRemoteDataSource {
        override suspend fun fetchHomework(): List<Homework> {
            error?.let { throw it }
            return homework
        }

        override suspend fun fetchDetail(homework: Homework): HomeworkDetail {
            detailError?.let { throw it }
            return HomeworkDetail(homework.content, emptyList())
        }

        override suspend fun fetchSubmittedAttachments(
            homework: Homework,
        ): List<SubmittedHomeworkAttachment> = emptyList()

        override suspend fun downloadTeacherAttachment(
            homeworkId: Int,
            attachment: HomeworkAttachment,
        ): HomeworkFileContent = HomeworkFileContent(attachment.fileName, "application/octet-stream", byteArrayOf(1))

        override suspend fun downloadSubmittedAttachment(
            attachment: SubmittedHomeworkAttachment,
        ): HomeworkFileContent = HomeworkFileContent(attachment.fileName, "application/octet-stream", byteArrayOf(1))

        override suspend fun submitHomework(
            homework: Homework,
            content: String,
            files: List<HomeworkFileContent>,
        ) {
            submitError?.let { throw it }
        }

        override fun attachmentDownloadUrl(homeworkId: Int, attachmentId: Int): String =
            "https://example.invalid/download"
    }

    private class FakeLocal(
        var snapshot: HomeworkSnapshot,
        private val failReplace: Boolean = false,
    ) : HomeworkLocalDataSource {
        val replacedAccounts = mutableListOf<String>()

        override fun load(accountScope: String): HomeworkSnapshot = snapshot

        override fun replace(accountScope: String, homework: List<Homework>) {
            if (failReplace) error("synthetic homework cache failure")
            replacedAccounts += accountScope
            snapshot = HomeworkSnapshot(
                homework.mapIndexed { index, item -> item.copy(id = 100 + index) },
            )
        }
    }

    private fun homework(id: Int, upId: Int, title: String) = Homework(
        id = id,
        upId = upId,
        idSnId = null,
        score = "",
        userId = 0,
        courseId = 1,
        courseName = "程序设计",
        title = title,
        content = "要求",
        createDate = "2026-07-01 08:00",
        endTime = "2026-08-01 20:00",
        openDate = "2026-07-01 09:00",
        status = 0,
        submitCount = 0,
        allCount = 30,
        subStatus = "未提交",
        scoreId = 0,
        homeworkType = 0,
    )
}
