package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.feature.settings.*
import team.bjtuss.bjtuselfservice.shared.update.*

/** Local UI fixture: no network, accounts, files or persistent settings. */
@Composable
fun DesktopUpdateSourceSmoke() {
    var dark by remember { mutableStateOf(false) }
    var scenario by remember { mutableStateOf("正常") }
    val model = remember {
        SettingsScreenModel(AppPreferences(), { true }, { true }, { true }, { AppUpdateChecker.Result.Unavailable },
            checkReleaseFromSource = { source ->
                delay(1200)
                when (scenario) {
                    "失败" -> AppUpdateChecker.Result.Unavailable
                    else -> AppUpdateChecker.Result.Success(AppUpdateChecker.Release(
                        tagName = if (scenario == "无更新") "v1.0.0" else "v9.0.0-KMP",
                        name = "本地界面测试", body = "四端安装包与镜像同步测试。",
                        htmlUrl = if (source == UpdateSource.GITEE) "https://gitee.com/${AppUpdateChecker.GITEE_REPO}/releases" else "https://github.com/${AppUpdateChecker.REPO}/releases",
                        source = if (source == UpdateSource.GITEE) source else UpdateSource.GITHUB,
                    ))
                }
            })
    }
    val state by model.state.collectAsState()
    PlatformAppTheme(useDarkTheme = dark, dynamicColorEnabled = false) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ dark = !dark }) { Text(if (dark) "浅色" else "深色") }
                    listOf("正常", "无更新", "失败").forEach { label ->
                        TextButton({ scenario = label }) { Text(label) }
                    }
                }
                SettingsWorkspace(model, "测试账号", currentPlatform(), false, {}, Modifier.weight(1f))
            }
            AppUpdateResultDialog(state.updateCheck, model::dismissUpdateCheck, model::postponeUpdate)
        }
    }
}
