package team.bjtuss.bjtuselfservice.shared.feature.courseware

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

actual val playbackPlaysInApp: Boolean = true

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun PlaybackPlayerView(html: String, baseUrl: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                // 播放页是本地 HTML：页面内任何跳转都拦掉。
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        },
    )
}

actual fun openPlaybackInSystemBrowser(html: String): Boolean = false

@Composable
actual fun PlaybackFramePreview(url: String, fallbackLabel: String, modifier: Modifier) {
    PlaybackPlayerView(
        html = androidx.compose.runtime.remember(url) { buildPlaybackPreviewHtml(url) },
        baseUrl = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore('/') + "/",
        modifier = modifier,
    )
}
