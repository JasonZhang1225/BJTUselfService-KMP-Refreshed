package team.bjtuss.bjtuselfservice.shared.data.mailbox

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailComposeDraft
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

class MailboxRemoteDataSourceTest {
    @Test
    fun bootstrapsCoremailAndUsesRawJsonForListThenReadsDetail() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/XT/index.jsp?sid=fixture-sid",
            ),
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/s/json",
                body = listResponse().encodeToByteArray(),
            ),
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/XT/jsp/readMessage.jsp",
                body = detailResponse().encodeToByteArray(),
            ),
        )
        val remote = SchoolMailboxRemoteDataSource(transport)

        val page = remote.listMessages(folderId = 1, start = 0, limit = 20, descending = true)
        val message = remote.readMessage("message-1")

        assertEquals("message-1", page.messages.single().id)
        assertEquals("课程通知", message.subject)
        assertEquals(3, transport.requests.size)
        val listRequest = transport.requests[1]
        val rawBody = assertNotNull(listRequest.rawBody).decodeToString()
        assertTrue(rawBody.contains("\"func\"") == false)
        assertTrue(rawBody.contains("\"fid\":1"))
        assertEquals("text/x-json; tz=\"Asia/Shanghai\"", listRequest.rawBodyContentType)
        assertEquals("text/x-json", listRequest.headers["Accept"])
        assertEquals("message-1", transport.requests[2].formFields["mid"])
    }

    @Test
    fun downloadsAttachmentFromMboxDataWithMessageAndPart() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(statusCode = 200, finalUrl = "https://mail.bjtu.edu.cn/coremail/XT/index.jsp?sid=fixture-sid"),
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/mbox-data/%E9%99%84%E4%BB%B6.docx?mode=download&mid=m%3A1&part=3",
                headers = mapOf(
                    "Content-Type" to listOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
                    "Content-Disposition" to listOf("attachment; filename=x.docx"),
                ),
                body = byteArrayOf(0x50, 0x4B, 3, 4),
            ),
        )
        val content = SchoolMailboxRemoteDataSource(transport).downloadAttachment("m:1", "3")

        assertEquals(4, content.bytes.size)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", content.contentType)
        val url = transport.requests[1].url
        assertTrue(url.startsWith("https://mail.bjtu.edu.cn/coremail/mbox-data?mode=download&sid=fixture-sid"))
        assertTrue(url.contains("&mid=m%3A1&part=3"))
    }

    @Test
    fun attachmentDownloadLandingOutsideMboxDataIsExpiredSession() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(statusCode = 200, finalUrl = "https://mail.bjtu.edu.cn/coremail/XT/index.jsp?sid=fixture-sid"),
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/index.jsp?cus=1",
                headers = mapOf("Content-Type" to listOf("text/html;charset=UTF-8")),
                body = "<html>login</html>".encodeToByteArray(),
            ),
        )
        val error = assertFailsWith<MailboxRemoteException> {
            SchoolMailboxRemoteDataSource(transport).downloadAttachment("m-1", "3")
        }
        assertEquals(MailboxRemoteFailure.SESSION_EXPIRED, error.reason)
    }

    @Test
    fun rejectsRedirectToNonCoremailHostAsExpiredSession() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://example.test/login",
            ),
        )
        val remote = SchoolMailboxRemoteDataSource(transport)

        val error = assertFailsWith<MailboxRemoteException> {
            remote.listMessages(folderId = 1, start = 0, limit = 20, descending = true)
        }
        assertEquals(MailboxRemoteFailure.SESSION_EXPIRED, error.reason)
    }

    @Test
    fun sendsCoremailSpecialFolderFidForTodoMail() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/XT/index.jsp?sid=fixture-sid",
            ),
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/s/json",
                body = "{\"code\":\"S_OK\",\"total\":0,\"var\":[]}".encodeToByteArray(),
            ),
        )
        val remote = SchoolMailboxRemoteDataSource(transport)

        remote.listMessages(folderId = -5, start = 0, limit = 20, descending = true)

        val rawBody = assertNotNull(transport.requests[1].rawBody).decodeToString()
        assertTrue(rawBody.contains("\"fid\":-5"))
    }

    @Test
    fun createsReplyDraftAndSendsDeliverPayloadWithoutSendingDuringCreate() = runBlocking {
        val transport = QueueTransport(
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/XT/index.jsp?sid=fixture-sid",
            ),
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/XT/jsp/compose.jsp",
                body = """
                    {"code":"S_OK","var":{"id":"compose-1","to":["teacher@example.test"],"subject":"Re: 课程通知","content":"<br><blockquote>原文</blockquote>"}}
                """.trimIndent().encodeToByteArray(),
            ),
            SchoolHttpResponse(
                statusCode = 200,
                finalUrl = "https://mail.bjtu.edu.cn/coremail/s/json",
                body = "{\"code\":\"S_OK\",\"var\":{}}".encodeToByteArray(),
            ),
        )
        val remote = SchoolMailboxRemoteDataSource(transport)

        val draft = remote.beginCompose(replyToMessageId = "message-1")
        assertEquals(
            MailComposeDraft(
                id = "compose-1",
                to = "teacher@example.test",
                subject = "Re: 课程通知",
                bodyText = "原文",
                replyToMessageId = "message-1",
                isReply = true,
            ),
            draft,
        )

        remote.sendMessage(draft.copy(bodyText = "收到\n谢谢"))

        assertEquals(3, transport.requests.size)
        val createRequest = transport.requests[1]
        assertEquals("reply", createRequest.formFields["ctype"])
        assertEquals("message-1", createRequest.formFields["mid"])
        val sendBody = transport.requests[2].rawBody!!.decodeToString()
        assertTrue(sendBody.contains("\"action\":\"deliver\""))
        assertTrue(sendBody.contains("\"ctype\":\"reply\""))
        assertTrue(sendBody.contains("收到<br>谢谢"))
    }

    private fun listResponse(): String =
        """{"code":"S_OK","total":1,"var":[{"id":"message-1","fid":1,"from":"teacher@example.test","subject":"课程通知","summary":"请查看安排","sentDate":"2026-08-29 09:10:00","receivedDate":"2026-08-29 09:10:00","size":128,"flags":{"read":true}}]}"""

    private fun detailResponse(): String =
        """{"code":"S_OK","var":{"mail":{"from":["teacher@example.test"],"to":["student@example.test"],"subject":"课程通知","mainPartData":{"content":"<p>正文</p>"},"attachments":[]},"mailInfo":{"id":"message-1","fid":1,"sentDate":"2026-08-29 09:10:00"}}}"""

    private class QueueTransport(vararg responses: SchoolHttpResponse) : SchoolHttpTransport {
        private val queue = responses.toMutableList()
        val requests = mutableListOf<SchoolHttpRequest>()

        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse {
            requests += request
            return queue.removeFirst()
        }

        override fun clearSession() = Unit
    }
}
