package team.bjtuss.bjtuselfservice.shared.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.platformSupportsDynamicColor
import team.bjtuss.bjtuselfservice.shared.update.AppUpdateChecker
import team.bjtuss.bjtuselfservice.shared.update.ReleaseNoteBlock
import team.bjtuss.bjtuselfservice.shared.update.annotatedInlineMarkdown
import team.bjtuss.bjtuselfservice.shared.update.parseReleaseNotes
import team.bjtuss.bjtuselfservice.shared.feature.scroll.desktopTouchScroll

@Composable
fun SettingsWorkspace(
    model: SettingsScreenModel,
    accountName: String,
    platform: PlatformInfo,
    expanded: Boolean,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val pageScrollState = rememberScrollState()

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除当前账号离线缓存？") },
            text = {
                Text("成绩、课表、考试和作业的离线副本会被删除；不会退出账号。之后可从学校系统重新下载。")
            },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    scope.launch { model.clearOfflineCache() }
                }) { Text("清除缓存") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }

    // 检查结果弹窗提到 SettingsWorkspace 外层渲染：「前往下载」属于应用壳层导航动作，
    // 挂在页面里时用户不在设置页就永远看不到自动检测出的新版本提示。
    AppUpdateResultDialog(state.updateCheck, model::dismissUpdateCheck)

    Column(
        modifier = modifier.fillMaxSize()
            .verticalScroll(pageScrollState)
            .desktopTouchScroll(pageScrollState)
            .padding(
            horizontal = if (expanded) 8.dp else 16.dp,
            vertical = 14.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 紧凑布局下 shell 顶栏已显示“设置”，页内不再重复；宽屏侧栏布局没有顶栏，保留页内标题。
        if (expanded) {
            Text("设置", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        SettingCard("账户", accountName.ifBlank { "未登录" })

        // 仅 Android 展示动态取色开关；iOS/桌面无 Material You，不出现「主题设置」整块。
        if (platformSupportsDynamicColor()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("动态取色", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "打开后使用系统壁纸颜色（Material You）；关闭则使用应用默认配色。浅色/深色仍跟随系统。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "动态取色",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = state.preferences.dynamicColor,
                            onCheckedChange = model::setDynamicColor,
                        )
                    }
                    if (state.saveFailed) {
                        Feedback("设置保存失败，请重试。", true, model::dismissFeedback)
                    }
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("底栏显示", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "物理在线关闭后仍可从“更多”进入；开启后会作为作业右侧的独立入口显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AutoSyncSettingRow(
                    "显示物理在线入口",
                    state.preferences.showPhyVlabInBottomNav,
                    model::setShowPhyVlabInBottomNav,
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("自动同步", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "打开后，每次登录成功会先显示离线数据，再自动拉取最新数据；物理在线会复用当前 CAS 会话建立 Moodle 会话，首次失败会自动重试一次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AutoSyncSettingRow("自动同步成绩", state.preferences.autoSyncGrades, model::setAutoSyncGrades)
                AutoSyncSettingRow("自动同步作业", state.preferences.autoSyncHomework, model::setAutoSyncHomework)
                AutoSyncSettingRow("自动同步课表", state.preferences.autoSyncSchedule, model::setAutoSyncSchedule)
                AutoSyncSettingRow("自动同步考试", state.preferences.autoSyncExams, model::setAutoSyncExams)
                AutoSyncSettingRow("自动同步物理在线（仅校园网）", state.preferences.autoSyncPhyVlab, model::setAutoSyncPhyVlab)
                if (state.saveFailed && !platformSupportsDynamicColor()) {
                    Feedback("同步设置保存失败，请重试。", true, model::dismissFeedback)
                }
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("版本与项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${platform.displayName} · KMP 迁移构建 v${AppUpdateChecker.CURRENT_VERSION}\n功能对齐基线：原安卓 v1.7.0",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { scope.launch { model.checkForUpdate() } },
                    enabled = state.updateCheck !is UpdateCheckState.Checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.updateCheck is UpdateCheckState.Checking) "正在检查更新…" else "检查更新")
                }
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://github.com/HFDLYS/BJTUselfService") },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("原作者 GitHub（安卓原版）")
                }
                OutlinedButton(
                    onClick = {
                        uriHandler.openUri("https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("本仓库 GitHub（KMP 三端）")
                }
                Text(
                    "预发布阶段更新检测指向本仓库 GitHub Releases（含 pre-release）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("本地数据与会话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                when (state.cacheAction) {
                    OfflineCacheActionState.Clearing -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    OfflineCacheActionState.Cleared ->
                        Feedback("离线缓存已清除；账号仍保留。", false, model::dismissFeedback)
                    OfflineCacheActionState.Failed ->
                        Feedback("离线缓存清除失败，请稍后重试。", true, model::dismissFeedback)
                    OfflineCacheActionState.Idle -> Unit
                }
                if (expanded) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CacheClearButton(
                            onClick = { confirmClear = true },
                            enabled = state.cacheAction != OfflineCacheActionState.Clearing,
                            modifier = Modifier.weight(1f),
                        )
                        LogoutButton(onClick = onLogout, modifier = Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CacheClearButton(
                            onClick = { confirmClear = true },
                            enabled = state.cacheAction != OfflineCacheActionState.Clearing,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        LogoutButton(onClick = onLogout, modifier = Modifier.fillMaxWidth())
                    }
                }
                // 仅桌面宽屏布局提示窗口行为；手机设置页不提 macOS。
                if (expanded && platform.family == PlatformFamily.MacOS) {
                    Text(
                        "关闭窗口只关闭窗口，不退出账号、不清除会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CacheClearButton(onClick: () -> Unit, enabled: Boolean, modifier: Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Text("清除离线缓存")
    }
}

/** GitHub `published_at` 是 ISO-8601（如 2026-08-09T13:24:35Z），弹窗里只展示日期；格式不符时回退原文。 */
private fun formatPublishedAt(iso: String): String =
    if (iso.length >= 10 && iso[4] == '-' && iso[7] == '-') iso.substring(0, 10) else iso

/**
 * 更新检测结果弹窗。应用壳（AuthenticatedAppShell）在 SettingsWorkspace 外层渲染它，
 * 因此自动/手动检查出的新版本提示不依赖用户停留在设置页。
 * 新版本 → 「前往下载」跳 GitHub 发布页；已最新/失败 → 轻量提示；Idle/Checking 不渲染。
 */
@Composable
fun AppUpdateResultDialog(check: UpdateCheckState, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val releaseNotesScrollState = rememberScrollState()
    when (check) {
        is UpdateCheckState.Done -> {
            if (check.hasUpdate) {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = {
                        AutoSizeDialogTitle("发现新版本 ${check.release.tagName}")
                    },
                    text = {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(releaseNotesScrollState)
                                .desktopTouchScroll(releaseNotesScrollState),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            check.release.publishedAt?.let { publishedAt ->
                                Text(
                                    "发布时间：${formatPublishedAt(publishedAt)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            check.release.body?.takeIf { it.isNotBlank() }?.let { body ->
                                ReleaseNotesBody(body)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            onDismiss()
                            uriHandler.openUri(check.release.htmlUrl)
                        }) { Text("前往下载") }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("暂不更新") }
                    },
                )
            } else {
                AlertDialog(
                    onDismissRequest = onDismiss,
                    title = { Text("已是最新版本") },
                    text = { Text("当前 v${AppUpdateChecker.CURRENT_VERSION} 已是最新发布（${check.release.tagName}）。") },
                    confirmButton = {
                        TextButton(onClick = onDismiss) { Text("好") }
                    },
                )
            }
        }
        UpdateCheckState.Failed -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text("检查更新失败") },
                text = { Text("无法连接 GitHub，请检查网络后重试。") },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("好") }
                },
            )
        }
        UpdateCheckState.Idle, UpdateCheckState.Checking -> Unit
    }
}

