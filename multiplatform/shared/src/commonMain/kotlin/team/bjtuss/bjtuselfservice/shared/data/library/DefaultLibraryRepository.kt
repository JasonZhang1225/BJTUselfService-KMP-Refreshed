package team.bjtuss.bjtuselfservice.shared.data.library

import kotlinx.coroutines.CancellationException

/**
 * 图书馆数据仓库：把“会话过期后重新走一次 SSO→opac 握手”集中在这里。
 *
 * 每个操作都按同一策略执行：直接调用数据源 → 只有失败分类是
 * [LibraryFailureKind.SESSION_EXPIRED] 时才调用 [LibraryAuthenticator.ensureSession]
 * 恢复会话 → 恢复成功则重试一次，恢复失败则抛回原始异常。
 * 重试只发生一次，避免登录失效时无限循环。
 */
class DefaultLibraryRepository(
    private val remote: LibraryRemoteDataSource,
    private val authenticator: LibraryAuthenticator,
) : LibraryRepository {

    override suspend fun loadUser(): LibraryUser =
        retryOnSessionExpired { remote.userInfo() }

    override suspend fun loadLoans(page: Int, rows: Int): LibraryLoanPage =
        retryOnSessionExpired { remote.loans(page, rows) }

    override suspend fun loadHistory(page: Int, rows: Int): LibraryLoanPage =
        retryOnSessionExpired { remote.history(page, rows) }

    override suspend fun renew(loanIds: List<Long>): LibraryRenewResult =
        retryOnSessionExpired { remote.renew(loanIds) }

    private suspend fun <T> retryOnSessionExpired(block: suspend () -> T): T = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: LibraryException) {
        // 网络失败、格式变化、服务端错误都不重登，直接交给界面展示。
        if (error.failure.kind != LibraryFailureKind.SESSION_EXPIRED) throw error
        // ensureSession 返回 null 表示会话已恢复；返回失败时保留原始异常，
        // 保证界面仍按“登录已过期”处理，而不是把登录错误混进书架数据错误。
        if (authenticator.ensureSession() != null) throw error
        // 重试只在这里调用一次：若恢复后的请求仍失败，异常直接从 catch 分支抛出，
        // 不会再经过本方法的重登判断，因此不会无限重试。
        block()
    }
}
