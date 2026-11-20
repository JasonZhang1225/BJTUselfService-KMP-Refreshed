package team.bjtuss.bjtuselfservice.shared.runtime

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import team.bjtuss.bjtuselfservice.shared.AuthenticatedSession
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.CaptchaChallenge
import team.bjtuss.bjtuselfservice.shared.auth.LoginState
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityCoordinator
import team.bjtuss.bjtuselfservice.shared.security.AccountSecurityStore

/** Transitional login state owner. Contains no Activity, controller, window or navigation callbacks. */
internal class LoginRuntimeContext(
    store: AccountSecurityStore,
    val scope: CoroutineScope,
    transportFactory: () -> SchoolHttpTransport = ::createSchoolHttpTransport,
) {
    val transport = lazy(LazyThreadSafetyMode.NONE, transportFactory)
    val protocol = lazy(LazyThreadSafetyMode.NONE) { SchoolLoginProtocol(transport.value) }
    val securityCoordinator = AccountSecurityCoordinator(store)
    val state = mutableStateOf<LoginState>(LoginState.SignedOut)
    val username = mutableStateOf("")
    val password = mutableStateOf("")
    val captchaAnswer = mutableStateOf("")
    val rememberCredentials = mutableStateOf(securityCoordinator.canStoreCredentials)
    val storageReady = mutableStateOf(false)
    val logoutCleanupPending = mutableStateOf(false)
    val storageMessage = mutableStateOf<String?>(null)
    val postLoginStorageFailure = mutableStateOf<String?>(null)
    val automationMessage = mutableStateOf<String?>(null)
    val manualDialogChallenge = mutableStateOf<CaptchaChallenge?>(null)
    val manualDialogMessage = mutableStateOf<String?>(null)
    val autoEntryProfile = mutableStateOf<StudentProfile?>(null)
    val silentAutoLogin = mutableStateOf(false)
    val autoLoginFailureAttempts = mutableStateOf<Int?>(null)
    private var started = false
    private var authentication = AccountWork(scope.coroutineContext[Job])

    private var accountPublisher: ((StudentProfile, Boolean, Credentials) -> AuthenticatedSession)? = null

    /** Platform resources are rebound by a host; publication itself runs in login work. */
    fun bindAccountPublisher(publish: (StudentProfile, Boolean, Credentials) -> AuthenticatedSession) {
        accountPublisher = publish
    }

    fun publishAccount(profile: StudentProfile, entryLoggingIn: Boolean, credentials: Credentials): AuthenticatedSession {
        authentication.checkActive()
        return checkNotNull(accountPublisher) { "Login account resources are not bound" }(profile, entryLoggingIn, credentials)
    }

    /** Only an explicit login/settings event starts a new authentication incarnation. */
    fun launch(block: suspend () -> Unit): Job {
        if (!authentication.isActive) authentication = AccountWork(scope.coroutineContext[Job])
        val work = authentication
        return scope.launch { work.run(block) }
    }

    internal val authenticationWork: AccountWork get() = authentication

    fun stopAuthentication(): AccountWork = authentication.also { it.stop() }


    /** Root reconstruction must not restore credentials again or replay a password POST. */
    fun startOnce(block: suspend () -> Unit) {
        if (started) return
        started = true
        launch(block)
    }

    fun close() {
        authentication.stop()
        accountPublisher = null
        password.value = ""
        username.value = ""
        captchaAnswer.value = ""
        manualDialogChallenge.value = null
        state.value = LoginState.SignedOut
        if (transport.isInitialized()) transport.value.close()
    }
}
