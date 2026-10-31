package team.bjtuss.bjtuselfservice.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import team.bjtuss.bjtuselfservice.shared.agent.remote.*
import team.bjtuss.bjtuselfservice.shared.feature.settings.LocalRemoteAgentController
import java.awt.Dimension
import java.awt.Desktop
import java.awt.desktop.AppReopenedListener
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.io.File
import team.bjtuss.bjtuselfservice.shared.App
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.registerDesktopCredentialWindowHandle
import team.bjtuss.bjtuselfservice.shared.cache.createDesktopCacheStore
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommand
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus
import team.bjtuss.bjtuselfservice.shared.security.createDesktopAccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.system.DesktopWindowHandle
import team.bjtuss.bjtuselfservice.shared.system.MacLaunchAgentStartupHost
import team.bjtuss.bjtuselfservice.shared.system.shouldStartHidden
import team.bjtuss.bjtuselfservice.shared.feature.settings.LocalAppStartupHost
import androidx.compose.runtime.CompositionLocalProvider
import team.bjtuss.bjtuselfservice.shared.system.DesktopWindowLifecycle
import team.bjtuss.bjtuselfservice.shared.DesktopSyncStatusSmoke

private const val SYNC_STATUS_SMOKE_ARGUMENT = "--sync-status-smoke"

/** 进程级持有，防止锁对象被回收；进程退出时系统释放。 */
private var appRunningLock: AutoCloseable? = null

