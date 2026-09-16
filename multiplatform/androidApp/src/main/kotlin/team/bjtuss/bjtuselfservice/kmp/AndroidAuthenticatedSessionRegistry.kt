package team.bjtuss.bjtuselfservice.kmp

import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession

/**
 * 根 Activity 与系统详情 Activity 之间的进程内会话桥。
 *
 * 不做持久化，不放入 Intent；进程重建后详情页会安全关闭并回到负责恢复登录的根 Activity。
 */
object AndroidAuthenticatedSessionRegistry {
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
        session?.notifyAppBecameActive()
    }
}

