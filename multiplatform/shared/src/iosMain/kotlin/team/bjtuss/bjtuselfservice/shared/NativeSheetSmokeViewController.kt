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
fun NativeSheetSmokeViewController(physicsLab: Boolean = false, updateNotes: Boolean = false): UIViewController {
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
                            if (updateNotes) {
                                team.bjtuss.bjtuselfservice.shared.feature.settings.AppUpdateResultDialog(
                                    team.bjtuss.bjtuselfservice.shared.feature.settings.UpdateCheckState.Done(
                                        team.bjtuss.bjtuselfservice.shared.update.AppUpdateChecker.Release(
                                            tagName = "v9.9.9", htmlUrl = "https://example.invalid/release",
                                            body = "# 更新说明\n\n" + (1..45).joinToString("\n\n") { "第 $it 项更新：这是一段用于核对窄屏换行、日志完整性和原生滚动布局的合成说明。" } + "\n\n日志末尾验收标记",
                                        ), hasUpdate = true,
                                    ), onDismiss = { visible = false }, onPostpone = { visible = false },
                                )
                            } else if (physicsLab) {
                                team.bjtuss.bjtuselfservice.shared.feature.shell.AppleSheet(
                                    onDismissRequest = { visible = false }, title = "物理实验同步",
                                    needsFullHeight = true, scrollableBody = true,
                                ) {
                                    team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabSettingsForm(
                                        state = team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLabState(
                                            enabled = true, message = "已同步 4 个实验。",
                                            labs = (1..4).map { index -> team.bjtuss.bjtuselfservice.shared.feature.physicslab.PhysicsLab(
                                                kotlinx.datetime.LocalDate(2026, 11, index), 4, "测试实验 $index",
                                                "测试教室", "测试教师", 1,
                                            ) },
                                        ), username = "fixture-account", password = "fixture-password", ready = true,
                                        onUsername = {}, onPassword = {}, onSave = {}, showTitle = false,
                                    )
                                }
                            } else HomeSyncDetailsDialog(
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
