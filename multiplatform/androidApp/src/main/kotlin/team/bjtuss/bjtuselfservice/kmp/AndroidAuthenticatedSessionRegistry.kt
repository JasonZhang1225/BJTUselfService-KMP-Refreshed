package team.bjtuss.bjtuselfservice.kmp

import android.app.Activity
import android.app.Application
import android.os.Bundle
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession

/**
 * 根 Activity 与系统详情 Activity 之间的进程内会话桥。
 *
 * 不做持久化，不放入 Intent；进程重建后详情页会安全关闭并回到负责恢复登录的根 Activity。
 */
object AndroidAuthenticatedSessionRegistry {
    private var lifecycleInstalled = false
    private var startedActivities = 0

    fun installLifecycle(application: Application) {
        if (lifecycleInstalled) return
        lifecycleInstalled = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) session?.notifyAppBecameActive()
            }
            override fun onActivityStopped(activity: Activity) { startedActivities = (startedActivities - 1).coerceAtLeast(0) }
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
    private val observers = linkedSetOf<(AuthenticatedSession?) -> Unit>()

    var session: AuthenticatedSession? = null
        private set

    @Synchronized
    fun update(value: AuthenticatedSession?) {
        session = value
        observers.toList().forEach { it(value) }
    }

    @Synchronized
    fun observe(observer: (AuthenticatedSession?) -> Unit): () -> Unit {
        observers += observer
        observer(session)
        return { synchronized(this) { observers -= observer } }
    }

    fun notifyAppBecameActive() {
        session?.notifyPageBecameActive()
    }
}

