package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStoreHandle
import team.bjtuss.bjtuselfservice.shared.feature.shell.AppCommandBus
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityStore
import team.bjtuss.bjtuselfservice.shared.files.HomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.UnavailableHomeworkFileGateway
import team.bjtuss.bjtuselfservice.shared.files.CoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.UnavailableCoursewareDirectoryGateway
import team.bjtuss.bjtuselfservice.shared.system.rememberPlatformIncreasedContrast
import team.bjtuss.bjtuselfservice.shared.system.rememberPlatformFontScale
import team.bjtuss.bjtuselfservice.shared.system.rememberPlatformReduceMotion
import team.bjtuss.bjtuselfservice.shared.system.rememberPlatformReduceTransparency
import team.bjtuss.bjtuselfservice.shared.auth.CaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.auth.UnavailableCaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.calendar.UnavailableSystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.webview.openExternalUrl

@Composable
fun App(
    accountSecurityStore: AccountSecurityStore,
    cacheStoreHandle: CacheStoreHandle,
    homeworkFileGateway: HomeworkFileGateway = UnavailableHomeworkFileGateway,
    coursewareDirectoryGateway: CoursewareDirectoryGateway = UnavailableCoursewareDirectoryGateway,
    systemCalendarGateway: SystemCalendarGateway = UnavailableSystemCalendarGateway,
    appCommandBus: AppCommandBus? = null,
    captchaRecognizer: CaptchaRecognizer = UnavailableCaptchaRecognizer,
    nativeNavigationEnabled: Boolean = false,
    /** M17：一级入口由宿主原生 tab 容器承载，本组合只负责产出登录与会话。 */
    nativeTabBarEnabled: Boolean = false,
    onOpenNativeRoute: (String) -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = ::openExternalUrl,
    onAuthenticatedSessionChanged: (AuthenticatedSession?) -> Unit = {},
) {
    val cacheStore = cacheStoreHandle.store
    var appPreferences by remember(cacheStore) {
        mutableStateOf(runCatching(cacheStore::preferences).getOrDefault(AppPreferences()))
    }
    // 浅深色始终跟随系统；Android 可按 preferences.dynamicColor 启用 Material You 动态取色。
    val useDarkTheme = isSystemInDarkTheme()
    val onPreferencesChanged: (AppPreferences) -> Boolean = { updated ->
        runCatching { cacheStore.savePreferences(updated) }.isSuccess.also { saved ->
            if (saved) appPreferences = updated
        }
    }

    PlatformAppTheme(
        useDarkTheme = useDarkTheme,
        dynamicColorEnabled = appPreferences.dynamicColor,
    ) { effectiveFontScale ->
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                LoginRoute(
                    platform = currentPlatform(),
                    windowClass = adaptiveWindowClassFor(
                        widthDp = maxWidth.value.toInt(),
                        fontScale = effectiveFontScale,
                    ),
                    accountSecurityStore = accountSecurityStore,
                    cacheStoreHandle = cacheStoreHandle,
                    homeworkFileGateway = homeworkFileGateway,
                    coursewareDirectoryGateway = coursewareDirectoryGateway,
                    systemCalendarGateway = systemCalendarGateway,
                    appCommandBus = appCommandBus,
                    appPreferences = appPreferences,
                    onPreferencesChanged = onPreferencesChanged,
                    captchaRecognizer = captchaRecognizer,
                    nativeNavigationEnabled = nativeNavigationEnabled,
                    nativeTabBarEnabled = nativeTabBarEnabled,
                    onOpenNativeRoute = onOpenNativeRoute,
                    onOpenExternalUrl = onOpenExternalUrl,
                    onAuthenticatedSessionChanged = onAuthenticatedSessionChanged,
                )
            }
        }
    }
}

/** 平台 Activity/UIViewController 中渲染单个原生导航目的地。 */
@Composable
fun AuthenticatedDestinationApp(
    session: AuthenticatedSession,
    routeId: String,
    homeworkFileGateway: HomeworkFileGateway = session.homeworkFileGateway,
    coursewareDirectoryGateway: CoursewareDirectoryGateway = session.coursewareDirectoryGateway,
    nativeTabBarEnabled: Boolean = false,
    useNativeTitleBar: Boolean = false,
    onOpenNativeRoute: (String) -> Unit,
    onCloseNativeRoute: () -> Unit,
    onSelectNativeTab: (String) -> Unit = {},
    onNativeTitleChanged: (String) -> Unit = {},
    onNativeActionChanged: (team.bjtuss.bjtuselfservice.shared.feature.shell.NativeBarAction?) -> Unit = {},
    onOpenExternalUrl: (String) -> Unit = ::openExternalUrl,
) {
    // 跟设置页开关同一 StateFlow，切换动态取色时原生二级页也会立刻换色。
    val settingsState by session.settingsModel.state.collectAsState()
    PlatformAppTheme(
        useDarkTheme = isSystemInDarkTheme(),
        dynamicColorEnabled = settingsState.preferences.dynamicColor,
    ) { effectiveFontScale ->
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                team.bjtuss.bjtuselfservice.shared.feature.shell.AuthenticatedAppShell(
                    session = session,
                    platform = currentPlatform(),
                    windowClass = adaptiveWindowClassFor(
                        widthDp = maxWidth.value.toInt(),
                        fontScale = effectiveFontScale,
                    ),
                    nativeNavigationEnabled = true,
                    nativeTabBarEnabled = nativeTabBarEnabled,
                    useNativeTitleBar = useNativeTitleBar,
                    onOpenNativeRoute = onOpenNativeRoute,
                    onSelectNativeTab = onSelectNativeTab,
                    onNativeTitleChanged = onNativeTitleChanged,
                    onNativeActionChanged = onNativeActionChanged,
                    onOpenExternalUrl = onOpenExternalUrl,
                    forcedRouteId = routeId,
                    onCloseNativeRoute = onCloseNativeRoute,
                    homeworkFileGatewayOverride = homeworkFileGateway,
                    coursewareDirectoryGatewayOverride = coursewareDirectoryGateway,
                )
            }
        }
    }
}

@Composable
private fun PlatformAppTheme(
    useDarkTheme: Boolean,
    dynamicColorEnabled: Boolean,
    content: @Composable (effectiveFontScale: Float) -> Unit,
) {
    val systemDensity = LocalDensity.current
    val effectiveFontScale = rememberPlatformFontScale(systemDensity.fontScale)
    val increasedContrast = rememberPlatformIncreasedContrast()
    val reduceMotion = rememberPlatformReduceMotion()
    val reduceTransparency = rememberPlatformReduceTransparency()
    val colorScheme = platformColorScheme(
        darkTheme = useDarkTheme,
        dynamicColorEnabled = dynamicColorEnabled,
        increasedContrast = increasedContrast,
    )
    CompositionLocalProvider(
        LocalDensity provides Density(systemDensity.density, effectiveFontScale),
        LocalReduceMotion provides reduceMotion,
        LocalReduceTransparency provides reduceTransparency,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            motionScheme = if (reduceMotion) ReducedMotionScheme else MotionScheme.standard(),
        ) {
            content(effectiveFontScale)
        }
    }
}

internal fun adaptiveWindowClassFor(widthDp: Int, fontScale: Float): WindowClass {
    val widthClass = windowClassFor(widthDp)
    return if (widthClass == WindowClass.Expanded && fontScale >= 1.5f) {
        WindowClass.Medium
    } else {
        widthClass
    }
}
