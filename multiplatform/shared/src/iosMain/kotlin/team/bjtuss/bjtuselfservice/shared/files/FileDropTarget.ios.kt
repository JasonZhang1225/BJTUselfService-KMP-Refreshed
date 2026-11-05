package team.bjtuss.bjtuselfservice.shared.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

actual val fileDropSupported: Boolean = false

@Composable
actual fun Modifier.fileDropTarget(
    enabled: Boolean,
    onHoverChange: (Boolean) -> Unit,
    onDrop: (HomeworkFilePickResult) -> Unit,
): Modifier = this