fun main(args: Array<String>) {
    if (args.firstOrNull() == "agent") {
        // AI Agent 命令行：在任何 AWT 类加载前切到 headless，不出现 Dock 图标、不打开窗口。
        System.setProperty("java.awt.headless", "true")
        val code = team.bjtuss.bjtuselfservice.shared.agent.runAgentCli(
            args.drop(1),
            team.bjtuss.bjtuselfservice.shared.agent.macOsAgentCliPlatform(),
        )
        kotlin.system.exitProcess(code)
    }
    if ("--sync-timing" in args) {
        // Enable only sanitized timing events; general business/debug logs stay off.
        team.bjtuss.bjtuselfservice.shared.logging.SyncTiming.sink = { println(it) }
    }
    if ("--playback-ui-harness" in args) {
        val rest = args.toList().dropWhile { it != "--playback-ui-harness" }.drop(1).toTypedArray()
        team.bjtuss.bjtuselfservice.shared.runDesktopPlaybackUiHarness(rest)
        return
    }
    if ("--update-source-smoke" in args || "--remote-ui-smoke" in args || SYNC_STATUS_SMOKE_ARGUMENT in args || "--schedule-events-smoke" in args || "--new-migration-smoke" in args || "--agent-ui-smoke" in args) {
        application {
            Window(
                onCloseRequest = { exitApplication() },
                title = "同步状态 · 本地测试",
                state = rememberWindowState(width = if ("--narrow" in args) 420.dp else 1080.dp, height = 720.dp),
            ) {
                if ("--update-source-smoke" in args) team.bjtuss.bjtuselfservice.shared.DesktopUpdateSourceSmoke(live = "--live-gitee" in args, largeText = "--large-text" in args) else if ("--remote-ui-smoke" in args) team.bjtuss.bjtuselfservice.shared.DesktopRemoteAgentSmoke() else if ("--agent-ui-smoke" in args) team.bjtuss.bjtuselfservice.shared.DesktopAgentUiSmoke() else if ("--new-migration-smoke" in args) team.bjtuss.bjtuselfservice.shared.feature.shell.NewMigrationUiProbe() else if ("--schedule-events-smoke" in args) team.bjtuss.bjtuselfservice.shared.DesktopScheduleEventsSmoke() else DesktopSyncStatusSmoke()
            }
        }
        return
    }
    val accountSecurityStore = createDesktopAccountSecurityStore()
    val cacheStoreHandle = createDesktopCacheStore()
    // 刷新执行者：持锁并开放本机 IPC，命令行 `cache update` 交给当前会话执行，避免两边各登录一次互相顶掉会话。
    // 后台命令行正在刷新时先请它交出执行权（最多等几秒），拿不到也照常启动。
    val currentSession = java.util.concurrent.atomic.AtomicReference<AuthenticatedSession?>(null)
    appRunningLock = team.bjtuss.bjtuselfservice.shared.agent.host.AppRefreshHost.start(
        File(System.getProperty("user.home"), "Library/Application Support/BJTUselfServiceKMP"),
        sessionProvider = currentSession::get,
    )

    val remoteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val remoteController = RemoteAgentController(
        store = RemoteAgentStore(cacheStoreHandle.store),
        secrets = VaultRemoteSecretStore(requireNotNull(accountSecurityStore.remoteVault)),
        executor = AppSessionRemoteExecutor(currentSession::get, cacheStoreHandle.store),
        scope = remoteScope,
        host = object : RemoteHostPlatform {
            override val usesDockForBackground = true
            override val deviceName = "${System.getProperty("user.name", "本机")} 的 Mac（macOS）".take(64)
        },
    )
    if (cacheStoreHandle.state == team.bjtuss.bjtuselfservice.shared.cache.CacheOpenState.OPENED) remoteController.start()
    // 关闭“后台定时同步”时，像手机端一样在每次 App 内刷新完成后上传。
    val sessionUpdates = kotlinx.coroutines.flow.MutableStateFlow<AuthenticatedSession?>(null)
    remoteController.followAppRefreshes(sessionUpdates)

    // 开机登录项带 --background 启动：用户开了静默启动就不弹窗口，点程序坞图标再打开（同一个窗口）。
    val startupHost = MacLaunchAgentStartupHost()
    val startHidden = shouldStartHidden(args, startupHost.isSilentStart(), canReopen = Desktop.isDesktopSupported())

    try {
        application {
            val state = rememberWindowState(width = 1080.dp, height = 720.dp)
            val appCommandBus = remember { AppCommandBus() }
            val lifecycle = remember { DesktopWindowLifecycle() }
            val shellSubscribers by appCommandBus.subscriptionCount.collectAsState()
            val shellReady = shellSubscribers > 0
            val desktop = remember {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            }
            val authenticatedSession = remember { mutableStateOf<AuthenticatedSession?>(null) }
            val windowVisible = remember { mutableStateOf(!startHidden) }
            val reopenListener = remember(lifecycle) {
                AppReopenedListener {
                    // 静默启动时窗口内容可能尚未挂上生命周期句柄：改由 Compose 的可见状态显示同一个窗口。
                    if (!lifecycle.reopenWindow()) windowVisible.value = true
                    authenticatedSession.value?.notifyAppBecameActive()
                }
            }

            DisposableEffect(desktop, reopenListener) {
                desktop?.addAppEventListener(reopenListener)
                onDispose { desktop?.removeAppEventListener(reopenListener) }
            }

            Window(
                onCloseRequest = { lifecycle.closeWindow() },
                title = "交大自由行 KMP",
                state = state,
                // 只决定首次是否显示；之后的隐藏/显示仍由 DesktopWindowLifecycle 直接操作这一个窗口。
                visible = windowVisible.value,
            ) {
                val windowHandle = remember(window, desktop) {
                    object : DesktopWindowHandle {
                        override fun hide() {
                            window.isVisible = false
                        }

                        override fun showAndFocus() {
                            window.isVisible = true
                            window.toFront()
                            window.requestFocus()
                            desktop?.requestForeground(true)
                        }
                    }
                }
                DisposableEffect(lifecycle, windowHandle) {
                    lifecycle.attach(windowHandle)
                    onDispose { lifecycle.detach(windowHandle) }
                }
                val focusListener = remember(window) {
                    object : WindowFocusListener {
                        override fun windowGainedFocus(event: WindowEvent) {
                            authenticatedSession.value?.notifyAppBecameActive()
                        }

                        override fun windowLostFocus(event: WindowEvent) {
                            authenticatedSession.value?.notifyAppBecameInactive()
                        }
                    }
                }
                DisposableEffect(window, focusListener) {
                    window.addWindowFocusListener(focusListener)
                    onDispose { window.removeWindowFocusListener(focusListener) }
                }
                LaunchedEffect(window) {
                    registerDesktopCredentialWindowHandle(window.windowHandle)
                }
                DisposableEffect(window) {
                    onDispose { registerDesktopCredentialWindowHandle(0L) }
                }
                    MenuBar {
                        // 与壳层一级入口一致；考试/课件/教室/邮箱/设置走「更多」或菜单二级。
                        Menu("前往") {
                            Item("首页", onClick = { appCommandBus.send(AppCommand.NAVIGATE_HOME) }, enabled = shellReady)
                            Item("课程表", onClick = { appCommandBus.send(AppCommand.NAVIGATE_SCHEDULE) }, enabled = shellReady)
                            Item("成绩", onClick = { appCommandBus.send(AppCommand.NAVIGATE_GRADES) }, enabled = shellReady)
                            Item("作业", onClick = { appCommandBus.send(AppCommand.NAVIGATE_HOMEWORK) }, enabled = shellReady)
                            Separator()
                            Item("考试安排", onClick = { appCommandBus.send(AppCommand.NAVIGATE_EXAMS) }, enabled = shellReady)
                            Item("课件", onClick = { appCommandBus.send(AppCommand.NAVIGATE_COURSEWARE) }, enabled = shellReady)
                            Item("课程回放", onClick = { appCommandBus.send(AppCommand.NAVIGATE_PLAYBACK) }, enabled = shellReady)
                            Item("教室查询", onClick = { appCommandBus.send(AppCommand.NAVIGATE_CLASSROOM_OCCUPANCY) }, enabled = shellReady)
                            Item("邮箱", onClick = { appCommandBus.send(AppCommand.NAVIGATE_MAILBOX) }, enabled = shellReady)
                            Separator()
                            Item(
                                "设置…",
                                onClick = { appCommandBus.send(AppCommand.NAVIGATE_SETTINGS) },
                                shortcut = KeyShortcut(Key.Comma, meta = true),
                                enabled = shellReady,
                            )
                        }
                        Menu("数据") {
                            Item(
                                "刷新当前页面",
                                onClick = { appCommandBus.send(AppCommand.REFRESH_CURRENT) },
                                shortcut = KeyShortcut(Key.R, meta = true),
                                enabled = shellReady,
                            )
                        }
                    }
                    LaunchedEffect(Unit) {
                        window.minimumSize = Dimension(720, 520)
                    }
                    val homeworkFileGateway = remember(window) {
                        DesktopHomeworkFileGateway { window }
                    }
                    val systemCalendarGateway = remember { DesktopSystemCalendarGateway() }
                    CompositionLocalProvider(LocalAppStartupHost provides startupHost, LocalRemoteAgentController provides remoteController) {
                    App(
                        accountSecurityStore = accountSecurityStore,
                        cacheStoreHandle = cacheStoreHandle,
                        homeworkFileGateway = homeworkFileGateway,
                        coursewareDirectoryGateway = homeworkFileGateway,
                        systemCalendarGateway = systemCalendarGateway,
                        appCommandBus = appCommandBus,
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
        remoteController.close()
        remoteScope.cancel()
        runCatching { appRunningLock?.close() }
        cacheStoreHandle.store.close()
    }
}
