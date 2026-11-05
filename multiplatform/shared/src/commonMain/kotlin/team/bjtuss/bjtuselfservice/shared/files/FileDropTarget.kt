package team.bjtuss.bjtuselfservice.shared.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 当前平台能否把系统文件拖进窗口：macOS / Windows 能；Android、iOS 只走系统文件选择器。 */
expect val fileDropSupported: Boolean

/**
 * 接受从系统文件管理器拖入的文件。不支持的平台原样返回 this。
 * [onDrop] 收到读好的文件（与文件选择器同一种结果）；文件夹或读取失败时是 [HomeworkFilePickResult.Failed]。
 */
@Composable
expect fun Modifier.fileDropTarget(
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onDrop: (HomeworkFilePickResult) -> Unit,
): Modifier
