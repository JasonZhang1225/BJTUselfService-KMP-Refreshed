package team.bjtuss.bjtuselfservice.shared.files

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.writeData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.create
import platform.Foundation.writeToURL
import platform.QuickLook.QLPreviewController
import platform.QuickLook.QLPreviewControllerDataSourceProtocol
import platform.QuickLook.QLPreviewControllerDelegateProtocol
import platform.QuickLook.QLPreviewItemProtocol
import platform.darwin.NSInteger
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.NSObject
import platform.darwin.dispatch_after
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_time

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosFileGateway(
    private val owner: () -> UIViewController,
) : FileGateway, DirectoryGateway {
    private var activeDelegate: IosDocumentPickerDelegate? = null
    private var activePicker: UIDocumentPickerViewController? = null

    private var activePreview: IosQuickLookSource? = null

    override val isAvailable: Boolean = true
    override val isDirectoryExportAvailable: Boolean = true
    override val isPreviewAvailable: Boolean = true

    /** 系统快速查看：文件只放在应用临时目录，预览关闭时删除。 */
    override suspend fun previewFile(file: FileContent): FilePreviewResult {
        if (activePreview != null || activeDelegate != null) {
            return FilePreviewResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        // 预览目录单独加前缀：进程在预览中途退出时来不及删，下次预览前统一清掉。
        removeStalePreviewDirectories()
        val temporary = file.writeTemporaryExport(PREVIEW_DIRECTORY_PREFIX)
            ?: return FilePreviewResult.Failed(FileGatewayFailure.IO)
        lateinit var source: IosQuickLookSource
        source = IosQuickLookSource(temporary.fileUrl) {
            removeTemporary(temporary.directoryUrl)
            if (activePreview === source) activePreview = null
        }
        val controller = QLPreviewController().apply {
            dataSource = source
            delegate = source
        }
        activePreview = source
        presentFromTop(controller) {
            if (activePreview === source) {
                activePreview = null
                removeTemporary(temporary.directoryUrl)
            }
        }
        return FilePreviewResult.Opened
    }

    override suspend fun pickFiles(): FilePickResult {
        if (activeDelegate != null) {
            return FilePickResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        return suspendCancellableCoroutine { continuation ->
            lateinit var delegate: IosDocumentPickerDelegate
            delegate = IosDocumentPickerDelegate(
                onPicked = { urls ->
                    val result = readFiles(urls)
                    finish(delegate, continuation, result)
                },
                onCancelled = {
                    finish(delegate, continuation, FilePickResult.Cancelled)
                },
            )
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeData),
                asCopy = true,
            ).apply {
                allowsMultipleSelection = true
                this.delegate = delegate
            }
            activeDelegate = delegate
            activePicker = picker
            continuation.invokeOnCancellation {
                cancel(delegate)
            }
            presentFromTop(picker) {
                finish(delegate, continuation, FilePickResult.Failed(FileGatewayFailure.UNAVAILABLE))
            }
        }
    }

    override suspend fun saveFile(file: FileContent): FileSaveResult {
        if (activeDelegate != null) {
            return FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        val temporary = file.writeTemporaryExport()
            ?: return FileSaveResult.Failed(FileGatewayFailure.IO)
        return suspendCancellableCoroutine { continuation ->
            lateinit var delegate: IosDocumentPickerDelegate
            delegate = IosDocumentPickerDelegate(
                onPicked = {
                    removeTemporary(temporary.directoryUrl)
                    finish(delegate, continuation, FileSaveResult.Saved)
                },
                onCancelled = {
                    removeTemporary(temporary.directoryUrl)
                    finish(delegate, continuation, FileSaveResult.Cancelled)
                },
            )
            val picker = UIDocumentPickerViewController(
                forExportingURLs = listOf(temporary.fileUrl),
                asCopy = true,
            ).apply {
                this.delegate = delegate
            }
            activeDelegate = delegate
            activePicker = picker
            continuation.invokeOnCancellation {
                removeTemporary(temporary.directoryUrl)
                cancel(delegate)
            }
            presentFromTop(picker) {
                if (activeDelegate === delegate) {
                    removeTemporary(temporary.directoryUrl)
                    finish(delegate, continuation, FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE))
                }
            }
        }
    }

    override val isStreamingSaveAvailable: Boolean = true

    /**
     * 系统导出面板只能导出已经写完的文件；回放动辄 1–2 GB，先下到临时目录再导出会占双倍空间。
     * 因此先让用户选一个文件夹（与课件目录导出同一面板），之后在里面边下边写 `.part`，
     * commit 时改成正式文件名（重名追加序号）；abort 删除 `.part`。
     */
    override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult {
        if (activeDelegate != null) {
            return StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        val folder = pickFolder() ?: return StreamingDestinationResult.Cancelled
        val scoped = folder.startAccessingSecurityScopedResource()
        val folderPath = folder.path
        val partPath = folderPath?.let { "$it/.${NSUUID().UUIDString}.part" }
        if (folderPath == null || partPath == null ||
            !NSFileManager.defaultManager.createFileAtPath(partPath, contents = null, attributes = null)
        ) {
            if (scoped) folder.stopAccessingSecurityScopedResource()
            return StreamingDestinationResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
        }
        val handle = NSFileHandle.fileHandleForWritingAtPath(partPath)
        if (handle == null) {
            NSFileManager.defaultManager.removeItemAtPath(partPath, error = null)
            if (scoped) folder.stopAccessingSecurityScopedResource()
            return StreamingDestinationResult.Failed(FileGatewayFailure.IO)
        }
        return StreamingDestinationResult.Opened(
            IosStreamingDestination(folder, scoped, folderPath, partPath, safeExportFileName(fileName), handle),
        )
    }

    private suspend fun pickFolder(): NSURL? = suspendCancellableCoroutine { continuation ->
        lateinit var delegate: IosDocumentPickerDelegate
        delegate = IosDocumentPickerDelegate(
            onPicked = { urls -> finish(delegate, continuation, urls.singleOrNull()) },
            onCancelled = { finish(delegate, continuation, null) },
        )
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeFolder),
            asCopy = false,
        ).apply {
            allowsMultipleSelection = false
            this.delegate = delegate
        }
        activeDelegate = delegate
        activePicker = picker
        continuation.invokeOnCancellation { cancel(delegate) }
        presentFromTop(picker) { finish(delegate, continuation, null) }
    }

    override suspend fun openDirectory(directoryName: String): DirectoryOpenResult {
        if (activeDelegate != null) {
            return DirectoryOpenResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        return suspendCancellableCoroutine { continuation ->
            lateinit var delegate: IosDocumentPickerDelegate
            delegate = IosDocumentPickerDelegate(
                onPicked = { urls ->
                    val selectedDirectory = urls.singleOrNull()
                    if (selectedDirectory == null) {
                        finish(delegate, continuation, DirectoryOpenResult.Cancelled)
                    } else {
                        CoroutineScope(continuation.context).launch {
                            var openedSession: DirectoryWriteSession? = null
                            var handedOff = false
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    openDirectorySession(selectedDirectory, directoryName)
                                }
                                if (result is DirectoryOpenResult.Opened) {
                                    openedSession = result.session
                                }
                                withContext(Dispatchers.Main) {
                                    handedOff = finish(delegate, continuation, result)
                                }
                            } finally {
                                if (!handedOff) openedSession?.abort()
                            }
                        }
                    }
                },
                onCancelled = {
                    finish(delegate, continuation, DirectoryOpenResult.Cancelled)
                },
            )
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeFolder),
                asCopy = false,
            ).apply {
                allowsMultipleSelection = false
                this.delegate = delegate
            }
            activeDelegate = delegate
            activePicker = picker
            continuation.invokeOnCancellation {
                cancel(delegate)
            }
            presentFromTop(picker) {
                finish(delegate, continuation, DirectoryOpenResult.Failed(FileGatewayFailure.UNAVAILABLE))
            }
        }
    }

    /**
     * 上传/保存入口常在 UIKit sheet 里（作业上传、日历导出），宿主控制器此时正呈现那张 sheet，
     * 直接让它再 present 会被 UIKit 静默拒绝：选择器不出来，协程也永远等不到回调。
     * 因此从最上层、且不在关闭动画中的控制器呈现。
     *
     * 文件选择器是系统远程界面，present 返回时 presentingViewController 可能仍为空，
     * 不能同步判定成败；以呈现完成回调为准，超时仍未呈现才调用 [onRejected] 复位状态。
     */
    private fun presentFromTop(controller: UIViewController, onRejected: () -> Unit) {
        var presenter = owner()
        while (true) {
            val next = presenter.presentedViewController ?: break
            if (next.isBeingDismissed()) break
            presenter = next
        }
        var presented = false
        presenter.presentViewController(controller, animated = true) { presented = true }
        dispatch_after(
            dispatch_time(DISPATCH_TIME_NOW, PRESENT_CONFIRM_TIMEOUT_NS),
            dispatch_get_main_queue(),
        ) {
            if (!presented && controller.presentingViewController == null) onRejected()
        }
    }

    private fun readFiles(urls: List<NSURL>): FilePickResult {
        if (urls.isEmpty()) return FilePickResult.Cancelled
        return try {
            FilePickResult.Selected(
                urls.map { url ->
                    val scoped = url.startAccessingSecurityScopedResource()
                    try {
                        val data = NSData.dataWithContentsOfURL(url)
                            ?: return FilePickResult.Failed(FileGatewayFailure.IO)
                        val fileName = safeExportFileName(url.lastPathComponent ?: "upload.bin")
                        FileContent(
                            fileName = fileName,
                            contentType = fileName.guessContentType(),
                            bytes = data.toByteArray(),
                        )
                    } finally {
                        if (scoped) url.stopAccessingSecurityScopedResource()
                    }
                },
            )
        } catch (_: Exception) {
            FilePickResult.Failed(FileGatewayFailure.IO)
        }
    }

    private fun <T> finish(
        delegate: IosDocumentPickerDelegate,
        continuation: CancellableContinuation<T>,
        result: T,
    ): Boolean {
        if (activeDelegate !== delegate) return false
        activePicker?.delegate = null
        activePicker = null
        activeDelegate = null
        if (!continuation.isActive) return false
        continuation.resume(result)
        return true
    }

    private fun cancel(delegate: IosDocumentPickerDelegate) {
        if (activeDelegate !== delegate) return
        activePicker?.delegate = null
        activePicker?.dismissViewControllerAnimated(true, completion = null)
        activePicker = null
        activeDelegate = null
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosDocumentPickerDelegate(
    private val onPicked: (List<NSURL>) -> Unit,
    private val onCancelled: () -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        onPicked(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onCancelled()
    }
}

/**
 * NSURL 通过 Objective-C 分类遵循 QLPreviewItem，Kotlin/Native 看不到这层遵循，
 * 直接 `as QLPreviewItemProtocol` 会抛 TypeCastException；因此自己实现预览项。
 */
@OptIn(ExperimentalForeignApi::class)
private class IosQuickLookItem(private val fileUrl: NSURL) : NSObject(), QLPreviewItemProtocol {
    override fun previewItemURL(): NSURL? = fileUrl
}

/** QLPreviewController 只弱引用数据源；网关持有本对象直到预览关闭。 */
@OptIn(ExperimentalForeignApi::class)
private class IosQuickLookSource(
    fileUrl: NSURL,
    private val onDismissed: () -> Unit,
) : NSObject(), QLPreviewControllerDataSourceProtocol, QLPreviewControllerDelegateProtocol {
    private val item = IosQuickLookItem(fileUrl)

    override fun numberOfPreviewItemsInPreviewController(controller: QLPreviewController): NSInteger = 1

    override fun previewController(
        controller: QLPreviewController,
        previewItemAtIndex: NSInteger,
    ): QLPreviewItemProtocol = item

    override fun previewControllerDidDismiss(controller: QLPreviewController) {
        onDismissed()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosStreamingDestination(
    private val folder: NSURL,
    private val scoped: Boolean,
    private val folderPath: String,
    private val partPath: String,
    private val fileName: String,
    private var handle: NSFileHandle?,
) : StreamingDestination {
    override var displayName: String = fileName
        private set

    override suspend fun write(bytes: ByteArray) {
        val current = handle ?: throw StreamingSinkIoException()
        val written = withContext(Dispatchers.Default) {
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                current.writeData(bytes.toNSData(), error = error.ptr) && error.value == null
            }
        }
        if (!written) throw StreamingSinkIoException()
    }

    override suspend fun commit(): FileSaveResult = withContext(Dispatchers.Default) {
        close()
        val finalPath = uniqueChildPath(folderPath, fileName)
        val moved = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            NSFileManager.defaultManager.moveItemAtPath(partPath, toPath = finalPath, error = error.ptr)
        }
        release()
        if (moved) {
            displayName = finalPath.substringAfterLast('/')
            FileSaveResult.Saved
        } else {
            NSFileManager.defaultManager.removeItemAtPath(partPath, error = null)
            FileSaveResult.Failed(FileGatewayFailure.IO)
        }
    }

    override suspend fun abort() {
        withContext(NonCancellable + Dispatchers.Default) {
            close()
            NSFileManager.defaultManager.removeItemAtPath(partPath, error = null)
            release()
        }
    }

    private fun close() {
        handle?.let { current ->
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                current.closeAndReturnError(error.ptr)
            }
        }
        handle = null
    }

    private var released = false
    private fun release() {
        if (released) return
        released = true
        if (scoped) folder.stopAccessingSecurityScopedResource()
    }
}

