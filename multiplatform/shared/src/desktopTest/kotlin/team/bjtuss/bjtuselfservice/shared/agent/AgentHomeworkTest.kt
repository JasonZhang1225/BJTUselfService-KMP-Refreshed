package team.bjtuss.bjtuselfservice.shared.agent

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshExecutor
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcCode
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcHandler
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcOutcome
import team.bjtuss.bjtuselfservice.shared.agent.host.RefreshIpcServer
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkDetailResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkOperationResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkRepository
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSnapshot
import team.bjtuss.bjtuselfservice.shared.data.homework.HomeworkSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkDetail
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.domain.homework.SubmittedHomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.sync.RefreshOrigin
import team.bjtuss.bjtuselfservice.shared.sync.RefreshSource

class AgentHomeworkTest {
    private val directory: File = Files.createTempDirectory("agent-homework").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun file(name: String, content: String = "作业内容") = File(directory, name).apply { writeText(content) }

    private fun homework(id: Int = 7, submitted: Boolean = false, end: String = "2099-01-01 12:00") = Homework(
        id = id, upId = id + 100, idSnId = if (submitted) 1 else null, score = "", userId = 1, courseId = 2,
        courseName = "测试课程", title = "第一次作业", content = "<p>按 学号+姓名 命名</p>", createDate = "2026-01-01 00:00",
        endTime = end, openDate = "2026-01-01 00:00", status = 1, submitCount = 0, allCount = 90,
        subStatus = if (submitted) "已提交" else "未提交", scoreId = 0, homeworkType = 0,
    )

    private inner class FakeRepository(
        var items: List<Homework>,
        val submittedAfterRefresh: Boolean = true,
    ) : HomeworkRepository {
        val submissions = mutableListOf<Pair<Homework, List<HomeworkFileContent>>>()
        var submitFailures = ArrayDeque<HomeworkOperationResult.Failure>()
        var attachments = listOf<HomeworkAttachment>()

        override fun load() = HomeworkSnapshot(items)
        override suspend fun refresh(): HomeworkRefreshResult = error("走公共刷新")
        override suspend fun loadDetail(homework: Homework) =
            HomeworkDetailResult.Success(HomeworkDetail(homework.content, attachments))
        override suspend fun loadSubmittedAttachments(homework: Homework) =
            HomeworkOperationResult.Success(listOf(SubmittedHomeworkAttachment("1", "旧文件.pdf", "/private")))
        override suspend fun downloadTeacherAttachment(homeworkId: Int, attachment: HomeworkAttachment) =
            HomeworkOperationResult.Success(HomeworkFileContent(attachment.fileName, "text/plain", "要求-$homeworkId".toByteArray()))
        override suspend fun downloadSubmittedAttachment(attachment: SubmittedHomeworkAttachment) =
            HomeworkOperationResult.Failure(HomeworkSyncFailure.NETWORK)
        override suspend fun submitHomework(homework: Homework, content: String, files: List<HomeworkFileContent>): HomeworkOperationResult<Unit> {
            submitFailures.removeFirstOrNull()?.let { return it }
            submissions += homework to files
            if (submittedAfterRefresh) items = items.map { it.copy(idSnId = 1, subStatus = "已提交") }
            return HomeworkOperationResult.Success(Unit)
        }
        override fun attachmentDownloadUrl(homeworkId: Int, attachmentId: Int) = ""
    }

    private fun executor(
        repository: HomeworkRepository,
        refresh: suspend () -> Boolean = { true },
        reauthenticate: (suspend () -> Boolean)? = null,
    ) = AgentHomeworkExecutor(repository, StudentProfile("测试同学", "20990001", "学生", "测试学院"), reauthenticate, refresh,
        nowEpochSeconds = { 1_800_000_000 })

    private fun submitRequest(execute: Boolean, replace: Boolean = false) = AgentHomeworkRequest(
        "submit", 7, uploads = listOf(AgentHomeworkUpload(file("draft.pdf").path, "20990001-测试同学.pdf")),
        note = "说明", execute = execute, replace = replace,
    )

    // region 参数

    private fun parse(vararg args: String) = parseAgentHomeworkCommand(args.toList())

