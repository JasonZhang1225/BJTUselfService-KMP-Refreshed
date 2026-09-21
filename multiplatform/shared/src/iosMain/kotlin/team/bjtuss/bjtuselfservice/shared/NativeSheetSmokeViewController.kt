package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItem
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.feature.shell.HomeSyncDetailsDialog
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalNativeSheetPresenter

/** DEBUG-only visual probe for the UIKit sheet shell; it never uses account data. */
fun NativeSheetSmokeViewController(): UIViewController {
    lateinit var controller: UIViewController
    val presenter = IosNativeSheetPresenter { controller }
    controller = ComposeUIViewController {
        PlatformAppTheme(
            useDarkTheme = isSystemInDarkTheme(),
            dynamicColorEnabled = false,
        ) {
            var visible by remember { mutableStateOf(false) }
            val items = remember {
                listOf(
                    HomeSyncItem("统一身份认证", "已完成", HomeSyncItemState.SUCCESS),
                    HomeSyncItem("首页账户状态", "已完成", HomeSyncItemState.SUCCESS),
                    HomeSyncItem("成绩", "正在同步成绩", HomeSyncItemState.SYNCING),
                    HomeSyncItem("课程表与校历周数", "正在同步课表并校准教学周", HomeSyncItemState.SYNCING),
                    HomeSyncItem("作业", "正在同步作业", HomeSyncItemState.SYNCING),
                    HomeSyncItem("考试安排", "已完成", HomeSyncItemState.SUCCESS),
                    HomeSyncItem("物理在线", "等待同步", HomeSyncItemState.WAITING),
                )
            }
            LaunchedEffect(Unit) {
                // The returned Compose controller is hosted by SwiftUI. Wait until
                // it has joined the window hierarchy before asking UIKit to present
                // the sheet; otherwise UIKit correctly ignores the early request.
                delay(250)
                visible = true
            }
            CompositionLocalProvider(LocalNativeSheetPresenter provides presenter) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("同步状态 smoke", style = MaterialTheme.typography.headlineMedium)
                        Text("使用模拟加载状态检查 iOS 原生 sheet。")
                        if (visible) {
                            HomeSyncDetailsDialog(
                                title = "同步中",
                                items = items,
                                canRetry = false,
                                onRetry = {},
                                onDismiss = { visible = false },
                            )
                        }
                    }
                }
            }
        }
    }
    return controller
}
