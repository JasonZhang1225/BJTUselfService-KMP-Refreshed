package team.bjtuss.bjtuselfservice.shared.feature.classroom

import team.bjtuss.bjtuselfservice.shared.runtime.AccountWork
import team.bjtuss.bjtuselfservice.shared.runtime.inAccount
import team.bjtuss.bjtuselfservice.shared.runtime.accountState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomFetchFailure
import team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomFetchResult
import team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomRepository
import team.bjtuss.bjtuselfservice.shared.domain.classroom.CLASSROOM_BUILDINGS
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomBuildingInfo
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomCapacity
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomFilter
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomSortDirection
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomSortField
import team.bjtuss.bjtuselfservice.shared.domain.classroom.applyFilterAndSort

/** 单个教学楼的加载状态。 */
sealed interface ClassroomBuildingState {
    /** 尚未请求。 */
    data object Idle : ClassroomBuildingState

    /** 正在请求。 */
    data object Loading : ClassroomBuildingState

    /** 请求成功，含教学楼名、轮询窗口与教室列表。 */
    data class Loaded(val info: ClassroomBuildingInfo) : ClassroomBuildingState

    /** 请求失败；[cached] 为本次会话内上一次成功的旧数据（可能为空）。 */
    data class Failed(
        val reason: ClassroomFetchFailure,
        val cached: ClassroomBuildingInfo?,
    ) : ClassroomBuildingState
}

data class ClassroomUiState(
    val buildings: List<String> = CLASSROOM_BUILDINGS,
    val selectedBuilding: String? = null,
    val buildingState: ClassroomBuildingState = ClassroomBuildingState.Idle,
    val filter: ClassroomFilter = ClassroomFilter(),
    val sortField: ClassroomSortField = ClassroomSortField.NAME,
    val sortDirection: ClassroomSortDirection = ClassroomSortDirection.ASCENDING,
    /**
     * 筛选排序后的教室列表（写入 state 时预计算）。
     * 不要做成 getter：每次 composition 读属性都会 filter+sort，iOS 输入时会卡键盘。
     */
    val visibleClassrooms: List<ClassroomCapacity> = emptyList(),
) {
    val isLoading: Boolean get() = buildingState == ClassroomBuildingState.Loading
}

/**
 * 教室切片的共享状态模型。每个教学楼的状态相互独立并保留最近一次成功快照；
 * 同一时刻只允许一个教学楼的请求在进行，避免快速切换时相互覆盖。
 */
class ClassroomScreenModel(
    private val repository: ClassroomRepository,
    private val accountWork: AccountWork? = null,
) {
    private val mutableState = accountWork.accountState(ClassroomUiState())
    val state: StateFlow<ClassroomUiState> = mutableState.asStateFlow()

    /** 会话内各教学楼最近一次成功快照，用于失败时保留旧数据。 */
    private val lastSuccessByBuilding = mutableMapOf<String, ClassroomBuildingInfo>()
    private val fetchMutex = Mutex()

    /** 选择教学楼并触发加载（若该楼从未成功加载过）。 */
    suspend fun selectBuilding(building: String) = accountWork.inAccount { selectBuildingInAccount(building = building) }

    private suspend fun selectBuildingInAccount(building: String) {
        require(building in CLASSROOM_BUILDINGS) { "unknown building" }
        val cached = lastSuccessByBuilding[building]
        updateState {
            it.copy(
                selectedBuilding = building,
                buildingState = when {
                    cached != null -> ClassroomBuildingState.Loaded(cached)
                    else -> ClassroomBuildingState.Loading
                },
            )
        }
        if (cached == null) {
            fetch(building)
        }
    }

    /** 强制刷新当前选中的教学楼。 */
    suspend fun refresh() = accountWork.inAccount { refreshInAccount() }

    private suspend fun refreshInAccount() {
        val building = mutableState.value.selectedBuilding ?: return
        fetch(building)
    }

    /** 返回教学楼列表（iPhone 两级导航的返回上一级）。 */
    fun clearSelection() {
        updateState {
            it.copy(
                selectedBuilding = null,
                buildingState = ClassroomBuildingState.Idle,
            )
        }
    }

    fun setNameQuery(query: String) {
        val current = mutableState.value
        if (current.filter.nameQuery == query) return
        updateState {
            it.copy(filter = it.filter.copy(nameQuery = query))
        }
    }

    fun setOnlyWithFreeSeats(only: Boolean) {
        updateState {
            it.copy(filter = it.filter.copy(onlyWithFreeSeats = only))
        }
    }

    /** 设置容量区间；null 表示该端不限制。 */
    fun setCapacityRange(min: Int?, max: Int?) {
        updateState {
            it.copy(filter = it.filter.copy(minCapacity = min, maxCapacity = max))
        }
    }

    fun clearFilter() {
        updateState { it.copy(filter = ClassroomFilter()) }
    }

    /** 点击同一排序维度切换方向，点击新维度则换维度并默认升序。 */
    fun setSortField(field: ClassroomSortField) {
        updateState {
            if (it.sortField == field) {
                it.copy(
                    sortDirection = when (it.sortDirection) {
                        ClassroomSortDirection.ASCENDING -> ClassroomSortDirection.DESCENDING
                        ClassroomSortDirection.DESCENDING -> ClassroomSortDirection.ASCENDING
                    },
                )
            } else {
                it.copy(
                    sortField = field,
                    sortDirection = ClassroomSortDirection.ASCENDING,
                )
            }
        }
    }

    private suspend fun fetch(building: String) {
        fetchMutex.withLock {
            // 若在排队期间用户切到了别的楼，这次请求只更新缓存不覆盖当前选中状态。
            if (mutableState.value.selectedBuilding == building) {
                updateState {
                    it.copy(buildingState = ClassroomBuildingState.Loading)
                }
            }
            when (val result = repository.fetchBuildingInfo(building)) {
                is ClassroomFetchResult.Success -> {
                    lastSuccessByBuilding[building] = result.info
                    if (mutableState.value.selectedBuilding == building) {
                        updateState {
                            it.copy(buildingState = ClassroomBuildingState.Loaded(result.info))
                        }
                    }
                }
                is ClassroomFetchResult.Failure -> {
                    if (mutableState.value.selectedBuilding == building) {
                        updateState {
                            it.copy(
                                buildingState = ClassroomBuildingState.Failed(
                                    reason = result.reason,
                                    cached = lastSuccessByBuilding[building],
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateState(transform: (ClassroomUiState) -> ClassroomUiState) {
        val next = transform(mutableState.value)
        mutableState.value = next.copy(visibleClassrooms = next.computeVisibleClassrooms())
    }
}

/** 按当前筛选与排序生成可见教室列表。 */
internal fun ClassroomUiState.computeVisibleClassrooms(): List<ClassroomCapacity> =
    when (val state = buildingState) {
        is ClassroomBuildingState.Loaded ->
            state.info.classrooms.applyFilterAndSort(filter, sortField, sortDirection)
        is ClassroomBuildingState.Failed ->
            state.cached?.classrooms?.applyFilterAndSort(filter, sortField, sortDirection)
                ?: emptyList()
        else -> emptyList()
    }
