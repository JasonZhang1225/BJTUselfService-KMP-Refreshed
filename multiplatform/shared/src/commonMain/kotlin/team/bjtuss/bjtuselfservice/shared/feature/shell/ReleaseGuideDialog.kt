package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private const val GUIDE_WAIT_SECONDS = 3

@Composable
internal fun TimedGuideDialog(
    title: String,
    body: String,
    saveFailed: Boolean,
    onConfirm: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(GUIDE_WAIT_SECONDS) }
    LaunchedEffect(title) {
        remaining = GUIDE_WAIT_SECONDS
        while (remaining > 0) {
            delay(1_000)
            remaining -= 1
        }
    }
    val desktopPresenter = LocalNativeDesktopPresentation.current
    if (desktopPresenter != null) {
        // Read the timer in composition, so each tick schedules a fresh native snapshot.
        val guide = NativeDesktopGuide(title, body, remaining, saveFailed, onConfirm)
        androidx.compose.runtime.SideEffect { desktopPresenter.showGuide(guide) }
        androidx.compose.runtime.DisposableEffect(desktopPresenter) {
            onDispose { desktopPresenter.showGuide(null) }
        }
        return
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(body)
                if (saveFailed) {
                    Text("确认保存失败，请重试。", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = remaining == 0) {
                Text(if (remaining > 0) "确认（$remaining）" else "确认")
            }
        },
    )
}

/**
 * 1.8.6 起启动时只弹这一个版本说明：取代 1.8.3/1.8.4 功能介绍、1.8.5 紧急修复说明和
 * “物理在线已自动关闭”四个弹窗。新装与升级用户都只看一次；物理在线一段只在升级时自动关闭过才出现。
 */
@Composable
internal fun Release186GuideDialog(
    phyVlabDisabledOnUpgrade: Boolean,
    saveFailed: Boolean,
    onConfirm: () -> Unit,
) {
    TimedGuideDialog(
        title = "1.8.6 版本说明",
        body = release186GuideBody(phyVlabDisabledOnUpgrade),
        saveFailed = saveFailed,
        onConfirm = onConfirm,
    )
}

internal fun release186GuideBody(phyVlabDisabledOnUpgrade: Boolean): String = buildList {
    add("· 登录：已适配 MIS 新统一身份认证。需要验证码时请看图手动输入，App 不再自动识别验证码。")
    if (phyVlabDisabledOnUpgrade) {
        add(
            "· 物理在线：原统一认证登录已失效，本次升级已自动关闭物理在线。" +
                "到「应用 → 设置 → 物理在线账号设置」填写独立账号，保存成功后会自动重新打开。",
        )
    }
    add("· 课程实时动态：iOS 灵动岛与实时活动（含 Apple Watch）、Android 实时动态通知，课前自动出现。")
    add("· 图书馆：在「应用 → 图书馆」查看借阅并续借。")
    add("· 作业：支持搜索；课程平台、物理在线、CITEL 的上传界面统一，修复 iOS 无法选择文件。")
    add("· ChatGPT 插件：可把课表、作业等同步到你自己的私人 ChatGPT 插件查询，在「应用」中设置，不配对不会上传任何数据。")
    add("· 物理在线、演示实验、CITEL 使用各自的独立账号，请在「应用 → 设置」中填写；底栏常用页可在「应用 → 设置 → 底栏显示」调整。")
    add("本版本为测试版，遇到问题请反馈。")
}.joinToString("\n\n")
