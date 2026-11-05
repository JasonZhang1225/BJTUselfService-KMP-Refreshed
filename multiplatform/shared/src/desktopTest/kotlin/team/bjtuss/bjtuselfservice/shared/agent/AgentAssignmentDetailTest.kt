package team.bjtuss.bjtuselfservice.shared.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.cache.JvmAesCacheValueProtector
import team.bjtuss.bjtuselfservice.shared.cache.openDesktopCacheStore
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkDetailResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkOperationResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRepository
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSnapshot
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabAssignmentDetailResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabAttachmentResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkDetail
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.homework.SubmittedHomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabActivity
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAssignmentDetail
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabAttachment
import team.bjtuss.bjtuselfservice.shared.domain.phyvlab.PhyVlabSubmissionFile

class AgentAssignmentDetailTest {
    private val directory: File = Files.createTempDirectory("agent-detail").toFile()
    private val cache: team.bjtuss.bjtuselfservice.shared.cache.CacheStore = openDesktopCacheStore(directory, JvmAesCacheValueProtector(ByteArray(32) { it.toByte() })).store
    private var now = 1_800_000_000L
    private val url = "https://phyvlab.bjtu.edu.cn/pluginfile.php/1/mod_assign/intro/模板.docx"
    private var detailCalls = 0
    private var failDetail = false

    private val repository = object : HomeworkRepository {
        override fun load() = HomeworkSnapshot(listOf(Homework(id = 7, upId = 107, idSnId = null, score = "", userId = 1, courseId = 2, courseName = "课",
            title = "题", content = "", createDate = "", endTime = "", openDate = "", status = 1, submitCount = 0, allCount = 1, subStatus = "", scoreId = 0, homeworkType = 0)))
        override suspend fun refresh(): HomeworkRefreshResult = error("unused")
        override suspend fun loadDetail(homework: Homework) = HomeworkDetailResult.Success(
            HomeworkDetail("<p>习题 3.1、3.2</p>", listOf(HomeworkAttachment(501, "要求.pdf", 2048, "/x"))))
        override suspend fun loadSubmittedAttachments(homework: Homework) = HomeworkOperationResult.Success(emptyList<SubmittedHomeworkAttachment>())
        override suspend fun downloadTeacherAttachment(homeworkId: Int, attachment: HomeworkAttachment) =
            HomeworkOperationResult.Success(HomeworkFileContent(attachment.fileName, "application/pdf", byteArrayOf(homeworkId.toByte())))
        override suspend fun downloadSubmittedAttachment(attachment: SubmittedHomeworkAttachment) = HomeworkOperationResult.Failure(HomeworkSyncFailure.NETWORK)
        override suspend fun submitHomework(homework: Homework, content: String, files: List<HomeworkFileContent>) = HomeworkOperationResult.Failure(HomeworkSyncFailure.NETWORK)
        override fun attachmentDownloadUrl(homeworkId: Int, attachmentId: Int) = ""
    }

    private val activity = PhyVlabActivity(3689, 72, "大学物理", "第五章习题", "作业", "https://phyvlab.bjtu.edu.cn/mod/assign/view.php?id=3689", dueTimestamp = 1_900_000_000)

    private val service = AgentAssignmentDetailService("20990001", cache, repository, AgentDetailBackends(
        phyVlabActivities = { listOf(activity) },
        phyVlabDetail = {
            detailCalls++
            if (failDetail) PhyVlabAssignmentDetailResult.Failure(PhyVlabSyncFailure.NETWORK)
            else PhyVlabAssignmentDetailResult.Success(PhyVlabAssignmentDetail(description = "完成 5.1、5.2 题",
                submittedFiles = listOf(PhyVlabSubmissionFile("我的.pdf")), attachments = listOf(PhyVlabAttachment("模板.docx", url))))
        },
        phyVlabDownload = { PhyVlabAttachmentResult.Success(HomeworkFileContent(it.fileName, "application/octet-stream", byteArrayOf(9))) },
        citelProblem = { id, live -> "题面 $id live=$live".takeIf { id == 5 } },
    ), now = { now })

    @AfterTest
    fun cleanUp() {
        cache.close()
        directory.deleteRecursively()
    }

