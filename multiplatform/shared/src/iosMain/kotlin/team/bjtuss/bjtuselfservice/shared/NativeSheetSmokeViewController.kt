package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.delay
import platform.UIKit.UIViewController
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheetOrAlert
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeSheetPresenter

/** DEBUG-only visual probe for the UIKit sheet shell; it never uses account data. */
fun NativeSheetSmokeViewController(): UIViewController {
    lateinit var controller: UIViewController
    val presenter = IosNativeSheetPresenter { controller }
    controller = ComposeUIViewController {
        MaterialTheme {
            var visible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                // The returned Compose controller is hosted by SwiftUI. Wait until
                // it has joined the window hierarchy before asking UIKit to present
                // the sheet; otherwise UIKit correctly ignores the early request.
                delay(250)
                visible = true
            }
            CompositionLocalProvider(LocalNativeSheetPresenter provides presenter) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("UIKit sheet smoke", style = MaterialTheme.typography.headlineMedium)
                    Text("背景、标题、关闭与确认动作均由原生 sheet 宿主提供。")
                    if (visible) {
                        AppleSheetOrAlert(
                            onDismissRequest = { visible = false },
                            title = "原生弹出框",
                            confirmLabel = "完成",
                            onConfirm = { visible = false },
                            dismissLabel = "取消",
                            needsFullHeight = false,
                        ) {
                            Text(
                                "这段正文来自 Compose；弹层材质、拖拽、标题栏和操作按钮来自 UIKit。",
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    return controller
}
