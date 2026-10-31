package team.bjtuss.bjtuselfservice.windows

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryFile
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryOpenResult
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryWriteSession
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestination
import team.bjtuss.bjtuselfservice.shared.files.StreamingDestinationResult
import team.bjtuss.bjtuselfservice.shared.files.StreamingSinkIoException
import team.bjtuss.bjtuselfservice.shared.files.safeExportFileName
import team.bjtuss.bjtuselfservice.shared.files.safeExportPathSegment

/**
 * Windows 文件网关：系统文件面板 + 临时文件原子写入。
 */
class WindowsHomeworkFileGateway(
    private val owner: () -> Frame? = { null },
    recoveryRoot: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "BJTUselfServiceKMP/playback-tasks"),
) : HomeworkFileGateway, CoursewareDirectoryGateway,
    team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryProvider {
    override val playbackRecovery = WindowsPlaybackRecovery(recoveryRoot)
    private val requestMutex = Mutex()

    override val isAvailable: Boolean = true
    override val isDirectoryExportAvailable: Boolean = true

    override suspend fun pickFiles(): HomeworkFilePickResult {
        if (!requestMutex.tryLock()) {
            return HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
        try {
            val selected = try {
                showDialog(FileDialog.LOAD, null, allowMultiple = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO)
            }
            if (selected.isEmpty()) return HomeworkFilePickResult.Cancelled
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                try {
                    HomeworkFilePickResult.Selected(
                        selected.map { file ->
                            context.ensureActive()
                            HomeworkFileContent(
                                fileName = file.name,
                                contentType = Files.probeContentType(file.toPath()) ?: "application/octet-stream",
                                bytes = file.readBytes(),
                            )
                        },
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: SecurityException) {
                    HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
                } catch (_: Exception) {
                    HomeworkFilePickResult.Failed(HomeworkFileGatewayFailure.IO)
                }
            }
        } finally {
            requestMutex.unlock()
        }
    }

    override suspend fun saveFile(file: HomeworkFileContent): HomeworkFileSaveResult {
        if (!requestMutex.tryLock()) {
            return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
        try {
            val selected = try {
                withContext(Dispatchers.IO) { WindowsFileDialog.save(safeExportFileName(file.fileName), nativeOwner()) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
            } ?: return HomeworkFileSaveResult.Cancelled
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                try {
                    writeFileAtomically(selected, file.bytes) { context.ensureActive() }
                    HomeworkFileSaveResult.Saved
                } catch (error: CancellationException) {
                    throw error
                } catch (_: SecurityException) {
                    HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
                } catch (_: Exception) {
                    HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
                }
            }
        } finally {
            requestMutex.unlock()
        }
    }

    override val isStreamingSaveAvailable: Boolean = true

    override suspend fun openStreamingDestination(fileName: String, contentType: String): StreamingDestinationResult {
        if (!requestMutex.tryLock()) {
            return StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
        // 面板只需串行到用户选好位置；之后的长时间写入不占锁，其他保存照常可用。
        val selected = try {
            withContext(Dispatchers.IO) { WindowsFileDialog.save(safeExportFileName(fileName), nativeOwner()) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.IO)
        } finally {
            requestMutex.unlock()
        } ?: return StreamingDestinationResult.Cancelled
        return try {
            withContext(Dispatchers.IO) {
                val parent = selected.absoluteFile.parentFile ?: throw IllegalStateException("Missing destination directory")
                // 仅分配不可预测的名称。任务记录先保存该名称，第一次写入才创建文件。
                StreamingDestinationResult.Opened(prepareFileStreamingDestination(selected, parent))
            }
        } catch (_: SecurityException) {
            StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.IO)
        }
    }

    override suspend fun openDirectory(directoryName: String): CoursewareDirectoryOpenResult {
        if (!requestMutex.tryLock()) {
            return CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
        }
        var sessionOpened = false
        var createdRoot: File? = null
        try {
            val destination = try {
                showDirectoryDialog()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.IO)
            } ?: return CoursewareDirectoryOpenResult.Cancelled
            val result = withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val root = File(destination, safeExportPathSegment(directoryName))
                if (root.exists()) {
                    return@withContext CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.IO)
                }
                try {
                    context.ensureActive()
                    if (!root.mkdirs()) throw IllegalStateException("Unable to create export root")
                    createdRoot = root
                    CoursewareDirectoryOpenResult.Opened(
                        WindowsDirectoryWriteSession(root) { requestMutex.unlock() },
                    )
                } catch (error: CancellationException) {
                    createdRoot?.takeIf { it == root }?.deleteRecursively()
                    throw error
                } catch (_: SecurityException) {
                    createdRoot?.takeIf { it == root }?.deleteRecursively()
                    CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
                } catch (_: Exception) {
                    createdRoot?.takeIf { it == root }?.deleteRecursively()
                    CoursewareDirectoryOpenResult.Failed(HomeworkFileGatewayFailure.IO)
                }
            }
            sessionOpened = result is CoursewareDirectoryOpenResult.Opened
            return result
        } catch (error: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { createdRoot?.deleteRecursively() }
            throw error
        } finally {
            if (!sessionOpened) requestMutex.unlock()
        }
    }

    private inner class WindowsDirectoryWriteSession(
        private val root: File,
        private val onClosed: () -> Unit,
    ) : CoursewareDirectoryWriteSession {
        private val lifecycleMutex = Mutex()
        private var closed = false

        override val supportsStreamingFiles: Boolean = true

        override suspend fun openFile(file: CoursewareDirectoryFile): StreamingDestinationResult = lifecycleMutex.withLock {
            if (closed) return@withLock StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
            withContext(Dispatchers.IO) {
                try {
                    val folder = file.relativeFolders.fold(root) { parent, segment -> File(parent, safeExportPathSegment(segment)) }
                    if (!folder.exists() && !folder.mkdirs()) throw IllegalStateException("Unable to create export folder")
                    val target = File(folder, safeExportFileName(file.content.fileName))
                    if (target.exists()) throw IllegalStateException("Export file already exists")
                    StreamingDestinationResult.Opened(prepareFileStreamingDestination(target, folder))
                } catch (_: SecurityException) {
                    StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
                } catch (_: Exception) {
                    StreamingDestinationResult.Failed(HomeworkFileGatewayFailure.IO)
                }
            }
        }

        override suspend fun write(file: CoursewareDirectoryFile): HomeworkFileSaveResult =
            lifecycleMutex.withLock {
                if (closed) return@withLock HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.UNAVAILABLE)
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
                            return@withContext HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
                        }
                        writeFileAtomically(destination, file.content.bytes) { context.ensureActive() }
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
            onClosed()
            HomeworkFileSaveResult.Saved
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
        WindowsFileDialog.folder(nativeOwner())
    }

    private fun nativeOwner(): com.sun.jna.Pointer? {
        var pointer: com.sun.jna.Pointer? = null
        SwingUtilities.invokeAndWait {
            owner()?.takeIf { it.isDisplayable }?.let { pointer = com.sun.jna.Native.getComponentPointer(it) }
        }
        return pointer
    }

    /**
     * 模态面板只能在非 EDT 线程上经 invokeAndWait 进入：Compose 的 Main 调度器把任务队列
     * 排空包在可重入的 performRun 里，在 EDT 上直接开面板会让嵌套事件循环重复派发仍在
     * 调用栈上的协程任务，表现为 Symbol 强转失败后整个界面卡死。
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

internal class FileStreamingDestination(
    private val target: File,
    internal val temporary: java.nio.file.Path,
    private var temporaryOwned: Boolean = true,
) : StreamingDestination {
    private var output: java.io.OutputStream? = null
    private var committed = false
    override val displayName: String = target.name
    override val outputPath: String = target.absolutePath
    override val recoveryToken: String get() = windowsDestinationToken(target, temporary.toFile())

    override suspend fun closeForRecovery() = withContext(NonCancellable + Dispatchers.IO) {
        runCatching { output?.close() }
        output = null
    }

    override suspend fun isRecoveryCommitAmbiguous(): Boolean = withContext(Dispatchers.IO) {
        fun exists(path: java.nio.file.Path): Boolean = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            true
        } catch (_: NoSuchFileException) { false }
        !exists(temporary) && exists(target.toPath())
    }

    override suspend fun write(bytes: ByteArray) {
        try {
            withContext(Dispatchers.IO) {
                val stream = output ?: (if (temporaryOwned) {
                    Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                } else {
                    Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS).also { temporaryOwned = true }
                }).also { output = it }
                stream.write(bytes)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw StreamingSinkIoException(error)
        }
    }

    override suspend fun commit(): HomeworkFileSaveResult = withContext(Dispatchers.IO) {
        try {
            output?.close()
            output = null
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            committed = true
            temporaryOwned = false
            HomeworkFileSaveResult.Saved
        } catch (_: SecurityException) {
            HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.PERMISSION_DENIED)
        } catch (_: Exception) {
            HomeworkFileSaveResult.Failed(HomeworkFileGatewayFailure.IO)
        }
    }

    override suspend fun abort() {
        runCatching { abortForCleanup() }
    }

    override suspend fun abortForCleanup() {
        withContext(NonCancellable + Dispatchers.IO) {
            try { output?.close() } finally { output = null }
            if (!committed && temporaryOwned) {
                check(!Files.isSymbolicLink(temporary)) { "保存临时文件已被替换" }
                Files.deleteIfExists(temporary)
            }
        }
    }
}

internal fun prepareFileStreamingDestination(target: File, parent: File = target.absoluteFile.parentFile): FileStreamingDestination {
    val temporary = parent.toPath().resolve(".bjtu-save-${java.util.UUID.randomUUID()}.tmp")
    return FileStreamingDestination(target, temporary, temporaryOwned = false)
}