@Composable
private fun AutoSizeDialogTitle(text: String) {
    val baseStyle = MaterialTheme.typography.headlineSmall
    val startSize = baseStyle.fontSize.takeIf { it != TextUnit.Unspecified } ?: 22.sp
    val minSize = 14.sp
    var fontSize by remember(text, startSize) { mutableStateOf(startSize) }
    Text(
        text = text,
        style = baseStyle.copy(fontSize = fontSize),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > minSize) {
                val next = (fontSize.value - 1f).coerceAtLeast(minSize.value).sp
                if (next != fontSize) fontSize = next
            }
        },
    )
}

@Composable
private fun ReleaseNotesBody(markdown: String) {
    val blocks = remember(markdown) { parseReleaseNotes(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is ReleaseNoteBlock.Heading -> InlineMarkdownText(
                    text = block.text,
                    style = when {
                        block.level <= 2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                is ReleaseNoteBlock.Paragraph -> InlineMarkdownText(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ReleaseNoteBlock.ListItems -> Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("•", style = MaterialTheme.typography.bodySmall)
                            InlineMarkdownText(
                                text = item,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                is ReleaseNoteBlock.Table -> ReleaseNotesTable(block)
            }
        }
    }
}

@Composable
private fun ReleaseNotesTable(table: ReleaseNoteBlock.Table) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        table.rows.forEach { row ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (table.headers.size >= 2 && row.size >= 2) {
                        InlineMarkdownText(
                            text = row[0],
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        InlineMarkdownText(
                            text = row[1],
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        table.headers.zip(row).forEach { (header, cell) ->
                            InlineMarkdownText(
                                text = "$header：$cell",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineMarkdownText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    Text(
        text = remember(text) { annotatedInlineMarkdown(text) },
        modifier = modifier,
        style = style.merge(fontWeight = fontWeight),
        color = color,
    )
}

@Composable
private fun LogoutButton(onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier) {
        Text("退出并清除登录信息")
    }
}

@Composable
private fun AutoSyncSettingRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingCard(title: String, value: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Feedback(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                message,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    }
}
