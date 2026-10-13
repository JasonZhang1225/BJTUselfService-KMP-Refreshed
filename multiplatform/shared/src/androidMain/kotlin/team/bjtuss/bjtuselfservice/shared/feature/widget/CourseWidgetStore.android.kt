package team.bjtuss.bjtuselfservice.shared.feature.widget

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** 单写者（主应用）+ 原子替换；小组件进程直接读文件，不启动数据库、网络或 Compose。 */
object AndroidCourseWidgetStore {
    private var appContext: Context? = null
    private var onChanged: (() -> Unit)? = null

    fun install(context: Context, onChanged: () -> Unit) {
        appContext = context.applicationContext
        this.onChanged = onChanged
    }

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "course-widget.json"))

    /** 不缓存跨进程状态；退出登录后，下一次刷新一定读取到空快照。 */
    fun read(context: Context): CourseWidgetSnapshot? = runCatching {
        file(context).openRead().use { input ->
            val buffer = ByteArray(MAX_SNAPSHOT_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count > MAX_SNAPSHOT_BYTES) return@use null
            decodeCourseWidgetSnapshot(String(buffer, 0, count, Charsets.UTF_8))
        }
    }.getOrNull()

    @Synchronized
    internal fun write(json: String?) {
        val context = appContext ?: return
        val target = file(context)
        if (json == null) {
            target.delete()
        } else {
            val bytes = json.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_SNAPSHOT_BYTES)
            val stream = target.startWrite()
            try {
                stream.write(bytes)
                target.finishWrite(stream)
            } catch (error: Exception) {
                target.failWrite(stream)
                throw error
            }
        }
        onChanged?.invoke()
    }

    private const val MAX_SNAPSHOT_BYTES = 1024 * 1024
}

internal actual fun writeCourseWidgetSnapshot(json: String?) = AndroidCourseWidgetStore.write(json)
