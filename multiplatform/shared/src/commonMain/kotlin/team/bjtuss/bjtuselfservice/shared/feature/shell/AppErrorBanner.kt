package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 全应用红色提示条统一规格：圆角、内边距、正文/标题字号、按钮间距。 */
private val ErrorBannerShape = RoundedCornerShape(16.dp)
private val ErrorBannerPadding = 14.dp
private val ErrorBannerActionSpacing = 8.dp

/**
 * 同步失败、授权风险等共用的错误/警告条。
 * 宽度由调用方页面的水平 padding 决定，本组件只 [fillMaxWidth]，不再叠一层 horizontal。
 */
@Composable
fun AppErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = "关闭",
    retryLabel: String = "重试",
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = ErrorBannerShape,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(ErrorBannerPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ErrorBannerActionSpacing),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (title != null) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text(retryLabel) }
            }
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text(dismissLabel) }
            }
        }
    }
}