    @Test
    fun parsesSubmitWithRenameAndDefaultsToPreview() {
        val source = file("draft.docx")
        val other = file("data.zip")
        val command = parse("homework", "submit", "course_platform:7", "--file", source.path, "--as", "2099-张三.docx",
            "--file", other.path, "--note", "第二版", "--json")
        assertEquals(7, command.request.homeworkId)
        assertFalse(command.request.execute, "不带 --yes 只预览")
        assertFalse(command.request.mutates)
        assertEquals(listOf("2099-张三.docx", "data.zip"), command.request.uploads.map { it.uploadName })
        assertEquals("第二版", command.request.note)
        assertTrue(command.json)
    }

    @Test
    fun yesAndReplaceAreExplicit() {
        val source = file("a.pdf")
        val command = parse("homework", "submit", "7", "--file", source.path, "--yes", "--replace")
        assertTrue(command.request.mutates)
        assertTrue(command.request.replace)
    }

    @Test
    fun rejectsOtherSourcesAndBadIds() {
        assertTrue("App" in assertFailsWith<AgentUsageException> { parse("homework", "submit", "citel:9", "--file", "x") }.message!!)
        assertFailsWith<AgentUsageException> { parse("homework", "submit", "phyvlab:9", "--file", "x") }
        assertFailsWith<AgentUsageException> { parse("homework", "show", "abc") }
        assertFailsWith<AgentUsageException> { parse("homework", "show", "0") }
        assertFailsWith<AgentUsageException> { parse("homework", "delete", "7") }
    }

    @Test
    fun renameCannotChangeExtensionOrEscapeTheName() {
        val source = file("draft.docx")
        assertTrue("转换" in assertFailsWith<AgentUsageException> { parse("homework", "submit", "7", "--file", source.path, "--as", "a.pdf") }.message!!)
        listOf("../a.docx", "a/b.docx", "a\\b.docx", ".docx", "a:b.docx", "a\nb.docx").forEach { name ->
            assertFailsWith<AgentUsageException>(name) { parse("homework", "submit", "7", "--file", source.path, "--as", name) }
        }
        assertEquals("A.DOCX", parse("homework", "submit", "7", "--file", source.path, "--as", "A.DOCX").request.uploads.single().uploadName)
    }

    @Test
    fun rejectsMissingEmptyDuplicateAndMisplacedOptions() {
        val empty = File(directory, "empty.pdf").apply { writeText("") }
        val one = file("one.pdf")
        val two = file("two.pdf")
        assertFailsWith<AgentUsageException> { parse("homework", "submit", "7") }
        assertFailsWith<AgentUsageException> { parse("homework", "submit", "7", "--file", File(directory, "none.pdf").path) }
        assertFailsWith<AgentUsageException> { parse("homework", "submit", "7", "--file", directory.path) }
        assertFailsWith<AgentUsageException> { parse("homework", "submit", "7", "--file", empty.path) }
        assertFailsWith<AgentUsageException> { parse("homework", "submit", "7", "--file", one.path, "--file", two.path, "--as", "ONE.pdf") }
        assertFailsWith<AgentUsageException> { parse("homework", "submit", "7", "--as", "a.pdf", "--file", one.path) }
        assertFailsWith<AgentUsageException> { parse("homework", "show", "7", "--yes") }
        assertFailsWith<AgentUsageException> { parse("homework", "show", "7", "--file", one.path) }
        assertFailsWith<AgentUsageException> { parse("homework", "show", "7", "--out", directory.path) }
        assertFailsWith<AgentUsageException> { parse("homework", "submit", "7", "--file", one.path, "--wat") }
    }

    // endregion

    // region 执行

