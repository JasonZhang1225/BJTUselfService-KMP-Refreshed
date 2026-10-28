package team.bjtuss.bjtuselfservice.shared.media

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.io.files.Path

/** 不跟随符号链接或 Windows junction，只删除任务目录本身拥有的条目。 */
internal actual fun deleteWorkTree(path: Path) {
    fun remove(item: java.nio.file.Path) {
        val attributes = try {
            Files.readAttributes(item, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) { return }
        // Windows junction 的 isSymbolicLink 可能是 false，但 isOther 为 true。
        if (attributes.isDirectory && !attributes.isSymbolicLink && !attributes.isOther) {
            Files.newDirectoryStream(item).use { entries -> entries.forEach(::remove) }
        }
        Files.deleteIfExists(item)
    }
    remove(java.nio.file.Paths.get(path.toString()))
}
