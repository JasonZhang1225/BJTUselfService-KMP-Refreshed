package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancyRepository
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancyResult
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.ClassroomOccupancySyncFailure
import team.bjtuss.bjtuselfservice.shared.data.classroomoccupancy.SemesterOptions
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.ClassroomOccupancy
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OCCUPANCY_BUILDINGS
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyBuilding
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancySemester
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.OccupancyWeekDate
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.AcademicWeekSlot
import team.bjtuss.bjtuselfservice.shared.domain.classroomoccupancy.academicWeekSlots

/** 周次筛选范围，与教务 zc 下拉一致（可到 30）；MIN/MAX 供 UI 弹层复用。 */
const val MIN_WEEK = 1
const val MAX_WEEK = 30

/**
 * 占用查询超时。到点必须清掉「同步中」。
 * 比 transport 默认 30s 短，避免顶栏一直转；超时后旧列表仍保留。
 */
private const val OCCUPANCY_QUERY_TIMEOUT_MILLIS = 12_000L

/** 一次教室占用查询的状态。 */
sealed interface ClassroomOccupancyQueryState {
    data object Idle : ClassroomOccupancyQueryState

    /** 正在请求，且尚无旧结果可展示。 */
    data object Loading : ClassroomOccupancyQueryState

    /**
     * 至少成功过一次。
     * [refreshing]=true 表示换周/刷新中：继续展示 [rooms]，顶栏「同步中」。
     * 任何完成 / 超时 / 取消路径都必须把 refreshing 清掉。
     */
    data class Loaded(
        val rooms: List<ClassroomOccupancy>,
        val refreshing: Boolean = false,
    ) : ClassroomOccupancyQueryState

    data class Failed(val reason: ClassroomOccupancySyncFailure) : ClassroomOccupancyQueryState
}

data class ClassroomOccupancyUiState(
    val buildings: List<OccupancyBuilding> = OCCUPANCY_BUILDINGS,
    val selectedBuilding: OccupancyBuilding? = null,
    val selectedWeek: Int = MIN_WEEK,
    val selectedWeekday: Int = 1,
    val semesters: List<OccupancySemester> = emptyList(),
    val selectedSemester: OccupancySemester? = null,
    val currentSemesterLabel: String? = null,
    val weekDates: Map<String, List<OccupancyWeekDate>> = emptyMap(),
    /** 当前分页落在校历补出的自然周时使用；教室占用查询不向教务发送虚构周数。 */
    val selectedNonTeachingWeekStart: LocalDate? = null,
    val queryState: ClassroomOccupancyQueryState = ClassroomOccupancyQueryState.Idle,
) {
    val isNonTeachingWeek: Boolean
        get() = selectedNonTeachingWeekStart != null

    val isLoading: Boolean
        get() = queryState == ClassroomOccupancyQueryState.Loading ||
            (queryState as? ClassroomOccupancyQueryState.Loaded)?.refreshing == true

    val rooms: List<ClassroomOccupancy>
        get() = (queryState as? ClassroomOccupancyQueryState.Loaded)?.rooms ?: emptyList()
}

/**
 * 教室占用状态模型。
 *
 * 查询用 token 丢弃过期响应（无 queryMutex 跨网络，避免卡死）。
 * 超时必清「同步中」。学期从占用 HTML 顺带解析；校历走公开通道。
 */
