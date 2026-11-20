package team.bjtuss.bjtuselfservice.desktop

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.files.FileContent
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.files.FileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.readLocalFiles
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
import team.bjtuss.bjtuselfservice.shared.files.safeExportFileName
import team.bjtuss.bjtuselfservice.shared.files.safeExportPathSegment

class DesktopFileGateway(
    private val owner: () -> Frame?,
) : FileGateway, DirectoryGateway {
    private val requestMutex = Mutex()

    override val isAvailable: Boolean = true
    override val isDirectoryExportAvailable: Boolean = true

    override suspend fun pickFiles(): FilePickResult {
        if (!requestMutex.tryLock()) {
            return FilePickResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        try {
            val selected = try {
                showDialog(FileDialog.LOAD, null, allowMultiple = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return FilePickResult.Failed(FileGatewayFailure.IO)
            }
            if (selected.isEmpty()) return FilePickResult.Cancelled
            return readLocalFiles(selected)
        } finally {
            requestMutex.unlock()
        }
    }

    override suspend fun saveFile(file: FileContent): FileSaveResult {
        if (!requestMutex.tryLock()) {
            return FileSaveResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        try {
            val selected = try {
                showDialog(FileDialog.SAVE, safeExportFileName(file.fileName), allowMultiple = false).singleOrNull()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return FileSaveResult.Failed(FileGatewayFailure.IO)
            } ?: return FileSaveResult.Cancelled
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                try {
                    writeFileAtomically(selected, file.bytes) { context.ensureActive() }
                    FileSaveResult.Saved
                } catch (error: CancellationException) {
                    throw error
                } catch (_: SecurityException) {
                    FileSaveResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
                } catch (_: Exception) {
                    FileSaveResult.Failed(FileGatewayFailure.IO)
                }
            }
        } finally {
            requestMutex.unlock()
        }
    }

    override val isStreamingSaveAvailable: Boolean = true

    override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult {
        if (!requestMutex.tryLock()) {
            return StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        // 面板只需串行到用户选好位置；之后的长时间写入不占锁，其他保存照常可用。
        val selected = try {
            showDialog(FileDialog.SAVE, safeExportFileName(fileName), allowMultiple = false).singleOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return StreamingDestinationResult.Failed(FileGatewayFailure.IO)
        } finally {
            requestMutex.unlock()
        } ?: return StreamingDestinationResult.Cancelled
        return try {
            withContext(Dispatchers.IO) {
                val parent = selected.absoluteFile.parentFile ?: throw IllegalStateException("Missing destination directory")
                // 大文件先写到目标目录里的临时文件，commit 才改名覆盖；abort 删除临时文件。
                val temporary = Files.createTempFile(parent.toPath(), ".bjtu-save-", ".tmp")
                StreamingDestinationResult.Opened(FileStreamingDestination(selected, temporary))
            }
        } catch (_: SecurityException) {
            StreamingDestinationResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            StreamingDestinationResult.Failed(FileGatewayFailure.IO)
        }
    }

    override val isPreviewAvailable: Boolean =
        Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)

    /**
     * 预览文件只写入当前用户的系统临时目录（macOS 为私有的 /var/folders/…），每次预览前清掉上一批，
     * 进程退出时再删一次；不碰用户的下载/文稿目录。
     */
    override suspend fun previewFile(file: FileContent): FilePreviewResult {
        if (!isPreviewAvailable) return FilePreviewResult.Failed(FileGatewayFailure.UNAVAILABLE)
        return withContext(Dispatchers.IO) {
            try {
                val root = File(System.getProperty("java.io.tmpdir"), "BJTUselfService-preview")
                root.listFiles()?.forEach { it.deleteRecursively() }
                val directory = Files.createTempDirectory(root.apply { mkdirs() }.toPath(), "p").toFile()
                val target = File(directory, safeExportFileName(file.fileName))
                target.writeBytes(file.bytes)
                directory.deleteOnExit()
                target.deleteOnExit()
                Desktop.getDesktop().open(target)
                FilePreviewResult.Opened
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                FilePreviewResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
            } catch (_: Exception) {
                FilePreviewResult.Failed(FileGatewayFailure.IO)
            }
        }
    }

    override suspend fun openDirectory(directoryName: String): DirectoryOpenResult {
        if (!requestMutex.tryLock()) {
            return DirectoryOpenResult.Failed(FileGatewayFailure.UNAVAILABLE)
        }
        var sessionOpened = false
        var createdRoot: File? = null
        try {
            val destination = try {
                showDirectoryDialog()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return DirectoryOpenResult.Failed(FileGatewayFailure.IO)
            } ?: return DirectoryOpenResult.Cancelled
            val result = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val root = File(destination, safeExportPathSegment(directoryName))
                if (root.exists()) {
                    return@withContext DirectoryOpenResult.Failed(FileGatewayFailure.IO)
                }
                try {
                    context.ensureActive()
                    if (!root.mkdirs()) throw IllegalStateException("Unable to create export root")
                    createdRoot = root
                    DirectoryOpenResult.Opened(
                        DesktopDirectoryWriteSession(root) { requestMutex.unlock() },
                    )
                } catch (error: CancellationException) {
                    createdRoot?.takeIf { it == root }?.deleteRecursively()
                    throw error
                } catch (_: SecurityException) {
                    createdRoot?.takeIf { it == root }?.deleteRecursively()
                    DirectoryOpenResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
                } catch (_: Exception) {
                    createdRoot?.takeIf { it == root }?.deleteRecursively()
                    DirectoryOpenResult.Failed(FileGatewayFailure.IO)
                }
            }
            sessionOpened = result is DirectoryOpenResult.Opened
            return result
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { createdRoot?.deleteRecursively() }
            throw error
        } finally {
            if (!sessionOpened) requestMutex.unlock()
        }
    }

    private inner class DesktopDirectoryWriteSession(
        private val root: File,
        private val onClosed: () -> Unit,
    ) : DirectoryWriteSession {
        private val lifecycleMutex = Mutex()
        private var closed = false

        override val supportsStreamingFiles: Boolean = true

        override suspend fun openFile(file: DirectoryFile): StreamingDestinationResult = lifecycleMutex.withLock {
            if (closed) return@withLock StreamingDestinationResult.Failed(FileGatewayFailure.UNAVAILABLE)
            withContext(Dispatchers.IO) {
                try {
                    val folder = file.relativeFolders.fold(root) { parent, segment -> File(parent, safeExportPathSegment(segment)) }
                    if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Unable to create export folder")
                    val target = File(folder, safeExportFileName(file.content.fileName))
                    if (target.exists()) throw IllegalStateException("Export file already exists")
                    val temp = Files.createTempFile(folder.toPath(), ".bjtu-save-", ".tmp")
                    StreamingDestinationResult.Opened(FileStreamingDestination(target, temp))
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
                    try {
                        context.ensureActive()
                        val folder = file.relativeFolders.fold(root) { parent, segment ->
                            File(parent, safeExportPathSegment(segment))
                        }
                        if (!folder.exists() && !folder.mkdirs()) {
                            throw IllegalStateException("Unable to create export folder")
                        }
                        val destination = File(folder, safeExportFileName(file.content.fileName))
                        if (destination.exists()) {
                            return@withContext FileSaveResult.Failed(FileGatewayFailure.IO)
                        }
                        writeFileAtomically(destination, file.content.bytes) { context.ensureActive() }
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
            onClosed()
            FileSaveResult.Saved
        }

        override suspend fun abort() {
            withContext(NonCancellable + Dispatchers.IO) {
                lifecycleMutex.withLock {
                    if (!closed) {
                        runCatching { root.deleteRecursively() }
                        closed = true
                        onClosed()
                    }
                }
            }
        }
    }

    private fun writeFileAtomically(
        destination: File,
        bytes: ByteArray,
        ensureActive: () -> Unit,
    ) {
        val parent = destination.absoluteFile.parentFile
            ?: throw IllegalStateException("Missing destination directory")
        val temporary = Files.createTempFile(parent.toPath(), ".bjtu-save-", ".tmp")
        try {
            ensureActive()
            Files.write(temporary, bytes)
            ensureActive()
            try {
                Files.move(
                    temporary,
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private suspend fun showDirectoryDialog(): File? = withContext(Dispatchers.IO) {
        var selected: File? = null
        val property = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(property)
        val show = {
            try {
                System.setProperty(property, "true")
                val dialog = FileDialog(owner(), "选择课件导出位置", FileDialog.LOAD)
                try {
                    dialog.isVisible = true
                    val directory = dialog.directory
                    val fileName = dialog.file
                    selected = when {
                        directory == null -> null
                        fileName == null -> File(directory)
                        else -> File(directory, fileName)
                    }
                } finally {
                    dialog.dispose()
                }
            } finally {
                if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
            }
        }
        SwingUtilities.invokeAndWait { show() }
        selected
    }

    /**
     * 模态面板必须在非 EDT 线程上经 invokeAndWait 进入：Compose 的 Main 调度器
     * (FlushCoroutineDispatcher) 会把任务队列的排空包在可重入的 performRun 里，
     * 若在 EDT 上直接 isVisible=true，面板的嵌套事件循环会重入同一次排空、
     * 重复派发仍在调用栈上的协程任务，表现为 Symbol 强转失败后整个界面卡死。
     */
    private suspend fun showDialog(
        mode: Int,
        suggestedName: String?,
        allowMultiple: Boolean,
    ): List<File> = withContext(Dispatchers.IO) {
        var selected = emptyList<File>()
        val show = {
            val dialog = FileDialog(owner(), if (mode == FileDialog.LOAD) "选择作业文件" else "保存附件", mode)
            try {
                dialog.isMultipleMode = allowMultiple
                if (suggestedName != null) dialog.file = suggestedName
                dialog.isVisible = true
                selected = if (allowMultiple) {
                    dialog.files.toList()
                } else {
                    val directory = dialog.directory
                    val fileName = dialog.file
                    if (directory == null || fileName == null) emptyList() else listOf(File(directory, fileName))
                }
            } finally {
                dialog.dispose()
            }
        }
        SwingUtilities.invokeAndWait { show() }
        selected
    }
}

private class FileStreamingDestination(
    private val target: File,
    private val temporary: java.nio.file.Path,
) : StreamingDestination {
    private var output: java.io.OutputStream? = Files.newOutputStream(temporary)
    override val displayName: String = target.name
    /** 与 Windows 一致：完成后卡片可「打开视频 / 打开文件夹」。 */
    override val outputPath: String = target.absolutePath

    override suspend fun write(bytes: ByteArray) {
        val stream = output ?: throw StreamingSinkIoException()
        try {
            withContext(Dispatchers.IO) { stream.write(bytes) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw StreamingSinkIoException(error)
        }
    }

    override suspend fun commit(): FileSaveResult = withContext(Dispatchers.IO) {
        try {
            output?.close()
            output = null
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            FileSaveResult.Saved
        } catch (_: SecurityException) {
            FileSaveResult.Failed(FileGatewayFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            FileSaveResult.Failed(FileGatewayFailure.IO)
        }
    }

    override suspend fun abort() {
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { output?.close() }
            output = null
            runCatching { Files.deleteIfExists(temporary) }
        }
    }
}
