package team.bjtuss.bjtuselfservice.shared

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.ui.ExperimentalComposeUiApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIViewController
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import team.bjtuss.bjtuselfservice.shared.cache.createAppleCacheStore
import team.bjtuss.bjtuselfservice.shared.security.createAppleAccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.files.IosHomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.feature.shell.NativeBarAction
import team.bjtuss.bjtuselfservice.shared.auth.IosCoreMlCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.calendar.IosSystemCalendarGateway

// 预热 WebKit：首次创建 WKWebView 要启动 WebContent 进程并初始化渲染子系统（主线程，秒级），
// 若等到进入邮箱页才创建会卡住主线程和进页转场动画。保留引用让进程池常驻。
private var prewarmedWebView: WKWebView? = null

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun MainViewController(): UIViewController = run {
    createMainViewController(
        nativeNavigationEnabled = false,
        nativeTabBarEnabled = false,
        onAuthenticatedSessionChanged = {},
        onOpenNativeRoute = {},
    )
}

/**
 * Swift UINavigationController 宿主使用的根 Compose 控制器。
 *
 * [nativeTabBarEnabled] = true 时（M17 玻璃壳）宿主只做登录与会话来源，一级入口改由
 * Swift 的 UITabBarController 逐 tab 承载；false 时保持 M10 行为，根组合自带壳层与自绘底栏。
 */
fun NativeMainViewController(
    onAuthenticatedSessionChanged: (AuthenticatedSession?) -> Unit,
    onOpenNativeRoute: (String) -> Unit,
    nativeTabBarEnabled: Boolean = false,
): UIViewController = createMainViewController(
    nativeNavigationEnabled = true,
    nativeTabBarEnabled = nativeTabBarEnabled,
    onAuthenticatedSessionChanged = onAuthenticatedSessionChanged,
    onOpenNativeRoute = onOpenNativeRoute,
)

/**
 * Swift 原生导航栈中的单个 Compose 目的地控制器。
 *
 * [useNativeTitleBar] 必须由宿主如实声明：只有真的会显示系统导航栏（M17 玻璃壳）时才为 true，
 * 回退壳（iOS 26 以下）传 false，否则页面会既没有系统标题也没有 Compose 自绘标题。
 */
fun NativeDestinationViewController(
    session: AuthenticatedSession,
    routeId: String,
    useNativeTitleBar: Boolean,
    onOpenNativeRoute: (String) -> Unit,
    onCloseNativeRoute: () -> Unit,
    onTitleChanged: (String) -> Unit = {},
    onActionChanged: (NativeBarAction?) -> Unit = {},
    onSelectNativeTab: (String) -> Unit = {},
): UIViewController = createDestinationViewController(
    session = session,
    routeId = routeId,
    nativeTabBarEnabled = false,
    useNativeTitleBar = useNativeTitleBar,
    onOpenNativeRoute = onOpenNativeRoute,
    onCloseNativeRoute = onCloseNativeRoute,
    onTitleChanged = onTitleChanged,
    onActionChanged = onActionChanged,
    onSelectNativeTab = onSelectNativeTab,
)

/**
 * M17：原生 tab 根页面控制器。底栏由系统玻璃 TabBar 提供，大标题与同步胶囊交给
 * 系统导航栏（`useNativeTitleBar = true`），页内不再自绘顶栏；宿主没有真把导航栏放出来时
 * 不要传 true，否则页面既没有系统标题也没有 Compose 自绘标题。
 */
fun NativeTabRootViewController(
    session: AuthenticatedSession,
    routeId: String,
    onOpenNativeRoute: (String) -> Unit,
    onCloseNativeRoute: () -> Unit,
    onTitleChanged: (String) -> Unit = {},
    onActionChanged: (NativeBarAction?) -> Unit = {},
    onSelectNativeTab: (String) -> Unit = {},
): UIViewController = createDestinationViewController(
    session = session,
    routeId = routeId,
    nativeTabBarEnabled = true,
    useNativeTitleBar = true,
    onOpenNativeRoute = onOpenNativeRoute,
    onCloseNativeRoute = onCloseNativeRoute,
    onTitleChanged = onTitleChanged,
    onActionChanged = onActionChanged,
    onSelectNativeTab = onSelectNativeTab,
)

@OptIn(ExperimentalComposeUiApi::class)
private fun createDestinationViewController(
    session: AuthenticatedSession,
    routeId: String,
    nativeTabBarEnabled: Boolean,
    useNativeTitleBar: Boolean,
    onOpenNativeRoute: (String) -> Unit,
    onCloseNativeRoute: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onActionChanged: (NativeBarAction?) -> Unit = {},
    onSelectNativeTab: (String) -> Unit,
): UIViewController {
    lateinit var controller: UIViewController
    val homeworkFileGateway = IosHomeworkFileGateway { controller }
    val nativeSheetPresenter = IosNativeSheetPresenter { controller }
    controller = ComposeUIViewController(
        configure = { opaque = false },
    ) {
        AuthenticatedDestinationApp(
            session = session,
            routeId = routeId,
            homeworkFileGateway = homeworkFileGateway,
            coursewareDirectoryGateway = homeworkFileGateway,
            nativeTabBarEnabled = nativeTabBarEnabled,
            useNativeTitleBar = useNativeTitleBar,
            nativeSheetPresenter = nativeSheetPresenter,
            onOpenNativeRoute = onOpenNativeRoute,
            onCloseNativeRoute = onCloseNativeRoute,
            onSelectNativeTab = onSelectNativeTab,
            onNativeTitleChanged = onTitleChanged,
            onNativeActionChanged = onActionChanged,
        )
    }
    return controller
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
private fun createMainViewController(
    nativeNavigationEnabled: Boolean,
    nativeTabBarEnabled: Boolean,
    onAuthenticatedSessionChanged: (AuthenticatedSession?) -> Unit,
    onOpenNativeRoute: (String) -> Unit,
): UIViewController = run {
    if (prewarmedWebView == null) {
        prewarmedWebView = WKWebView(
            frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
            configuration = WKWebViewConfiguration(),
        )
    }
    val accountSecurityStore = createAppleAccountSecurityStore(accessibleAfterFirstUnlock = true)
    val cacheStoreHandle = createAppleCacheStore()
    lateinit var controller: UIViewController
    val homeworkFileGateway = IosHomeworkFileGateway { controller }
    val systemCalendarGateway = IosSystemCalendarGateway()
    val captchaRecognizer = IosCoreMlCaptchaRecognizer()
    val nativeSheetPresenter = IosNativeSheetPresenter { controller }
    controller = ComposeUIViewController(
        configure = { opaque = false },
    ) {
        App(
            accountSecurityStore = accountSecurityStore,
            cacheStoreHandle = cacheStoreHandle,
            homeworkFileGateway = homeworkFileGateway,
            coursewareDirectoryGateway = homeworkFileGateway,
            systemCalendarGateway = systemCalendarGateway,
            captchaRecognizer = captchaRecognizer,
            nativeNavigationEnabled = nativeNavigationEnabled,
            nativeTabBarEnabled = nativeTabBarEnabled,
            nativeSheetPresenter = nativeSheetPresenter,
            onOpenNativeRoute = onOpenNativeRoute,
            onAuthenticatedSessionChanged = onAuthenticatedSessionChanged,
        )
    }
    controller
}
