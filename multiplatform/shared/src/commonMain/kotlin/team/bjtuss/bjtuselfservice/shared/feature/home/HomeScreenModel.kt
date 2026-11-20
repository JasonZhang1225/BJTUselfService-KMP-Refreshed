package team.bjtuss.bjtuselfservice.shared.feature.home

import team.bjtuss.bjtuselfservice.shared.runtime.AccountWork
import team.bjtuss.bjtuselfservice.shared.runtime.inAccount
import team.bjtuss.bjtuselfservice.shared.runtime.accountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusFailure
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.home.HomeStatusRepository
import team.bjtuss.bjtuselfservice.shared.domain.home.HomeStatus

data class HomeUiState(
    val status: HomeStatus? = null,
    val isRefreshing: Boolean = false,
    val failure: HomeStatusFailure? = null,
)

class HomeScreenModel(private val repository: HomeStatusRepository,
    private val accountWork: AccountWork? = null,
) {
    private val mutableState = accountWork.accountState(HomeUiState())
    val state: StateFlow<HomeUiState> = mutableState.asStateFlow()
    private val refreshMutex = Mutex()

    suspend fun initialize() = accountWork.inAccount { initializeInAccount() }

    private suspend fun initializeInAccount() {
        if (mutableState.value.status == null) {
            mutableState.value = mutableState.value.copy(status = runCatching(repository::load).getOrNull())
            refresh()
        }
    }

    /**
     * 刷新首页状态。
     *
     * 切后台/协程取消时若不清掉 [HomeUiState.isRefreshing]，首页顶栏会一直显示「同步中」，
     * 且旧实现用 isRefreshing 当互斥会导致后续刷新永远直接 return（假同步）。
     */
    suspend fun refresh() = accountWork.inAccount { refreshInAccount() }

    private suspend fun refreshInAccount() {
        if (!refreshMutex.tryLock()) return
        try {
            mutableState.value = mutableState.value.copy(isRefreshing = true, failure = null)
            mutableState.value = when (val result = repository.refresh()) {
                is HomeStatusRefreshResult.Success -> HomeUiState(status = result.status)
                is HomeStatusRefreshResult.Failure -> HomeUiState(
                    status = result.cached ?: mutableState.value.status,
                    failure = result.reason,
                )
            }
        } catch (error: CancellationException) {
            // 取消仍须结束 loading；继续向上抛出，避免吞掉结构化并发取消。
            clearRefreshing()
            throw error
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                isRefreshing = false,
                failure = HomeStatusFailure.NETWORK,
            )
        } finally {
            clearRefreshing()
            refreshMutex.unlock()
        }
    }

    private fun clearRefreshing() {
        val current = mutableState.value
        if (current.isRefreshing) {
            mutableState.value = current.copy(isRefreshing = false)
        }
    }
}
