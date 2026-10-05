package team.bjtuss.bjtuselfservice.shared

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Called only by the Swift DEBUG launch-argument gate. */
fun NewMigrationProbeViewController(): UIViewController = ComposeUIViewController {
    team.bjtuss.bjtuselfservice.shared.feature.shell.NewMigrationUiProbe()
}
