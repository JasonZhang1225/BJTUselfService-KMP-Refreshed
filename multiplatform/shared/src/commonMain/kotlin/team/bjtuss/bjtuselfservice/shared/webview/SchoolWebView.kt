package team.bjtuss.bjtuselfservice.shared.webview

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 平台网页容器。把一个经过 `SchoolWebDomainPolicy` 校验的请求渲染为
 * 应用内网页；Cookie 同步、外部链接和系统浏览器分流由平台实现。
 *
 * 调用方必须先通过 `SchoolWebDomainPolicy.validate` 校验；
 * 校验失败的请求平台实现应直接拒绝渲染。
 */
@Composable
expect fun SchoolWebView(
    request: WebPageRequest,
    modifier: Modifier = Modifier,
    onOpenExternal: (String) -> Unit = {},
)

/** 用系统浏览器/默认方式打开一个外部链接。 */
expect fun openExternalUrl(url: String)

/**
 * 清除学校内嵌网页容器持有的 Cookie、DOM Storage 与磁盘缓存。
 *
 * 退出账号时必须与 Ktor 会话一起调用，避免 WebView 的独立 Cookie jar
 * 在账号切换后继续带出上一账号的邮箱/MIS 会话。
 */
expect suspend fun clearSchoolWebViewData(): Boolean
