package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.WebKit.WKContentWorld
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCaptchaProof
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.phyVlabDebug

@OptIn(ExperimentalForeignApi::class)
private class CaptchaNavigation(
    private val onProof: (PhyVlabCaptchaProof) -> Unit,
    private val onUnavailable: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    private var awaitingResult = false
    private var active = true

    fun dispose() {
        active = false
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (!active || awaitingResult) return
        awaitingResult = true
        // 由宿主在当前文档里等待结果，避免脚本回调依赖另一个消息通道。
        webView.callAsyncJavaScript(
            "return await window.$PHYVLAB_CAPTCHA_RESULT_PROMISE;",
            arguments = null,
            inFrame = null,
            inContentWorld = WKContentWorld.pageWorld,
        ) { result, error ->
            if (active) {
                val proof = (result as? String)?.let(PhyVlabCaptchaProof::parse)
                if (error != null || proof == null) {
                    phyVlabDebug(if (error != null) "captcha result read failed" else "captcha result rejected by parser")
                    unavailable()
                } else {
                    phyVlabDebug("captcha proof received")
                    onProof(proof)
                }
            }
        }
    }

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

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) = unavailable()

    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        didFailNavigation: WKNavigation?,
        withError: NSError,
    ) = unavailable()

    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) = unavailable()

    private fun unavailable() {
        if (!active) return
        dispose()
        onUnavailable()
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
    val unavailable by rememberUpdatedState(onUnavailable)
    val navigation = remember { CaptchaNavigation({ deliver(it) }, { unavailable() }) }
    UIKitView(
        modifier = Modifier.fillMaxWidth().height(440.dp),
        accessibilityEnabled = true,
        factory = {
            val config = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            }
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config).apply {
                navigationDelegate = navigation
                loadHTMLString(
                    phyVlabCaptchaHtml(captchaId, PhyVlabCaptchaDelivery.WebViewPromise),
                    NSURL(string = PHYVLAB_CAPTCHA_BASE_URL),
                )
            }
        },
        onRelease = { browser ->
            navigation.dispose()
            browser.navigationDelegate = null
            browser.stopLoading()
        },
    )
}
