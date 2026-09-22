package team.bjtuss.bjtuselfservice.shared.webview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI

/**
 * macOS 桌面端不内嵌 JCEF（避免大体积原生依赖与打包复杂度），按既定方案
 * 把学校网页交给系统默认浏览器。应用内显示一个清晰的引导卡片，按钮使用
 * 具体目标域名作标签，符合"直接、具体的标签"原则。
 */
@Composable
actual fun SchoolWebView(
    request: WebPageRequest,
    modifier: Modifier,
    onOpenExternal: (String) -> Unit,
) {
    if (SchoolWebDomainPolicy.validate(request) != WebPageValidation.Allowed) return
    val host = request.url.removePrefix("https://").substringBefore('/')
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = request.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "此页面将在系统浏览器中打开，以便使用完整的浏览与安全能力。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(
            onClick = { onOpenExternal(request.url) },
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text("在浏览器中打开 $host")
        }
    }
}

actual fun openExternalUrl(url: String) {
    if (!url.startsWith("https://")) return
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

// 桌面端不内嵌网页，也不接收 Ktor Cookie，因此没有独立网页会话可清理。
actual suspend fun clearSchoolWebViewData(): Boolean = true
