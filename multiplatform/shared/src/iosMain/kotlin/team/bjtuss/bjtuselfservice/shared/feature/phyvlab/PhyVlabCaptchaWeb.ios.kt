package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
import kotlin.coroutines.resume
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCaptchaProof
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.phyVlabDebug

private const val CAPTCHA_READ_INTERVAL_MS = 350L
private const val CAPTCHA_READ_TIMEOUT_MS = 5_000L

@OptIn(ExperimentalForeignApi::class)
private class CaptchaNavigation(
    private val scope: CoroutineScope,
    private val onProof: (PhyVlabCaptchaProof) -> Unit,
    private val onUnavailable: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    private var readJob: Job? = null
    private var active = true

    fun dispose() {
        active = false
        readJob?.cancel()
    }

    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        if (!active || readJob != null || webView.URL?.absoluteString != PHYVLAB_CAPTCHA_BASE_URL) return
        readJob = scope.launch {
            // WebKit can leave a long-running JavaScript completion pending even after the page has a result.
            // Read the retained value with one bounded evaluation at a time.
            while (true) {
                val result = readResult(webView)
                if (result == null) {
                    phyVlabDebug("captcha result read failed or timed out")
                    unavailable()
                    return@launch
                }
                if (result.isEmpty()) {
                    delay(CAPTCHA_READ_INTERVAL_MS)
                    continue
                }

                val proof = PhyVlabCaptchaProof.parse(result)
                if (proof == null) {
                    phyVlabDebug("captcha result rejected by parser")
                    unavailable()
                } else {
                    phyVlabDebug("captcha proof received")
                    onProof(proof)
                }
                return@launch
            }
        }
    }

    private suspend fun readResult(webView: WKWebView): String? =
        withTimeoutOrNull(CAPTCHA_READ_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                webView.evaluateJavaScript("window.$PHYVLAB_CAPTCHA_RESULT_VARIABLE ?? ''") { result, error ->
                    if (continuation.isActive) continuation.resume(if (error == null) result as? String else null)
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
    val scope = rememberCoroutineScope()
    val navigation = remember(scope) { CaptchaNavigation(scope, { deliver(it) }, { unavailable() }) }
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
                    phyVlabCaptchaHtml(captchaId),
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
