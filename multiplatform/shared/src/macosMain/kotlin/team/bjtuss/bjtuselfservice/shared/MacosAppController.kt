package team.bjtuss.bjtuselfservice.shared

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AppKit.NSViewController
import team.bjtuss.bjtuselfservice.shared.cache.createAppleCacheStore
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeSidebarBus
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeSidebarItem
import team.bjtuss.bjtuselfservice.shared.feature.shell.nativeSidebarItems
import team.bjtuss.bjtuselfservice.shared.security.createAppleAccountSecurityStore

/**
 * macOS 原生宿主入口。
 *
 * Swift 侧的 `macosApp` 用它拿到三样东西：
 * - 一个承载共享 Compose 界面的原生视图控制器；
 * - 原生侧栏的条目列表（会话就绪后即可读取）；
 * - 把侧栏选中项送回共享壳层的通道。
 *
 * 侧栏与工具栏由 SwiftUI 的 `NavigationSplitView` 画，Compose 只负责右侧内容区，
 * 与 iOS 端「UIKit 承载 Compose」的分工一致。
 *
 * ObjC 导出名显式写死，Swift 侧不必猜 Kotlin/Native 的默认拼接规则。
 */
@OptIn(ExperimentalForeignApi::class)
class MacosAppController {

    /** 侧栏选中项 → 共享壳层。Swift 侧调用 [selectSidebarItem]。 */
    private val sidebarBus: NativeSidebarBus = NativeSidebarBus()

    /** 桌面菜单与快捷键用的命令总线（刷新当前页、前往某页）。 */
    private val commandBus: AppCommandBus = AppCommandBus()

    private val accountSecurityStore = createAppleAccountSecurityStore(accessibleAfterFirstUnlock = false)
    private val cacheStoreHandle = createAppleCacheStore()

    private var session: AuthenticatedSession? = null

    /**
     * 侧栏条目。未登录时也返回默认功能目录：侧栏由宿主提供，
     * 不该在登录前整条空着（对齐 Mac App Store 的分类侧栏）。
     */
    fun sidebarItems(): List<NativeSidebarItem> =
        session?.let { nativeSidebarItems(it) }
            ?: nativeSidebarItems(team.bjtuss.bjtuselfservice.shared.cache.AppPreferences())

    /** 当前登录账号的显示名；未登录时为空串。 */
    fun accountName(): String = session?.profile?.name.orEmpty()

    /** 当前登录账号的学院；未登录时为空串。 */
    fun accountDepartment(): String = session?.profile?.department.orEmpty()

    /** 是否已登录。 */
    fun isSignedIn(): Boolean = session != null

    /** 原生侧栏选中某个一级入口（routeId 为 section 名）。 */
    fun selectSidebarItem(routeId: String): Boolean = sidebarBus.select(routeId)

    /** 桌面菜单「刷新当前页面」。 */
    fun refreshCurrentPage(): Boolean = commandBus.send(team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommand.REFRESH_CURRENT)

    /**
     * 创建承载共享界面的原生视图控制器。
     *
     * [onSectionChanged] 回报当前一级入口（原生侧栏据此高亮）；
     * [onSessionChanged] 回报登录态变化（未登录为 nil）。
     */
    fun createRootViewController(
        onSectionChanged: (String) -> Unit,
        onSessionChanged: (AuthenticatedSession?) -> Unit,
        onScrollProgress: (Double) -> Unit,
    ): NSViewController {
        val host = MacosComposeHost {
            App(
                accountSecurityStore = accountSecurityStore,
                cacheStoreHandle = cacheStoreHandle,
                appCommandBus = commandBus,
                nativeSidebarEnabled = true,
                nativeSidebarBus = sidebarBus,
                onNativeSectionChanged = onSectionChanged,
                // 共享层用像素（Float）上报滚动偏移，这里转成 Double，
                // 避免 ObjC 导出成装箱的 Kotlin Float，Swift 侧要用指针接。
                onNativeScrollProgress = { offsetPx -> onScrollProgress(offsetPx.toDouble()) },
                onAuthenticatedSessionChanged = { updated ->
                    session = updated
                    onSessionChanged(updated)
                },
            )
        }
        return NSViewController().apply {
            view = host.view
        }
    }
}