/** 同名文件已存在时追加“ (2)”“ (3)”，不覆盖用户已有的录像。 */
@OptIn(ExperimentalForeignApi::class)
private fun uniqueChildPath(folderPath: String, fileName: String): String {
    val manager = NSFileManager.defaultManager
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val extension = if (dot > 0) fileName.substring(dot) else ""
    var candidate = "$folderPath/$fileName"
    var index = 2
    while (manager.fileExistsAtPath(candidate) && index < 1000) {
        candidate = "$folderPath/$base ($index)$extension"
        index++
    }
    return candidate
}

@OptIn(ExperimentalForeignApi::class)
private data class TemporaryExport(
    val fileUrl: NSURL,
    val directoryUrl: NSURL,
)

private const val PREVIEW_DIRECTORY_PREFIX = "bjtu-preview-"

/** 呈现动画约 0.4 秒；留足余量，避免慢机上把正常弹出的选择器误判为被拒。 */
private const val PRESENT_CONFIRM_TIMEOUT_NS = 2_000_000_000L

@OptIn(ExperimentalForeignApi::class)
private fun removeStalePreviewDirectories() {
    val root = NSTemporaryDirectory().trimEnd('/')
    val manager = NSFileManager.defaultManager
    manager.contentsOfDirectoryAtPath(root, error = null)
        ?.filterIsInstance<String>()
        ?.filter { it.startsWith(PREVIEW_DIRECTORY_PREFIX) }
        ?.forEach { manager.removeItemAtPath("$root/$it", error = null) }
}

