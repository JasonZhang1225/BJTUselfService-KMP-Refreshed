package team.bjtuss.bjtuselfservice.shared.feature.mailbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import team.bjtuss.bjtuselfservice.shared.runtime.AccountWork
import team.bjtuss.bjtuselfservice.shared.data.mailbox.MailboxRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.mailbox.MailboxRemoteException
import team.bjtuss.bjtuselfservice.shared.data.mailbox.MailboxRemoteFailure
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachment
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailAttachmentContent
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailComposeDraft
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailMessage
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailboxPage
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailSummary
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator
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
    private fun attachmentModel(block: suspend (String, String) -> MailAttachmentContent): MailboxScreenModel {
        val summary = sampleSummary("m-1", "附件")
        return MailboxScreenModel(
            transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
            remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 1, messages = listOf(summary)),
                detail = summary.toDetail(),
                attachmentBlock = block,
            ),
        )
    }

    @Test
    fun downloadAttachmentPassesMessageAndPartIds() = runBlocking {
        var requested: Pair<String, String>? = null
        val model = attachmentModel { mid, part ->
            requested = mid to part
            MailAttachmentContent(byteArrayOf(7), "application/pdf")
        }
        val result = model.downloadAttachment("m-1", MailAttachment("3", "a.pdf", 1, "application/pdf"))
        assertEquals("m-1" to "3", requested)
        assertEquals(1, assertIs<MailAttachmentDownloadResult.Success>(result).content.bytes.size)
    }

    @Test
    fun downloadAttachmentRejectsOversizedAndMissingIdsWithoutNetwork() = runBlocking {
        var calls = 0
        val model = attachmentModel { _, _ -> calls++; MailAttachmentContent(ByteArray(0), null) }
        val oversized = model.downloadAttachment("m-1", MailAttachment("3", "big.zip", MAIL_ATTACHMENT_MAX_BYTES + 1, null))
        val missing = model.downloadAttachment("m-1", MailAttachment(null, "x.pdf", 10, null))
        assertEquals(MailAttachmentFailure.TOO_LARGE, assertIs<MailAttachmentDownloadResult.Failed>(oversized).reason)
        assertEquals(MailAttachmentFailure.UNAVAILABLE, assertIs<MailAttachmentDownloadResult.Failed>(missing).reason)
        assertEquals(0, calls)
    }

    @Test
    fun downloadAttachmentMapsSessionExpiryAndNetworkFailures() = runBlocking {
        val expired = attachmentModel { _, _ -> throw MailboxRemoteException(MailboxRemoteFailure.SESSION_EXPIRED) }
            .downloadAttachment("m-1", MailAttachment("3", "a.pdf", 1, null))
        val network = attachmentModel { _, _ -> throw MailboxRemoteException(MailboxRemoteFailure.NETWORK) }
            .downloadAttachment("m-1", MailAttachment("3", "a.pdf", 1, null))
        assertEquals(MailAttachmentFailure.SESSION_EXPIRED, assertIs<MailAttachmentDownloadResult.Failed>(expired).reason)
        assertEquals(MailAttachmentFailure.NETWORK, assertIs<MailAttachmentDownloadResult.Failed>(network).reason)
    }

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

    @Test
    fun cancelledInitializeDoesNotLeaveLoadingStuckOrSkipRefreshWithSession() = runBlocking {
        val summary = sampleSummary("m-init", "收件箱邮件")
        val initEntered = CompletableDeferred<Unit>()
        val probeEntered = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        var listCalls = 0
        val remote = FakeMailboxRemote(
            page = MailboxPage(totalCount = 1, messages = listOf(summary)),
            detail = summary.toDetail(),
            listBlock = {
                if (++listCalls == 1) {
                    initEntered.complete(Unit)
                    awaitCancellation()
                }
            },
        )
        val model = MailboxScreenModel(
            transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
            remote = remote,
        )

        val initJob = launch { model.initialize() }
        withTimeout(5_000) { initEntered.await() }

        val session = SessionRefreshCoordinator(
            reauthenticate = { true },
            probeSession = {
                probeEntered.complete(Unit)
                releaseProbe.await()
                true
            },
        )
        val refreshJob = launch(start = CoroutineStart.UNDISPATCHED) { model.refreshWithSession(session) }
        withTimeout(5_000) { probeEntered.await() }

        initJob.cancelAndJoin()
        val pausedState = assertIs<MailboxUiState.Ready>(model.state.value)
        assertFalse(pausedState.isListLoading)

        releaseProbe.complete(Unit)
        withTimeout(5_000) { refreshJob.join() }

        val ready = assertIs<MailboxUiState.Ready>(model.state.value)
        assertFalse(ready.isListLoading)
        assertEquals(listOf(summary), ready.messages)
        assertEquals(2, listCalls)
    }

    @Test
    fun cancellationDuringCookieLookupPropagatesAndRestoresIdleState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val model = MailboxScreenModel(
            transport = CookieTransport(
                onCookies = {
                    entered.complete(Unit)
                    awaitCancellation()
                },
            ),
            remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 0, messages = emptyList()),
                detail = emptyDetail(),
            ),
        )

        val job = launch { model.refresh() }
        withTimeout(5_000) { entered.await() }
        job.cancelAndJoin()

        assertEquals(MailboxUiState.Idle, model.state.value)
    }

    @Test
    fun sessionRecoveryPreservesSelectedNonInboxFolder() = runBlocking {
        val sentSummary = sampleSummary("m-sent", "已发送邮件").copy(folderId = 3)
        var hasMailboxCookie = true
        val remote = FakeMailboxRemote(
            page = MailboxPage(totalCount = 1, messages = listOf(sentSummary)),
            detail = sentSummary.toDetail(),
        )
        val model = MailboxScreenModel(
            transport = CookieTransport(
                onCookies = {
                    if (hasMailboxCookie) listOf(SchoolSessionCookie("session", "secret")) else emptyList()
                },
            ),
            remote = remote,
        )
        model.initialize()
        model.selectFolder(3)

        hasMailboxCookie = false
        var recoveries = 0
        val session = SessionRefreshCoordinator(
            reauthenticate = {
                recoveries++
                hasMailboxCookie = true
                true
            },
            probeSession = { true },
        )
        model.refreshWithSession(session)

        assertEquals(1, recoveries)
        val ready = assertIs<MailboxUiState.Ready>(model.state.value)
        assertEquals(3, ready.selectedFolderId)
        assertEquals(3, remote.requestedFolderIds.last())
    }

    @Test
    fun refreshWithSessionJoiningCoordinatorlessRefreshRecoversAndRetries() = runBlocking {
        val summary = sampleSummary("m-join", "恢复后的邮件")
        val cookiesEntered = CompletableDeferred<Unit>()
        val releaseCookies = CompletableDeferred<Unit>()
        var cookieCalls = 0
        var recovered = false
        val remote = FakeMailboxRemote(
            page = MailboxPage(totalCount = 1, messages = listOf(summary)),
            detail = summary.toDetail(),
        )
        val model = MailboxScreenModel(
            transport = CookieTransport(
                onCookies = {
                    if (++cookieCalls == 1) {
                        cookiesEntered.complete(Unit)
                        releaseCookies.await()
                    }
                    if (recovered) listOf(SchoolSessionCookie("session", "secret")) else emptyList()
                },
            ),
            remote = remote,
        )

        val manualNoSession = launch { model.refresh() }
        withTimeout(5_000) { cookiesEntered.await() }

        var recoveries = 0
        val session = SessionRefreshCoordinator(
            reauthenticate = {
                recoveries++
                recovered = true
                true
            },
            probeSession = { true },
        )
        val joinedWithSession = launch(start = CoroutineStart.UNDISPATCHED) {
            model.refreshWithSession(session)
        }
        releaseCookies.complete(Unit)
        withTimeout(5_000) {
            manualNoSession.join()
            joinedWithSession.join()
        }

        assertEquals(1, recoveries)
        val ready = assertIs<MailboxUiState.Ready>(model.state.value)
        assertEquals(listOf(summary), ready.messages)
    }

    @Test
    fun multipleJoinedRefreshWithSessionCallersAwaitSharedRecoveryEvenIfFirstJoinerCancels() = runBlocking {
        val account = AccountWork()
        try {
            val summary = sampleSummary("m-multi-join", "共享恢复邮件")
            val cookiesEntered = CompletableDeferred<Unit>()
            val releaseCookies = CompletableDeferred<Unit>()
            val recoveryEntered = CompletableDeferred<Unit>()
            val releaseRecovery = CompletableDeferred<Unit>()
            var cookieCalls = 0
            var recovered = false
            val remote = FakeMailboxRemote(
                page = MailboxPage(totalCount = 1, messages = listOf(summary)),
                detail = summary.toDetail(),
            )
            val model = MailboxScreenModel(
                transport = CookieTransport(
                    onCookies = {
                        if (++cookieCalls == 1) {
                            cookiesEntered.complete(Unit)
                            releaseCookies.await()
                        }
                        if (recovered) listOf(SchoolSessionCookie("session", "secret")) else emptyList()
                    },
                ),
                remote = remote,
                accountWork = account,
                refreshScope = account.scope(Dispatchers.Default),
            )

            val manualNoSession = launch { model.refresh() }
            withTimeout(5_000) { cookiesEntered.await() }

            var recoveries = 0
            val firstSession = SessionRefreshCoordinator(
                reauthenticate = {
                    recoveries++
                    recoveryEntered.complete(Unit)
                    releaseRecovery.await()
                    recovered = true
                    true
                },
                probeSession = { true },
            )
            val secondSession = SessionRefreshCoordinator(
                reauthenticate = {
                    recoveries++
                    true
                },
                probeSession = { true },
            )

            val firstJoiner = launch(start = CoroutineStart.UNDISPATCHED) {
                model.refreshWithSession(firstSession)
            }
            val secondJoiner = launch(start = CoroutineStart.UNDISPATCHED) {
                model.refreshWithSession(secondSession)
            }
            releaseCookies.complete(Unit)
            withTimeout(5_000) { recoveryEntered.await() }

            firstJoiner.cancelAndJoin()
            assertFalse(secondJoiner.isCompleted)

            releaseRecovery.complete(Unit)
            withTimeout(5_000) {
                manualNoSession.join()
                secondJoiner.join()
            }

            assertEquals(1, recoveries)
            val ready = assertIs<MailboxUiState.Ready>(model.state.value)
            assertEquals(listOf(summary), ready.messages)
        } finally {
            account.stop()
            account.join()
        }
    }

    @Test
    fun folderSwitchAfterFirstLoadInvalidatesReuseForPendingRefreshWithSession() = runBlocking {
        val summary = sampleSummary("m-reselect", "收件箱新邮件")
        val initListEntered = CompletableDeferred<Unit>()
        val releaseInitList = CompletableDeferred<Unit>()
        val probeEntered = CompletableDeferred<Unit>()
        val releaseProbe = CompletableDeferred<Unit>()
        val cancelableInboxReloadEntered = CompletableDeferred<Unit>()
        var listCalls = 0
        val remote = FakeMailboxRemote(
            page = MailboxPage(totalCount = 1, messages = listOf(summary)),
            detail = summary.toDetail(),
            listBlock = {
                when (++listCalls) {
                    1 -> {
                        initListEntered.complete(Unit)
                        releaseInitList.await()
                    }
                    3 -> {
                        cancelableInboxReloadEntered.complete(Unit)
                        awaitCancellation()
                    }
                }
            },
        )
        val model = MailboxScreenModel(
            transport = CookieTransport(listOf(SchoolSessionCookie("session", "secret"))),
            remote = remote,
        )

        val initJob = launch { model.initialize() }
        withTimeout(5_000) { initListEntered.await() }

        val session = SessionRefreshCoordinator(
            reauthenticate = { true },
            probeSession = {
                probeEntered.complete(Unit)
                releaseProbe.await()
                true
            },
        )
        val refreshJob = launch(start = CoroutineStart.UNDISPATCHED) { model.refreshWithSession(session) }
        withTimeout(5_000) { probeEntered.await() }

        releaseInitList.complete(Unit)
        withTimeout(5_000) { initJob.join() }

        // 先切到已发送，再切回收件箱并在列表拉取阶段取消，确保收件箱列表已被清空。
        model.selectFolder(3)
        val returnToInbox = launch { model.selectFolder(1) }
        withTimeout(5_000) { cancelableInboxReloadEntered.await() }
        returnToInbox.cancelAndJoin()
        val cleared = assertIs<MailboxUiState.Ready>(model.state.value)
        assertEquals(1, cleared.selectedFolderId)
        assertTrue(cleared.messages.isEmpty())

        releaseProbe.complete(Unit)
        withTimeout(5_000) { refreshJob.join() }

        val reloaded = assertIs<MailboxUiState.Ready>(model.state.value)
        assertEquals(1, reloaded.selectedFolderId)
        assertEquals(listOf(summary), reloaded.messages)
        assertEquals(4, listCalls)
    }
}

