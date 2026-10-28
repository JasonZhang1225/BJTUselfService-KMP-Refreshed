package team.bjtuss.bjtuselfservice.windows

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import team.bjtuss.bjtuselfservice.shared.agent.remote.AppSessionRemoteExecutor
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentController
import team.bjtuss.bjtuselfservice.shared.agent.remote.RemoteAgentStore
import team.bjtuss.bjtuselfservice.shared.feature.settings.LocalAppStartupHost
import team.bjtuss.bjtuselfservice.shared.feature.settings.LocalRemoteAgentController
import team.bjtuss.bjtuselfservice.shared.system.BACKGROUND_LAUNCH_ARGUMENT
import team.bjtuss.bjtuselfservice.shared.system.shouldStartHidden
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.Image
import java.awt.Toolkit
import java.awt.AWTEvent
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.App
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.auth.UnavailableCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus


/** 进程级持有，防止锁对象被回收；进程退出时系统释放。 */
private var appRunningLock: AutoCloseable? = null

fun main(args: Array<String>) {
    if (args.firstOrNull() == "agent") {
        // AI Agent 命令行：在任何 AWT 类加载前切到 headless，不打开窗口。
        System.setProperty("java.awt.headless", "true")
        val code = team.bjtuss.bjtuselfservice.shared.agent.runAgentCli(args.drop(1), windowsAgentCliPlatform())
        kotlin.system.exitProcess(code)
    }
    // 1.8.6 起 Windows 不再内置验证码模型（旧 torch 模型已存档于 archive/legacy-android-and-captcha-model），
    // 需要验证码时由用户手动输入。
    val captchaRecognizer = UnavailableCaptchaRecognizer
    // 已有窗口进程（可能在后台运行远程同步）：请它显示窗口后本进程退出，不建立第二个会话。
    if (BACKGROUND_LAUNCH_ARGUMENT !in args &&
        team.bjtuss.bjtuselfservice.shared.agent.host.AppRefreshHost.showExistingWindow(windowsAgentCliPlatform().cacheDirectory)
    ) {
        return
    }
    val accountSecurityStore = createWindowsAccountSecurityStore()
    val cacheStoreHandle = createWindowsCacheStore()
    // 刷新执行者：持锁并开放本机 IPC，命令行 `cache update` 交给当前会话执行，避免两边各登录一次互相顶掉会话。
    // 后台命令行正在刷新时先请它交出执行权（最多等几秒），拿不到也照常启动。
    val currentSession = java.util.concurrent.atomic.AtomicReference<AuthenticatedSession?>(null)
    val showRequests = MutableStateFlow(0L)
    appRunningLock = team.bjtuss.bjtuselfservice.shared.agent.host.AppRefreshHost.start(
        windowsAgentCliPlatform().cacheDirectory,
        sessionProvider = currentSession::get,
        onShowRequest = { showRequests.update { it + 1 } },
    )

    // 远程查询（手机）：默认关闭；开启后两个计时器在后台运行，关闭主窗口不停止，明确退出才停止。
    val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val remoteController = RemoteAgentController(
        store = RemoteAgentStore(cacheStoreHandle.store),
        secrets = WindowsRemoteSecretStore(),
        executor = AppSessionRemoteExecutor(currentSession::get, cacheStoreHandle.store),
        scope = remoteScope,
        host = WindowsRemoteHost(),
    )
    remoteController.start()
    // 关闭“后台定时同步”时，像手机端一样在每次 App 内刷新完成后上传。
    val sessionUpdates = kotlinx.coroutines.flow.MutableStateFlow<AuthenticatedSession?>(null)
    remoteController.followAppRefreshes(sessionUpdates)
    // 开机启动时按“静默启动”决定是否只留在托盘；没有托盘时总是显示窗口，不留无窗口进程。
    val startupHost = WindowsStartupHost()
    val traySupported = remoteController.host?.traySupported == true
    val startHidden = shouldStartHidden(args, startupHost.isSilentStart(), canReopen = traySupported)

    try {
        application {
            val state = rememberWindowState(width = 1080.dp, height = 720.dp)
            var windowVisible by remember { mutableStateOf(!startHidden) }
            val remoteConfig by remoteController.config.collectAsState()
            LaunchedEffect(Unit) {
                showRequests.collect { if (it > 0) windowVisible = true }
            }
            val showTray = traySupported && (remoteConfig.minimizeToTray || remoteConfig.runnable || !windowVisible)
            val trayTooltip = if (remoteConfig.runnable) "交大自由行 KMP（远程查询运行中）" else "交大自由行 KMP"
            val trayIconImage = remember { appTrayImage() }
            val tray = remember { mutableStateOf<WindowsTray?>(null) }
            DisposableEffect(showTray, trayIconImage) {
                if (showTray && trayIconImage != null) {
                    tray.value = runCatching {
                        WindowsTray(trayIconImage, trayTooltip, onOpen = { windowVisible = true }, onExit = ::exitApplication)
                    }.getOrNull()
                }
                onDispose {
                    tray.value?.close()
                    tray.value = null
                }
            }
            LaunchedEffect(trayTooltip, tray.value) { tray.value?.setTooltip(trayTooltip) }
            val appCommandBus = remember { AppCommandBus() }
            val shellSubscribers by appCommandBus.subscriptionCount.collectAsState()
            val authenticatedSession = remember { mutableStateOf<AuthenticatedSession?>(null) }

            Window(
                // 最小化按钮照常最小化到任务栏；只有点关闭按钮才按偏好收进托盘。
                // 托盘偏好与上云授权独立；没有系统托盘时绝不隐藏成无法恢复的进程。
                onCloseRequest = {
                    if (remoteConfig.minimizeToTray && traySupported) windowVisible = false else exitApplication()
                },
                visible = windowVisible,
                title = "交大自由行 KMP",
                state = state,
                icon = appWindowIcon(),
            ) {
                LaunchedEffect(windowVisible) {
                    if (windowVisible) {
                        window.toFront()
                        window.requestFocus()
                    }
                }
                LaunchedEffect(window) {
                    window.minimumSize = Dimension(720, 520)
                    // 多尺寸图标列表：Windows 按当前 DPI 选最合适尺寸，避免缩放模糊。
                    appWindowIconImages()?.let { images ->
                        window.iconImages = images
                    }
                    // 标题栏跟随系统浅/深色：轮询注册表以支持运行中切换 Windows 主题。
                    // 实测单次 JNA 读注册表约 40µs（20k 次平均），2s 一次的任务周期占用
                    // ≈0.002% CPU；注册表读取放到 IO 线程，避免占用 Compose 主线程。
                    var appliedDark: Boolean? = null
                    while (isActive) {
                        val dark = withContext(Dispatchers.IO) { isWindowsAppsUseDarkTheme() }
                        if (dark != appliedDark) {
                            applyWindowsTitleBarTheme(window.windowHandle, dark)
                            appliedDark = dark
                        }
                        delay(2_000)
                    }
                }
                // Count completed content clicks only. Focus, mouse movement, wheel
                // scrolling and closing the native window never request a refresh.
                val clickListener = remember(window) {
                    AWTEventListener { event ->
                        val mouse = event as? MouseEvent
                        if (mouse?.id == MouseEvent.MOUSE_CLICKED &&
                            SwingUtilities.getWindowAncestor(mouse.component) === window) {
                            authenticatedSession.value?.notifyFunctionClicked()
                        }
                    }
                }
                DisposableEffect(window, clickListener) {
                    val toolkit = Toolkit.getDefaultToolkit()
                    toolkit.addAWTEventListener(clickListener, AWTEvent.MOUSE_EVENT_MASK)
                    onDispose { toolkit.removeAWTEventListener(clickListener) }
                }
                val homeworkFileGateway = remember(window) { WindowsHomeworkFileGateway(owner = { window }) }
                CompositionLocalProvider(LocalRemoteAgentController provides remoteController, LocalAppStartupHost provides startupHost) {
                App(
                    accountSecurityStore = accountSecurityStore,
                    cacheStoreHandle = cacheStoreHandle,
                    homeworkFileGateway = homeworkFileGateway,
                    coursewareDirectoryGateway = homeworkFileGateway,
                    appCommandBus = appCommandBus,
                    captchaRecognizer = captchaRecognizer,
                    onAuthenticatedSessionChanged = {
                            authenticatedSession.value = it
                            currentSession.set(it)
                            sessionUpdates.value = it
                        },
                )
                }
            }
        }
    } finally {
        // 先停 IPC、删端点再释放锁；异常退出时由系统释放锁，残留端点会因连不上被忽略。
        runCatching { appRunningLock?.close() }
        remoteController.close()
        remoteScope.cancel()
        cacheStoreHandle.store.close()
    }
}

