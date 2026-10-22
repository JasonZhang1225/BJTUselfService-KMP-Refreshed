package team.bjtuss.bjtuselfservice.kmp

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import team.bjtuss.bjtuselfservice.kmp.live.CourseLiveUpdates
import team.bjtuss.bjtuselfservice.shared.App
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.cache.createAndroidCacheStore
import team.bjtuss.bjtuselfservice.shared.security.createAndroidAccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.logging.AppLog

class MainActivity : ComponentActivity() {
    private val refreshRate = AndroidRefreshRateController(this)
    private val sessionOwner = Any()
    // 回调与 Activity 同生命周期，避免重组更换回调触发登录页 DisposableEffect 的清理。
    private val publishSession: (AuthenticatedSession?) -> Unit = { session ->
        AndroidAuthenticatedSessionRegistry.update(sessionOwner, session)
    }
    // 卡片 Live 菜单第一次开启时申请通知权限；结果交回实时动态协调器。
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        CourseLiveUpdates.onPermissionResult(this, granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAuthenticatedSessionRegistry.installLifecycle(application)
        // 课表快照写入后通知桌面小组件重排、实时动态重新对齐（进程内一次即可，重复调用无副作用）。
        team.bjtuss.bjtuselfservice.shared.feature.widget.installAndroidCourseWidgetStore(this) {
            team.bjtuss.bjtuselfservice.kmp.widget.CourseWidgetUpdater.updateAll(it)
            CourseLiveUpdates.reconcile(it)
        }
        CourseLiveUpdates.install(this)
        CourseLiveUpdates.attachPermissionRequester {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        enableImmersiveEdgeToEdge()
        // 调试日志只在 debug 构建开启；release 保持关闭。
        AppLog.enabled = BuildConfig.DEBUG
        // 不要在此处预热 WebView：Chromium 会让 HyperOS 把应用标成「跟随应用内设置」并锁 60Hz。
        refreshRate.start()
        val accountSecurityStore = createAndroidAccountSecurityStore(this)
        val cacheStoreHandle = createAndroidCacheStore(this)
        val homeworkFileGateway = AndroidHomeworkFileGateway(this)
        setContent {
            App(
                accountSecurityStore = accountSecurityStore,
                cacheStoreHandle = cacheStoreHandle,
                homeworkFileGateway = homeworkFileGateway,
                coursewareDirectoryGateway = homeworkFileGateway,
                nativeNavigationEnabled = true,
                onOpenExternalUrl = ::openExternalUrl,
                onOpenNativeRoute = { routeId ->
                    if (BuildConfig.DEBUG) Log.d("NativeNavigation", "Root requested route=$routeId sessionReady=${AndroidAuthenticatedSessionRegistry.session != null}")
                    rememberSystemBottomInset()
                    startActivity(NativeDetailActivity.intentFor(this, routeId))
                },
                onAuthenticatedSessionChanged = publishSession,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        AndroidAuthenticatedSessionRegistry.notifyAppBecameActive()
        // 从设置页回来时通知权限可能变了；也补上后台没按时到的换课。
        CourseLiveUpdates.reconcile(this)
        refreshRate.apply()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshRate.apply()
    }

    override fun onDestroy() {
        refreshRate.stop()
        CourseLiveUpdates.attachPermissionRequester(null)
        if (isFinishing) AndroidAuthenticatedSessionRegistry.update(sessionOwner, null)
        super.onDestroy()
    }

    private fun openExternalUrl(url: String) {
        openHttpsUrl(this, url)
    }
}
