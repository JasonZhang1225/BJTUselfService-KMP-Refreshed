package team.bjtuss.bjtuselfservice.kmp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import team.bjtuss.bjtuselfservice.shared.feature.home.weChatUrlLinkScheme

private const val WECHAT_PACKAGE = "com.tencent.mm"

/**
 * 打开 HTTPS 链接。完美校园 URL Link 在 Android 上不能丢给系统浏览器：
 * Edge/Chrome 只会停在「请在手机打开网页链接」。微信也不接收 `https://wxaurl.cn`
 * 的 ACTION_VIEW。同一 ticket 要用 `weixin://dl/business/?t=` 才能唤起小程序。
 * 校历公众号文章仍走系统浏览器：微信会把第三方唤起的 mp.weixin.qq.com 判成 invalid source。
 * GitHub、校园网、物理在线同样走系统浏览器。
 */
internal fun openHttpsUrl(activity: Activity, url: String) {
    if (!url.startsWith("https://")) return
    val scheme = weChatUrlLinkScheme(url)
    if (scheme != null) {
        val ticket = scheme.substringAfter("t=")
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse(scheme)).setPackage(WECHAT_PACKAGE),
            Intent.parseUri(
                "intent://dl/business/?t=$ticket#Intent;scheme=weixin;package=$WECHAT_PACKAGE;end",
                Intent.URI_INTENT_SCHEME,
            ),
        )
        for (intent in attempts) {
            try {
                activity.startActivity(intent)
                return
            } catch (_: Exception) {
                // 下一手；都失败再打开系统浏览器。
            }
        }
    }
    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
