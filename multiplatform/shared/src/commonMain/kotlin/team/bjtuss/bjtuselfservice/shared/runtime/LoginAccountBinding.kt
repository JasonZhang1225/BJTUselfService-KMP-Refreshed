package team.bjtuss.bjtuselfservice.shared.runtime

import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.PlatformInfo
import team.bjtuss.bjtuselfservice.shared.auth.CaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.calendar.SystemCalendarGateway
import team.bjtuss.bjtuselfservice.shared.files.DirectoryGateway
import team.bjtuss.bjtuselfservice.shared.files.FileGateway
import team.bjtuss.bjtuselfservice.shared.feature.courseware.PlaybackRecoveryAccess
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator

/** Publishes a login result directly into the retained runtime; no UI lifecycle is involved. */
internal fun bindAuthenticatedSession(
    runtime: AppRuntime,
    profile: StudentProfile,
    entryLoggingIn: Boolean,
    transport: SchoolHttpTransport,
    loginProtocol: SchoolLoginProtocol,
    cacheStore: CacheStore,
    platform: PlatformInfo,
    appPreferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Boolean,
    securityCoordinator: AccountSecurityCoordinator,
    captchaRecognizer: CaptchaRecognizer,
    username: String,
    password: String,
    fileGateway: FileGateway,
    directoryGateway: DirectoryGateway,
    systemCalendarGateway: SystemCalendarGateway,
    onLogout: (String) -> Unit,
    onPurgeLogout: (String) -> Unit,
    playbackRecovery: PlaybackRecoveryAccess? = null,
): AuthenticatedSession {
    val account = runtime.obtainAccountSession(
        profile = profile,
        entryLoggingIn = entryLoggingIn,
        transport = transport,
        loginProtocol = loginProtocol,
        cacheStore = cacheStore,
        platform = platform,
        preferences = appPreferences,
        onPreferencesChanged = onPreferencesChanged,
        securityCoordinator = securityCoordinator,
        captchaRecognizer = captchaRecognizer,
        credentials = Credentials(username.trim(), password),
        fileGateway = fileGateway,
        playbackRecovery = playbackRecovery,
        directoryGateway = directoryGateway,
        systemCalendarGateway = systemCalendarGateway,
        onLogout = onLogout,
        onPurgeLogout = onPurgeLogout,
    )
    account.update(
        profile = profile,
        entryLoggingIn = entryLoggingIn,
        credentials = Credentials(username.trim(), password),
        preferences = appPreferences,
        onPreferencesChanged = onPreferencesChanged,
        onLogout = onLogout,
        onPurgeLogout = onPurgeLogout,
    )
    return account.presentation
}
