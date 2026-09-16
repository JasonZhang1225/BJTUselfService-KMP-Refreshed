package team.bjtuss.bjtuselfservice.shared.data.homework

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.domain.homework.Homework
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkAttachment
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

class HomeworkRemoteDataSourceTest {
    @Test
    fun boundsListAndScoreConcurrencyAtThreeWithoutChangingResultOrder() = runBlocking {
        val transport = ConcurrentHomeworkTransport()
        val remote = SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0)

        val homework = remote.fetchHomework()

        assertEquals(
            listOf(
                "17-0", "17-1", "17-2",
                "18-0", "18-1", "18-2",
            ),
            homework.map(Homework::title),
        )
        assertEquals(3, transport.maxActiveListRequests)
        assertEquals(3, transport.maxActiveScoreRequests)
    }

    @Test
    fun initializesHttpsSessionAndFetchesThreeTaskTypes() = runBlocking {
        val transport = QueueTransport(
            smartResponse("<html></html>"),
            smartResponse("""{"sessionId":"session-value"}"""),
            smartResponse("""{"STATUS":"0","result":[{"xqCode":"2026-1"}]}"""),
            smartResponse("""{"STATUS":"0","courseList":[{"id":17,"name":"程序设计","teacher_id":28}]}"""),
            smartResponse(homeworkList(101, "平时作业")),
            smartResponse(homeworkList(102, "课程设计")),
            smartResponse(homeworkList(103, "实验报告")),
        )
        val remote = SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0)

        val homework = remote.fetchHomework()

        assertEquals(listOf(0, 1, 2), homework.map { it.homeworkType })
        assertTrue(transport.requests.all { it.url.startsWith("https://") })
        assertTrue(transport.requests.none { "123.121.147.7" in it.url })
        assertTrue(transport.requests.drop(2).any { it.headers["sessionid"] == "session-value" })
        val download = remote.attachmentDownloadUrl(101, 8)
        assertTrue(download.startsWith("https://bksycenter.bjtu.edu.cn/"))
        assertTrue("noteId=101" in download)
    }

    @Test
    fun macLegacyEndpointUsesOnlyAuthorizedHttpOriginAfterHttpsModule() = runBlocking {
        val transport = QueueTransport(
            legacyResponse("<html></html>"),
            legacyResponse("""{"sessionId":"session-value"}"""),
            legacyResponse("""{"STATUS":"0","result":[{"xqCode":"2026-1"}]}"""),
            legacyResponse("""{"STATUS":"0","courseList":[{"id":17,"name":"程序设计","teacher_id":28}]}"""),
            legacyResponse(homeworkList(101, "平时作业")),
            legacyResponse(homeworkList(102, "课程设计")),
            legacyResponse(homeworkList(103, "实验报告")),
        )
        val remote = SchoolHomeworkRemoteDataSource(
            transport = transport,
            requestDelayMillis = 0,
            endpoint = SmartPlatformEndpoint.LegacyHttp,
        )

        assertEquals(3, remote.fetchHomework().size)
        assertEquals("https://mis.bjtu.edu.cn/module/module/28/", transport.requests.first().url)
        assertTrue(
            transport.requests.drop(1).all { request ->
                request.url.startsWith("http://123.121.147.7:88/ve/")
            },
        )
        assertTrue(transport.requests.drop(2).any { it.headers["sessionid"] == "session-value" })
        assertFalse(transport.requests.any { "123.121.147.7:1936" in it.url })
    }

    @Test
    fun refusesLegacyHttpRedirectBeforeSendingSecondRequest() {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mis.bjtu.edu.cn/module/module/28/",
                body = """<form id="redirect" action="http://123.121.147.7:88/ve/"></form>"""
                    .encodeToByteArray(),
            ),
        )

        val error = assertFailsWith<HomeworkRemoteException> {
            runBlocking {
                SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0).fetchHomework()
            }
        }

        assertEquals(HomeworkRemoteFailure.SECURE_CHANNEL_UNAVAILABLE, error.reason)
        assertEquals(1, transport.requests.size)
        assertFalse("123.121.147.7" in error.toString())
    }

    @Test
    fun stopsHandshakeAtPlainHttpRedirectAndReportsSecureChannelUnavailable() {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 302,
                finalUrl = "https://mis.bjtu.edu.cn/module/module/28/",
                headers = mapOf("Location" to listOf("http://123.121.147.7:88/oauth/api/user/thirdLogin")),
            ),
        )

        val error = assertFailsWith<HomeworkRemoteException> {
            runBlocking {
                SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0).fetchHomework()
            }
        }

        assertEquals(HomeworkRemoteFailure.SECURE_CHANNEL_UNAVAILABLE, error.reason)
        assertEquals(1, transport.requests.size)
        assertFalse(transport.requests.any { "123.121.147.7" in it.url })
    }

    @Test
    fun reportsMissingHttpsArticleEndpointAsSecureChannelUnavailable() {
        val transport = QueueTransport(
            smartResponse("<html></html>"),
            SchoolHttpResponse(
                statusCode = 404,
                finalUrl = "https://bksycenter.bjtu.edu.cn/ve/back/coursePlatform/message.shtml",
            ),
        )

        val error = assertFailsWith<HomeworkRemoteException> {
            runBlocking {
                SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0).fetchHomework()
            }
        }

        assertEquals(HomeworkRemoteFailure.SECURE_CHANNEL_UNAVAILABLE, error.reason)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun downloadsBothAttachmentKindsAndSubmitsMultipartWithoutLoggingFileData() = runBlocking {
        val transport = QueueTransport(
            smartResponse("<html></html>"),
            smartResponse("""{"sessionId":"session-value"}"""),
            smartResponse("""{"STATUS":"0","result":[{"xqCode":"2026-1"}]}"""),
            smartResponse("""{"STATUS":"0","courseList":[{"id":17,"name":"程序设计","teacher_id":28}]}"""),
            smartBytes("teacher-file".encodeToByteArray(), "application/pdf"),
            smartResponse(
                """<div class="homeworkContent" onclick="download('/private/submitted','我的+作业.pdf','81')"></div>""",
            ),
            smartBytes("submitted-file".encodeToByteArray(), "application/pdf"),
            smartResponse(
                """{"fileNameNoExt":"answer","fileExtName":"pdf","fileSize":"11","visitName":"server-visit"}""",
            ),
            // The original Android 1.7.0 client accepts any successful HTTP response here.
            smartResponse("{\"STATUS\":\"5\",\"message\":\"网关回执不稳定\"}"),
        )
        val remote = SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0)
        val homework = homework()
        val teacher = HomeworkAttachment(7, "教师模板.pdf", 12, "/private/teacher")

        val teacherFile = remote.downloadTeacherAttachment(homework.upId, teacher)
        val submitted = remote.fetchSubmittedAttachments(homework).single()
        val submittedFile = remote.downloadSubmittedAttachment(submitted)
        remote.submitHomework(
            homework = homework,
            content = "提交说明",
            files = listOf(HomeworkFileContent("我的答案.pdf", "application/pdf", "answer-body".encodeToByteArray())),
        )

        assertEquals("teacher-file", teacherFile.bytes.decodeToString())
        assertEquals("submitted-file", submittedFile.bytes.decodeToString())
        val uploadRequest = transport.requests[7]
        assertEquals("POST", uploadRequest.method.name)
        assertEquals("我的答案.pdf", uploadRequest.multipartFiles.single().fileName)
        assertEquals("application/octet-stream", uploadRequest.multipartFiles.single().contentType)
        assertEquals(mapOf("sessionid" to "session-value"), uploadRequest.headers)
        assertFalse("我的答案.pdf" in uploadRequest.toString())
        val submitRequest = transport.requests[8]
        assertEquals("%E6%8F%90%E4%BA%A4%E8%AF%B4%E6%98%8E", submitRequest.formFields["content"])
        assertTrue("sendStuHomeWorks" in submitRequest.url)
        assertEquals(mapOf("sessionid" to "session-value"), submitRequest.headers)
        assertFalse("提交说明" in submitRequest.toString())
        assertFalse("%E6%8F%90" in submitRequest.toString())
    }

    @Test
    fun classifiesStatusMessageUploadResponseAsSessionExpired() = runBlocking {
        val transport = QueueTransport(
            smartResponse("<html></html>"),
            smartResponse("""{"sessionId":"session-value"}"""),
            smartResponse("""{"STATUS":"0","result":[{"xqCode":"2026-1"}]}"""),
            smartResponse("""{"STATUS":"0","courseList":[{"id":17,"name":"程序设计","teacher_id":28}]}"""),
            smartResponse("""{"STATUS":"1","MSG":"会话结束，请重新登录"}"""),
        )
        val remote = SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0)

        val error = assertFailsWith<HomeworkRemoteException> {
            remote.submitHomework(
                homework = homework(),
                content = "提交说明",
                files = listOf(HomeworkFileContent("答案.pdf", "application/pdf", byteArrayOf(1))),
            )
        }

        assertEquals(HomeworkRemoteFailure.SESSION_EXPIRED, error.reason)
    }

    @Test
    fun reportsMalformedHomeworkListSoTheRepositoryCanKeepItsCache() = runBlocking {
        val transport = QueueTransport(
            smartResponse("<html></html>"),
            smartResponse("""{"sessionId":"session-value"}"""),
            smartResponse("""{"STATUS":"0","result":[{"xqCode":"2026-1"}]}"""),
            smartResponse("""{"STATUS":"0","courseList":[{"id":17,"name":"程序设计","teacher_id":28}]}"""),
            smartResponse("<html>login</html>"),
            smartResponse(homeworkList(102, "课程设计")),
            smartResponse("""{"STATUS":"5","message":"系统异常"}"""),
        )

        val error = assertFailsWith<HomeworkRemoteException> {
            SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0).fetchHomework()
        }

        assertEquals(HomeworkRemoteFailure.MALFORMED_RESPONSE, error.reason)
    }

    @Test
    fun emptySemesterYieldsEmptyHomeworkWithoutCourseRequests() = runBlocking {
        val transport = QueueTransport(
            smartResponse("<html></html>"),
            smartResponse("""{"sessionId":"session-value"}"""),
            smartResponse("""{"STATUS":"0","result":[]}"""),
        )

        val homework = SchoolHomeworkRemoteDataSource(transport, requestDelayMillis = 0).fetchHomework()

        assertEquals(emptyList(), homework)
        assertTrue(transport.requests.none { "getCourseList" in it.url || "getHomeWorkList" in it.url })
    }

    private class QueueTransport(vararg responses: SchoolHttpResponse) : SchoolHttpTransport {
        private val queue = responses.toMutableList()
        val requests = mutableListOf<SchoolHttpRequest>()

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request
            return queue.removeFirst()
        }

        override fun clearSession() = Unit
    }

    private inner class ConcurrentHomeworkTransport : SchoolHttpTransport {
        var activeListRequests = 0
        var maxActiveListRequests = 0
        var activeScoreRequests = 0
        var maxActiveScoreRequests = 0

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = when {
            request.url == "https://mis.bjtu.edu.cn/module/module/28/" -> smartResponse("<html></html>")
            "message.shtml" in request.url -> smartResponse("""{"sessionId":"session-value"}""")
            "teachCalendar.shtml" in request.url ->
                smartResponse("""{"STATUS":"0","result":[{"xqCode":"2026-1"}]}""")
            "course.shtml" in request.url -> smartResponse(
                """{"STATUS":"0","courseList":[""" +
                    """{"id":17,"name":"课程17","teacher_id":28},""" +
                    """{"id":18,"name":"课程18","teacher_id":29}]}""",
            )
            "homeWork.shtml" in request.url -> {
                activeListRequests += 1
                maxActiveListRequests = maxOf(maxActiveListRequests, activeListRequests)
                delay(20)
                val courseId = request.url.queryInt("cId")
                val homeworkType = request.url.queryInt("subType")
                activeListRequests -= 1
                smartResponse(
                    """{"STATUS":"0","courseNoteList":[{"id":${courseId * 10 + homeworkType},""" +
                        """"course_id":$courseId,"course_name":"课程$courseId","title":"$courseId-$homeworkType",""" +
                        """"end_time":"2026-08-01 20:00","subStatus":"已提交","scoreId":1}]}""",
                )
            }
            "courseWorkInfo.shtml" in request.url -> {
                activeScoreRequests += 1
                maxActiveScoreRequests = maxOf(maxActiveScoreRequests, activeScoreRequests)
                delay(20)
                activeScoreRequests -= 1
                smartResponse("""<input id="oldScore" value="95">""")
            }
            else -> error("Unexpected request")
        }

        override fun clearSession() = Unit
    }

    private fun smartResponse(body: String) = SchoolHttpResponse(
        statusCode = 200,
        finalUrl = "https://bksycenter.bjtu.edu.cn/ve/",
        body = body.encodeToByteArray(),
    )

    private fun String.queryInt(name: String): Int = substringAfter("$name=")
        .substringBefore('&')
        .toInt()

    private fun legacyResponse(body: String) = SchoolHttpResponse(
        statusCode = 200,
        finalUrl = "http://123.121.147.7:88/ve/",
        body = body.encodeToByteArray(),
    )

    private fun smartBytes(bytes: ByteArray, contentType: String) = SchoolHttpResponse(
        statusCode = 200,
        finalUrl = "https://bksycenter.bjtu.edu.cn/ve/download",
        headers = mapOf("Content-Type" to listOf(contentType)),
        body = bytes,
    )

    private fun homeworkList(id: Int, title: String) = """{
      "STATUS":"0","courseNoteList":[{
        "id":$id,"course_id":17,"course_name":"程序设计","title":"$title",
        "end_time":"2026-08-01 20:00","subStatus":"未提交","scoreId":0
      }]
    }""".trimIndent()

    private fun homework() = Homework(
        id = 1,
        upId = 101,
        idSnId = 201,
        score = "95",
        userId = 301,
        courseId = 17,
        courseName = "程序设计",
        title = "综合作业",
        content = "要求",
        createDate = "2026-07-01 08:00",
        endTime = "2026-08-01 20:00",
        openDate = "2026-07-01 09:00",
        status = 1,
        submitCount = 1,
        allCount = 30,
        subStatus = "已提交",
        scoreId = 1,
        homeworkType = 0,
    )
}
