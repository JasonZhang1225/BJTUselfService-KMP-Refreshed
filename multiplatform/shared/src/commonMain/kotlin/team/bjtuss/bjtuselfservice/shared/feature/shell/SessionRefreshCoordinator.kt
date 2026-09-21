package team.bjtuss.bjtuselfservice.shared.feature.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 把“刷新一次 → 发现会话过期 → 恢复会话 → 重试”统一到应用壳。
 *
 * 同一个刷新动作可能同时刷新首页的多个模块；恢复过程用 Mutex 合并，避免并发发起多组
 * 验证码请求。恢复失败时保留当前错误页，等待用户再次点击右上角刷新。
 */
internal class SessionRefreshCoordinator(
    private val reauthenticate: (suspend () -> Boolean)?,
    private val probeSession: (suspend () -> Boolean)? = null,
    private val onRecoveryStateChanged: (Boolean) -> Unit = {},
    private val maxRecoveryAttempts: Int = 2,
) {
    init {
        require(maxRecoveryAttempts >= 1)
    }

    private val mutex = Mutex()
    private var recoveryAttempts = 0
    private var recoverySucceeded = false
    private var preflightResult: Boolean? = null

    suspend fun run(
        operation: suspend () -> Unit,
        sessionExpired: () -> Boolean,
    ) {
        // A long-idle app can still have cached page state while the server-side
        // cookie is gone. Probe once per refresh batch before any module request;
        // the existing post-operation check remains the race-condition fallback.
        // Let the module publish its normal SESSION_EXPIRED state when recovery
        // itself failed, instead of silently doing nothing on the current page.
        ensureSessionBeforeOperation()
        operation()
        if (!sessionExpired()) return

        repeat(maxRecoveryAttempts) {
            if (attemptRecovery()) {
                operation()
                if (!sessionExpired()) return
            }
        }
    }

    private suspend fun ensureSessionBeforeOperation() {
        val probe = probeSession ?: return
        val result = mutex.withLock {
            preflightResult ?: try {
                probe().also { preflightResult = it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // A probe timeout is not proof of an expired session; let the
                // actual module request classify a transient network outage.
                null
            }
        }
        if (result != false) return

        val recovered = attemptRecovery()
        if (recovered) {
            mutex.withLock { preflightResult = true }
        }
    }

    private suspend fun attemptRecovery(): Boolean = mutex.withLock {
        // 同一个刷新动作的多个模块共享一次成功的恢复结果；否则首页并发刷新会重复
        // 发起验证码请求。若本次恢复失败，仍允许下一次（最多两次）重新尝试。
        if (recoverySucceeded) return@withLock true
        if (recoveryAttempts >= maxRecoveryAttempts || reauthenticate == null) return@withLock false
        recoveryAttempts += 1
        onRecoveryStateChanged(true)
        try {
            reauthenticate.invoke().also { succeeded ->
                if (succeeded) recoverySucceeded = true
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        } finally {
            onRecoveryStateChanged(false)
        }
    }
}
