package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItem
import team.bjtuss.bjtuselfservice.shared.feature.home.HomeSyncItemState
import team.bjtuss.bjtuselfservice.shared.feature.shell.HomeSyncDetailsDialog

/**
 * Local-only desktop visual probe for the sync sheet. It uses synthetic states
 * so the loading layout can be checked without an account or network request.
 */
@Composable
fun DesktopSyncStatusSmoke() {
    var visible by remember { mutableStateOf(true) }
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

    PlatformAppTheme(
        useDarkTheme = isSystemInDarkTheme(),
        dynamicColorEnabled = false,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
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
