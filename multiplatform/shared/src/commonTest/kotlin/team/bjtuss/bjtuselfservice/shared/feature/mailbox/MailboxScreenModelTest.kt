package team.bjtuss.bjtuselfservice.shared.feature.mailbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.data.mailbox.MailboxRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailComposeDraft
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailMessage
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailboxPage
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailSummary
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.SchoolSessionCookie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailboxScreenModelTest {
    @Test
    fun preparesMailboxWithNarrowedMisCookies() {
        runBlocking {
            val model = MailboxScreenModel(
                CookieTransport(listOf(SchoolSessionCookie("session", "secret", "/module/"))),
            )
            model.initialize()
            val ready = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals("https://mis.bjtu.edu.cn/module/module/26/", ready.request.url)
            assertEquals("mis.bjtu.edu.cn", ready.request.cookies.single().domain)
            assertEquals("/module/", ready.request.cookies.single().path)
            assertFalse(ready.request.toString().contains("secret"))
            assertEquals(
                listOf(1, -5, 2, 3, 4, 5, 6),
                ready.folders.map { it.id },
            )
            assertEquals("已发送", ready.folders[3].name)
        }
    }

    @Test
    fun emptySessionIsReported() {
        runBlocking {
            val model = MailboxScreenModel(CookieTransport(emptyList()))
            model.refresh()
            assertIs<MailboxUiState.SessionUnavailable>(model.state.value)
        }
    }

    @Test
    fun cookieReadFailureIsReported() {
        runBlocking {
            val model = MailboxScreenModel(CookieTransport(failure = true))
            model.refresh()
            assertIs<MailboxUiState.SessionUnavailable>(model.state.value)
        }
    }

    @Test
    fun loadsListOpensDetailAndReturnsToList() {
        runBlocking {
            val summary = MailSummary(
                id = "message-1",
                folderId = 1,
                sender = "teacher@example.test",
                subject = "课程通知",
                preview = "请查看安排",
                sentAt = "2026-08-29 09:10:00",
                receivedAt = "2026-08-29 09:10:00",
                sizeBytes = 128,
                isRead = true,
                hasAttachments = false,
            )
            val detail = MailMessage(
                id = summary.id,
                folderId = summary.folderId,
                from = listOf(summary.sender),
                to = listOf("student@example.test"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = summary.subject,
                bodyHtml = "<p>正文</p>",
                sentAt = summary.sentAt,
                attachments = emptyList(),
            )
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 1, messages = listOf(summary)),
                detail = detail,
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = remote,
            )

            model.initialize()
            val listState = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(listOf(summary), listState.messages)
            assertEquals(1, listState.totalCount)

            model.prepareMessage(summary)
            val loadingState = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(null, loadingState.selectedMessage)
            assertEquals(summary.id, loadingState.pendingMessageId)
            assertTrue(loadingState.isMessageLoading)

            model.openMessage(summary)
            val detailState = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(detail, detailState.selectedMessage)
            assertFalse(detailState.isMessageLoading)

            model.clearSelectedMessage()
            val returnedState = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(null, returnedState.selectedMessage)
            assertEquals(summary.id, returnedState.pendingMessageId)
            assertEquals(listOf(summary), returnedState.messages)
        }
    }

    @Test
    fun openingUnreadMessageMarksItReadLocally() {
        runBlocking {
            val unread = MailSummary(
                id = "message-unread",
                folderId = 1,
                sender = "teacher@example.test",
                subject = "新邮件",
                preview = "正文摘要",
                sentAt = "2026-08-29 09:10:00",
                receivedAt = "2026-08-29 09:10:00",
                sizeBytes = 128,
                isRead = false,
                hasAttachments = false,
            )
            val detail = MailMessage(
                id = unread.id,
                folderId = unread.folderId,
                from = listOf(unread.sender),
                to = listOf("student@example.test"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = unread.subject,
                bodyHtml = "<p>正文</p>",
                sentAt = unread.sentAt,
                attachments = emptyList(),
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = FakeMailboxRemote(
                    page = MailboxPage(totalCount = 1, messages = listOf(unread)),
                    detail = detail,
                ),
            )

            model.initialize()
            assertEquals(false, assertIs<MailboxUiState.Ready>(model.state.value).messages.single().isRead)

            model.openMessage(unread)

            val afterOpen = assertIs<MailboxUiState.Ready>(model.state.value)
            assertTrue(afterOpen.messages.single().isRead)
        }
    }

    @Test
    fun unreadProbeCountsInboxAndDecrementsOnOpen() {
        runBlocking {
            val unread = MailSummary(
                id = "message-unread",
                folderId = 1,
                sender = "a@example.test",
                subject = "新邮件",
                preview = "",
                sentAt = "",
                receivedAt = "",
                sizeBytes = 0,
                isRead = false,
                hasAttachments = false,
            )
            val read = MailSummary(
                id = "message-read",
                folderId = 1,
                sender = "b@example.test",
                subject = "旧邮件",
                preview = "",
                sentAt = "",
                receivedAt = "",
                sizeBytes = 0,
                isRead = true,
                hasAttachments = false,
            )
            val detail = MailMessage(
                id = unread.id,
                folderId = 1,
                from = listOf(unread.sender),
                to = emptyList(),
                cc = emptyList(),
                bcc = emptyList(),
                subject = unread.subject,
                bodyHtml = "<p>x</p>",
                sentAt = "",
                attachments = emptyList(),
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = FakeMailboxRemote(
                    page = MailboxPage(totalCount = 2, messages = listOf(unread, read)),
                    detail = detail,
                ),
            )

            model.refreshUnreadInboxCount()
            assertEquals(1, model.unreadSummary.value?.count)

            model.initialize()
            model.openMessage(unread)
            // 打开未读邮件后首页徽标未读数本地减一。
            assertEquals(0, model.unreadSummary.value?.count)
        }
    }

    @Test
    fun loadsNextMailboxPageWithoutDuplicatingMessages() {
        runBlocking {
            val first = MailSummary(
                id = "message-1",
                folderId = 1,
                sender = "first@example.test",
                subject = "第一页",
                preview = "摘要",
                sentAt = "2026-08-29 09:10:00",
                receivedAt = "2026-08-29 09:10:00",
                sizeBytes = 128,
                isRead = true,
                hasAttachments = false,
            )
            val second = first.copy(id = "message-2", subject = "第二页")
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 2, messages = listOf(first)),
                detail = emptyDetail(),
                nextPage = MailboxPage(totalCount = 2, messages = listOf(first, second)),
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = remote,
            )

            model.initialize()
            assertTrue(assertIs<MailboxUiState.Ready>(model.state.value).hasMoreMessages)

            model.loadMore()

            val ready = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(listOf(first, second), ready.messages)
            assertFalse(ready.hasMoreMessages)
            assertFalse(ready.isLoadingMore)
        }
    }

    @Test
    fun startsReplyWithServerDraftAndClearsItAfterSuccessfulSend() {
        runBlocking {
            val summary = MailSummary(
                id = "message-1",
                folderId = 1,
                sender = "teacher@example.test",
                subject = "课程通知",
                preview = "请查看安排",
                sentAt = "2026-08-29 09:10:00",
                receivedAt = "2026-08-29 09:10:00",
                sizeBytes = 128,
                isRead = true,
                hasAttachments = false,
            )
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 1, messages = listOf(summary)),
                detail = emptyDetail(),
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = remote,
            )

            model.initialize()
            model.startCompose(replyToMessageId = summary.id)

            val ready = assertIs<MailboxUiState.Ready>(model.state.value)
            val draft = assertIs<MailComposeDraft>(ready.compose.draft)
            assertTrue(draft.isReply)
            assertEquals(summary.id, draft.replyToMessageId)

            val edited = draft.copy(to = "student@example.test", subject = "Re: 课程通知", bodyText = "收到")
            model.updateCompose(edited)
            assertTrue(model.sendCompose())
            assertEquals(edited, remote.sentDraft)
            assertEquals(null, assertIs<MailboxUiState.Ready>(model.state.value).compose.draft)
        }
    }

    @Test
    fun selectingSentFolderLoadsItsCoremailFolderId() {
        runBlocking {
            val summary = MailSummary(
                id = "sent-1",
                folderId = 3,
                sender = "student@example.test",
                subject = "已发送资料",
                preview = "",
                sentAt = "2026-08-29 10:00:00",
                receivedAt = "",
                sizeBytes = 32,
                isRead = true,
                hasAttachments = false,
            )
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 1, messages = listOf(summary)),
                detail = emptyDetail(),
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = remote,
            )

            model.initialize()
            model.selectFolder(3)

            assertEquals(listOf(1, 3), remote.requestedFolderIds)
            val ready = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(3, ready.selectedFolderId)
            assertEquals(listOf(summary), ready.messages)
        }
    }

    @Test
    fun staleDetailDisposeDoesNotClearANewerMessage() {
        runBlocking {
            val first = sampleSummary("message-1", "第一封")
            val second = sampleSummary("message-2", "第二封")
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 2, messages = listOf(first, second)),
                detail = emptyDetail(),
                details = mapOf(
                    first.id to first.toDetail(),
                    second.id to second.toDetail(),
                ),
                readBlock = { id ->
                    if (id == first.id) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                },
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = remote,
            )
            model.initialize()

            model.prepareMessage(first)
            val firstGeneration = model.currentMessageGeneration()
            val firstJob = launch { model.openMessage(first) }
            firstStarted.await()

            model.prepareMessage(second)
            val secondJob = launch { model.openMessage(second) }
            secondJob.join()

            model.clearSelectedMessage(openedGeneration = firstGeneration)

            val afterStaleClear = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(second.id, afterStaleClear.selectedMessage?.id)
            assertFalse(afterStaleClear.isMessageLoading)

            releaseFirst.complete(Unit)
            firstJob.join()

            val finalState = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(second.id, finalState.selectedMessage?.id)
            assertEquals("第二封", finalState.selectedMessage?.subject)
            assertFalse(finalState.isMessageLoading)
        }
    }

    @Test
    fun laterMessageDoesNotWaitBehindAStaleDetailRequest() {
        runBlocking {
            val first = sampleSummary("message-1", "第一封")
            val second = sampleSummary("message-2", "第二封")
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 2, messages = listOf(first, second)),
                detail = emptyDetail(),
                details = mapOf(
                    first.id to first.toDetail(),
                    second.id to second.toDetail(),
                ),
                readBlock = { id ->
                    if (id == first.id) {
                        firstStarted.complete(Unit)
                        releaseFirst.await()
                    }
                },
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = remote,
            )
            model.initialize()

            val firstJob = launch { model.openMessage(first) }
            firstStarted.await()
            model.openMessage(second)

            val ready = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(second.id, ready.selectedMessage?.id)
            assertFalse(ready.isMessageLoading)

            releaseFirst.complete(Unit)
            firstJob.join()
            val afterStale = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(second.id, afterStale.selectedMessage?.id)
        }
    }

    @Test
    fun matchingDetailDisposeStillClearsTheCurrentMessage() {
        runBlocking {
            val summary = sampleSummary("message-1", "课程通知")
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 1, messages = listOf(summary)),
                detail = summary.toDetail(),
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = remote,
            )
            model.initialize()
            model.prepareMessage(summary)
            val openedGeneration = model.currentMessageGeneration()
            model.openMessage(summary)

            model.clearSelectedMessage(openedGeneration = openedGeneration)

            val ready = assertIs<MailboxUiState.Ready>(model.state.value)
            assertNull(ready.selectedMessage)
            assertEquals(summary.id, ready.pendingMessageId)
            assertFalse(ready.isMessageLoading)
        }
    }

    @Test
    fun reloadsPendingMessageAfterOverlappingDetailPageClearsLoading() {
        runBlocking {
            val summary = sampleSummary("message-1", "课程通知")
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 1, messages = listOf(summary)),
                detail = summary.toDetail(),
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
                remote = remote,
            )
            model.initialize()
            model.prepareMessage(summary)
            model.openMessage(summary)

            // 上一封详情 Activity 改绑到当前代次后 dispose，相当于清掉当前 loading。
            model.clearSelectedMessage()
            val cleared = assertIs<MailboxUiState.Ready>(model.state.value)
            assertNull(cleared.selectedMessage)
            assertEquals(summary.id, cleared.pendingMessageId)
            assertFalse(cleared.isMessageLoading)

            model.openMessage(summary)
            val reloaded = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(summary.id, reloaded.selectedMessage?.id)
            assertFalse(reloaded.isMessageLoading)
        }
    }
}

