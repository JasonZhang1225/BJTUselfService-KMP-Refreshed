package team.bjtuss.bjtuselfservice.kmp

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import team.bjtuss.bjtuselfservice.kmp.widget.CourseWidgetUpdates
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommand
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import team.bjtuss.bjtuselfservice.shared.App
import team.bjtuss.bjtuselfservice.shared.cache.createAndroidCacheStore
import team.bjtuss.bjtuselfservice.shared.security.createAndroidAccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.auth.AndroidOnnxCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.logging.AppLog

class MainActivity : ComponentActivity() {
    private val refreshRate = AndroidRefreshRateController(this)
    private val commandBus = AppCommandBus()
    private var openWidgetSchedule by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CourseWidgetUpdates.install(applicationContext)
        openWidgetSchedule = intent.getBooleanExtra(CourseWidgetUpdates.OPEN_SCHEDULE, false)
        AndroidAuthenticatedSessionRegistry.installLifecycle(application)
        enableImmersiveEdgeToEdge()
        // 调试日志只在 debug 构建开启；release 保持关闭。
        AppLog.enabled = BuildConfig.DEBUG
        // 不要在此处预热 WebView：Chromium 会让 HyperOS 把应用标成「跟随应用内设置」并锁 60Hz。
        refreshRate.start()
        val accountSecurityStore = createAndroidAccountSecurityStore(this)
        val cacheStoreHandle = createAndroidCacheStore(this)
        val homeworkFileGateway = AndroidHomeworkFileGateway(this)
        val captchaRecognizer = AndroidOnnxCaptchaRecognizer(this)
        setContent {
            LaunchedEffect(openWidgetSchedule) {
                if (openWidgetSchedule) {
                    // 冷启动 / 登录恢复完成后再导航，不丢弃还没有订阅者的命令。
                    commandBus.subscriptionCount.first { it > 0 }
                    commandBus.send(AppCommand.NAVIGATE_SCHEDULE)
                    openWidgetSchedule = false
                    intent.removeExtra(CourseWidgetUpdates.OPEN_SCHEDULE)
                }
            }
            App(
                accountSecurityStore = accountSecurityStore,
                cacheStoreHandle = cacheStoreHandle,
                homeworkFileGateway = homeworkFileGateway,
                coursewareDirectoryGateway = homeworkFileGateway,
                captchaRecognizer = captchaRecognizer,
                appCommandBus = commandBus,
                nativeNavigationEnabled = true,
                onOpenExternalUrl = ::openExternalUrl,
                onOpenNativeRoute = { routeId ->
                    rememberSystemBottomInset()
                    startActivity(NativeDetailActivity.intentFor(this, routeId))
                },
                onAuthenticatedSessionChanged = AndroidAuthenticatedSessionRegistry::update,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(CourseWidgetUpdates.OPEN_SCHEDULE, false)) openWidgetSchedule = true
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
        openHttpsUrl(this, url)
    }
}
