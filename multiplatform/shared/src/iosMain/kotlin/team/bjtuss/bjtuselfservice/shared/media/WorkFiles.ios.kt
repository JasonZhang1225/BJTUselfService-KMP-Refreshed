package team.bjtuss.bjtuselfservice.shared.media

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** 沿用 iOS 工作目录清理方式，删除错误交给调用方记录和重试。 */
internal actual fun deleteWorkTree(path: Path) {
    if (!SystemFileSystem.exists(path)) return
    if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) {
        SystemFileSystem.list(path).forEach(::deleteWorkTree)
    }
    SystemFileSystem.delete(path, mustExist = false)
}
