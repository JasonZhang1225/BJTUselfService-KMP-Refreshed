package team.bjtuss.bjtuselfservice.kmp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import team.bjtuss.bjtuselfservice.shared.files.FileContent
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.files.FileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.FilePickResult
import team.bjtuss.bjtuselfservice.shared.files.FilePreviewResult
import team.bjtuss.bjtuselfservice.shared.files.FileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestination
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingSinkIoException
import team.bjtuss.bjtuselfservice.shared.files.DirectoryFile
import team.bjtuss.bjtuselfservice.shared.files.DirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.DirectoryOpenResult
import team.bjtuss.bjtuselfservice.shared.files.DirectoryWriteSession
import team.bjtuss.bjtuselfservice.shared.files.previewContentType
import team.bjtuss.bjtuselfservice.shared.files.safeExportFileName
import team.bjtuss.bjtuselfservice.shared.files.safeExportPathSegment

class AndroidFileGateway(
    private val activity: ComponentActivity,
) : FileGateway, DirectoryGateway, DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pickContinuation: CancellableContinuation<FilePickResult>? = null
    private var saveContinuation: CancellableContinuation<FileSaveResult>? = null
    private var pendingSaveFile: FileContent? = null
    private var directoryContinuation: CancellableContinuation<DirectoryOpenResult>? = null
    private var pendingDirectoryName: String? = null
    private var directoryOpenJob: Job? = null
    private var streamUriContinuation: CancellableContinuation<Uri?>? = null

    private val streamSaveLauncher = activity.registerForActivityResult(
        // 用通用类型而不是具体媒体类型：系统“创建文档”会按 MIME 补扩展名，
        // 之前写死 video/mp2t 会把回放下载的 MP4 存成“xxx.mp4.ts”。文件名里已经带正确扩展名。
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val continuation = streamUriContinuation ?: return@registerForActivityResult
        streamUriContinuation = null
        if (continuation.isActive) continuation.resume(uri)
    }

    private val pickLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val continuation = pickContinuation ?: return@registerForActivityResult
        if (uris.isEmpty()) {
            pickContinuation = null
            if (continuation.isActive) continuation.resume(FilePickResult.Cancelled)
            return@registerForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) { readSelectedFiles(uris) }
            if (pickContinuation === continuation) {
                pickContinuation = null
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    private val saveLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val continuation = saveContinuation ?: return@registerForActivityResult
        val file = pendingSaveFile
        if (uri == null) {
            saveContinuation = null
            pendingSaveFile = null
            if (continuation.isActive) continuation.resume(FileSaveResult.Cancelled)
            return@registerForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (file == null) {
                    FileSaveResult.Failed(FileGatewayFailure.IO)
                } else {
                    writeSelectedFile(uri, file)
                }
            }
            if (saveContinuation === continuation) {
                saveContinuation = null
                pendingSaveFile = null
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

    private val directoryLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val continuation = directoryContinuation ?: return@registerForActivityResult
        val directoryName = pendingDirectoryName
        if (uri == null) {
            directoryContinuation = null
            pendingDirectoryName = null
            if (continuation.isActive) continuation.resume(DirectoryOpenResult.Cancelled)
            return@registerForActivityResult
        }
        directoryOpenJob = scope.launch {
            var openedSession: DirectoryWriteSession? = null
            var handedOff = false
            try {
                val result = withContext(Dispatchers.IO) {
                    if (directoryName == null) {
                        DirectoryOpenResult.Failed(FileGatewayFailure.IO)
                    } else {
                        openSelectedDirectory(uri, directoryName)
                    }
                }
                if (result is DirectoryOpenResult.Opened) {
                    openedSession = result.session
                }
                if (directoryContinuation === continuation) {
                    directoryContinuation = null
                    pendingDirectoryName = null
                    if (continuation.isActive) {
                        continuation.resume(result)
                        handedOff = true
                    }
                }
            } finally {
                if (!handedOff) openedSession?.abort()
                directoryOpenJob = null
            }
        }
    }

    init {
        activity.lifecycle.addObserver(this)
    }

    override val isAvailable: Boolean = true
    override val isDirectoryExportAvailable: Boolean = true

    override suspend fun pickFiles(): FilePickResult {
        if (hasActiveRequest()) {
            return FilePickResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        return suspendCancellableCoroutine { continuation ->
            pickContinuation = continuation
            continuation.invokeOnCancellation {
                if (pickContinuation === continuation) pickContinuation = null
            }
            pickLauncher.launch(arrayOf("*/*"))
        }
    }

    override val isPreviewAvailable: Boolean = true

    /**
     * 写入 cacheDir/previews/ 后用 FileProvider 只读授权交给系统“打开方式”。
     * 每次预览前清掉上一批；缓存目录也会被系统按需回收。
     *
     * createChooser 默认只把 FLAG_GRANT 给选择器自己，目标应用读不到 content URI。
     * 必须把同一 URI 放进 ClipData，并显式 grant 给能处理该 MIME 的应用。
     */
    override suspend fun previewFile(file: FileContent): FilePreviewResult {
        val uri = try {
            withContext(Dispatchers.IO) {
                val root = File(activity.cacheDir, "previews")
                root.listFiles()?.forEach { it.deleteRecursively() }
                val directory = File(root, System.nanoTime().toString()).apply { mkdirs() }
                val target = File(directory, safeExportFileName(file.fileName))
                target.writeBytes(file.bytes)
                FileProvider.getUriForFile(activity, "${activity.packageName}.previews", target)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            return FilePreviewResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            return FilePreviewResult.Failed(FileGatewayFailure.IO)
        }
        val mimeType = previewContentType(file.fileName, file.contentType)
        val grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(grantFlags)
        view.clipData = ClipData.newRawUri(safeExportFileName(file.fileName), uri)
        activity.packageManager.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY).forEach { resolve ->
            activity.grantUriPermission(resolve.activityInfo.packageName, uri, grantFlags)
        }
        val chooser = Intent.createChooser(view, "打开方式").apply {
            clipData = view.clipData
            addFlags(grantFlags)
        }
        return try {
            activity.startActivity(chooser)
            FilePreviewResult.Opened
        } catch (_: ActivityNotFoundException) {
            FilePreviewResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
    }

    override suspend fun saveFile(file: FileContent): FileSaveResult {
        if (hasActiveRequest()) {
            return FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        return suspendCancellableCoroutine { continuation ->
            pendingSaveFile = file
            saveContinuation = continuation
            continuation.invokeOnCancellation {
                if (saveContinuation === continuation) {
                    saveContinuation = null
                    pendingSaveFile = null
                }
            }
            saveLauncher.launch(safeExportFileName(file.fileName))
        }
    }

    override val isStreamingSaveAvailable: Boolean = true

    /**
     * 回放等大文件：先用系统“创建文档”选好位置，之后（可在后台服务里）分块写入该文档。
     * 写入在 applicationContext 的 ContentResolver 上进行，发起页面关掉也不影响；
     * abort 时删除这个未完成的文档。
     */
    override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult {
        if (hasActiveRequest()) {
            return StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        val uri = suspendCancellableCoroutine<Uri?> { continuation ->
            streamUriContinuation = continuation
            continuation.invokeOnCancellation {
                if (streamUriContinuation === continuation) streamUriContinuation = null
            }
            streamSaveLauncher.launch(safeExportFileName(fileName))
        } ?: return StreamingDestinationResult.Cancelled
        val resolver = activity.applicationContext.contentResolver
        return try {
            // CreateDocument 的授权要持久化，进程退出后才能重新打开同一保存目标。
            runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val output = withContext(Dispatchers.IO) { resolver.openOutputStream(uri, "wt") }
            if (output == null) {
                withContext(Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(resolver, uri) } }
                StreamingDestinationResult.Failed(FileGatewayFailure.IO)
            } else {
                StreamingDestinationResult.Opened(
                    ContentStreamingDestination(resolver, uri, output, displayName(uri) ?: safeExportFileName(fileName)),
                )
            }
        } catch (_: SecurityException) {
            StreamingDestinationResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
        } catch (_: FileNotFoundException) {
            StreamingDestinationResult.Failed(FileGatewayFailure.IO)
        }
    }

    override suspend fun openDirectory(directoryName: String): DirectoryOpenResult {
        if (hasActiveRequest()) {
            return DirectoryOpenResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        return suspendCancellableCoroutine { continuation ->
            pendingDirectoryName = directoryName
            directoryContinuation = continuation
            continuation.invokeOnCancellation {
                if (directoryContinuation === continuation) {
                    directoryContinuation = null
                    pendingDirectoryName = null
                    directoryOpenJob?.cancel()
                }
            }
            directoryLauncher.launch(null)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        directoryOpenJob?.cancel()
        if (pickContinuation?.isActive == true) {
            pickContinuation?.resume(FilePickResult.Failed(FileGatewayFailure.UNAVAILABLE))
        }
        if (saveContinuation?.isActive == true) {
            saveContinuation?.resume(FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE))
        }
        if (directoryContinuation?.isActive == true) {
            directoryContinuation?.resume(
                DirectoryOpenResult.Failed(FileGatewayFailure.UNAVAILABLE),
            )
        }
        if (streamUriContinuation?.isActive == true) streamUriContinuation?.resume(null)
        streamUriContinuation = null
        pickContinuation = null
        saveContinuation = null
        pendingSaveFile = null
        directoryContinuation = null
        pendingDirectoryName = null
        directoryOpenJob = null
        scope.cancel()
    }

    private fun readSelectedFiles(uris: List<Uri>): FilePickResult = try {
        FilePickResult.Selected(
            uris.map { uri ->
                val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
                val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw FileNotFoundException()
                FileContent(
                    fileName = safeExportFileName(name),
                    contentType = activity.contentResolver.getType(uri) ?: "application/octet-stream",
                    bytes = bytes,
                )
            },
        )
    } catch (_: SecurityException) {
        FilePickResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
    } catch (_: Exception) {
        FilePickResult.Failed(FileGatewayFailure.IO)
    }

    private fun writeSelectedFile(
        uri: Uri,
        file: FileContent,
    ): FileSaveResult = try {
        activity.contentResolver.openOutputStream(uri, "w")?.use { output ->
            output.write(file.bytes)
        } ?: throw FileNotFoundException()
        FileSaveResult.Saved
    } catch (_: SecurityException) {
        FileSaveResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
    } catch (_: Exception) {
        FileSaveResult.Failed(FileGatewayFailure.IO)
    }

    private suspend fun openSelectedDirectory(
        destination: Uri,
        directoryName: String,
    ): DirectoryOpenResult {
        val context = currentCoroutineContext()
        val resolver = activity.contentResolver
        var createdRoot: Uri? = null
        return try {
            context.ensureActive()
            val parentDocument = DocumentsContract.buildDocumentUriUsingTree(
                destination,
                DocumentsContract.getTreeDocumentId(destination),
            )
            val root = DocumentsContract.createDocument(
                resolver,
                parentDocument,
                DocumentsContract.Document.MIME_TYPE_DIR,
                safeExportPathSegment(directoryName),
            ) ?: throw FileNotFoundException()
            createdRoot = root
            context.ensureActive()
            DirectoryOpenResult.Opened(AndroidDirectoryWriteSession(root))
        } catch (error: CancellationException) {
            createdRoot?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw error
        } catch (_: SecurityException) {
            createdRoot?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            DirectoryOpenResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            createdRoot?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            DirectoryOpenResult.Failed(FileGatewayFailure.IO)
        }
    }

    private inner class AndroidDirectoryWriteSession(
        private val root: Uri,
    ) : DirectoryWriteSession {
        private val lifecycleMutex = Mutex()
        private val folders = mutableMapOf<List<String>, Uri>(emptyList<String>() to root)
        private var closed = false

        override val supportsStreamingFiles: Boolean = true

        override suspend fun openFile(file: DirectoryFile): StreamingDestinationResult = lifecycleMutex.withLock {
            if (closed) return@withLock StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE)
            withContext(Dispatchers.IO) {
                val resolver = activity.contentResolver
                try {
                    var path = emptyList<String>()
                    var parent = root
                    file.relativeFolders.forEach { segment ->
                        val safe = safeExportPathSegment(segment)
                        path = path + safe
                        parent = folders.getOrPut(path) {
                            DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, safe)
                                ?: throw FileNotFoundException()
                        }
                    }
                    val name = safeExportFileName(file.content.fileName)
                    val uri = DocumentsContract.createDocument(resolver, parent, file.content.contentType.ifBlank { "application/octet-stream" }, name)
                        ?: throw FileNotFoundException()
                    val output = resolver.openOutputStream(uri, "w") ?: throw FileNotFoundException()
                    StreamingDestinationResult.Opened(ContentStreamingDestination(resolver, uri, output, name))
                } catch (_: SecurityException) {
                    StreamingDestinationResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
                } catch (_: Exception) {
                    StreamingDestinationResult.Failed(FileGatewayFailure.IO)
                }
            }
        }

        override suspend fun write(file: DirectoryFile): FileSaveResult =
            lifecycleMutex.withLock {
                if (closed) return@withLock FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE)
                withContext(Dispatchers.IO) {
                    val context = currentCoroutineContext()
                    val resolver = activity.contentResolver
                    try {
                        context.ensureActive()
                        var path = emptyList<String>()
                        var parent = root
                        file.relativeFolders.forEach { segment ->
                            val safeSegment = safeExportPathSegment(segment)
                            path = path + safeSegment
                            parent = folders.getOrPut(path) {
                                DocumentsContract.createDocument(
                                    resolver,
                                    parent,
                                    DocumentsContract.Document.MIME_TYPE_DIR,
                                    safeSegment,
                                ) ?: throw FileNotFoundException()
                            }
                        }
                        val fileUri = DocumentsContract.createDocument(
                            resolver,
                            parent,
                            file.content.contentType.ifBlank { "application/octet-stream" },
                            safeExportFileName(file.content.fileName),
                        ) ?: throw FileNotFoundException()
                        resolver.openOutputStream(fileUri, "w")?.use { it.write(file.content.bytes) }
                            ?: throw FileNotFoundException()
                        context.ensureActive()
                        FileSaveResult.Saved
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: SecurityException) {
                        FileSaveResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
                    } catch (_: Exception) {
                        FileSaveResult.Failed(FileGatewayFailure.IO)
                    }
                }
            }

        override suspend fun commit(): FileSaveResult = lifecycleMutex.withLock {
            if (closed) return@withLock FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE)
            closed = true
            FileSaveResult.Saved
        }

        override suspend fun abort() {
            withContext(NonCancellable + Dispatchers.IO) {
                lifecycleMutex.withLock {
                    if (!closed) {
                        runCatching { DocumentsContract.deleteDocument(activity.contentResolver, root) }
                        closed = true
                    }
                }
            }
        }
    }

    private fun hasActiveRequest(): Boolean =
        pickContinuation != null || saveContinuation != null || directoryContinuation != null ||
            streamUriContinuation != null

    private fun displayName(uri: Uri): String? = activity.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0) null else cursor.getString(index)
    }
}

