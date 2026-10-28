package team.bjtuss.bjtuselfservice.shared.feature.courseware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareOperationResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRefreshResult
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRemoteException
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRemoteFailure
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareRepository
import team.bjtuss.bjtuselfservice.shared.data.courseware.CoursewareSyncFailure
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareCourse
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.CoursewareSnapshot
import team.bjtuss.bjtuselfservice.shared.domain.courseware.VisibleCoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.coursewarePathNames
import team.bjtuss.bjtuselfservice.shared.domain.courseware.findCoursewareNode
import team.bjtuss.bjtuselfservice.shared.domain.courseware.nodesAtCoursewarePath
import team.bjtuss.bjtuselfservice.shared.domain.courseware.visibleCoursewareTree
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryFile
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryOpenResult
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryWriteSession
import team.bjtuss.bjtuselfservice.shared.files.CoursewareExportNameAllocator
import team.bjtuss.bjtuselfservice.shared.data.courseware.suggestedFileName
import team.bjtuss.bjtuselfservice.shared.data.courseware.toSyncFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingFileSink
import team.bjtuss.bjtuselfservice.shared.files.StreamingSinkIoException

enum class CoursewareContentSource {
    CACHE,
    NETWORK,
}

data class CoursewareUiState(
    val courses: List<CoursewareCourse> = emptyList(),
    val selectedCourseId: Int? = null,
    val compactFolderPath: List<String> = emptyList(),
    val expandedFolderKeys: Set<String> = emptySet(),
    val selectedNodeKey: String? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isDownloading: Boolean = false,
    val loadingCourseIds: Set<Int> = emptySet(),
    val loadingFolderKeys: Set<String> = emptySet(),
    val directoryDownloadCompleted: Int = 0,
    val directoryDownloadTotal: Int = 0,
    val directoryCurrentName: String? = null,
    val directoryCurrentSize: String? = null,
    /** 最近一次目录导出中老师未开放下载、备用方式也没取到而跳过的文件名。 */
    val directoryExportSkipped: List<String> = emptyList(),
    /** 未开放下载文件边下边存时的进度（已写入字节, 总字节）。 */
    val streamProgress: Pair<Long, Long>? = null,
    val streamNodeKey: String? = null,
    val streamListedSize: String? = null,
    val source: CoursewareContentSource? = null,
    val failure: CoursewareSyncFailure? = null,
    val fileFailure: CoursewareSyncFailure? = null,
) {
    val selectedCourse: CoursewareCourse?
        get() = courses.firstOrNull { it.id == selectedCourseId }

    val isSelectedCourseLoading: Boolean
        get() = selectedCourseId in loadingCourseIds

    val compactNodes: List<CoursewareNode>
        get() = selectedCourse?.let { nodesAtCoursewarePath(it, compactFolderPath) }.orEmpty()

    val compactPathNames: List<String>
        get() = selectedCourse?.let { coursewarePathNames(it, compactFolderPath) }.orEmpty()

    val visibleTree: List<VisibleCoursewareNode>
        get() = selectedCourse?.let { visibleCoursewareTree(it.children, expandedFolderKeys) }.orEmpty()

    val selectedNode: CoursewareNode?
        get() = selectedCourse?.let { findCoursewareNode(it.children, selectedNodeKey.orEmpty()) }
}

