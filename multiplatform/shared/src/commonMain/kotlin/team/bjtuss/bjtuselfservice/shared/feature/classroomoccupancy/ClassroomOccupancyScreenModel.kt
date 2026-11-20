package team.bjtuss.bjtuselfservice.shared.feature.classroomoccupancy

import team.bjtuss.bjtuselfservice.shared.runtime.AccountWork
import team.bjtuss.bjtuselfservice.shared.runtime.AccountMutationLock
import team.bjtuss.bjtuselfservice.shared.runtime.inAccount
import team.bjtuss.bjtuselfservice.shared.runtime.accountState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
import team.bjtuss.bjtuselfservice.shared.feature.shell.SessionRefreshCoordinator

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
    val people: List<team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomCapacity> = emptyList(),
    val peopleLoading: Boolean = false,
    val peopleSnapshotRange: String = "",
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
    private val peopleRepository: team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomRepository? = null,
    private val currentWeekProvider: () -> Int = { 1 },
    // 教室占用按北京时间的星期和节次排。
    private val todayWeekdayProvider: () -> Int = {
        Clock.System.now().toLocalDateTime(team.bjtuss.bjtuselfservice.shared.domain.time.SchoolTimeZone).dayOfWeek.isoDayNumber
    },
    private val accountWork: AccountWork? = null,
    private val refreshScope: CoroutineScope? = null,
) {
    private val mutableState = accountWork.accountState(ClassroomOccupancyUiState())
    val state: StateFlow<ClassroomOccupancyUiState> = mutableState.asStateFlow()

    private var initialized = false
    private var queryToken = 0
    private val queryLock = AccountMutationLock()
    private val inFlightQueries = mutableMapOf<Query, QueryRun>()
    private val pageRefreshes = mutableMapOf<Query?, Deferred<Unit>>()
    private var lastCompletedQuery: Pair<Query, Int>? = null

    private data class Query(val buildingId: String, val week: Int, val semesterId: String?)

    private class QueryRun(var token: Int, var previousRooms: List<ClassroomOccupancy>?) {
        lateinit var completion: Deferred<Unit>
    }

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

    suspend fun ensureWeekDatesLoaded() = accountWork.inAccount { ensureWeekDatesLoadedInAccount() }

    private suspend fun ensureWeekDatesLoadedInAccount() {
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

    suspend fun ensureSemestersLoaded() = accountWork.inAccount { ensureSemestersLoadedInAccount() }

    private suspend fun ensureSemestersLoadedInAccount() {
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

    suspend fun selectWeek(week: Int) = accountWork.inAccount { selectWeekInAccount(week = week) }

    private suspend fun selectWeekInAccount(week: Int) {
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
            people = emptyList(),
            peopleLoading = false,
            peopleSnapshotRange = "",
            queryState = ClassroomOccupancyQueryState.Idle,
        )
    }

    suspend fun selectSemester(semester: OccupancySemester?) = accountWork.inAccount { selectSemesterInAccount(semester = semester) }

    private suspend fun selectSemesterInAccount(semester: OccupancySemester?) {
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

    suspend fun moveWeekBy(offset: Int) = accountWork.inAccount { moveWeekByInAccount(offset = offset) }

    private suspend fun moveWeekByInAccount(offset: Int) {
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

    suspend fun refreshPeople() = accountWork.inAccount { refreshPeopleInAccount() }

    private suspend fun refreshPeopleInAccount() {
        val building = mutableState.value.selectedBuilding ?: return
        val remote = peopleRepository ?: return
        if (building.name !in team.bjtuss.bjtuselfservice.shared.domain.classroom.CLASSROOM_BUILDINGS) return
        mutableState.value = mutableState.value.copy(peopleLoading = true)
        try {
            val result = withTimeoutOrNull(8_000L) { remote.fetchBuildingInfo(building.name) }
            if (mutableState.value.selectedBuilding != building) return
            val info = (result as? team.bjtuss.bjtuselfservice.shared.data.classroom.ClassroomFetchResult.Success)?.info
            mutableState.value = mutableState.value.copy(
                people = info?.classrooms.orEmpty(),
                peopleSnapshotRange = info?.let { "${it.effectiveStart}—${it.effectiveEnd}" }.orEmpty(),
            )
        } finally {
            if (mutableState.value.selectedBuilding == building) mutableState.value = mutableState.value.copy(peopleLoading = false)
        }
    }

    suspend fun refresh() = accountWork.inAccount { query() }

    internal suspend fun refreshWithSession(session: SessionRefreshCoordinator) = accountWork.inAccount {
        coroutineScope {
            val refresh = queryLock.withLock {
                val conditions = state.value.queryConditions()
                val active = pageRefreshes[conditions]?.takeUnless { it.isCompleted }
                val startedAfter = queryToken
                active ?: (refreshScope ?: this).async(start = CoroutineStart.LAZY) {
                    var reuseAfter: Int? = startedAfter
                    session.run(
                        operation = {
                            val after = reuseAfter
                            reuseAfter = null
                            query(reuseAfter = after)
                        },
                        sessionExpired = {
                            (state.value.queryState as? ClassroomOccupancyQueryState.Failed)
                                ?.reason == ClassroomOccupancySyncFailure.SESSION_EXPIRED
                        },
                    )
                }.also { pending ->
                    pageRefreshes[conditions] = pending
                    pending.invokeOnCompletion {
                        queryLock.withLock {
                            if (pageRefreshes[conditions] === pending) pageRefreshes.remove(conditions)
                        }
                    }
                }
            }
            refresh.await()
        }
    }

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
    private suspend fun query(reuseAfter: Int? = null) = coroutineScope {
        val completion = queryLock.withLock {
            val current = state.value
            val conditions = current.queryConditions() ?: return@withLock null
            // A filter action can complete the same query while the page refresh is checking MIS.
            if (reuseAfter != null && queryToken > reuseAfter && lastCompletedQuery == (conditions to queryToken)) {
                return@withLock null
            }
            val token = ++queryToken
            val previousRooms = (current.queryState as? ClassroomOccupancyQueryState.Loaded)?.rooms
            markQueryStarted(previousRooms)
            val active = inFlightQueries[conditions]?.takeUnless { it.completion.isCompleted }
            if (active != null) {
                active.token = token
                active.previousRooms = previousRooms
                return@withLock active.completion
            }
            val next = QueryRun(token, previousRooms)
            next.completion = (refreshScope ?: this).async(start = CoroutineStart.LAZY) {
                runQuery(conditions, next)
            }
            inFlightQueries[conditions] = next
            next.completion.invokeOnCompletion {
                queryLock.withLock {
                    if (inFlightQueries[conditions] === next) inFlightQueries.remove(conditions)
                }
            }
            next.completion
        }
        completion?.await()
        Unit
    }

    private suspend fun runQuery(conditions: Query, query: QueryRun) {
        try {
            val result = fetchOccupancy(conditions)
            queryLock.withLock {
                if (inFlightQueries[conditions] === query) inFlightQueries.remove(conditions)
                if (query.token == queryToken && state.value.queryConditions() == conditions) {
                    lastCompletedQuery = conditions to query.token
                    finishQuery(query.previousRooms, result)
                }
            }
        } catch (error: CancellationException) {
            queryLock.withLock {
                if (query.token == queryToken && state.value.queryConditions() == conditions) {
                    restoreAfterCancel(query.previousRooms)
                }
            }
            throw error
        }
    }

    private fun ClassroomOccupancyUiState.queryConditions(): Query? {
        if (selectedNonTeachingWeekStart != null) return null
        val building = selectedBuilding ?: return null
        return Query(building.id, selectedWeek, selectedSemester?.id)
    }

    private suspend fun fetchOccupancy(conditions: Query): ClassroomOccupancyResult = try {
        withTimeout(OCCUPANCY_QUERY_TIMEOUT_MILLIS) {
            repository.fetchOccupancy(conditions.week, conditions.buildingId, conditions.semesterId)
        }
    } catch (_: TimeoutCancellationException) {
        ClassroomOccupancyResult.Failure(ClassroomOccupancySyncFailure.NETWORK)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ClassroomOccupancyResult.Failure(ClassroomOccupancySyncFailure.NETWORK)
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
        previousRooms: List<ClassroomOccupancy>?,
        result: ClassroomOccupancyResult,
    ) {
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