private class CookieTransport(
    private val cookies: List<SchoolSessionCookie> = emptyList(),
    private val failure: Boolean = false,
    private val onCookies: (suspend () -> List<SchoolSessionCookie>)? = null,
) : SchoolHttpTransport {
    override suspend fun execute(request: SchoolHttpRequest) = SchoolHttpResponse(200, request.url)

    override suspend fun sessionCookiesFor(url: String): List<SchoolSessionCookie> {
        if (failure) error("cookie storage")
        return onCookies?.invoke() ?: cookies
    }

    override fun clearSession() = Unit
}

private class FakeMailboxRemote(
    private val page: MailboxPage,
    private val detail: MailMessage,
    private val nextPage: MailboxPage? = null,
    private val details: Map<String, MailMessage> = emptyMap(),
    private val readBlock: suspend (String) -> Unit = {},
    private val listBlock: suspend (Int) -> Unit = {},
    private val attachmentBlock: suspend (String, String) -> MailAttachmentContent = { _, _ ->
        MailAttachmentContent(byteArrayOf(1, 2, 3), "application/pdf")
    },
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
        listBlock(folderId)
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

    override suspend fun downloadAttachment(messageId: String, attachmentId: String): MailAttachmentContent =
        attachmentBlock(messageId, attachmentId)
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