internal class ContentStreamingDestination(
    private val resolver: android.content.ContentResolver,
    private val uri: Uri,
    private var output: java.io.OutputStream?,
    override val displayName: String,
    override val recoveredOutputMayBeCommitted: Boolean = false,
) : StreamingDestination {
    override val recoveryToken: String get() = uri.toString()
    private var finished = false
    private var committed = false
    override suspend fun closeForRecovery() = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { output?.close() }
        output = null
    }
    override suspend fun write(bytes: ByteArray) {
        try {
            withContext(Dispatchers.IO) {
                if (finished) throw StreamingSinkIoException()
                val stream = output ?: resolver.openOutputStream(uri, "wt")?.also { output = it } ?: throw StreamingSinkIoException()
                stream.write(bytes)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw StreamingSinkIoException(error)
        }
    }

    override suspend fun commit(): FileSaveResult = withContext(Dispatchers.IO) {
        try {
            output?.flush()
            output?.close()
            output = null
            finished = true
            committed = true
            runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            FileSaveResult.Saved
        } catch (_: Exception) {
            FileSaveResult.Failed(FileGatewayFailure.IO)
        }
    }

    override suspend fun abort() {
        runCatching { abortForCleanup() }.onFailure {
            android.util.Log.w("AndroidFileGateway", "deleteDocument failed: ${it.javaClass.simpleName}")
        }
    }

    override suspend fun abortForCleanup() {
        withContext(NonCancellable + Dispatchers.IO) {
            if (committed) return@withContext
            runCatching { output?.close() }
            output = null
            finished = true
            if (!DocumentsContract.deleteDocument(resolver, uri)) {
                throw java.io.IOException("无法删除未完成的保存目标")
            }
            runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
    }
}