private class CookieTransport(
    private val cookies: List<SchoolSessionCookie> = emptyList(),
    private val failure: Boolean = false,
) : SchoolHttpTransport {
    override suspend fun execute(request: SchoolHttpRequest) = SchoolHttpResponse(200, request.url)

    override suspend fun sessionCookiesFor(url: String): List<SchoolSessionCookie> {
        if (failure) error("cookie storage")
        return cookies
    }

    override fun clearSession() = Unit
}

private class FakeMailboxRemote(
    private val page: MailboxPage,
    private val detail: MailMessage,
    private val nextPage: MailboxPage? = null,
    private val details: Map<String, MailMessage> = emptyMap(),
    private val readBlock: suspend (String) -> Unit = {},
) : MailboxRemoteDataSource {
    val requestedFolderIds = mutableListOf<Int>()
    var sentDraft: MailComposeDraft? = null

    override suspend fun listMessages(
        folderId: Int,
        start: Int,
        limit: Int,
        descending: Boolean,
    ): MailboxPage {
        requestedFolderIds += folderId
        return if (start > 0 && nextPage != null) nextPage else page
    }

    override suspend fun readMessage(messageId: String): MailMessage {
        readBlock(messageId)
        return details[messageId] ?: detail
    }

    override suspend fun beginCompose(replyToMessageId: String?): MailComposeDraft =
        MailComposeDraft(
            id = "compose-1",
            replyToMessageId = replyToMessageId,
            isReply = replyToMessageId != null,
        )

    override suspend fun sendMessage(draft: MailComposeDraft) {
        sentDraft = draft
    }

    override suspend fun cancelCompose(composeId: String) = Unit
}

private fun sampleSummary(id: String, subject: String) = MailSummary(
    id = id,
    folderId = 1,
    sender = "teacher@example.test",
    subject = subject,
    preview = "摘要",
    sentAt = "2026-08-29 09:10:00",
    receivedAt = "2026-08-29 09:10:00",
    sizeBytes = 128,
    isRead = true,
    hasAttachments = false,
)

private fun MailSummary.toDetail() = MailMessage(
    id = id,
    folderId = folderId,
    from = listOf(sender),
    to = listOf("student@example.test"),
    cc = emptyList(),
    bcc = emptyList(),
    subject = subject,
    bodyHtml = "<p>$subject</p>",
    sentAt = sentAt,
    attachments = emptyList(),
)

private fun emptyDetail() = MailMessage(
    id = "detail",
    folderId = 1,
    from = emptyList(),
    to = emptyList(),
    cc = emptyList(),
    bcc = emptyList(),
    subject = "",
    bodyHtml = "",
    sentAt = "",
    attachments = emptyList(),
)