    @Test
    fun previewDoesNotSubmit() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        val result = assertIs<AgentHomeworkOutcome.Done>(executor(repository).run(submitRequest(execute = false))).result
        assertTrue(repository.submissions.isEmpty())
        assertEquals("preview", result.submission!!.state)
        assertFalse(result.submission!!.replaces)
        assertNull(result.submission!!.verified)
        assertEquals("course_platform:7", result.id)
    }

    @Test
    fun submitUsesRequestedNameAndVerifiesAfterSync() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        var refreshes = 0
        val result = assertIs<AgentHomeworkOutcome.Done>(
            executor(repository, refresh = { refreshes++; true }).run(submitRequest(execute = true)),
        ).result
        val (target, files) = repository.submissions.single()
        assertEquals(107, target.upId)
        assertEquals("20990001-测试同学.pdf", files.single().fileName)
        assertEquals("作业内容", files.single().bytes.decodeToString())
        assertEquals(2, refreshes, "提交前后各同步一次")
        assertEquals(true, result.submission!!.verified)
        assertEquals(listOf("旧文件.pdf"), result.submittedFiles)
    }

    @Test
    fun alreadySubmittedNeedsReplace() = runBlocking {
        val repository = FakeRepository(listOf(homework(submitted = true)))
        val preview = assertIs<AgentHomeworkOutcome.Done>(executor(repository).run(submitRequest(execute = false))).result
        assertTrue(preview.submission!!.replaces)
        assertEquals(listOf("旧文件.pdf"), preview.submittedFiles)
        assertTrue(preview.warnings.any { "--replace" in it })

        val blocked = assertIs<AgentHomeworkOutcome.Failed>(executor(repository).run(submitRequest(execute = true)))
        assertEquals(AgentHomeworkFailureCode.ALREADY_SUBMITTED, blocked.code)
        assertTrue("旧文件.pdf" in blocked.message)
        assertTrue(repository.submissions.isEmpty())

        assertIs<AgentHomeworkOutcome.Done>(executor(repository).run(submitRequest(execute = true, replace = true)))
        assertEquals(1, repository.submissions.size)
    }

    @Test
    fun staleCacheIsSyncedBeforeDecidingAlreadySubmitted() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        // 网页端刚交过：同步之后才看得到。
        val result = executor(repository, refresh = {
            repository.items = listOf(homework(submitted = true))
            true
        }).run(submitRequest(execute = true))
        assertEquals(AgentHomeworkFailureCode.ALREADY_SUBMITTED, assertIs<AgentHomeworkOutcome.Failed>(result).code)
        assertTrue(repository.submissions.isEmpty())
    }

    @Test
    fun failedSyncStopsBeforeUploading() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        val result = executor(repository, refresh = { false }).run(submitRequest(execute = true))
        assertEquals(AgentHomeworkFailureCode.NETWORK, assertIs<AgentHomeworkOutcome.Failed>(result).code)
        assertTrue(repository.submissions.isEmpty())
    }

    @Test
    fun unverifiedSubmissionIsReportedAsSuch() = runBlocking {
        val repository = FakeRepository(listOf(homework()), submittedAfterRefresh = false)
        val result = assertIs<AgentHomeworkOutcome.Done>(executor(repository).run(submitRequest(execute = true))).result
        assertEquals("submitted", result.submission!!.state)
        assertEquals(false, result.submission!!.verified)
    }

    @Test
    fun overdueIsWarnedInPreview() = runBlocking {
        val repository = FakeRepository(listOf(homework(end = "2026-01-02 12:00")))
        val preview = assertIs<AgentHomeworkOutcome.Done>(executor(repository).run(submitRequest(execute = false))).result
        assertTrue(preview.overdue)
        assertTrue(preview.warnings.any { "截止" in it })
    }

    @Test
    fun serverRejectionKeepsItsMessage() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        repository.submitFailures += HomeworkOperationResult.Failure(HomeworkSyncFailure.SUBMIT_REJECTED, serverMessage = "上传文件类型不支持")
        val failed = assertIs<AgentHomeworkOutcome.Failed>(executor(repository).run(submitRequest(execute = true)))
        assertEquals(AgentHomeworkFailureCode.REJECTED, failed.code)
        assertTrue("上传文件类型不支持" in failed.message)
    }

    @Test
    fun expiredSessionRecoversOnceThenRetries() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        repository.submitFailures += HomeworkOperationResult.Failure(HomeworkSyncFailure.SESSION_EXPIRED)
        var recoveries = 0
        assertIs<AgentHomeworkOutcome.Done>(
            executor(repository, reauthenticate = { recoveries++; true }).run(submitRequest(execute = true)),
        )
        assertEquals(1, recoveries)
        assertEquals(1, repository.submissions.size)
    }

    @Test
    fun expiredSessionThatCannotRecoverIsNotRetriedForever() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        repeat(5) { repository.submitFailures += HomeworkOperationResult.Failure(HomeworkSyncFailure.SESSION_EXPIRED) }
        var recoveries = 0
        val failed = executor(repository, reauthenticate = { recoveries++; false }).run(submitRequest(execute = true))
        assertEquals(AgentHomeworkFailureCode.SESSION_EXPIRED, assertIs<AgentHomeworkOutcome.Failed>(failed).code)
        assertEquals(1, recoveries)
        assertEquals(4, repository.submitFailures.size, "恢复失败后不再重放提交")
    }

    @Test
    fun missingFileAtSubmitTimeStopsBeforeUploading() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        val request = submitRequest(execute = true).let { it.copy(uploads = it.uploads.map { upload -> upload.copy(path = upload.path + ".gone") }) }
        assertEquals(AgentHomeworkFailureCode.IO, assertIs<AgentHomeworkOutcome.Failed>(executor(repository).run(request)).code)
        assertTrue(repository.submissions.isEmpty())
    }

    @Test
    fun unknownHomeworkIsReported() = runBlocking {
        val repository = FakeRepository(listOf(homework(id = 8)))
        val failed = executor(repository).run(AgentHomeworkRequest("show", 7))
        assertEquals(AgentHomeworkFailureCode.NOT_FOUND, assertIs<AgentHomeworkOutcome.Failed>(failed).code)
    }

    @Test
    fun showReturnsPlainRequirementsAndSavesAttachmentsSafely() = runBlocking {
        val repository = FakeRepository(listOf(homework()))
        repository.attachments = listOf(
            HomeworkAttachment(1, "../../要求.docx", 10, "/x"),
            HomeworkAttachment(2, "要求.docx", 10, "/y"),
        )
        val destination = File(directory, "attachments")
        val result = assertIs<AgentHomeworkOutcome.Done>(
            executor(repository).run(AgentHomeworkRequest("show", 7, outDir = destination.path)),
        ).result
        assertEquals("按 学号+姓名 命名", result.content)
        assertEquals("20990001", result.student!!.studentId)
        assertEquals(setOf("要求.docx", "要求 (2).docx"), destination.list()!!.toSet())
        assertEquals(listOf(destination.path), result.attachments.map { File(it.savedAs!!).parent }.distinct())
        assertEquals("要求-107", File(result.attachments[0].savedAs!!).readText())
        assertEquals(listOf("attachments"), directory.list()!!.toList(), "不留暂存目录")
    }

    @Test
    fun attachmentNamesAreDeduplicatedCaseInsensitively() {
        assertEquals(listOf("A.pdf", "a (2).pdf", "b"), uniqueAttachmentNames(listOf("A.pdf", "a.pdf", "b")))
    }

    // endregion

    // region 输出与 IPC

    private fun output(outcome: AgentHomeworkOutcome, vararg args: String) =
        outcome.toOutput(parseAgentHomeworkCommand(listOf("homework", "show", "7") + args), "app")

    @Test
    fun failureCodesMapToExitCodes() {
        assertEquals(AgentExit.SUBMIT_BLOCKED, output(AgentHomeworkOutcome.Failed(AgentHomeworkFailureCode.ALREADY_SUBMITTED, "x")).exitCode)
        assertEquals(AgentExit.NO_DATA, output(AgentHomeworkOutcome.Failed(AgentHomeworkFailureCode.NOT_FOUND, "x")).exitCode)
        assertEquals(AgentExit.LOGIN_FAILED, output(AgentHomeworkOutcome.Failed(AgentHomeworkFailureCode.SESSION_EXPIRED, "x")).exitCode)
        assertEquals(AgentExit.LOGIN_FAILED, output(AgentHomeworkOutcome.Failed(RefreshIpcCode.NOT_SIGNED_IN, "x")).exitCode)
        assertEquals(AgentExit.PARTIAL, output(AgentHomeworkOutcome.Failed(AgentHomeworkFailureCode.REJECTED, "x")).exitCode)
    }

    @Test
    fun jsonOutputCarriesExecutorAndData() {
        val done = AgentHomeworkOutcome.Done(AgentHomeworkResult("course_platform:7", "课", "题", "平时作业", overdue = false, submitted = false))
        val body = Json.parseToJsonElement(output(done, "--json").stdout).jsonObject
        assertEquals("homework show", body["command"]!!.jsonPrimitive.content)
        assertEquals("app", body["executor"]!!.jsonPrimitive.content)
        assertEquals("course_platform:7", body["data"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val failed = output(AgentHomeworkOutcome.Failed("network", "无法连接"), "--json")
        assertEquals("network", Json.parseToJsonElement(failed.stdout).jsonObject["code"]!!.jsonPrimitive.content)
    }

    private fun platform() = AgentCliPlatform(directory, { null }, { null })

    private fun serve(handler: RefreshIpcHandler) = RefreshIpcServer.start(directory, RefreshExecutor.APP, handler)!!

    private open class Handler : RefreshIpcHandler {
        var received: AgentHomeworkRequest? = null
        override suspend fun refresh(sources: Set<RefreshSource>, origin: RefreshOrigin, deadlineMillis: Long): RefreshIpcOutcome =
            RefreshIpcOutcome.Rejected(RefreshIpcCode.UNSUPPORTED, "")
        override suspend fun homework(request: AgentHomeworkRequest, deadlineMillis: Long): AgentHomeworkOutcome {
            received = request
            return AgentHomeworkOutcome.Done(AgentHomeworkResult("course_platform:7", "课", "题", "平时作业", overdue = false, submitted = true))
        }
    }

    @Test
    fun runningAppReceivesTheRequestOverIpc() {
        val handler = Handler()
        serve(handler).use {
            val source = file("a.pdf")
            val output = runAgentHomework(listOf("homework", "submit", "7", "--file", source.path, "--as", "b.pdf", "--yes", "--json"), platform())
            assertEquals(AgentExit.OK, output.exitCode)
            assertEquals("b.pdf", handler.received!!.uploads.single().uploadName)
            assertTrue(handler.received!!.mutates)
            assertEquals("app", Json.parseToJsonElement(output.stdout).jsonObject["executor"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun executorErrorsKeepTheirCodeOverIpc() {
        serve(object : Handler() {
            override suspend fun homework(request: AgentHomeworkRequest, deadlineMillis: Long) =
                AgentHomeworkOutcome.Failed(AgentHomeworkFailureCode.ALREADY_SUBMITTED, "已交过")
        }).use {
            val source = file("a.pdf")
            val output = runAgentHomework(listOf("homework", "submit", "7", "--file", source.path, "--yes"), platform())
            assertEquals(AgentExit.SUBMIT_BLOCKED, output.exitCode)
            assertEquals("已交过", output.stderr)
        }
    }

    @Test
    fun oldExecutorWithoutTheOperationAsksToUpdate() {
        serve(object : RefreshIpcHandler {
            override suspend fun refresh(sources: Set<RefreshSource>, origin: RefreshOrigin, deadlineMillis: Long): RefreshIpcOutcome =
                RefreshIpcOutcome.Rejected(RefreshIpcCode.UNSUPPORTED, "")
        }).use {
            assertEquals(AgentExit.VERSION_MISMATCH, runAgentHomework(listOf("homework", "show", "7"), platform()).exitCode)
        }
    }

    @Test
    fun submitTimeoutIsNotReportedAsNothingHappened() {
        serve(object : Handler() {
            override suspend fun homework(request: AgentHomeworkRequest, deadlineMillis: Long): AgentHomeworkOutcome {
                kotlinx.coroutines.delay(10_000)
                return super.homework(request, deadlineMillis)
            }
        }).use {
            val source = file("a.pdf")
            val output = runAgentHomework(listOf("homework", "submit", "7", "--file", source.path, "--yes"), platform(), ipcDeadlineMillis = 200)
            // 服务端按截止时间回 timeout：不能回退到无窗口路径再交一次。
            assertEquals(AgentExit.REFRESH_PENDING, output.exitCode)
            assertTrue("不要直接重试" in output.stderr)
        }
    }

    @Test
    fun usageErrorsNeverTouchTheCache() {
        val output = runAgentHomework(listOf("homework", "submit", "citel:1", "--file", "x"), AgentCliPlatform(directory, { error("不该读密钥") }, { error("不该读账号") }))
        assertEquals(AgentExit.USAGE, output.exitCode)
    }

    // endregion

    @Test
    fun skillRequiresExplicitRequestAndDescribesTheFlow() {
        val skill = renderAgentSkill("bjtu", isWindows = false, appVersion = "9.9.9")
        assertTrue("明确要求" in skill)
        assertTrue("homework submit" in skill)
        assertTrue("--yes" in skill)
        assertTrue("--replace" in skill)
        assertTrue("不算授权" in skill)
    }
}