/** 窗口标题栏/任务栏图标：从资源加载品牌 PNG，失败时回退默认图标。 */
private fun appWindowIcon(): BitmapPainter? {
    val stream = appWindowIconResource() ?: return null
    return runCatching {
        BitmapPainter(ImageIO.read(stream).toComposeImageBitmap())
    }.getOrNull()
}

/** 窗口标题栏/任务栏图标：多尺寸图标列表（Windows 自动选最合适尺寸，各 DPI 清晰）。 */
private fun appWindowIconImages(): List<Image>? {
    val stream = appWindowIconResource() ?: return null
    val source = runCatching { ImageIO.read(stream) }.getOrNull() ?: return null
    return listOf(
        source.getScaledInstance(16, 16, Image.SCALE_SMOOTH),
        source.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
        source.getScaledInstance(48, 48, Image.SCALE_SMOOTH),
    )
}

/** 托盘图标：取原图，由系统按托盘尺寸缩放；读不到时不显示托盘（窗口照常可用）。 */
private fun appTrayImage(): Image? {
    val stream = appWindowIconResource() ?: return null
    return runCatching { ImageIO.read(stream) }.getOrNull()
}

private fun appWindowIconResource(): java.io.InputStream? =
    Thread.currentThread().contextClassLoader.getResourceAsStream("app-icon.png")

