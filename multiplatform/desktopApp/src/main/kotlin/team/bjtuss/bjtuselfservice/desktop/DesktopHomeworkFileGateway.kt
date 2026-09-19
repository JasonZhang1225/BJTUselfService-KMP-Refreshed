package team.bjtuss.bjtuselfservice.desktop

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
import team.bjtuss.bjtuselfservice.shared.domain.homework.HomeworkFileContent
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGatewayFailure
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFilePickResult
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileSaveResult
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryFile
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryOpenResult
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryWriteSession
import team.bjtuss.bjtuselfservice.shared.files.safeExportFileName
import team.bjtuss.bjtuselfservice.shared.files.safeExportPathSegment

class DesktopHomeworkFileGateway(
    private val owner: () -> Frame?,
) : HomeworkFileGateway, CoursewareDirectoryGateway {
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
                showDialog(FileDialog.SAVE, safeExportFileName(file.fileName), allowMultiple = false).singleOrNull()
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
                        DesktopDirectoryWriteSession(root) { requestMutex.unlock() },
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

    private inner class DesktopDirectoryWriteSession(
        private val root: File,
        private val onClosed: () -> Unit,
    ) : CoursewareDirectoryWriteSession {
        private val lifecycleMutex = Mutex()
        private var closed = false

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
