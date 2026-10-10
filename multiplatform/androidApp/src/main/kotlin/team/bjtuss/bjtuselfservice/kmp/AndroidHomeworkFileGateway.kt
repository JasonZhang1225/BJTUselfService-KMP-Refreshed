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
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePreviewResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryFile
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryOpenResult
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryWriteSession
import team.bjtuss.bjtuselfservice.shared.files.previewContentType
import team.bjtuss.bjtuselfservice.shared.files.safeExportFileName
import team.bjtuss.bjtuselfservice.shared.files.safeExportPathSegment

class AndroidHomeworkFileGateway(
    private val activity: ComponentActivity,
) : HomeworkFileGateway, CoursewareDirectoryGateway, DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pickContinuation: CancellableContinuation<HomeworkFilePickResult>? = null
    private var saveContinuation: CancellableContinuation<HomeworkFileSaveResult>? = null
    private var pendingSaveFile: HomeworkFileContent? = null
    private var directoryContinuation: CancellableContinuation<CoursewareDirectoryOpenResult>? = null
    private var pendingDirectoryName: String? = null
    private var directoryOpenJob: Job? = null

    private val pickLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        val continuation = pickContinuation ?: return@registerForActivityResult
        if (uris.isEmpty()) {
            pickContinuation = null
            if (continuation.isActive) continuation.resume(HomeworkFilePickResult.Cancelled)
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
            if (continuation.isActive) continuation.resume(HomeworkFileSaveResult.Cancelled)
            return@registerForActivityResult
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (file == null) {
                    HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
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
            if (continuation.isActive) continuation.resume(CoursewareDirectoryOpenResult.Cancelled)
            return@registerForActivityResult
        }
        directoryOpenJob = scope.launch {
            var openedSession: CoursewareDirectoryWriteSession? = null
            var handedOff = false
            try {
                val result = withContext(Dispatchers.IO) {
                    if (directoryName == null) {
                        CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.IO)
                    } else {
                        openSelectedDirectory(uri, directoryName)
                    }
                }
                if (result is CoursewareDirectoryOpenResult.Opened) {
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

    override suspend fun pickFiles(): HomeworkFilePickResult {
        if (hasActiveRequest()) {
            return HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
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
    override suspend fun previewFile(file: HomeworkFileContent): HomeworkFilePreviewResult {
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
            return HomeworkFilePreviewResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            return HomeworkFilePreviewResult.Failed(HomeworkFileGatewayFailure.IO)
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
            HomeworkFilePreviewResult.Opened
        } catch (_: ActivityNotFoundException) {
            HomeworkFilePreviewResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
    }

    override suspend fun saveFile(file: HomeworkFileContent): HomeworkFileSaveResult {
        if (hasActiveRequest()) {
            return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
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

    override suspend fun openDirectory(directoryName: String): CoursewareDirectoryOpenResult {
        if (hasActiveRequest()) {
            return CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
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
            pickContinuation?.resume(HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE))
        }
        if (saveContinuation?.isActive == true) {
            saveContinuation?.resume(HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE))
        }
        if (directoryContinuation?.isActive == true) {
            directoryContinuation?.resume(
                CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE),
            )
        }
        pickContinuation = null
        saveContinuation = null
        pendingSaveFile = null
        directoryContinuation = null
        pendingDirectoryName = null
        directoryOpenJob = null
        scope.cancel()
    }

    private fun readSelectedFiles(uris: List<Uri>): HomeworkFilePickResult = try {
        HomeworkFilePickResult.Selected(
            uris.map { uri ->
                val name = displayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
                val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw FileNotFoundException()
                HomeworkFileContent(
                    fileName = safeExportFileName(name),
                    contentType = activity.contentResolver.getType(uri) ?: "application/octet-stream",
                    bytes = bytes,
                )
            },
        )
    } catch (_: SecurityException) {
        HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
    } catch (_: Exception) {
        HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO)
    }

    private fun writeSelectedFile(
        uri: Uri,
        file: HomeworkFileContent,
    ): HomeworkFileSaveResult = try {
        activity.contentResolver.openOutputStream(uri, "w")?.use { output ->
            output.write(file.bytes)
        } ?: throw FileNotFoundException()
        HomeworkFileSaveResult.Saved
    } catch (_: SecurityException) {
        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
    } catch (_: Exception) {
        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
    }

    private suspend fun openSelectedDirectory(
        destination: Uri,
        directoryName: String,
    ): CoursewareDirectoryOpenResult {
        val context = currentCoroutineContext()
        val resolver = activity.contentResolver
        var createdRoot: Uri? = null
        return try {
            context.ensureActive()
            val root = DocumentsContract.createDocument(
                resolver,
                destination,
                DocumentsContract.Document.MIME_TYPE_DIR,
                safeExportPathSegment(directoryName),
            ) ?: throw FileNotFoundException()
            createdRoot = root
            context.ensureActive()
            CoursewareDirectoryOpenResult.Opened(AndroidDirectoryWriteSession(root))
        } catch (error: CancellationException) {
            createdRoot?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            throw error
        } catch (_: SecurityException) {
            createdRoot?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            createdRoot?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.IO)
        }
    }

    private inner class AndroidDirectoryWriteSession(
        private val root: Uri,
    ) : CoursewareDirectoryWriteSession {
        private val lifecycleMutex = Mutex()
        private val folders = mutableMapOf<List<String>, Uri>(emptyList<String>() to root)
        private var closed = false

        override suspend fun write(file: CoursewareDirectoryFile): HomeworkFileSaveResult =
            lifecycleMutex.withLock {
                if (closed) return@withLock HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
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
                        HomeworkFileSaveResult.Saved
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: SecurityException) {
                        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
                    } catch (_: Exception) {
                        HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
                    }
                }
            }

        override suspend fun commit(): HomeworkFileSaveResult = lifecycleMutex.withLock {
            if (closed) return@withLock HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
            closed = true
            HomeworkFileSaveResult.Saved
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
        pickContinuation != null || saveContinuation != null || directoryContinuation != null

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
