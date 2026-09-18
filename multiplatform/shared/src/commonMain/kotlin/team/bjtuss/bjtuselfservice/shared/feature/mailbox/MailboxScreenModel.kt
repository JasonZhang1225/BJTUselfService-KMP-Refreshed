package team.bjtuss.bjtuselfservice.shared.feature.mailbox

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import team.bjtuss.bjtuselfservice.shared.data.mailbox.MailboxRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.data.mailbox.MailboxRemoteFailure
import team.bjtuss.bjtuselfservice.shared.data.mailbox.MailboxRemoteException
import team.bjtuss.bjtuselfservice.shared.data.mailbox.SchoolMailboxRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailComposeDraft
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailMessage
import team.bjtuss.bjtuselfservice.shared.domain.mailbox.MailSummary
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.webview.SchoolWebDomainPolicy
import team.bjtuss.bjtuselfservice.shared.webview.WebCookie
import team.bjtuss.bjtuselfservice.shared.webview.WebPageRequest
import team.bjtuss.bjtuselfservice.shared.webview.WebPageValidation

private const val MAILBOX_URL = "https://mis.bjtu.edu.cn/module/module/26/"
private const val MAILBOX_HOST = "mis.bjtu.edu.cn"

sealed interface MailboxUiState {
    data object Idle : MailboxUiState
    data object Preparing : MailboxUiState
    data class Ready(
        val request: WebPageRequest,
        val folders: List<MailboxFolderUi> = defaultMailboxFolders,
        val selectedFolderId: Int = INBOX_FOLDER_ID,
        val messages: List<MailSummary> = emptyList(),
        val totalCount: Int = 0,
        val selectedMessage: MailMessage? = null,
        val pendingMessageId: String? = null,
        val isListLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasMoreMessages: Boolean = false,
        val isMessageLoading: Boolean = false,
        val failure: MailboxFailure? = null,
        val compose: MailboxComposeState = MailboxComposeState(),
    ) : MailboxUiState
    data object SessionUnavailable : MailboxUiState
}

data class MailboxFolderUi(
    val id: Int,
    val name: String,
    val kind: MailboxFolderKind = MailboxFolderKind.CUSTOM,
    val section: MailboxFolderSection = MailboxFolderSection.PRIMARY,
    val unreadCount: Int? = null,
)

data class MailboxComposeState(
    val draft: MailComposeDraft? = null,
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val failure: MailboxFailure? = null,
)

/** Coremail 内置文件夹的语义，用于图标和发件箱中的对方字段展示。 */
enum class MailboxFolderKind {
    INBOX,
    TODO,
    DRAFTS,
    SENT,
    DELETED,
    SPAM,
    VIRUS,
    CUSTOM,
}

enum class MailboxFolderSection {
    PRIMARY,
    OTHER,
}

enum class MailboxFailure {
    NETWORK,
    PARSE,
    SESSION_EXPIRED,
}

private const val INBOX_FOLDER_ID = 1
private const val DRAFTS_FOLDER_ID = 2
private const val SENT_FOLDER_ID = 3
private const val TODO_FOLDER_ID = -5
private const val DELETED_FOLDER_ID = 4
private const val SPAM_FOLDER_ID = 5
private const val VIRUS_FOLDER_ID = 6
private const val PAGE_SIZE = 20

/** 首页未读徽标探测：Coremail 无未读计数字段，只能拉收件箱一页消息数未读；超过此上限按封顶显示。 */
private const val UNREAD_PROBE_LIMIT = 50

/** 收件箱未读摘要；[capped] 为 true 表示未读数可能超过探测页大小。 */
data class MailboxUnreadSummary(val count: Int, val capped: Boolean)
private val defaultMailboxFolders = listOf(
    // Coremail 的 ztMail 顺序不是 FID：已发送在网页 hash 中是 fid=3，草稿箱是 fid=2。
    MailboxFolderUi(INBOX_FOLDER_ID, "收件箱", MailboxFolderKind.INBOX),
    MailboxFolderUi(TODO_FOLDER_ID, "待办邮件", MailboxFolderKind.TODO),
    MailboxFolderUi(DRAFTS_FOLDER_ID, "草稿箱", MailboxFolderKind.DRAFTS),
    MailboxFolderUi(SENT_FOLDER_ID, "已发送", MailboxFolderKind.SENT),
    MailboxFolderUi(DELETED_FOLDER_ID, "已删除", MailboxFolderKind.DELETED, MailboxFolderSection.OTHER),
    MailboxFolderUi(SPAM_FOLDER_ID, "垃圾邮件", MailboxFolderKind.SPAM, MailboxFolderSection.OTHER),
    MailboxFolderUi(VIRUS_FOLDER_ID, "病毒邮件", MailboxFolderKind.VIRUS, MailboxFolderSection.OTHER),
)

