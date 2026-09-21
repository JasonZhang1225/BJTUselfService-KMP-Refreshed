package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.currentPlatform

/**
 * A shared sheet whose iOS presentation is owned by UIKit.
 *
 * On iOS the composition is hosted inside a real
 * `UISheetPresentationController`; UIKit therefore supplies the system
 * rounded surface, grabber, detent transition, background material and
 * interactive dismissal. Compose's Material sheet is only the non-iOS
 * fallback (and remains useful in previews/tests without a host controller).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    needsFullHeight: Boolean = false,
    title: String? = null,
    confirmLabel: String? = null,
    confirmEnabled: Boolean = true,
    onConfirm: (() -> Unit)? = null,
    todayLabel: String? = null,
    todayEnabled: Boolean = true,
    onToday: (() -> Unit)? = null,
    dismissLabel: String? = null,
    dismissEnabled: Boolean = true,
    showDismissButton: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val isIos = currentPlatform().family == PlatformFamily.IOS
    val presenter = if (isIos) LocalNativeSheetPresenter.current else null

    if (presenter != null) {
        // The UIKit sheet is a separate Compose root, so inherited MaterialTheme
        // locals do not cross the UIViewController boundary automatically. Capture
        // the current app scheme here; otherwise a dark-mode sheet falls back to
        // Compose's default light colors and looks unlike the system sheet.
        val appColorScheme = MaterialTheme.colorScheme
        val appTypography = MaterialTheme.typography
        val appShapes = MaterialTheme.shapes
        val currentContent = @Composable {
            MaterialTheme(
                colorScheme = appColorScheme,
                typography = appTypography,
                shapes = appShapes,
            ) {
                // ComposeUIViewController is a separate root from the app shell.
                // MaterialTheme carries the palette, but it does not provide a
                // LocalContentColor by itself. Without this provider, Text with
                // an unspecified color falls back to black over the native dark
                // glass surface.
                CompositionLocalProvider(LocalContentColor provides appColorScheme.onSurface) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (needsFullHeight) Modifier.fillMaxHeight() else Modifier)
                            // Native title/action bar occupies the top of a sheet
                            // navigation controller; keep Compose body below it.
                            .padding(top = 44.dp)
                            .navigationBarsPadding(),
                        content = content,
                    )
                }
            }
        }
        SideEffect {
            presenter.update(
                content = currentContent,
                onDismissRequest = onDismissRequest,
                title = title,
                confirmLabel = confirmLabel,
                confirmEnabled = confirmEnabled,
                onConfirm = onConfirm,
                todayLabel = todayLabel,
                todayEnabled = todayEnabled,
                onToday = onToday,
                dismissLabel = dismissLabel,
                dismissEnabled = dismissEnabled,
                showDismissButton = showDismissButton,
            )
        }
        LaunchedEffect(presenter, needsFullHeight) {
            presenter.present(needsFullHeight)
        }
        DisposableEffect(presenter) {
            onDispose { presenter.dismiss() }
        }
        return
    }

    // Non-iOS fallback: keep the shared component usable on Android/desktop
    // and in previews without introducing a platform host dependency.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetGesturesEnabled = true,
        containerColor = BottomSheetDefaults.ContainerColor,
        tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        dragHandle = { BottomSheetDefaults.DragHandle() },
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (title != null || confirmLabel != null || todayLabel != null ||
                    dismissLabel != null || showDismissButton
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        title?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (confirmLabel != null && onConfirm != null) {
                            TextButton(
                                onClick = onConfirm,
                                enabled = confirmEnabled,
                            ) { Text(confirmLabel) }
                        }
                        if (todayLabel != null && onToday != null) {
                            TextButton(
                                onClick = onToday,
                                enabled = todayEnabled,
                            ) { Text(todayLabel) }
                        }
                        if (dismissLabel != null) {
                            TextButton(
                                onClick = onDismissRequest,
                                enabled = dismissEnabled,
                            ) { Text(dismissLabel) }
                        } else if (showDismissButton) {
                            TextButton(onClick = onDismissRequest) { Text("关闭") }
                        }
                    }
                }
                Column(modifier = Modifier.fillMaxWidth(), content = content)
            }
        },
    )
}

/** iOS uses the host-owned system sheet; other platforms keep a Material dialog. */
@Composable
fun AppleSheetOrAlert(
    onDismissRequest: () -> Unit,
    title: String?,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    todayLabel: String? = null,
    todayEnabled: Boolean = true,
    onToday: (() -> Unit)? = null,
    dismissLabel: String? = null,
    dismissEnabled: Boolean = true,
    showDismissButton: Boolean = true,
    needsFullHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (currentPlatform().family == PlatformFamily.IOS) {
        // On iOS the title and actions are UIBarButtonItems in the native sheet
        // navigation bar. Compose supplies only the body; it does not imitate
        // the system sheet chrome with Material buttons.
        AppleSheet(
            onDismissRequest = onDismissRequest,
            needsFullHeight = needsFullHeight,
            title = title,
            confirmLabel = confirmLabel,
            confirmEnabled = confirmEnabled,
            onConfirm = onConfirm,
            todayLabel = todayLabel,
            todayEnabled = todayEnabled,
            onToday = onToday,
            dismissLabel = dismissLabel,
            dismissEnabled = dismissEnabled,
            showDismissButton = showDismissButton,
        ) {
            content()
        }
    } else {
        val fallbackConfirmLabel = confirmLabel ?: if (showDismissButton) "关闭" else null
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title = title?.let { { Text(it) } },
            text = { Column { content() } },
            confirmButton = if (fallbackConfirmLabel != null) {
                {
                    TextButton(onClick = onConfirm ?: onDismissRequest) {
                        Text(fallbackConfirmLabel)
                    }
                }
            } else {
                {}
            },
            dismissButton = dismissLabel?.let { label ->
                { TextButton(onClick = onDismissRequest) { Text(label) } }
            },
        )
    }
}
