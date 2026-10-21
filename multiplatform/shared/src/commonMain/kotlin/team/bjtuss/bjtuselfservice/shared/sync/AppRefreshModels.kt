package team.bjtuss.bjtuselfservice.shared.sync

/**
 * App 全局刷新覆盖的数据源。顺序即报告顺序；`wire` 用于 CLI JSON、IPC 与远程协议，发布后不改名。
 * 教室占用、课件、题面等按需查询的内容不在这里（不做全量预抓取）。
 */
enum class RefreshSource(val wire: String, val label: String) {
    HOME("home", "首页"),
    MAIL_UNREAD("mailUnread", "邮箱"),
    HOMEWORK("homework", "课程平台作业"),
    EXAMS("exams", "考试"),
    COURSES("courses", "课表"),
    GRADES("grades", "成绩"),
    PHYSICS_LAB("physicsLab", "物理实验"),
    PHYSICS_DEMO("physicsDemo", "演示实验"),
    CITEL("citel", "CITEL"),
    PHYVLAB("phyvlab", "物理在线"),
    ;

    companion object {
        fun fromWire(wire: String): RefreshSource? = entries.firstOrNull { it.wire == wire }
    }
}

/** 谁发起了这次刷新；只用于记录和展示，不改变学校协议。 */
enum class RefreshOrigin(val wire: String) {
    APP("app"),
    CLI("cli"),
    SCHEDULED("scheduled"),
    REMOTE("remote"),
    ;

    companion object {
        fun fromWire(wire: String): RefreshOrigin? = entries.firstOrNull { it.wire == wire }
    }
}

enum class RefreshStatus(val wire: String) {
    SUCCESS("success"),
    FAILED("failed"),
    /** 设置里关闭或账号未配置：没有访问网络。 */
    SKIPPED("skipped"),
    /** 需要用户在 App 里处理（重新登录、补填独立账号密码等）；自动重试不会成功。 */
    NEEDS_USER_ACTION("needs_user_action"),
    ;

    companion object {
        fun fromWire(wire: String): RefreshStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** 刷新前判断来源是否参与。 */
enum class RefreshAvailability {
    ENABLED,
    DISABLED,
    NOT_CONFIGURED,
}

/** 跳过与失败的可判定原因码；`detail` 另带给用户看的文字。 */
object RefreshReason {
    const val DISABLED = "disabled"
    const val NOT_CONFIGURED = "not_configured"
    const val SESSION_EXPIRED = "session_expired"
    const val RECOVERY_FAILED = "recovery_failed"
    const val TIMEOUT = "timeout"
    const val CANCELLED = "cancelled"
    const val ERROR = "error"
}

data class RefreshSourceResult(
    val source: RefreshSource,
    val status: RefreshStatus,
    /** 可判定原因码（[RefreshReason] 或来源自身的失败枚举小写名）；成功时为 null。 */
    val reason: String? = null,
    /** 给人看的说明，可能为 null。 */
    val message: String? = null,
    /**
     * 该来源缓存数据的真实同步时间（Unix 秒）。失败时保留上次成功的时间，绝不用本次刷新时间冒充；
     * null 表示从未同步或旧版本 App 没有记录。
     */
    val fetchedAtEpochSeconds: Long? = null,
)

data class RefreshRequest(
    val sources: Set<RefreshSource> = RefreshSource.entries.toSet(),
    val origin: RefreshOrigin,
    /** Windows 闲置后点击：先恢复登录再刷新；恢复失败则整批不访问学校。 */
    val forceReauthenticate: Boolean = false,
)

data class RefreshReport(
    val accountScope: String,
    val origin: RefreshOrigin,
    val startedAtEpochSeconds: Long,
    val finishedAtEpochSeconds: Long,
    val results: List<RefreshSourceResult>,
) {
    fun result(source: RefreshSource): RefreshSourceResult? = results.firstOrNull { it.source == source }

    /** 所有参与的来源都成功（跳过不算失败）。 */
    val allSucceeded: Boolean
        get() = results.all { it.status == RefreshStatus.SUCCESS || it.status == RefreshStatus.SKIPPED }

    val needsUserAction: Boolean get() = results.any { it.status == RefreshStatus.NEEDS_USER_ACTION }
}
