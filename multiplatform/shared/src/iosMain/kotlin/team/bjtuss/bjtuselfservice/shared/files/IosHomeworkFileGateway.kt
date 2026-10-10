package team.bjtuss.bjtuselfservice.shared.files

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.darwin.NSObject
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosHomeworkFileGateway(
    private val owner: () -> UIViewController,
) : HomeworkFileGateway, CoursewareDirectoryGateway {
    private var activeDelegate: IosDocumentPickerDelegate? = null
    private var activePicker: UIDocumentPickerViewController? = null

    private var activePreview: IosQuickLookSource? = null

    override val isAvailable: Boolean = true
    override val isDirectoryExportAvailable: Boolean = true
    override val isPreviewAvailable: Boolean = true

    /** 系统快速查看：文件只放在应用临时目录，预览关闭时删除。 */
    override suspend fun previewFile(file: HomeworkFileContent): HomeworkFilePreviewResult {
        if (activePreview != null || activeDelegate != null) {
            return HomeworkFilePreviewResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
        // 预览目录单独加前缀：进程在预览中途退出时来不及删，下次预览前统一清掉。
        removeStalePreviewDirectories()
        val temporary = file.writeTemporaryExport(PREVIEW_DIRECTORY_PREFIX)
            ?: return HomeworkFilePreviewResult.Failed(HomeworkFileGatewayFailure.IO)
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
        owner().presentViewController(controller, animated = true, completion = null)
        return HomeworkFilePreviewResult.Opened
    }

    override suspend fun pickFiles(): HomeworkFilePickResult {
        if (activeDelegate != null) {
            return HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
        return suspendCancellableCoroutine { continuation ->
            lateinit var delegate: IosDocumentPickerDelegate
            delegate = IosDocumentPickerDelegate(
                onPicked = { urls ->
                    val result = readFiles(urls)
                    finish(delegate, continuation, result)
                },
                onCancelled = {
                    finish(delegate, continuation, HomeworkFilePickResult.Cancelled)
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
            owner().presentViewController(picker, animated = true, completion = null)
        }
    }

    override suspend fun saveFile(file: HomeworkFileContent): HomeworkFileSaveResult {
        if (activeDelegate != null) {
            return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
        val temporary = file.writeTemporaryExport()
            ?: return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
        return suspendCancellableCoroutine { continuation ->
            lateinit var delegate: IosDocumentPickerDelegate
            delegate = IosDocumentPickerDelegate(
                onPicked = {
                    removeTemporary(temporary.directoryUrl)
                    finish(delegate, continuation, HomeworkFileSaveResult.Saved)
                },
                onCancelled = {
                    removeTemporary(temporary.directoryUrl)
                    finish(delegate, continuation, HomeworkFileSaveResult.Cancelled)
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
            owner().presentViewController(picker, animated = true, completion = null)
        }
    }

    override suspend fun openDirectory(directoryName: String): CoursewareDirectoryOpenResult {
        if (activeDelegate != null) {
            return CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
        return suspendCancellableCoroutine { continuation ->
            lateinit var delegate: IosDocumentPickerDelegate
            delegate = IosDocumentPickerDelegate(
                onPicked = { urls ->
                    val selectedDirectory = urls.singleOrNull()
                    if (selectedDirectory == null) {
                        finish(delegate, continuation, CoursewareDirectoryOpenResult.Cancelled)
                    } else {
                        CoroutineScope(continuation.context).launch {
                            var openedSession: CoursewareDirectoryWriteSession? = null
                            var handedOff = false
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    openCoursewareDirectorySession(selectedDirectory, directoryName)
                                }
                                if (result is CoursewareDirectoryOpenResult.Opened) {
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
                    finish(delegate, continuation, CoursewareDirectoryOpenResult.Cancelled)
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
            owner().presentViewController(picker, animated = true, completion = null)
        }
    }

    private fun readFiles(urls: List<NSURL>): HomeworkFilePickResult {
        if (urls.isEmpty()) return HomeworkFilePickResult.Cancelled
        return try {
            HomeworkFilePickResult.Selected(
                urls.map { url ->
                    val scoped = url.startAccessingSecurityScopedResource()
                    try {
                        val data = NSData.dataWithContentsOfURL(url)
                            ?: return HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO)
                        val fileName = safeExportFileName(url.lastPathComponent ?: "upload.bin")
                        HomeworkFileContent(
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
            HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO)
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
private data class TemporaryExport(
    val fileUrl: NSURL,
    val directoryUrl: NSURL,
)

private const val PREVIEW_DIRECTORY_PREFIX = "bjtu-preview-"

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
private fun HomeworkFileContent.writeTemporaryExport(prefix: String = "bjtu-homework-"): TemporaryExport? {
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
private suspend fun openCoursewareDirectorySession(
    selectedDirectory: NSURL,
    directoryName: String,
): CoursewareDirectoryOpenResult {
    if (!selectedDirectory.startAccessingSecurityScopedResource()) {
        return CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
    }
    val context = currentCoroutineContext()
    var createdRootUrl: NSURL? = null
    return try {
        var result: CoursewareDirectoryOpenResult =
            CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.IO)
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
            result = CoursewareDirectoryOpenResult.Opened(
                IosCoursewareDirectoryWriteSession(selectedDirectory, candidate),
            )
        }
        context.ensureActive()
        if (result !is CoursewareDirectoryOpenResult.Opened) {
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
        CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.IO)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class IosCoursewareDirectoryWriteSession(
    private val selectedDirectory: NSURL,
    private val rootUrl: NSURL,
) : CoursewareDirectoryWriteSession {
    private val lifecycleMutex = Mutex()
    private var closed = false

    override suspend fun write(file: CoursewareDirectoryFile): HomeworkFileSaveResult =
        lifecycleMutex.withLock {
            if (closed) return@withLock HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                try {
                    var result: HomeworkFileSaveResult =
                        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
                    NSFileCoordinator().coordinateWritingItemAtURL(
                        url = rootUrl,
                        options = 0uL,
                        error = null,
                    ) fileAccess@{ coordinatedRoot ->
                        context.ensureActive()
                        val root = coordinatedRoot ?: return@fileAccess
                        result = writeCoursewareFile(root, file) { context.ensureActive() }
                    }
                    context.ensureActive()
                    result
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
                }
            }
        }

    override suspend fun commit(): HomeworkFileSaveResult = lifecycleMutex.withLock {
        if (closed) return@withLock HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        closed = true
        selectedDirectory.stopAccessingSecurityScopedResource()
        HomeworkFileSaveResult.Saved
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
private fun writeCoursewareFile(
    rootUrl: NSURL,
    file: CoursewareDirectoryFile,
    ensureActive: () -> Unit,
): HomeworkFileSaveResult {
    ensureActive()
    val manager = NSFileManager.defaultManager
    var folderUrl: NSURL? = rootUrl
    file.relativeFolders.forEach { segment ->
        folderUrl = folderUrl?.URLByAppendingPathComponent(safeExportPathSegment(segment), isDirectory = true)
    }
    val resolvedFolderUrl = folderUrl ?: return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
    val folderPath = resolvedFolderUrl.path ?: return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
    if (!manager.createDirectoryAtPath(
            path = folderPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    ) {
        return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
    }
    val fileUrl = resolvedFolderUrl.URLByAppendingPathComponent(
        safeExportFileName(file.content.fileName),
        isDirectory = false,
    ) ?: return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
    val filePath = fileUrl.path ?: return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
    if (manager.fileExistsAtPath(filePath)) {
        return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
    }
    ensureActive()
    return if (file.content.bytes.toNSData().writeToURL(fileUrl, atomically = true)) {
        HomeworkFileSaveResult.Saved
    } else {
        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
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
