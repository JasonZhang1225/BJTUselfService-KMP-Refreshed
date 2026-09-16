package team.bjtuss.bjtuselfservice.shared.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSHTTPCookieDomain
import platform.Foundation.NSHTTPCookieName
import platform.Foundation.NSHTTPCookiePath
import platform.Foundation.NSHTTPCookieValue
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.UIKit.UIApplication
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
private class WebNavDelegate(
    private val pageHost: String,
    private val policy: ExternalLinkPolicy,
    private val onOpenExternal: (String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString ?: run {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            return
        }
        if (SchoolWebDomainPolicy.isSchoolHost(url)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
        } else {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
            if (policy == ExternalLinkPolicy.OPEN_EXTERNALLY) onOpenExternal(url)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SchoolWebView(
    request: WebPageRequest,
    modifier: Modifier,
    onOpenExternal: (String) -> Unit,
) {
    if (SchoolWebDomainPolicy.validate(request) != WebPageValidation.Allowed) return
    val delegate = remember(request.url) {
        WebNavDelegate(request.url, request.externalLinkPolicy, onOpenExternal)
    }
    UIKitView(
        modifier = modifier,
        // WKWebView 必须进入 UIKit 互操作无障碍树，否则页面虽已加载，VoiceOver
        // 与 Computer Use 都只能看到一个空白 Compose 区域。
        accessibilityEnabled = true,
        factory = {
            val config = WKWebViewConfiguration()
            val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)
            webView.navigationDelegate = delegate
            val store = config.websiteDataStore.httpCookieStore
            fun loadPage() {
                webView.loadRequest(NSURLRequest(NSURL(string = request.url)))
            }
            if (request.cookies.isEmpty()) {
                loadPage()
                return@UIKitView webView
            }
            var pendingCookies = request.cookies.size
            fun cookieFinished() {
                pendingCookies -= 1
                if (pendingCookies == 0) loadPage()
            }
            request.cookies.forEach { cookie ->
                val props = mapOf<Any?, Any>(
                    NSHTTPCookieName to cookie.name,
                    NSHTTPCookieValue to cookie.value,
                    NSHTTPCookieDomain to cookie.domain,
                    NSHTTPCookiePath to cookie.path,
                )
                val nativeCookie = NSHTTPCookie.cookieWithProperties(props)
                if (nativeCookie == null) {
                    cookieFinished()
                } else {
                    store.setCookie(nativeCookie) { cookieFinished() }
                }
            }
            webView
        },
    )
}

actual fun openExternalUrl(url: String) {
    val target = NSURL(string = url)
        ?.takeIf { it.scheme?.equals("https", ignoreCase = true) == true }
        ?: return
    // 使用 iOS 10+ 的 options/completion API。旧的 openURL(NSURL) 在新系统上虽然
    // 仍可能编译通过，但从 Compose 点击回调触发时可能只被静默忽略。
    UIApplication.sharedApplication.openURL(
        target,
        options = emptyMap<Any?, Any>(),
        completionHandler = null,
    )
}