@OptIn(ExperimentalForeignApi::class)
private fun FileContent.writeTemporaryExport(prefix: String = "bjtu-homework-"): TemporaryExport? {
    val safeName = safeExportFileName(fileName)
    val directoryPath = NSTemporaryDirectory().trimEnd('/') + "/" + prefix + NSUUID().UUIDString
    val manager = NSFileManager.defaultManager
    if (!manager.createDirectoryAtPath(
            path = directoryPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    ) {
        return null
    }
    val directoryUrl = NSURL(fileURLWithPath = directoryPath, isDirectory = true)
    val fileUrl = NSURL(fileURLWithPath = "$directoryPath/$safeName")
    if (!bytes.toNSData().writeToURL(fileUrl, atomically = true)) {
        removeTemporary(directoryUrl)
        return null
    }
    return TemporaryExport(fileUrl = fileUrl, directoryUrl = directoryUrl)
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun openDirectorySession(
    selectedDirectory: NSURL,
    directoryName: String,
): DirectoryOpenResult {
    if (!selectedDirectory.startAccessingSecurityScopedResource()) {
        return DirectoryOpenResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
    }
    val context = currentCoroutineContext()
    var createdRootUrl: NSURL? = null
    return try {
        var result: DirectoryOpenResult =
            DirectoryOpenResult.Failed(FileGatewayFailure.IO)
        NSFileCoordinator().coordinateWritingItemAtURL(
            url = selectedDirectory,
            options = 0uL,
            error = null,
        ) directoryAccess@{ coordinatedDirectory ->
            context.ensureActive()
            val directory = coordinatedDirectory ?: return@directoryAccess
            val candidate = directory.URLByAppendingPathComponent(
                safeExportPathSegment(directoryName),
                isDirectory = true,
            ) ?: return@directoryAccess
            val rootPath = candidate.path ?: return@directoryAccess
            val manager = NSFileManager.defaultManager
            if (manager.fileExistsAtPath(rootPath)) return@directoryAccess
            if (!manager.createDirectoryAtPath(
                    path = rootPath,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null,
                )
            ) {
                return@directoryAccess
            }
            createdRootUrl = candidate
            result = DirectoryOpenResult.Opened(
                IosDirectoryWriteSession(selectedDirectory, candidate),
            )
        }
        context.ensureActive()
        if (result !is DirectoryOpenResult.Opened) {
            selectedDirectory.stopAccessingSecurityScopedResource()
        }
        result
    } catch (error: CancellationException) {
        createdRootUrl?.let { NSFileManager.defaultManager.removeItemAtURL(it, error = null) }
        selectedDirectory.stopAccessingSecurityScopedResource()
        throw error
    } catch (_: Exception) {
        createdRootUrl?.let { NSFileManager.defaultManager.removeItemAtURL(it, error = null) }
        selectedDirectory.stopAccessingSecurityScopedResource()
        DirectoryOpenResult.Failed(FileGatewayFailure.IO)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosDirectoryWriteSession(
    private val selectedDirectory: NSURL,
    private val rootUrl: NSURL,
) : DirectoryWriteSession {
    private val lifecycleMutex = Mutex()
    private var closed = false

    override val supportsStreamingFiles: Boolean = true

    override suspend fun openFile(file: DirectoryFile): StreamingDestinationResult = lifecycleMutex.withLock {
        if (closed) return@withLock StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE)
        withContext(Dispatchers.Default) {
            val manager = NSFileManager.defaultManager
            val rootPath = rootUrl.path ?: return@withContext StreamingDestinationResult.Failed(FileGatewayFailure.IO)
            val folderPath = file.relativeFolders.fold(rootPath) { path, segment -> "$path/${safeExportPathSegment(segment)}" }
            if (!manager.createDirectoryAtPath(folderPath, withIntermediateDirectories = true, attributes = null, error = null)) {
                return@withContext StreamingDestinationResult.Failed(FileGatewayFailure.IO)
            }
            val name = safeExportFileName(file.content.fileName)
            if (manager.fileExistsAtPath("$folderPath/$name")) return@withContext StreamingDestinationResult.Failed(FileGatewayFailure.IO)
            val part = "$folderPath/.${NSUUID().UUIDString}.part"
            if (!manager.createFileAtPath(part, contents = null, attributes = null)) return@withContext StreamingDestinationResult.Failed(FileGatewayFailure.IO)
            val handle = NSFileHandle.fileHandleForWritingAtPath(part)
            if (handle == null) {
                manager.removeItemAtPath(part, error = null)
                return@withContext StreamingDestinationResult.Failed(FileGatewayFailure.IO)
            }
            StreamingDestinationResult.Opened(IosStreamingDestination(selectedDirectory, false, folderPath, part, name, handle))
        }
    }

    override suspend fun write(file: DirectoryFile): FileSaveResult =
        lifecycleMutex.withLock {
            if (closed) return@withLock FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE)
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                try {
                    var result: FileSaveResult =
                        FileSaveResult.Failed(FileGatewayFailure.IO)
                    NSFileCoordinator().coordinateWritingItemAtURL(
                        url = rootUrl,
                        options = 0uL,
                        error = null,
                    ) fileAccess@{ coordinatedRoot ->
                        context.ensureActive()
                        val root = coordinatedRoot ?: return@fileAccess
                        result = writeDirectoryFile(root, file) { context.ensureActive() }
                    }
                    context.ensureActive()
                    result
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    FileSaveResult.Failed(FileGatewayFailure.IO)
                }
            }
        }

    override suspend fun commit(): FileSaveResult = lifecycleMutex.withLock {
        if (closed) return@withLock FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE)
        closed = true
        selectedDirectory.stopAccessingSecurityScopedResource()
        FileSaveResult.Saved
    }

    override suspend fun abort() {
        withContext(NonCancellable + Dispatchers.Default) {
            lifecycleMutex.withLock {
                if (!closed) {
                    try {
                        NSFileCoordinator().coordinateWritingItemAtURL(
                            url = rootUrl,
                            options = 0uL,
                            error = null,
                        ) { coordinatedRoot ->
                            coordinatedRoot?.let { NSFileManager.defaultManager.removeItemAtURL(it, error = null) }
                        }
                    } finally {
                        closed = true
                        selectedDirectory.stopAccessingSecurityScopedResource()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeDirectoryFile(
    rootUrl: NSURL,
    file: DirectoryFile,
    ensureActive: () -> Unit,
): FileSaveResult {
    ensureActive()
    val manager = NSFileManager.defaultManager
    var folderUrl: NSURL? = rootUrl
    file.relativeFolders.forEach { segment ->
        folderUrl = folderUrl?.URLByAppendingPathComponent(safeExportPathSegment(segment), isDirectory = true)
    }
    val resolvedFolderUrl = folderUrl ?: return FileSaveResult.Failed(FileGatewayFailure.IO)
    val folderPath = resolvedFolderUrl.path ?: return FileSaveResult.Failed(FileGatewayFailure.IO)
    if (!manager.createDirectoryAtPath(
            path = folderPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    ) {
        return FileSaveResult.Failed(FileGatewayFailure.IO)
    }
    val fileUrl = resolvedFolderUrl.URLByAppendingPathComponent(
        safeExportFileName(file.content.fileName),
        isDirectory = false,
    ) ?: return FileSaveResult.Failed(FileGatewayFailure.IO)
    val filePath = fileUrl.path ?: return FileSaveResult.Failed(FileGatewayFailure.IO)
    if (manager.fileExistsAtPath(filePath)) {
        return FileSaveResult.Failed(FileGatewayFailure.IO)
    }
    ensureActive()
    return if (file.content.bytes.toNSData().writeToURL(fileUrl, atomically = true)) {
        FileSaveResult.Saved
    } else {
        FileSaveResult.Failed(FileGatewayFailure.IO)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun removeTemporary(url: NSURL) {
    NSFileManager.defaultManager.removeItemAtURL(url, error = null)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.convert())
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    if (length == 0uL) return byteArrayOf()
    val pointer = bytes?.reinterpret<ByteVar>() ?: return byteArrayOf()
    return pointer.readBytes(length.toInt())
}

private fun String.guessContentType(): String = when (substringAfterLast('.', "").lowercase()) {
    "pdf" -> "application/pdf"
    "doc" -> "application/msword"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xls" -> "application/vnd.ms-excel"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "ppt" -> "application/vnd.ms-powerpoint"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "txt" -> "text/plain"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