class CoursewareScreenModel(
    private val repository: CoursewareRepository,
    /** 会话过期时在当前 App 会话内重新建立学校登录态，不跳回登录页。 */
    private val reauthenticate: (suspend () -> Boolean)? = null,
) {
    private val mutableState = MutableStateFlow(CoursewareUiState())
    val state: StateFlow<CoursewareUiState> = mutableState.asStateFlow()

    private val refreshMutex = Mutex()
    private val operationMutex = Mutex()
    private val prefetchMutex = Mutex()
    private val freshCourseIds = mutableSetOf<Int>()
    private var initialized = false
    private var navigationGeneration = 0L

    suspend fun initialize() {
        if (initialized) return
        initialized = true
        val cached = runCatching(repository::load).getOrNull()
        if (cached != null) {
            applySnapshot(
                snapshot = cached,
                source = if (cached.courses.isEmpty()) null else CoursewareContentSource.CACHE,
                failure = null,
            )
        } else {
            mutableState.value = mutableState.value.copy(
                isLoading = true,
                failure = CoursewareSyncFailure.CACHE,
            )
        }
        refresh()
        // 列表同步后（或失败仅有缓存）仍补拉未加载课程的顶层数量。
        ensureCourseRootsLoaded()
    }

    suspend fun refresh() {
        refreshMutex.withLock {
            var reloadLoaded = false
            operationMutex.withLock {
                val before = mutableState.value
                mutableState.value = before.copy(
                    isLoading = before.courses.isEmpty(),
                    isRefreshing = before.courses.isNotEmpty(),
                    failure = null,
                )
                try {
                    when (val result = repository.refresh()) {
                        is CoursewareRefreshResult.Success -> {
                            freshCourseIds.clear()
                            applySnapshot(
                                result.snapshot,
                                CoursewareContentSource.NETWORK,
                                null,
                            )
                            reloadLoaded = true
                        }
                        is CoursewareRefreshResult.Failure -> applySnapshot(
                            result.snapshot,
                            if (result.snapshot.courses.isEmpty()) null else CoursewareContentSource.CACHE,
                            result.reason,
                        )
                    }
                } finally {
                    val current = mutableState.value
                    if (current.isRefreshing || current.isLoading) {
                        mutableState.value = current.copy(isRefreshing = false, isLoading = false)
                    }
                }
            }
            // 目录刷新成功后才重拉顶层；失败时留下已有子树，只补还没加载的课。
            ensureCourseRootsLoaded(reloadLoaded = reloadLoaded)
        }
    }

    /**
     * 并发补拉尚未加载顶层目录的课程（有界并发）。
     * 打开选课列表 / 初始化 / 手动同步后都会调用；已在加载中则跳过。
     */
    suspend fun ensureCourseRootsLoaded(reloadLoaded: Boolean = false) {
        val ids = mutableState.value.courses
            .filter { reloadLoaded || !it.childrenLoaded }
            .map { it.id }
        if (ids.isEmpty()) {
            prefetchUnloadedFolders()
            return
        }
        if (!reloadLoaded && ids.all { it in mutableState.value.loadingCourseIds }) return
        operationMutex.withLock {
            preloadUnloadedCourseRootsLocked(ids)
        }
        prefetchUnloadedFolders()
    }

    suspend fun selectCourse(courseId: Int) {
        if (mutableState.value.courses.none { it.id == courseId }) return
        navigationGeneration++
        // Navigation is local and must not queue behind a network/download operation.
        mutableState.value = mutableState.value.copy(
            selectedCourseId = courseId, compactFolderPath = emptyList(),
            expandedFolderKeys = emptySet(), selectedNodeKey = null, fileFailure = null,
        )
        operationMutex.withLock {
            if (mutableState.value.selectedCourseId != courseId) return@withLock
            loadCourseLocked(courseId)
        }
        prefetchUnloadedFolders()
    }

    suspend fun openCompactNode(stableKey: String) {
        val generation = ++navigationGeneration
        var state = mutableState.value
        val courseId = state.selectedCourseId
        if (state.selectedCourse?.childrenLoaded != true) return
        val node = state.compactNodes.firstOrNull { it.stableKey == stableKey } ?: return
        if (node.isFolder && !node.childrenLoaded && !loadFolder(stableKey)) return
        state = mutableState.value
        if (generation != navigationGeneration || state.selectedCourseId != courseId) return
        mutableState.value = if (node.isFolder) {
            state.copy(
                compactFolderPath = state.compactFolderPath + node.stableKey,
                selectedNodeKey = null,
                fileFailure = null,
            )
        } else {
            state.copy(selectedNodeKey = node.stableKey, fileFailure = null)
        }
    }

    fun navigateCompactBack(): Boolean {
        val state = mutableState.value
        if (state.compactFolderPath.isEmpty()) return false
        navigationGeneration++
        mutableState.value = state.copy(
            compactFolderPath = state.compactFolderPath.dropLast(1),
            selectedNodeKey = null,
            fileFailure = null,
        )
        return true
    }

    suspend fun toggleExpanded(stableKey: String) {
        val generation = ++navigationGeneration
        var state = mutableState.value
        val course = state.selectedCourse ?: return
        val node = findCoursewareNode(course.children, stableKey) ?: return
        if (!node.isFolder) return
        if (stableKey !in state.expandedFolderKeys && !node.childrenLoaded && !loadFolder(stableKey)) return
        state = mutableState.value
        if (generation != navigationGeneration || state.selectedCourseId != course.id) return
        val expanded = state.expandedFolderKeys.toMutableSet().apply {
            if (!add(stableKey)) remove(stableKey)
        }
        mutableState.value = state.copy(
            expandedFolderKeys = expanded,
            selectedNodeKey = stableKey,
            fileFailure = null,
        )
    }

    fun selectNode(stableKey: String) {
        val course = mutableState.value.selectedCourse ?: return
        if (!course.childrenLoaded) return
        navigationGeneration++
        if (stableKey.isBlank()) {
            mutableState.value = mutableState.value.copy(selectedNodeKey = null, fileFailure = null)
            return
        }
        if (findCoursewareNode(course.children, stableKey) == null) return
        mutableState.value = mutableState.value.copy(selectedNodeKey = stableKey, fileFailure = null)
    }

    suspend fun downloadResource(
        stableKey: String,
    ): CoursewareOperationResult<HomeworkFileContent> = operationMutex.withLock {
        val course = mutableState.value.selectedCourse
            ?: return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
        if (!course.childrenLoaded || course.id in mutableState.value.loadingCourseIds) {
            return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.NETWORK)
        }
        val node = findCoursewareNode(course.children, stableKey)
            ?.takeIf { !it.isFolder }
            ?: return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
        mutableState.value = mutableState.value.copy(
            isDownloading = true,
            selectedNodeKey = node.stableKey,
            fileFailure = null,
        )
        try {
            downloadRestrictedAware(node).also { result ->
                if (result is CoursewareOperationResult.Failure) {
                    mutableState.value = mutableState.value.copy(fileFailure = result.reason)
                }
            }
        } finally {
            mutableState.value = mutableState.value.copy(isDownloading = false)
        }
    }

    /**
     * 未开放下载的文件由数据层改走服务器存储路径；那条路也失败时，
     * 网络/数据格式错误统一说成“未开放下载且备用方式失败”，不误导成断网。
     */
    private suspend fun downloadRestrictedAware(node: CoursewareNode): CoursewareOperationResult<HomeworkFileContent> {
        val result = downloadFileWithRecovery { repository.downloadResource(node) }
        if (node.downloadAllowed || result !is CoursewareOperationResult.Failure) return result
        return when (result.reason) {
            CoursewareSyncFailure.NETWORK, CoursewareSyncFailure.MALFORMED_RESPONSE ->
                CoursewareOperationResult.Failure(CoursewareSyncFailure.DOWNLOAD_NOT_ALLOWED)
            else -> result
        }
    }

    /** 普通课件先选择目标，再从下载票据逐块写入。 */
    suspend fun saveResourceStreaming(
        stableKey: String,
        gateway: HomeworkFileGateway,
    ): CoursewareOperationResult<HomeworkFileSaveResult> = operationMutex.withLock {
        val course = mutableState.value.selectedCourse
            ?: return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
        val node = findCoursewareNode(course.children, stableKey)?.takeIf { !it.isFolder }
            ?: return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
        mutableState.value = mutableState.value.copy(
            isDownloading = true, selectedNodeKey = node.stableKey, fileFailure = null,
            streamNodeKey = node.stableKey, streamListedSize = node.size.takeIf { it.isNotBlank() },
        )
        try {
            val saved = gateway.saveStreamingFile(node.suggestedFileName(), "application/octet-stream") { sink ->
                streamResourceWithRecovery(node, sink) { written, total ->
                    mutableState.value = mutableState.value.copy(streamProgress = written to total)
                }
            }
            CoursewareOperationResult.Success(saved)
        } catch (error: CancellationException) {
            throw error
        } catch (error: CoursewareRemoteException) {
            val reason = error.reason.toSyncFailure()
            mutableState.value = mutableState.value.copy(fileFailure = reason)
            CoursewareOperationResult.Failure(reason)
        } finally {
            mutableState.value = mutableState.value.copy(isDownloading = false, streamProgress = null, streamNodeKey = null, streamListedSize = null)
        }
    }

    /** 备用地址可能是转换件，先确定实际文件名再选择目标。 */
    suspend fun saveRestrictedResource(
        stableKey: String,
        gateway: HomeworkFileGateway,
    ): CoursewareOperationResult<HomeworkFileSaveResult> = operationMutex.withLock {
        val course = mutableState.value.selectedCourse
            ?: return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
        val node = findCoursewareNode(course.children, stableKey)?.takeIf { !it.isFolder }
            ?: return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
        mutableState.value = mutableState.value.copy(isDownloading = true, selectedNodeKey = node.stableKey, fileFailure = null, streamNodeKey = node.stableKey, streamListedSize = null)
        try {
            val plan = when (val planned = recoverOperation { repository.planStoredDownload(node) }) {
                is CoursewareOperationResult.Failure -> {
                    val reason = when (planned.reason) {
                        CoursewareSyncFailure.NETWORK, CoursewareSyncFailure.MALFORMED_RESPONSE ->
                            CoursewareSyncFailure.DOWNLOAD_NOT_ALLOWED
                        else -> planned.reason
                    }
                    mutableState.value = mutableState.value.copy(fileFailure = reason)
                    return@withLock CoursewareOperationResult.Failure(reason)
                }
                is CoursewareOperationResult.Success -> planned.value
            }
            mutableState.value = mutableState.value.copy(streamListedSize = if (plan.isConvertedCopy) null else node.size.takeIf { it.isNotBlank() })
            val saved = try {
                gateway.saveStreamingFile(plan.fileName, plan.contentType) { sink ->
                    repository.streamStoredDownload(plan, sink) { written, total ->
                        mutableState.value = mutableState.value.copy(streamProgress = written to total)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: CoursewareRemoteException) {
                mutableState.value = mutableState.value.copy(fileFailure = CoursewareSyncFailure.NETWORK)
                return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.NETWORK)
            }
            CoursewareOperationResult.Success(saved)
        } finally {
            mutableState.value = mutableState.value.copy(isDownloading = false, streamProgress = null, streamNodeKey = null, streamListedSize = null)
        }
    }

    suspend fun exportDirectory(
        stableKey: String?,
        directoryName: String,
        gateway: CoursewareDirectoryGateway,
        keepCompletedOnCancel: () -> Boolean = { true },
        onCancelledWithKeptFiles: (HomeworkFileSaveResult) -> Unit = {},
    ): CoursewareOperationResult<HomeworkFileSaveResult> {
        val courseId = mutableState.value.selectedCourseId
            ?: return CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
        return operationMutex.withLock {
            val hydrationFailure = hydrateExportTree(stableKey, courseId)
            if (hydrationFailure != null) return@withLock CoursewareOperationResult.Failure(hydrationFailure)
            val course = mutableState.value.courses.firstOrNull { it.id == courseId }
                ?: return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
            // 未开放下载的文件也一并导出（数据层走存储路径）；只有那条路也失败的才跳过并说明。
            val resources = course.resourcesWithFolders(stableKey)
            mutableState.value = mutableState.value.copy(directoryExportSkipped = emptyList())
            if (resources.isEmpty()) {
                val reason = CoursewareSyncFailure.MALFORMED_RESPONSE
                mutableState.value = mutableState.value.copy(fileFailure = reason)
                return@withLock CoursewareOperationResult.Failure(reason)
            }
            mutableState.value = mutableState.value.copy(
                isDownloading = true,
                directoryDownloadCompleted = 0,
                directoryDownloadTotal = resources.size,
                fileFailure = null,
            )
            try {
                val opened = try {
                    gateway.openDirectory(directoryName)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.IO)
                }
                when (opened) {
                    CoursewareDirectoryOpenResult.Cancelled ->
                        CoursewareOperationResult.Success(HomeworkFileSaveResult.Cancelled)
                    is CoursewareDirectoryOpenResult.Failed ->
                        CoursewareOperationResult.Success(HomeworkFileSaveResult.Failed(opened.reason))
                    is CoursewareDirectoryOpenResult.Opened ->
                        exportResources(resources, opened.session, keepCompletedOnCancel, onCancelledWithKeptFiles)
                }
            } finally {
                mutableState.value = mutableState.value.copy(
                    isDownloading = false,
                    directoryDownloadCompleted = 0,
                    directoryDownloadTotal = 0,
                    directoryCurrentName = null,
                    directoryCurrentSize = null,
                )
            }
        }

    }

    private suspend fun exportResources(
        resources: List<CoursewareResourcePath>,
        session: CoursewareDirectoryWriteSession,
        keepCompletedOnCancel: () -> Boolean,
        onCancelledWithKeptFiles: (HomeworkFileSaveResult) -> Unit,
    ): CoursewareOperationResult<HomeworkFileSaveResult> {
        var committed = false
        val nameAllocator = CoursewareExportNameAllocator()
        return try {
            val skipped = mutableListOf<String>()
            for ((index, item) in resources.withIndex()) {
                mutableState.value = mutableState.value.copy(
                    directoryCurrentName = item.node.name,
                    directoryCurrentSize = item.node.size.takeIf { it.isNotBlank() },
                )
                if (session.supportsStreamingFiles) {
                    val plan = if (!item.node.downloadAllowed) {
                        when (val planned = recoverOperation { repository.planStoredDownload(item.node) }) {
                            is CoursewareOperationResult.Failure -> {
                                if (planned.reason == CoursewareSyncFailure.NETWORK || planned.reason == CoursewareSyncFailure.MALFORMED_RESPONSE || planned.reason == CoursewareSyncFailure.DOWNLOAD_NOT_ALLOWED) {
                                    skipped += item.node.name
                                    mutableState.value = mutableState.value.copy(directoryDownloadCompleted = index + 1, directoryExportSkipped = skipped.toList(), directoryCurrentName = null, directoryCurrentSize = null)
                                    continue
                                }
                                mutableState.value = mutableState.value.copy(fileFailure = planned.reason)
                                return planned
                            }
                            is CoursewareOperationResult.Success -> planned.value
                        }
                    } else null
                    if (plan?.isConvertedCopy == true) {
                        mutableState.value = mutableState.value.copy(directoryCurrentName = plan.fileName, directoryCurrentSize = null)
                    }
                    val exportFile = nameAllocator.resolve(CoursewareDirectoryFile(item.folders, HomeworkFileContent(
                        fileName = plan?.fileName ?: item.node.suggestedFileName(),
                        contentType = plan?.contentType ?: "application/octet-stream",
                        bytes = byteArrayOf(),
                    )))
                    // 恢复到已取消的调用方时 withContext 可能丢弃返回值；先保存句柄以便清理。
                    var created: StreamingDestinationResult? = null
                    try {
                        withContext(NonCancellable) { created = session.openFile(exportFile) }
                    } catch (error: CancellationException) {
                        withContext(NonCancellable) {
                            (created as? StreamingDestinationResult.Opened)?.destination?.let { runCatching { it.abort() } }
                        }
                        throw error
                    }
                    val destination = when (val opened = created ?: StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.IO)) {
                        is StreamingDestinationResult.Opened -> opened.destination
                        is StreamingDestinationResult.Failed -> return CoursewareOperationResult.Success(HomeworkFileSaveResult.Failed(opened.reason))
                        StreamingDestinationResult.Cancelled -> return CoursewareOperationResult.Success(HomeworkFileSaveResult.Cancelled)
                    }
                    var saved = false
                    try {
                        currentCoroutineContext().ensureActive()
                        val sink = StreamingFileSink(destination::write)
                        val progress: (Long, Long) -> Unit = { written, total ->
                            mutableState.value = mutableState.value.copy(streamProgress = written to total)
                        }
                        if (plan == null) streamResourceWithRecovery(item.node, sink, progress)
                        else repository.streamStoredDownload(plan, sink, progress)
                        val result = destination.commit()
                        saved = result == HomeworkFileSaveResult.Saved
                        if (!saved) return CoursewareOperationResult.Success(result)
                    } finally {
                        if (!saved) withContext(NonCancellable) { runCatching { destination.abort() } }
                        mutableState.value = mutableState.value.copy(streamProgress = null)
                    }
                    mutableState.value = mutableState.value.copy(directoryDownloadCompleted = index + 1, directoryCurrentName = null, directoryCurrentSize = null)
                    continue
                }
                val downloaded = when (val result = downloadRestrictedAware(item.node)) {
                    is CoursewareOperationResult.Failure -> {
                        if (result.reason == CoursewareSyncFailure.DOWNLOAD_NOT_ALLOWED ||
                            result.reason == CoursewareSyncFailure.FILE_TOO_LARGE
                        ) {
                            skipped += item.node.name
                            mutableState.value = mutableState.value.copy(
                                directoryDownloadCompleted = index + 1,
                                directoryExportSkipped = skipped.toList(),
                                directoryCurrentName = null,
                                directoryCurrentSize = null,
                            )
                            continue
                        }
                        mutableState.value = mutableState.value.copy(fileFailure = result.reason)
                        return result
                    }
                    is CoursewareOperationResult.Success -> result.value
                }
                val exportFile = nameAllocator.resolve(CoursewareDirectoryFile(item.folders, downloaded))
                when (val written = session.write(exportFile)) {
                    HomeworkFileSaveResult.Saved -> {
                        mutableState.value = mutableState.value.copy(directoryDownloadCompleted = index + 1, directoryCurrentName = null, directoryCurrentSize = null)
                    }
                    else -> return CoursewareOperationResult.Success(written)
                }
            }
            val result = session.commit()
            committed = result == HomeworkFileSaveResult.Saved
            CoursewareOperationResult.Success(result)
        } catch (error: CancellationException) {
            if (keepCompletedOnCancel()) {
                // 不管释放会话是否成功，都不能再调用 abort 删除已保存的文件。
                committed = true
                val result = withContext(NonCancellable) {
                    try { session.commit() } catch (_: Exception) {
                        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
                    }
                }
                onCancelledWithKeptFiles(result)
            }
            throw error
        } catch (error: CoursewareRemoteException) {
            val reason = error.reason.toSyncFailure()
            mutableState.value = mutableState.value.copy(fileFailure = reason)
            CoursewareOperationResult.Failure(reason)
        } catch (_: StreamingSinkIoException) {
            CoursewareOperationResult.Success(HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO))
        } catch (_: Exception) {
            CoursewareOperationResult.Success(
                HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO),
            )
        } finally {
            if (!committed) {
                withContext(NonCancellable) { runCatching { session.abort() } }
            }
        }
    }

    private suspend fun streamResourceWithRecovery(node: CoursewareNode, sink: StreamingFileSink, onProgress: (Long, Long) -> Unit) {
        var written = false
        val tracked = StreamingFileSink { bytes ->
            sink.write(bytes)
            if (bytes.isNotEmpty()) written = true
        }
        try {
            repository.streamResource(node, tracked, onProgress)
        } catch (error: CoursewareRemoteException) {
            if (error.reason != CoursewareRemoteFailure.SESSION_EXPIRED || written) throw error
            val recovered = try { reauthenticate?.invoke() == true } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
            if (!recovered) throw error
            repository.streamResource(node, tracked, onProgress)
        }
    }

    suspend fun downloadTeachingCalendar(): CoursewareOperationResult<HomeworkFileContent> = operationMutex.withLock {
        val course = mutableState.value.selectedCourse
            ?: return@withLock CoursewareOperationResult.Failure(CoursewareSyncFailure.MALFORMED_RESPONSE)
        mutableState.value = mutableState.value.copy(isDownloading = true, fileFailure = null)
        try {
            downloadFileWithRecovery { repository.downloadTeachingCalendar(course) }.also { result ->
                if (result is CoursewareOperationResult.Failure) {
                    mutableState.value = mutableState.value.copy(fileFailure = result.reason)
                }
            }
        } finally {
            mutableState.value = mutableState.value.copy(isDownloading = false)
        }
    }

    fun resourcesUnder(stableKey: String?): List<CoursewareNode> {
        val course = mutableState.value.selectedCourse ?: return emptyList()
        val roots = if (stableKey == null) {
            course.children
        } else {
            val node = findCoursewareNode(course.children, stableKey) ?: return emptyList()
            if (node.isFolder) node.children else listOf(node)
        }
        return roots.flatMapResources()
    }

    fun dismissFailure() {
        mutableState.value = mutableState.value.copy(failure = null)
    }

    fun dismissFileFailure() {
        mutableState.value = mutableState.value.copy(fileFailure = null)
    }

    /**
     * 文件下载只包含取票和 GET，不会重复提交用户数据，因此可以安全地做一次恢复/重试：
     * - 会话失效：恢复学校登录态后再取一次下载票；
     * - 网络切换或临时无效响应：短暂等待后再请求一次。
     */
    private suspend fun <T> recoverOperation(
        operation: suspend () -> CoursewareOperationResult<T>,
    ): CoursewareOperationResult<T> {
        val result = operation()
        if (result !is CoursewareOperationResult.Failure || result.reason != CoursewareSyncFailure.SESSION_EXPIRED) return result
        val recovered = try {
            reauthenticate?.invoke() == true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        return if (recovered) operation() else result
    }

    private suspend fun downloadFileWithRecovery(
        operation: suspend () -> CoursewareOperationResult<HomeworkFileContent>,
    ): CoursewareOperationResult<HomeworkFileContent> {
        var result = operation()
        if (result is CoursewareOperationResult.Failure &&
            result.reason == CoursewareSyncFailure.SESSION_EXPIRED
        ) {
            val recovered = try {
                reauthenticate?.invoke() == true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (recovered) {
                result = operation()
            }
        }
        if (result is CoursewareOperationResult.Failure &&
            result.reason in setOf(CoursewareSyncFailure.NETWORK, CoursewareSyncFailure.MALFORMED_RESPONSE)
        ) {
            delay(COURSEWARE_DOWNLOAD_RETRY_DELAY_MILLIS)
            result = operation()
        }
        return result
    }

    private suspend fun loadFolder(stableKey: String): Boolean {
        val courseId = mutableState.value.selectedCourseId ?: return false
        return operationMutex.withLock {
            if (mutableState.value.selectedCourseId != courseId) return@withLock false
            loadFolderLocked(stableKey, courseId)
        }
    }

    private suspend fun loadFolderLocked(stableKey: String, courseId: Int): Boolean {
        var before = mutableState.value
        var course = before.courses.firstOrNull { it.id == courseId } ?: return false
        if (!course.childrenLoaded) {
            if (!loadCourseLocked(course.id)) return false
            before = mutableState.value
            course = before.courses.firstOrNull { it.id == courseId } ?: return false
        }
        val folder = findCoursewareNode(course.children, stableKey)
            ?.takeIf(CoursewareNode::isFolder)
            ?: return false
        if (folder.childrenLoaded) return true
        mutableState.value = before.copy(
            loadingFolderKeys = before.loadingFolderKeys + stableKey,
            fileFailure = null,
        )
        return try {
            when (
                val result = repository.loadFolder(
                    snapshot = CoursewareSnapshot(mutableState.value.courses),
                    courseId = course.id,
                    folderKey = stableKey,
                )
            ) {
                is CoursewareOperationResult.Success -> {
                    applySnapshot(result.value, CoursewareContentSource.NETWORK, mutableState.value.failure)
                    true
                }
                is CoursewareOperationResult.Failure -> {
                    mutableState.value = mutableState.value.copy(fileFailure = result.reason)
                    false
                }
            }
        } finally {
            mutableState.value = mutableState.value.copy(
                loadingFolderKeys = mutableState.value.loadingFolderKeys - stableKey,
            )
        }
    }

    private suspend fun hydrateExportTree(stableKey: String?, selectedCourseId: Int): CoursewareSyncFailure? {
        if (!loadCourseLocked(selectedCourseId)) {
            return mutableState.value.fileFailure ?: CoursewareSyncFailure.NETWORK
        }
        val visited = mutableSetOf<String>()
        while (true) {
            val course = mutableState.value.courses.firstOrNull { it.id == selectedCourseId }
                ?: return CoursewareSyncFailure.MALFORMED_RESPONSE
            val roots = if (stableKey == null) {
                course.children
            } else {
                val selected = findCoursewareNode(course.children, stableKey)
                    ?: return CoursewareSyncFailure.MALFORMED_RESPONSE
                listOf(selected)
            }
            val unloaded = roots.firstUnloadedFolder() ?: return null
            if (!visited.add(unloaded.stableKey)) return CoursewareSyncFailure.MALFORMED_RESPONSE
            if (!loadFolderLocked(unloaded.stableKey, selectedCourseId)) {
                return mutableState.value.fileFailure ?: CoursewareSyncFailure.NETWORK
            }
        }
    }

    private suspend fun loadCourseLocked(courseId: Int): Boolean {
        val before = mutableState.value
        val course = before.courses.firstOrNull { it.id == courseId } ?: return false
        if (courseId in freshCourseIds && course.childrenLoaded) return true
        mutableState.value = before.copy(
            loadingCourseIds = before.loadingCourseIds + courseId,
            fileFailure = null,
        )
        return try {
            when (
                val result = repository.loadCourse(
                    snapshot = CoursewareSnapshot(mutableState.value.courses),
                    courseId = courseId,
                )
            ) {
                is CoursewareOperationResult.Success -> {
                    freshCourseIds += courseId
                    applySnapshot(result.value, CoursewareContentSource.NETWORK, mutableState.value.failure)
                    true
                }
                is CoursewareOperationResult.Failure -> {
                    mutableState.value = mutableState.value.copy(fileFailure = result.reason)
                    false
                }
            }
        } finally {
            mutableState.value = mutableState.value.copy(
                loadingCourseIds = mutableState.value.loadingCourseIds - courseId,
            )
        }
    }

    /**
     * 在已持有 [operationMutex] 时调用：并发加载尚未拉顶层目录的课程。
     * 网络并发、一次合并落库；单课失败不阻断其它课。
     */
    private suspend fun preloadUnloadedCourseRootsLocked(courseIds: List<Int>) {
        val before = mutableState.value
        if (courseIds.isEmpty()) return
        mutableState.value = before.copy(
            loadingCourseIds = before.loadingCourseIds + courseIds,
        )
        try {
            when (
                val result = repository.loadCoursesConcurrently(
                    snapshot = CoursewareSnapshot(mutableState.value.courses),
                    courseIds = courseIds,
                    // 智慧教学接口对并发敏感；2 路在速度与稳定性之间折中。
                    concurrency = 2,
                )
            ) {
                is CoursewareOperationResult.Success -> {
                    val loadedIds = result.value.courses
                        .filter { it.childrenLoaded && it.id in courseIds }
                        .map { it.id }
                    freshCourseIds += loadedIds
                    applySnapshot(
                        result.value,
                        source = mutableState.value.source ?: CoursewareContentSource.NETWORK,
                        failure = mutableState.value.failure,
                    )
                }
                is CoursewareOperationResult.Failure -> {
                    // 课件目录刷新失败时保留旧内容，但必须明确提示用户，避免把旧目录
                    // 误认为已经同步成功；再次刷新或重新选择课程都会重试。
                    mutableState.value = mutableState.value.copy(failure = result.reason)
                }
            }
        } finally {
            mutableState.value = mutableState.value.copy(
                loadingCourseIds = mutableState.value.loadingCourseIds - courseIds.toSet(),
            )
        }
    }

    private suspend fun prefetchUnloadedFolders() {
        prefetchMutex.withLock {
            val snapshot = CoursewareSnapshot(mutableState.value.courses)
            when (
                val result = repository.loadUnloadedFolders(
                    snapshot = snapshot,
                    concurrency = 2,
                )
            ) {
                is CoursewareOperationResult.Success -> {
                    if (result.value == snapshot) return@withLock
                    operationMutex.withLock {
                        applySnapshot(
                            result.value,
                            source = mutableState.value.source ?: CoursewareContentSource.NETWORK,
                            failure = mutableState.value.failure,
                        )
                    }
                }
                is CoursewareOperationResult.Failure -> Unit
            }
        }
    }

    private fun applySnapshot(
        snapshot: CoursewareSnapshot,
        source: CoursewareContentSource?,
        failure: CoursewareSyncFailure?,
        resetNavigation: Boolean = false,
    ) {
        val current = mutableState.value
        val selectedCourseId = current.selectedCourseId
            ?.takeIf { id -> snapshot.courses.any { it.id == id } }
            ?: snapshot.courses.firstOrNull()?.id
        val selectedCourse = snapshot.courses.firstOrNull { it.id == selectedCourseId }
        val allKeys = selectedCourse?.children?.allNodeKeys()
        val validPath = selectedCourse?.let { nodesAtCoursewarePath(it, current.compactFolderPath) } != null
        mutableState.value = current.copy(
            courses = snapshot.courses,
            selectedCourseId = selectedCourseId,
            compactFolderPath = if (resetNavigation) {
                emptyList()
            } else {
                current.compactFolderPath.takeIf { validPath }.orEmpty()
            },
            expandedFolderKeys = if (resetNavigation) {
                emptySet()
            } else {
                current.expandedFolderKeys.filterTo(mutableSetOf()) { it in allKeys.orEmpty() }
            },
            selectedNodeKey = if (resetNavigation) {
                null
            } else {
                current.selectedNodeKey?.takeIf { it in allKeys.orEmpty() }
            },
            loadingCourseIds = current.loadingCourseIds.filterTo(mutableSetOf()) { id ->
                snapshot.courses.any { it.id == id }
            },
            loadingFolderKeys = current.loadingFolderKeys.filterTo(mutableSetOf()) { it in allKeys.orEmpty() },
            isLoading = false,
            isRefreshing = false,
            source = source,
            failure = failure,
        )
    }
}

private const val COURSEWARE_DOWNLOAD_RETRY_DELAY_MILLIS = 250L

private fun List<CoursewareNode>.firstUnloadedFolder(): CoursewareNode? {
    for (node in this) {
        if (node.isFolder && !node.childrenLoaded) return node
        node.children.firstUnloadedFolder()?.let { return it }
    }
    return null
}

private fun List<CoursewareNode>.allNodeKeys(): Set<String> = buildSet {
    fun addLevel(nodes: List<CoursewareNode>) {
        nodes.forEach { node ->
            add(node.stableKey)
            addLevel(node.children)
        }
    }
    addLevel(this@allNodeKeys)
}

private fun List<CoursewareNode>.flatMapResources(): List<CoursewareNode> = buildList {
    fun addLevel(nodes: List<CoursewareNode>) {
        nodes.forEach { node ->
            if (node.isFolder) addLevel(node.children) else add(node)
        }
    }
    addLevel(this@flatMapResources)
}

private data class CoursewareResourcePath(
    val node: CoursewareNode,
    val folders: List<String>,
)

private fun CoursewareCourse.resourcesWithFolders(stableKey: String?): List<CoursewareResourcePath> {
    val root = if (stableKey == null) {
        children
    } else {
        val node = findCoursewareNode(children, stableKey) ?: return emptyList()
        if (node.isFolder) node.children else listOf(node)
    }
    return buildList {
        fun addLevel(nodes: List<CoursewareNode>, folders: List<String>) {
            nodes.forEach { node ->
                if (node.isFolder) {
                    addLevel(node.children, folders + node.name)
                } else {
                    add(CoursewareResourcePath(node, folders))
                }
            }
        }
        addLevel(root, emptyList())
    }
}