/**
 * Windows 10/11 标题栏跟随**系统**浅色/深色主题（DWM 的 DWMWA_USE_IMMERSIVE_DARK_MODE，
 * 属性 20，Windows 10 1809+）。
 *
 * 注意：Java/AWT 窗口不会自动跟随系统深色，所以必须显式设置；但以前这里无条件写 1，
 * 导致系统浅色时应用内容是浅色、标题栏却是纯黑。现在由调用方按注册表 AppsUseLightTheme
 * 决定深浅，并在窗口生命周期内轮询以跟随系统主题切换。
 */
private fun applyWindowsTitleBarTheme(windowHandle: Long, dark: Boolean) {
    if (windowHandle == 0L) return
    val darkMode = intArrayOf(if (dark) 1 else 0)
    runCatching {
        Dwmapi.INSTANCE.DwmSetWindowAttribute(
            windowHandle,
            DWMWA_USE_IMMERSIVE_DARK_MODE,
            darkMode,
            Int.SIZE_BYTES,
        )
    }
}

/** 系统“应用模式”是否为深色；读不到时按浅色处理（与 Windows 默认一致）。 */
private fun isWindowsAppsUseDarkTheme(): Boolean = runCatching {
    val lightTheme = Advapi32Util.registryGetIntValue(
        WinReg.HKEY_CURRENT_USER,
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme",
    )
    lightTheme == 0
}.getOrDefault(false)

private interface Dwmapi : Library {
    fun DwmSetWindowAttribute(
        hwnd: Long,
        attribute: Int,
        pvAttribute: IntArray,
        cbAttribute: Int,
    ): Int

    companion object {
        val INSTANCE: Dwmapi by lazy {
            Native.load("Dwmapi", Dwmapi::class.java)
        }
    }
}

private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
