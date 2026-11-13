package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import team.bjtuss.bjtuselfservice.shared.currentPlatform
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.cache.CacheStore
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabSessionResult
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabManualLogin
import team.bjtuss.bjtuselfservice.shared.feature.settings.IndependentAccountSettings
import team.bjtuss.bjtuselfservice.shared.security.CredentialVault

data class PhyVlabAccountState(
    val username: String = "",
    val configured: Boolean = false,
    val saving: Boolean = false,
    val failed: Boolean = false,
    val message: String? = null,
)

/**
 * 物理在线独立账号（与物理实验、演示实验同一套逻辑）：密码只进系统安全存储，
 * 普通缓存只记“已配置”和用户名。保存前先用新账号真实登录一次，错误账号不会覆盖旧配置。
 */
class PhyVlabAccountModel(
    private val scope: String,
    private val cache: CacheStore,
    private val vault: CredentialVault?,
    /** 用给定账号登录物理在线；由会话协议提供，校验成功即建立了 MoodleSession。 */
    private val rememberSession: suspend (String) -> Unit = {},
    private val clearSession: suspend () -> Unit = {},
    private val signIn: suspend (Credentials) -> PhyVlabSessionResult,
) {
    private val mutableState = MutableStateFlow(PhyVlabAccountState(username = scope))
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var initialized = false

    suspend fun initialize() = mutex.withLock {
        if (initialized) return@withLock
        val configured = cache.metadata(scope, CONFIGURED_KEY) != "false"
        val creds = try {
            if (configured) vault?.load() else null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (creds?.isValid == true && cache.metadata(scope, CONFIGURED_KEY) == null) {
            cache.putMetadata(scope, CONFIGURED_KEY, "true")
            cache.putMetadata(scope, USERNAME_KEY, creds.username)
        }
        mutableState.value = PhyVlabAccountState(
            username = creds?.username ?: cache.metadata(scope, USERNAME_KEY) ?: scope,
            configured = creds?.isValid == true,
        )
        initialized = true
    }

    /** 会话协议在 MoodleSession 缺失时读取；未配置或读取失败返回 null。 */
    suspend fun credentials(): Credentials? {
        initialize()
        if (!state.value.configured) return null
        return try {
            vault?.load()?.takeIf { it.isValid }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    internal suspend fun configurationCredentials(username: String, password: String): Credentials? {
        initialize()
        return try {
            val storage = vault ?: throw PhyVlabAccountFailure("当前平台安全存储不可用。")
            val creds = if (password.isNotBlank()) {
                Credentials(username.trim(), password)
            } else {
                storage.load()?.takeIf { it.username == username.trim() }
            }
            creds?.takeIf { it.isValid }
                ?: throw PhyVlabAccountFailure("请填写物理在线的账号和密码。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showError((e as? PhyVlabAccountFailure)?.message ?: "无法读取物理在线账号。")
            null
        }
    }

    internal sealed interface ConfigureOutcome {
        data object Saved : ConfigureOutcome
        data class CaptchaRequired(val credentials: Credentials) : ConfigureOutcome
        data object Failed : ConfigureOutcome
    }

    suspend fun configure(username: String, password: String): Boolean =
        configureWithOutcome(username, password, showCaptchaError = true) == ConfigureOutcome.Saved

    internal suspend fun configureOrRequireCaptcha(username: String, password: String): ConfigureOutcome =
        configureWithOutcome(username, password, showCaptchaError = false)

    private suspend fun configureWithOutcome(
        username: String,
        password: String,
        showCaptchaError: Boolean,
    ): ConfigureOutcome {
        val creds = configurationCredentials(username, password) ?: return ConfigureOutcome.Failed
        var captchaRequired = false
        val saved = saveConfiguration(creds) {
            when (val result = withTimeout(40_000) { signIn(creds) }) {
                is PhyVlabSessionResult.Ready -> Unit
                PhyVlabSessionResult.CaptchaRequired, PhyVlabSessionResult.CaptchaRejected -> {
                    captchaRequired = true
                    throw PhyVlabAccountFailure(PHYVLAB_CAPTCHA_REQUIRED_MESSAGE)
                }
                PhyVlabSessionResult.CredentialsRejected, PhyVlabSessionResult.AccountRequired ->
                    throw PhyVlabAccountFailure("物理在线账号或密码错误。")
                is PhyVlabSessionResult.Failed -> throw PhyVlabAccountFailure("无法连接物理在线，请稍后重试。")
                    .also { phyVlabAccountDebug(result) }
            }
        }
        return when {
            saved -> ConfigureOutcome.Saved
            captchaRequired -> {
                if (!showCaptchaError) {
                    mutableState.value = state.value.copy(failed = false, message = null)
                }
                ConfigureOutcome.CaptchaRequired(creds)
            }
            else -> ConfigureOutcome.Failed
        }
    }

    internal suspend fun saveVerifiedConfiguration(creds: Credentials): Boolean =
        saveConfiguration(creds) {}

    internal fun showError(message: String) {
        mutableState.value = state.value.copy(failed = true, message = message)
    }

    private suspend fun saveConfiguration(creds: Credentials, verify: suspend () -> Unit): Boolean =
        mutex.withLock {
            mutableState.value = state.value.copy(saving = true, failed = false, message = null)
            try {
                val storage = vault ?: throw PhyVlabAccountFailure("当前平台安全存储不可用。")
                verify()
                try {
                    storage.save(creds)
                    cache.putMetadata(scope, USERNAME_KEY, creds.username)
                    cache.putMetadata(scope, CONFIGURED_KEY, "true")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 验证或采用会话后持久化失败时，清除未归属该账号的会话，避免后续刷新串号。
                    try {
                        clearSession()
                    } catch (clearError: CancellationException) {
                        throw clearError
                    } catch (_: Exception) {
                    }
                    throw e
                }
                rememberSession(creds.username)
                mutableState.value = PhyVlabAccountState(creds.username, configured = true, message = "已登录物理在线并保存账号。")
                true
            } catch (_: TimeoutCancellationException) {
                showError("连接物理在线超时，请稍后重试。")
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError((e as? PhyVlabAccountFailure)?.message ?: "物理在线登录或安全保存失败。")
                false
            } finally {
                mutableState.value = state.value.copy(saving = false)
            }
        }

    internal suspend fun clearSessionForTesting(): Boolean = mutex.withLock {
        try {
            clearSession()
            mutableState.value = state.value.copy(
                failed = false,
                message = "已退出本机物理在线会话。账号已保留，可重新验证。",
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            showError("退出物理在线失败，请重试。")
            false
        }
    }

    suspend fun clearConfiguration() {
        initialize()
        mutex.withLock {
            try {
                clearSession()
                vault?.clear()
                cache.putMetadata(scope, CONFIGURED_KEY, "false")
                mutableState.value = PhyVlabAccountState(username = state.value.username, message = "已清除物理在线密码。")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(failed = true, message = "清除失败，请重试。")
            }
        }
    }

    companion object {
        const val CONFIGURED_KEY = "phyvlab.configured"
        const val USERNAME_KEY = "phyvlab.username"
    }
}

private class PhyVlabAccountFailure(message: String) : Exception(message)

private fun phyVlabAccountDebug(result: PhyVlabSessionResult.Failed) {
    team.bjtuss.bjtuselfservice.shared.data.phyvlab.phyVlabDebug(
        "account verify failed reason=${result.reason} detail=${result.detail ?: "none"}",
    )
}

@Composable
internal fun PhyVlabAccountSettingsScreen(
    model: PhyVlabAccountModel,
    prepareManualLogin: suspend () -> PhyVlabManualLogin,
    modifier: Modifier = Modifier,
    onAccountSaved: () -> Unit = {},
    onTestSessionCleared: () -> Unit = {},
) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    var username by remember(model) { mutableStateOf("") }
    var password by remember(model) { mutableStateOf("") }
    var ready by remember(model) { mutableStateOf(false) }
    var clearingTestSession by remember(model) { mutableStateOf(false) }
    var accountToVerify by remember(model) { mutableStateOf<Credentials?>(null) }
    LaunchedEffect(model) {
        model.initialize()
        username = model.state.value.username
        ready = true
    }
    IndependentAccountSettings(
        username, password, state.configured && username.trim() == state.username,
        ready, state.saving || clearingTestSession, { username = it }, { password = it },
        onSave = {
            scope.launch {
                when (val outcome = model.configureOrRequireCaptcha(username, password)) {
                    PhyVlabAccountModel.ConfigureOutcome.Saved -> {
                        password = ""
                        onAccountSaved()
                    }
                    is PhyVlabAccountModel.ConfigureOutcome.CaptchaRequired -> {
                        accountToVerify = outcome.credentials
                    }
                    PhyVlabAccountModel.ConfigureOutcome.Failed -> Unit
                }
            }
        },
        message = state.message, failed = state.failed, modifier = modifier,
        websiteUrl = "https://phyvlab.bjtu.edu.cn/",
        canClear = state.configured,
        onClear = { scope.launch { model.clearConfiguration(); password = "" } },
        showSavingDialog = false,
        additionalActions = {
            if (currentPlatform().isDebugBuild && state.configured) {
                TextButton(
                    enabled = ready && !state.saving && !clearingTestSession && accountToVerify == null,
                    onClick = {
                        clearingTestSession = true
                        scope.launch {
                            try {
                                if (model.clearSessionForTesting()) onTestSessionCleared()
                            } finally {
                                clearingTestSession = false
                            }
                        }
                    },
                ) { Text("退出物理在线会话（调试）") }
            }
        },
    )
    accountToVerify?.let { account ->
        PhyVlabCaptchaSheet(
            account = account,
            prepare = prepareManualLogin,
            onSuccess = {
                if (!model.saveVerifiedConfiguration(account)) false
                else {
                    accountToVerify = null
                    password = ""
                    onAccountSaved()
                    true
                }
            },
            onRejected = { model.showError("物理在线账号或密码错误，请检查后重试。") },
            onDismiss = { accountToVerify = null },
        )
    }
}