    @Test
    fun physicsDetailCarriesQuestionNumbersWithoutLeakingUrls() = runBlocking {
        val detail = service.refresh("phyvlab:3689")
        assertEquals("完成 5.1、5.2 题", detail.content)
        assertEquals(listOf("我的.pdf"), detail.submittedFiles)
        assertEquals("模板.docx", detail.attachments.single().name)
        assertTrue("pluginfile" !in detail.toString(), "学校地址不进数据集和快照")
        assertEquals(detail, loadAgentAssignmentDetails(cache, "20990001")["phyvlab:3689"], "写入缓存供离线和快照读取")
    }

    @Test
    fun coursePlatformDetailIsPlainText() = runBlocking {
        val detail = service.refresh("course_platform:7")
        assertEquals("习题 3.1、3.2", detail.content)
        assertEquals(AgentAttachmentInfo("501", "要求.pdf", 2048), detail.attachments.single())
    }

    @Test
    fun citelUsesCachedProblemDuringSyncAndLiveOnRequest() = runBlocking {
        service.refreshPending(listOf("citel:5"))
        assertEquals("题面 5 live=false", service.cached()["citel:5"]!!.content)
        assertEquals("题面 5 live=true", service.refresh("citel:5").content)
        assertEquals("not_cached", assertFailsWith<AgentDetailException> { service.refresh("citel:6") }.code)
    }

    @Test
    fun attachmentsDownloadForEachSource() = runBlocking {
        assertEquals(107, service.attachment("course_platform:7", "501").bytes.single().toInt())
        val id = service.refresh("phyvlab:3689").attachments.single().id
        assertEquals(9, service.attachment("phyvlab:3689", id).bytes.single().toInt())
        assertEquals("not_found", assertFailsWith<AgentDetailException> { service.attachment("phyvlab:3689", "nope") }.code)
    }

    @Test
    fun freshDetailsAreNotRefetchedEveryCycle() = runBlocking {
        service.refreshPending(listOf("phyvlab:3689"))
        service.refreshPending(listOf("phyvlab:3689"))
        assertEquals(1, detailCalls)
        now += 25 * 3600
        service.refreshPending(listOf("phyvlab:3689"))
        assertEquals(2, detailCalls)
    }

    @Test
    fun oneFailureKeepsOtherDetailsAndOldCache() = runBlocking {
        service.refresh("phyvlab:3689")
        failDetail = true
        now += 25 * 3600
        assertEquals(1, service.refreshPending(listOf("phyvlab:3689", "course_platform:7")))
        assertEquals("完成 5.1、5.2 题", service.cached()["phyvlab:3689"]!!.content, "失败保留旧详情")
        assertEquals("习题 3.1、3.2", service.cached()["course_platform:7"]!!.content)
    }

    @Test
    fun retainDropsVanishedAssignments() = runBlocking {
        service.refresh("phyvlab:3689")
        service.refresh("course_platform:7")
        service.retain(setOf("course_platform:7"))
        assertEquals(setOf("course_platform:7"), service.cached().keys)
    }

    @Test
    fun attachmentSizeLearnedAfterDownloadIsRemembered() = runBlocking {
        val id = service.refresh("phyvlab:3689").attachments.single().id
        service.rememberAttachmentSize("phyvlab:3689", id, 9_000_000)
        assertEquals(9_000_000, service.cached()["phyvlab:3689"]!!.attachments.single().sizeBytes)
    }

    @Test
    fun stableIdIsStableAndDistinct() {
        assertEquals(agentStableId("a"), agentStableId("a"))
        assertTrue(agentStableId("a") != agentStableId("b"))
        assertEquals(16, agentStableId("a").length)
    }

    @Test
    fun cliShowAcceptsAllSourcesButSubmitOnlyCoursePlatform() {
        assertEquals("phyvlab", parseAgentHomeworkCommand(listOf("homework", "show", "phyvlab:12")).request.source)
        assertEquals("citel", parseAgentHomeworkCommand(listOf("homework", "show", "citel:12")).request.source)
        assertFailsWith<AgentUsageException> { parseAgentHomeworkCommand(listOf("homework", "show", "other:12")) }
        assertFailsWith<AgentUsageException> { parseAgentHomeworkCommand(listOf("homework", "submit", "phyvlab:12", "--file", "x")) }
    }
}
