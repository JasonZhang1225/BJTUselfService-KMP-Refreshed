package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
internal actual fun AppleMenuAnchor(
    options: List<AppleMenuOption>,
    selectedKey: String?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier,
) = MaterialMenuAnchor(options, selectedKey, expanded, onDismiss, onSelect, modifier)

internal actual val appleMenuAnchorHandlesTap: Boolean = false