class ClassroomOccupancyScreenModel(
    private val repository: ClassroomOccupancyRepository,
    private val currentWeekProvider: () -> Int = { 1 },
    private val todayWeekdayProvider: () -> Int = {
        Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).dayOfWeek.isoDayNumber
    },
) {
    private val mutableState = MutableStateFlow(ClassroomOccupancyUiState())
    val state: StateFlow<ClassroomOccupancyUiState> = mutableState.asStateFlow()

    private var initialized = false
    private var queryToken = 0

    private val weekDatesMutex = Mutex()
    private var weekDatesNonEmpty = false

    private val semesterMutex = Mutex()

    val currentWeek: Int get() = currentWeekProvider().coerceIn(MIN_WEEK, MAX_WEEK)

    /**
     * 进页面：只写默认周/星期，**不发起 aa 请求**（避免占会话锁拖慢首查）。
     */
    fun initialize() {
        if (initialized) return
        initialized = true
        mutableState.value = mutableState.value.copy(
            selectedWeek = currentWeek,
            selectedWeekday = todayWeekdayProvider().coerceIn(1, 7),
        )
    }

    suspend fun ensureWeekDatesLoaded() {
        if (weekDatesNonEmpty) return
        weekDatesMutex.withLock {
            if (weekDatesNonEmpty) return
            try {
                val dates = repository.fetchWeekDates()
                if (dates.isNotEmpty()) {
                    mutableState.value = mutableState.value.copy(weekDates = dates)
                    weekDatesNonEmpty = true
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 静默
            }
        }
    }

    suspend fun ensureSemestersLoaded() {
        if (mutableState.value.semesters.isNotEmpty()) return
        semesterMutex.withLock {
            if (mutableState.value.semesters.isNotEmpty()) return
            try {
                applySemesterOptions(repository.fetchSemesters())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 静默
            }
        }
    }

    suspend fun selectWeek(week: Int) {
        val clamped = week.coerceIn(MIN_WEEK, MAX_WEEK)
        val current = mutableState.value
        if (clamped == current.selectedWeek && current.selectedNonTeachingWeekStart == null) return
        mutableState.value = current.copy(
            selectedWeek = clamped,
            selectedNonTeachingWeekStart = null,
        )
        query()
    }

    /** 选择校历中明确留出的自然周；这类周没有可发送给 room_view 的教学周编号。 */
    fun selectNonTeachingWeek(startDate: LocalDate) {
        val current = mutableState.value
        if (current.selectedNonTeachingWeekStart == startDate) return
        mutableState.value = current.copy(
            selectedWeek = 0,
            selectedNonTeachingWeekStart = startDate,
            queryState = ClassroomOccupancyQueryState.Idle,
        )
    }

    fun selectBuilding(building: OccupancyBuilding) {
        if (building == mutableState.value.selectedBuilding) return
        mutableState.value = mutableState.value.copy(
            selectedBuilding = building,
            queryState = ClassroomOccupancyQueryState.Idle,
        )
    }

    suspend fun selectSemester(semester: OccupancySemester?) {
        if (semester == mutableState.value.selectedSemester) return
        mutableState.value = mutableState.value.copy(
            selectedSemester = semester,
            selectedWeek = mutableState.value.selectedWeek.coerceIn(MIN_WEEK, semesterMaxWeek(semester)),
            selectedNonTeachingWeekStart = null,
            queryState = ClassroomOccupancyQueryState.Idle,
        )
        query()
    }

    fun selectWeekday(weekday: Int) {
        mutableState.value = mutableState.value.copy(
            selectedWeekday = weekday.coerceIn(1, 7),
        )
    }

    fun weekDateOf(week: Int): OccupancyWeekDate? {
        val state = mutableState.value
        val label = state.selectedSemester?.label ?: state.currentSemesterLabel ?: return null
        return state.weekDates[label]?.firstOrNull { it.week == week }
    }

    fun weekSlots(): List<AcademicWeekSlot> {
        val state = mutableState.value
        val label = state.selectedSemester?.label ?: state.currentSemesterLabel ?: return emptyList()
        return academicWeekSlots(state.weekDates[label].orEmpty(), MAX_WEEK)
    }

    fun canMoveWeekBy(offset: Int): Boolean {
        if (offset !in setOf(-1, 1)) return false
        val current = mutableState.value
        val slots = weekSlots()
        if (slots.isEmpty()) {
            return when (offset) {
                -1 -> current.selectedWeek > MIN_WEEK
                else -> current.selectedWeek < MAX_WEEK
            }
        }
        val currentIndex = when {
            current.selectedNonTeachingWeekStart != null -> slots.indexOfFirst {
                it.startDate == current.selectedNonTeachingWeekStart
            }
            else -> slots.indexOfFirst { it.teachingWeek == current.selectedWeek }
        }.takeIf { it >= 0 } ?: 0
        return currentIndex + offset in slots.indices
    }

    suspend fun moveWeekBy(offset: Int) {
        if (offset !in setOf(-1, 1)) return
        val current = mutableState.value
        val slots = weekSlots()
        if (slots.isEmpty()) {
            selectWeek((current.selectedWeek + offset).coerceIn(MIN_WEEK, MAX_WEEK))
            return
        }
        val currentIndex = when {
            current.selectedNonTeachingWeekStart != null -> slots.indexOfFirst {
                it.startDate == current.selectedNonTeachingWeekStart
            }
            else -> slots.indexOfFirst { it.teachingWeek == current.selectedWeek }
        }.takeIf { it >= 0 } ?: 0
        val target = slots.getOrNull(currentIndex + offset) ?: return
        if (target.isNonTeachingWeek) {
            selectNonTeachingWeek(target.startDate)
        } else {
            target.teachingWeek?.let { selectWeek(it) }
        }
    }

    suspend fun refresh() = query()

    private fun applySemesterOptions(options: SemesterOptions) {
        if (options.all.isEmpty()) return
        val current = mutableState.value
        if (current.semesters.isNotEmpty() && current.currentSemesterLabel != null) return
        mutableState.value = current.copy(
            semesters = options.all,
            currentSemesterLabel = options.selected?.label ?: current.currentSemesterLabel,
        )
    }

    private fun semesterMaxWeek(semester: OccupancySemester?): Int {
        val label = semester?.label ?: mutableState.value.currentSemesterLabel
            ?: return MAX_WEEK
        return mutableState.value.weekDates[label]?.maxOfOrNull(OccupancyWeekDate::week) ?: MAX_WEEK
    }

    /**
     * Token 丢弃过期响应；12s 超时必清进度。
     * 不在网络调用外包 Mutex：持锁等待会把切周卡死，且取消等锁时易留下 refreshing=true。
     */
    private suspend fun query() {
        if (mutableState.value.selectedNonTeachingWeekStart != null) return
        val building = mutableState.value.selectedBuilding ?: return
        val token = ++queryToken
        val week = mutableState.value.selectedWeek
        val semesterId = mutableState.value.selectedSemester?.id
        val previousRooms = (mutableState.value.queryState as? ClassroomOccupancyQueryState.Loaded)?.rooms

        markQueryStarted(previousRooms)

        val result = try {
            withTimeout(OCCUPANCY_QUERY_TIMEOUT_MILLIS) {
                repository.fetchOccupancy(week, building.id, semesterId)
            }
        } catch (_: TimeoutCancellationException) {
            // 超时：清「同步中」，有旧列表就保留，没有则 Failed。
            finishQuery(
                token,
                previousRooms,
                ClassroomOccupancyResult.Failure(ClassroomOccupancySyncFailure.NETWORK),
            )
            return
        } catch (error: CancellationException) {
            // 调用方协程被取消（例如错误地用弹层 scope）：必须清进度，再向上抛。
            if (token == queryToken) {
                restoreAfterCancel(previousRooms)
            }
            throw error
        } catch (_: Exception) {
            ClassroomOccupancyResult.Failure(ClassroomOccupancySyncFailure.NETWORK)
        }

        finishQuery(token, previousRooms, result)
    }

    private fun markQueryStarted(previousRooms: List<ClassroomOccupancy>?) {
        mutableState.value = mutableState.value.copy(
            queryState = if (previousRooms != null) {
                ClassroomOccupancyQueryState.Loaded(previousRooms, refreshing = true)
            } else {
                ClassroomOccupancyQueryState.Loading
            },
        )
    }

    private fun restoreAfterCancel(previousRooms: List<ClassroomOccupancy>?) {
        mutableState.value = mutableState.value.copy(
            queryState = if (previousRooms != null) {
                ClassroomOccupancyQueryState.Loaded(previousRooms, refreshing = false)
            } else {
                ClassroomOccupancyQueryState.Idle
            },
        )
    }

    private fun finishQuery(
        token: Int,
        previousRooms: List<ClassroomOccupancy>?,
        result: ClassroomOccupancyResult,
    ) {
        if (token != queryToken) return
        if (result is ClassroomOccupancyResult.Success) {
            result.semesterOptions?.let { applySemesterOptions(it) }
        }
        val next = when (result) {
            is ClassroomOccupancyResult.Success ->
                ClassroomOccupancyQueryState.Loaded(result.rooms, refreshing = false)
            is ClassroomOccupancyResult.Failure ->
                if (previousRooms != null) {
                    // 切周失败/超时：保留上一周列表，别整页失败。
                    ClassroomOccupancyQueryState.Loaded(previousRooms, refreshing = false)
                } else {
                    ClassroomOccupancyQueryState.Failed(result.reason)
                }
        }
        mutableState.value = mutableState.value.copy(queryState = next)
    }
}
