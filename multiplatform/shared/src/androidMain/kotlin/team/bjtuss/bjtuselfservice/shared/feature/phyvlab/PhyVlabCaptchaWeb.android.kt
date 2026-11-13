package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCaptchaProof

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun PhyVlabCaptchaWeb(
    captchaId: String,
    onProof: (PhyVlabCaptchaProof) -> Unit,
    onUnavailable: () -> Unit,
) {
    val deliver by rememberUpdatedState(onProof)
    var view by remember { mutableStateOf<WebView?>(null) }
    LaunchedEffect(view) {
        val browser = view ?: return@LaunchedEffect
        while (true) {
            delay(350)
            browser.evaluateJavascript("window.phyVlabCaptchaProof || null") { result ->
                val json = runCatching { Json.parseToJsonElement(result) as? JsonPrimitive }.getOrNull()
                if (json?.isString == true) PhyVlabCaptchaProof.parse(json.content)?.let(deliver)
            }
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(440.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        request.isForMainFrame
                }
                view = this
                loadDataWithBaseURL(PHYVLAB_CAPTCHA_BASE_URL, phyVlabCaptchaHtml(captchaId, PhyVlabCaptchaDelivery.WindowVariable), "text/html", "UTF-8", null)
            }
        },
        onRelease = { browser ->
            view = null
            browser.stopLoading()
            browser.destroy()
        },
    )
}
