package team.bjtuss.bjtuselfservice.shared.feature.otherfunction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.OtherFunctionFailure
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.OtherFunctionTask
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.OtherFunctionTaskState
import team.bjtuss.bjtuselfservice.shared.domain.otherfunction.ReportCardLanguage
import team.bjtuss.bjtuselfservice.shared.feature.shell.LocalTopBarClearance

const val SCHOOL_CALENDAR_ARTICLE_URL = "https://mp.weixin.qq.com/s/_O3Jwni5D2ZB93fmczCYmQ"

/** 校历由公众号文章维护，入口只负责打开文章，不再请求已经失效的校历下载接口。 */
@Composable
fun SchoolCalendarArticleWorkspace(
    expanded: Boolean,
    onOpenArticle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OtherFunctionPageScaffold(
        title = "校历",
        subtitle = "当前最新校历为 2026-2027 校历，点击跳转学校公众号文章获取详情",
        expanded = expanded,
        modifier = modifier,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "校历信息",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "当前最新校历为 2026-2027 校历，点击下方按钮跳转学校公众号文章获取详情。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenArticle) {
                    Text("查看 2026-2027 校历")
                }
            }
        }
    }
}

/**
 * 成绩单下载：独立页面，支持中英文版本切换。
 */
@Composable
fun ReportCardDownloadWorkspace(
    model: OtherFunctionScreenModel,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()

    OtherFunctionPageScaffold(
        title = "成绩单下载",
        subtitle = "下载个人学习成绩单，支持中英文版本",
        expanded = expanded,
        modifier = modifier,
    ) {
        OtherFunctionCard(
            title = "成绩单下载",
            description = "下载个人学习成绩单，支持中英文版本",
            actionLabel = "下载",
            taskState = state.reportCardState,
            enabled = !state.isAnyTaskRunning,
            onAction = { scope.launch { model.downloadReportCard() } },
            onDismiss = { model.clearTaskState(OtherFunctionTask.REPORT_CARD) },
            extraContent = {
                // 中文版/英文版二选一分段按钮：选中的一段填充主题色，不要开关。
                ReportCardLanguageSelector(
                    english = state.reportCardLanguage == ReportCardLanguage.ENGLISH,
                    onSelect = { english ->
                        model.setReportCardLanguage(
                            if (english) ReportCardLanguage.ENGLISH else ReportCardLanguage.CHINESE,
                        )
                    },
                )
            },
        )
    }
}

@Composable
private fun OtherFunctionPageScaffold(
    title: String,
    subtitle: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = if (expanded) {
            modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        } else {
            // 短静态页不滚动：留在原生栏下（老起笔 14.dp + 栏高，其余平台恒 0 不变）。
            modifier.padding(horizontal = 16.dp).padding(top = 14.dp + LocalTopBarClearance.current)
        },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (expanded) {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}

@Composable
private fun ReportCardLanguageSelector(english: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        LanguageSegment(
            label = "中文版",
            selected = !english,
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f),
        )
        LanguageSegment(
            label = "英文版",
            selected = english,
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LanguageSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun OtherFunctionCard(
    title: String,
    description: String,
    actionLabel: String,
    taskState: OtherFunctionTaskState,
    enabled: Boolean,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    infoText: String? = null,
    extraContent: @Composable (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            infoText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            extraContent?.invoke()

            TaskStatusRow(
                taskState = taskState,
                onDismiss = onDismiss,
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = onAction,
                    enabled = enabled && taskState != OtherFunctionTaskState.Downloading,
                ) {
                    if (taskState == OtherFunctionTaskState.Downloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun TaskStatusRow(
    taskState: OtherFunctionTaskState,
    onDismiss: () -> Unit,
) {
    when (taskState) {
        OtherFunctionTaskState.Idle,
        OtherFunctionTaskState.SaveCancelled,
        -> Unit
        OtherFunctionTaskState.Downloading -> {
            // 转圈只在下载按钮内保留，这里只显示状态文字。
            Text(
                "正在下载…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is OtherFunctionTaskState.Saved -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "已保存：${taskState.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
        is OtherFunctionTaskState.Failed -> {
            val message = when (taskState.reason) {
                OtherFunctionFailure.NETWORK -> "网络请求失败，请检查网络后重试。"
                OtherFunctionFailure.PARSE -> "页面格式异常，暂时无法解析文件地址。"
                OtherFunctionFailure.SESSION_EXPIRED -> "登录会话已过期，请重新登录后再下载。"
                OtherFunctionFailure.SAVE_FAILED -> "系统保存失败，请重新选择保存位置。"
                OtherFunctionFailure.SAVE_UNAVAILABLE -> "当前平台不支持系统保存面板。"
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}
