package team.bjtuss.bjtuselfservice.shared.data.mailbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailMessage
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailboxPage

class MailboxJsonParserTest {
    @Test
    fun parsesMessageListWithoutDependingOnEmailContent() {
        val result = parseMailboxMessageList(
            """
            {
              "code":"S_OK",
              "total":2,
              "var":[
                {
                  "id":"message-1",
                  "fid":1,
                  "from":"老师 <teacher@example.test>",
                  "subject":"课程通知",
                  "summary":"请查看本周安排",
                  "sentDate":"2026-08-29 09:10:00",
                  "receivedDate":"2026-08-29 09:10:01",
                  "size":2048,
                  "flags":{"read":false,"attached":true}
                },
                {
                  "id":"message-2",
                  "fid":1,
                  "from":["service@example.test"],
                  "subject":"无附件邮件",
                  "summary":"纯文本摘要",
                  "sentDate":"2026-08-28 18:00:00",
                  "receivedDate":"2026-08-28 18:00:00",
                  "size":64,
                  "flags":{"read":true}
                }
              ]
            }
            """.trimIndent(),
        )

        val page = assertIs<MailboxJsonParseResult.Success<MailboxPage>>(result).value
        assertEquals(2, page.totalCount)
        assertEquals(2, page.messages.size)
        assertEquals("message-1", page.messages.first().id)
        assertFalse(page.messages.first().isRead)
        assertTrue(page.messages.first().hasAttachments)
        assertEquals("service@example.test", page.messages.last().sender)
    }

    @Test
    fun treatsMissingReadFlagAsUnread() {
        // Coremail 真实行为：未读邮件的 flags 里根本没有 read 键，已读才带 read=true。
        val result = parseMailboxMessageList(
            """
            {
              "code":"S_OK",
              "total":2,
              "var":[
                {"id":"unread-1","fid":1,"from":"a@example.test","subject":"新邮件","flags":{}},
                {"id":"read-1","fid":1,"from":"b@example.test","subject":"旧邮件","flags":{"read":true}}
              ]
            }
            """.trimIndent(),
        )

        val page = assertIs<MailboxJsonParseResult.Success<MailboxPage>>(result).value
        assertTrue(page.messages.first().isRead.not())
        assertTrue(page.messages.last().isRead)
    }

    @Test
    fun parsesMessageDetailAndAttachmentMetadata() {
        val result = parseMailboxMessage(
            """
            {
              "code":"S_OK",
              "var":{
                "mail":{
                  "from":["teacher@example.test"],
                  "to":["student@example.test"],
                  "cc":["class@example.test"],
                  "bcc":[],
                  "subject":"实验安排",
                  "mainPartData":{"content":"<p>第一段</p><p>第二段</p>"},
                  "attachments":[{"id":"attachment-1","name":"schedule.pdf","size":4096,"contentType":"application/pdf"}]
                },
                "mailInfo":{"id":"message-1","fid":1,"sentDate":"2026-08-29 09:10:00"}
              }
            }
            """.trimIndent(),
            fallbackMessageId = "fallback-id",
        )

        val message = assertIs<MailboxJsonParseResult.Success<MailMessage>>(result).value
        assertEquals("message-1", message.id)
        assertEquals(listOf("teacher@example.test"), message.from)
        assertEquals(listOf("student@example.test"), message.to)
        assertEquals(listOf("class@example.test"), message.cc)
        assertEquals("实验安排", message.subject)
        assertEquals("schedule.pdf", message.attachments.single().name)
        assertEquals(4096, message.attachments.single().sizeBytes)
    }

    @Test
    fun usesRecipientsWhenSentFolderOmitsSender() {
        val result = parseMailboxMessageList(
            """
            {"code":"S_OK","total":1,"var":[
              {"id":"sent-1","fid":3,"to":["同学 <student@example.test>"],
               "subject":"资料","summary":"已发送","sentDate":"2026-08-29 10:00:00",
               "receivedDate":"2026-08-29 10:00:00","size":32,"flags":{"read":true}}
            ]}
            """.trimIndent(),
        )

        val page = assertIs<MailboxJsonParseResult.Success<MailboxPage>>(result).value
        assertEquals("同学 <student@example.test>", page.messages.single().sender)
        assertEquals(listOf("同学 <student@example.test>"), page.messages.single().recipients)
        assertEquals(3, page.messages.single().folderId)
    }

    @Test
    fun rejectsNonSuccessResponseWithoutLeakingItsBody() {
        val result = parseMailboxMessageList(
            """{"code":"E_SESSION","message":"private diagnostic"}""",
        )

        assertEquals(MailboxJsonParseResult.Failure("code"), result)
        assertFalse(result.toString().contains("private diagnostic"))
    }
}
