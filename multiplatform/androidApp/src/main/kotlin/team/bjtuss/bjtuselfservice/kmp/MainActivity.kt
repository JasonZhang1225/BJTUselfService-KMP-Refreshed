package team.bjtuss.bjtuselfservice.kmp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import team.bjtuss.bjtuselfservice.shared.App
import team.bjtuss.bjtuselfservice.shared.cache.createAndroidCacheStore
import team.bjtuss.bjtuselfservice.shared.security.createAndroidAccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.auth.AndroidOnnxCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.logging.AppLog

class MainActivity : ComponentActivity() {
    private val refreshRate = AndroidRefreshRateController(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 调试日志只在 debug 构建开启；release 保持关闭。
        AppLog.enabled = BuildConfig.DEBUG
        // 不要在此处预热 WebView：Chromium 会让 HyperOS 把应用标成「跟随应用内设置」并锁 60Hz。
        refreshRate.start()
        val accountSecurityStore = createAndroidAccountSecurityStore(this)
        val cacheStoreHandle = createAndroidCacheStore(this)
        val homeworkFileGateway = AndroidHomeworkFileGateway(this)
        val captchaRecognizer = AndroidOnnxCaptchaRecognizer(this)
        setContent {
            App(
                accountSecurityStore = accountSecurityStore,
                cacheStoreHandle = cacheStoreHandle,
                homeworkFileGateway = homeworkFileGateway,
                coursewareDirectoryGateway = homeworkFileGateway,
                captchaRecognizer = captchaRecognizer,
                nativeNavigationEnabled = true,
                onOpenExternalUrl = ::openExternalUrl,
                onOpenNativeRoute = { routeId ->
                    startActivity(NativeDetailActivity.intentFor(this, routeId))
                },
                onAuthenticatedSessionChanged = AndroidAuthenticatedSessionRegistry::update,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        AndroidAuthenticatedSessionRegistry.notifyAppBecameActive()
        refreshRate.apply()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshRate.apply()
    }

    override fun onDestroy() {
        refreshRate.stop()
        if (isFinishing) AndroidAuthenticatedSessionRegistry.update(null)
        super.onDestroy()
    }

    private fun openExternalUrl(url: String) {
        if (!url.startsWith("https://")) return
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