class MailboxScreenModel(
    private val transport: SchoolHttpTransport,
    private val remote: MailboxRemoteDataSource = SchoolMailboxRemoteDataSource(transport),
) {
    private val mutableState = MutableStateFlow<MailboxUiState>(MailboxUiState.Idle)
    val state: StateFlow<MailboxUiState> = mutableState.asStateFlow()

    // 首页「新邮件」徽标用的收件箱未读摘要；null 表示尚未探测成功（首页回退 MIS 聚合值）。
    private val _unreadSummary = MutableStateFlow<MailboxUnreadSummary?>(null)
    val unreadSummary: StateFlow<MailboxUnreadSummary?> = _unreadSummary.asStateFlow()

    private val operationMutex = Mutex()
    // 详情页可能在上一次读取尚未返回时被退出并重新打开；只允许当前消息的结果落地。
    private var messageRequestId: String? = null
    // 每次 prepare/open 递增。原生详情页 onDispose 必须带上打开时的代次，
    // 否则快速返回后再打开下一封时，上一页迟到的 clear 会把新详情打成空页。
    private var messageGeneration = 0

    suspend fun initialize() {
        if (mutableState.value == MailboxUiState.Idle) refresh()
    }

    /**
     * 轻量探测收件箱未读数供首页徽标使用（Coremail 无未读计数字段，只能拉一页消息数未读）。
     * 会话失效或网络错误时保留上一次成功值，不清零，避免首页在偶发失败时回退到误导数字。
     */
    suspend fun refreshUnreadInboxCount() {
        val summary = runCatching {
            val page = remote.listMessages(
                folderId = INBOX_FOLDER_ID,
                start = 0,
                limit = UNREAD_PROBE_LIMIT,
                descending = true,
            )
            val unread = page.messages.count { !it.isRead }
            MailboxUnreadSummary(count = unread, capped = unread >= UNREAD_PROBE_LIMIT)
        }.getOrNull()
        if (summary != null) _unreadSummary.value = summary
    }

    suspend fun refresh() {
        operationMutex.withLock {
            val previous = mutableState.value as? MailboxUiState.Ready
            messageRequestId = null
            messageGeneration += 1
            mutableState.value = MailboxUiState.Preparing
            val cookies = runCatching { transport.sessionCookiesFor(MAILBOX_URL) }
                .getOrElse {
                    mutableState.value = MailboxUiState.SessionUnavailable
                    return
                }
            if (cookies.isEmpty()) {
                mutableState.value = MailboxUiState.SessionUnavailable
                return
            }

            // 只导出对目标 URL 生效的 Cookie，并把 Domain 收窄到当前 MIS host。
            val request = WebPageRequest(
                url = MAILBOX_URL,
                title = "校内邮箱",
                cookies = cookies.map { cookie ->
                    WebCookie(
                        name = cookie.name,
                        value = cookie.value,
                        domain = MAILBOX_HOST,
                        path = cookie.path,
                        secure = cookie.secure,
                    )
                },
            )
            if (SchoolWebDomainPolicy.validate(request) != WebPageValidation.Allowed) {
                mutableState.value = MailboxUiState.SessionUnavailable
                return
            }
            val ready = MailboxUiState.Ready(
                request = request,
                folders = previous?.folders ?: defaultMailboxFolders,
                selectedFolderId = previous?.selectedFolderId ?: INBOX_FOLDER_ID,
                isListLoading = true,
            )
            mutableState.value = ready
            loadMessages(ready.selectedFolderId)
        }
    }

    suspend fun selectFolder(folderId: Int) {
        operationMutex.withLock {
            val current = mutableState.value as? MailboxUiState.Ready ?: return
            mutableState.value = current.copy(
                selectedFolderId = folderId,
                messages = emptyList(),
                totalCount = 0,
                selectedMessage = null,
                pendingMessageId = null,
                isListLoading = true,
                isLoadingMore = false,
                hasMoreMessages = false,
                isMessageLoading = false,
                failure = null,
            )
            messageRequestId = null
            messageGeneration += 1
            loadMessages(folderId)
        }
    }

    /** 加载当前文件夹的下一页；列表状态已经串行化，避免快速点击产生重复页。 */
    suspend fun loadMore() {
        operationMutex.withLock {
            val current = mutableState.value as? MailboxUiState.Ready ?: return
            if (
                current.isListLoading ||
                current.isLoadingMore ||
                !current.hasMoreMessages ||
                current.messages.isEmpty()
            ) {
                return
            }
            mutableState.value = current.copy(isLoadingMore = true, failure = null)
            try {
                val page = remote.listMessages(
                    folderId = current.selectedFolderId,
                    start = current.messages.size,
                    limit = PAGE_SIZE,
                    descending = true,
                )
                val latest = mutableState.value as? MailboxUiState.Ready ?: return
                val existingIds = latest.messages.mapTo(mutableSetOf(), MailSummary::id)
                val merged = latest.messages + page.messages.filter { existingIds.add(it.id) }
                mutableState.value = latest.copy(
                    messages = merged,
                    totalCount = maxOf(latest.totalCount, page.totalCount),
                    isLoadingMore = false,
                    hasMoreMessages = merged.size < page.totalCount && page.messages.isNotEmpty(),
                    failure = null,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailboxRemoteException) {
                mutableState.value = (mutableState.value as? MailboxUiState.Ready)?.copy(
                    isLoadingMore = false,
                    failure = error.reason.toUiFailure(),
                ) ?: return
            } catch (_: Exception) {
                mutableState.value = (mutableState.value as? MailboxUiState.Ready)?.copy(
                    isLoadingMore = false,
                    failure = MailboxFailure.NETWORK,
                ) ?: return
            }
        }
    }

    /** 原生详情页打开时的代次；离开时用同一代次调用 [clearSelectedMessage]。 */
    fun currentMessageGeneration(): Int = messageGeneration

    /** 在原生详情页 push 前同步切到 loading，避免新页面先看到空阅读区。 */
    fun prepareMessage(message: MailSummary) {
        val current = mutableState.value as? MailboxUiState.Ready ?: return
        beginMessageRequest(message.id)
        mutableState.value = current.copy(
            selectedMessage = null,
            pendingMessageId = message.id,
            isMessageLoading = true,
            failure = null,
        )
    }

    /**
     * 详情读取不占用列表互斥锁。上一封还在请求时再打开下一封，必须立刻开始新请求；
     * 过期结果靠 [messageRequestId] 和代次丢弃，不能让第 3、4 封卡在队列里。
     */
    suspend fun openMessage(message: MailSummary) {
        val current = mutableState.value as? MailboxUiState.Ready ?: return
        if (messageRequestId != message.id || !current.isMessageLoading) {
            prepareMessage(message)
        }
        val generation = messageGeneration
        val requestedId = message.id
        try {
            val detail = remote.readMessage(message.id)
            applyMessageResult(generation, requestedId) { latest ->
                latest.copy(
                    selectedMessage = detail,
                    pendingMessageId = requestedId,
                    isMessageLoading = false,
                    failure = null,
                    // readMessage.jsp 已在服务端置 \Seen；同步本地列表项，
                    // 返回后蓝点/加粗即时消失，无需手动刷新。
                    messages = latest.messages.map {
                        if (it.id == requestedId && !it.isRead) it.copy(isRead = true) else it
                    },
                )
            }
            // 首页徽标同步：刚打开的是未读邮件则本地未读数减一。
            if (!message.isRead) {
                _unreadSummary.update { current ->
                    current?.let { it.copy(count = (it.count - 1).coerceAtLeast(0), capped = false) }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailboxRemoteException) {
            applyMessageResult(generation, requestedId) { latest ->
                latest.copy(
                    isMessageLoading = false,
                    failure = error.reason.toUiFailure(),
                )
            }
        } catch (_: Exception) {
            applyMessageResult(generation, requestedId) { latest ->
                latest.copy(
                    isMessageLoading = false,
                    failure = MailboxFailure.NETWORK,
                )
            }
        }
    }

    suspend fun startCompose(replyToMessageId: String? = null) {
        operationMutex.withLock {
            val current = mutableState.value as? MailboxUiState.Ready ?: return
            mutableState.value = current.copy(
                compose = MailboxComposeState(isLoading = true),
            )
            try {
                val draft = remote.beginCompose(replyToMessageId)
                mutableState.value = (mutableState.value as? MailboxUiState.Ready)?.copy(
                    compose = MailboxComposeState(draft = draft),
                ) ?: return
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailboxRemoteException) {
                mutableState.value = (mutableState.value as? MailboxUiState.Ready)?.copy(
                    compose = MailboxComposeState(failure = error.reason.toUiFailure()),
                ) ?: return
            } catch (_: Exception) {
                mutableState.value = (mutableState.value as? MailboxUiState.Ready)?.copy(
                    compose = MailboxComposeState(failure = MailboxFailure.NETWORK),
                ) ?: return
            }
        }
    }

    fun updateCompose(draft: MailComposeDraft) {
        val current = mutableState.value as? MailboxUiState.Ready ?: return
        mutableState.value = current.copy(
            compose = current.compose.copy(draft = draft, failure = null),
        )
    }

    suspend fun sendCompose(): Boolean {
        operationMutex.withLock {
            val current = mutableState.value as? MailboxUiState.Ready ?: return false
            val draft = current.compose.draft ?: return false
            mutableState.value = current.copy(
                compose = current.compose.copy(isSending = true, failure = null),
            )
            return try {
                remote.sendMessage(draft)
                (mutableState.value as? MailboxUiState.Ready)?.let { latest ->
                    mutableState.value = latest.copy(compose = MailboxComposeState())
                }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailboxRemoteException) {
                (mutableState.value as? MailboxUiState.Ready)?.let { latest ->
                    mutableState.value = latest.copy(
                        compose = MailboxComposeState(
                            draft = draft,
                            failure = error.reason.toUiFailure(),
                        ),
                    )
                }
                false
            } catch (_: Exception) {
                (mutableState.value as? MailboxUiState.Ready)?.let { latest ->
                    mutableState.value = latest.copy(
                        compose = MailboxComposeState(
                            draft = draft,
                            failure = MailboxFailure.NETWORK,
                        ),
                    )
                }
                false
            }
        }
    }

    suspend fun cancelCompose() {
        operationMutex.withLock {
            val current = mutableState.value as? MailboxUiState.Ready ?: return
            val composeId = current.compose.draft?.id
            // 先清掉本地编辑态，系统返回/Activity 销毁时不会短暂把编辑器留在根页；
            // 服务器临时草稿再尽力取消。
            mutableState.value = current.copy(compose = MailboxComposeState())
            if (!composeId.isNullOrBlank()) {
                runCatching { remote.cancelCompose(composeId) }
            }
        }
    }

    /**
     * @param openedGeneration 原生详情页打开时的代次。迟到的上一页 dispose 不得清掉正在看的下一封。
     * 壳内返回不传代次，总是清当前选中。
     */
    fun clearSelectedMessage(openedGeneration: Int? = null) {
        val current = mutableState.value as? MailboxUiState.Ready ?: return
        if (openedGeneration != null && openedGeneration != messageGeneration) return
        messageRequestId = null
        messageGeneration += 1
        // 保留 pendingMessageId：原生详情页被上一页误清 loading 后，仍能按它重试。
        mutableState.value = current.copy(selectedMessage = null, isMessageLoading = false)
    }

    fun dismissFailure() {
        val current = mutableState.value as? MailboxUiState.Ready ?: return
        mutableState.value = current.copy(failure = null)
    }

    private fun beginMessageRequest(messageId: String) {
        messageRequestId = messageId
        messageGeneration += 1
    }

    private fun applyMessageResult(
        generation: Int,
        requestedId: String,
        transform: (MailboxUiState.Ready) -> MailboxUiState.Ready,
    ) {
        if (messageGeneration != generation || messageRequestId != requestedId) return
        val latest = mutableState.value as? MailboxUiState.Ready ?: return
        mutableState.value = transform(latest)
    }

    private suspend fun loadMessages(folderId: Int) {
        try {
            val page = remote.listMessages(
                folderId = folderId,
                start = 0,
                limit = PAGE_SIZE,
                descending = true,
            )
            mutableState.value = (mutableState.value as? MailboxUiState.Ready)?.copy(
                messages = page.messages,
                totalCount = page.totalCount,
                isListLoading = false,
                isLoadingMore = false,
                hasMoreMessages = page.messages.size < page.totalCount && page.messages.isNotEmpty(),
                failure = null,
            ) ?: return
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailboxRemoteException) {
            mutableState.value = (mutableState.value as? MailboxUiState.Ready)?.copy(
                isListLoading = false,
                failure = error.reason.toUiFailure(),
            ) ?: return
        } catch (_: Exception) {
            mutableState.value = (mutableState.value as? MailboxUiState.Ready)?.copy(
                isListLoading = false,
                failure = MailboxFailure.NETWORK,
            ) ?: return
        }
    }
}

private fun MailboxRemoteFailure.toUiFailure(): MailboxFailure = when (this) {
    MailboxRemoteFailure.NETWORK -> MailboxFailure.NETWORK
    MailboxRemoteFailure.PARSE -> MailboxFailure.PARSE
    MailboxRemoteFailure.SESSION_EXPIRED -> MailboxFailure.SESSION_EXPIRED
}
