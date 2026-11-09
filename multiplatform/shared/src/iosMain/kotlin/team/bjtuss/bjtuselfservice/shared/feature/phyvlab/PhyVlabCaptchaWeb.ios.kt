package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCaptchaProof

@OptIn(ExperimentalForeignApi::class)
private class CaptchaNavigation : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString
        val allowed = url == "about:blank" || url == PHYVLAB_CAPTCHA_BASE_URL
        decisionHandler(
            if (allowed) WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            else WKNavigationActionPolicy.WKNavigationActionPolicyCancel,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PhyVlabCaptchaWeb(
    captchaId: String,
    onProof: (PhyVlabCaptchaProof) -> Unit,
    onUnavailable: () -> Unit,
) {
    val deliver by rememberUpdatedState(onProof)
    val navigation = remember { CaptchaNavigation() }
    var view by remember { mutableStateOf<WKWebView?>(null) }
    LaunchedEffect(view) {
        val browser = view ?: return@LaunchedEffect
        while (true) {
            delay(350)
            browser.evaluateJavaScript("window.phyVlabCaptchaProof || null") { result, _ ->
                (result as? String)?.let(PhyVlabCaptchaProof::parse)?.let(deliver)
            }
        }
    }
    UIKitView(
        modifier = Modifier.fillMaxWidth().height(440.dp),
        accessibilityEnabled = true,
        factory = {
            val config = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            }
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config).apply {
                navigationDelegate = navigation
                view = this
                loadHTMLString(phyVlabCaptchaHtml(captchaId), NSURL(string = PHYVLAB_CAPTCHA_BASE_URL))
            }
        },
        onRelease = { browser ->
            view = null
            browser.stopLoading()
            browser.navigationDelegate = null
        },
    )
}
