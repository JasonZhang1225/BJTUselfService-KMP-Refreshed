package team.bjtuss.bjtuselfservice.shared.feature.courseware

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackStream

/** iOS 用 WKWebView，Android 用系统 MediaPlayer；桌面端交给系统浏览器。 */
expect val playbackPlaysInApp: Boolean

/**
 * 课程回放录像的下载与合成是否对用户开放。
 * 仅 Windows / macOS 为 true；Android / iOS 只保留在线播放（课件文件下载不受此项影响）。
 */
expect val playbackRecordingDownloadAvailable: Boolean

/**
 * App 内播放容器。[streams] 供 Android 原生播放器使用；其他平台使用 HTML。
 * [baseUrl] 是录播服务器的 HTTPS origin，让页面与视频同源加载；
 * 页面只允许 `media-src https:`，不跟随任何链接。
 */
@Composable
expect fun PlaybackPlayerView(html: String, baseUrl: String, modifier: Modifier = Modifier, streams: List<PlaybackStream> = emptyList())

/** 把播放页写进应用临时目录并用系统浏览器打开；不支持的平台返回 false。 */
expect fun openPlaybackInSystemBrowser(html: String): Boolean

/**
 * 单路画面的静态预览（取第 10 分钟左右的一帧），用来在选择画面时认出是哪一路。
 * iOS 用 WebView、Android 用系统 MediaPlayer 解 HLS；macOS 调用随 App 打包的 AVFoundation 截帧小程序；
 * 其他平台没有预览时显示 [fallbackLabel]。
 */
@Composable
expect fun PlaybackFramePreview(url: String, fallbackLabel: String, modifier: Modifier = Modifier)
