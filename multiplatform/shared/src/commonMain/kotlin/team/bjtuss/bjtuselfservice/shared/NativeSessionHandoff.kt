package team.bjtuss.bjtuselfservice.shared

/**
 * 进程内原生页面的会话交接。调用方须在主线程（或同一把锁下）访问。
 * owner 是每个根宿主独立的令牌，不保存 Activity/Window。旧宿主的清理不能撤销新宿主的登记。
 */
class NativeSessionHandoff<T : Any> {
    private var owner: Any? = null
    var session: T? = null
        private set

    /** 返回会话实例是否变化；同一会话转交给新 owner 时无需关闭或重建详情页。 */
    fun update(owner: Any, session: T?): Boolean {
        if (session == null && this.owner !== owner) return false
        this.owner = if (session == null) null else owner
        if (this.session === session) return false
        this.session = session
        return true
    }
}
