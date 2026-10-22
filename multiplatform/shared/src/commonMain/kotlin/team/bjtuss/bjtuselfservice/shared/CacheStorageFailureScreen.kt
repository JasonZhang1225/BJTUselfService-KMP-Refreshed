package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 存储异常时停止正常启动，避免把临时空库当成新安装继续写入设置或清理账号。 */
@Composable
internal fun CacheStorageFailureScreen(useDarkTheme: Boolean = isSystemInDarkTheme()) {
    PlatformAppTheme(useDarkTheme = useDarkTheme, dynamicColorEnabled = false) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("暂时无法读取本地数据", style = MaterialTheme.typography.headlineSmall)
                Text("本次启动已停止。原数据库、设置文件和已保存的账号密码不会因此被删除或重建。")
                Text("请完全退出 App 后重新打开。若仍出现此页面，请保留 App 和数据进行排查，避免卸载或清除配置信息。")
            }
        }
    }
}
