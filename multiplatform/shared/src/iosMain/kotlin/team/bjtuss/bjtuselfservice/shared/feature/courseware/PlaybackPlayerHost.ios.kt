package team.bjtuss.bjtuselfservice.shared.feature.courseware

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.WebKit.WKAudiovisualMediaTypeNone
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeOther
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

actual val playbackPlaysInApp: Boolean = true
actual val playbackRecordingDownloadAvailable: Boolean = false

/** 播放页是本地 HTML：只放行首次加载，页面内任何跳转都拦掉。 */
private class PlaybackNavigationDelegate : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        decisionHandler(
            if (decidePolicyForNavigationAction.navigationType == WKNavigationTypeOther) {
                WKNavigationActionPolicy.WKNavigationActionPolicyAllow
            } else {
                WKNavigationActionPolicy.WKNavigationActionPolicyCancel
            },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlaybackPlayerView(html: String, baseUrl: String, modifier: Modifier, streams: List<team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream>) {
    val delegate = androidx.compose.runtime.remember { PlaybackNavigationDelegate() }
    UIKitView(
        modifier = modifier,
        accessibilityEnabled = true,
        factory = {
            val config = WKWebViewConfiguration()
            config.websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            config.allowsInlineMediaPlayback = true
            config.preferences.elementFullscreenEnabled = true
            config.allowsPictureInPictureMediaPlayback = true
            config.mediaTypesRequiringUserActionForPlayback = WKAudiovisualMediaTypeNone
            val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)
            webView.navigationDelegate = delegate
            webView.setOpaque(false)
            webView.backgroundColor = UIColor.blackColor
            webView.scrollView.backgroundColor = UIColor.blackColor
            webView.loadHTMLString(html, baseURL = NSURL(string = baseUrl))
            webView
        },
        onRelease = { webView ->
            // 关闭页面时立刻停掉两路视频，不让声音在后台继续。
            webView.stopLoading()
            webView.loadHTMLString("", baseURL = null)
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
