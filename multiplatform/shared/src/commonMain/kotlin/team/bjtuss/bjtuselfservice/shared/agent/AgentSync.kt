package team.bjtuss.bjtuselfservice.shared.agent

import kotlinx.coroutines.CancellationException
import team.bjtuss.bjtuselfservice.shared.auth.AutomaticLoginCoordinator
import team.bjtuss.bjtuselfservice.shared.auth.AutomaticLoginResult
import team.bjtuss.bjtuselfservice.shared.auth.AuthenticationResult
import team.bjtuss.bjtuselfservice.shared.auth.CaptchaChallenge
import team.bjtuss.bjtuselfservice.shared.auth.CaptchaRecognizer
import team.bjtuss.bjtuselfservice.shared.auth.ChallengeResult
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.auth.LoginAutomationGateway
import team.bjtuss.bjtuselfservice.shared.auth.LoginFailure
import team.bjtuss.bjtuselfservice.shared.auth.SchoolLoginProtocol
import team.bjtuss.bjtuselfservice.shared.auth.StudentProfile

sealed interface AgentLoginOutcome {
    data object Ok : AgentLoginOutcome
    /** 自动登录停下（需要验证码、密码被拒或响应不明确等），需要用户打开 App 手动登录。 */
    data class ManualRequired(val reason: LoginFailure) : AgentLoginOutcome
    data object Network : AgentLoginOutcome
}

/** 无窗口登录结果：成功时带学校返回的档案，用于装配与 App 相同的业务组合根。 */
data class HeadlessLoginResult(val outcome: AgentLoginOutcome, val profile: StudentProfile?)

/**
 * 无窗口宿主的登录：与 [team.bjtuss.bjtuselfservice.shared.auth.SchoolSessionRecovery] 相同的自动登录流程
 * （需要验证码时直接停下，密码最多提交 1 次），但保留失败原因，且不清除、不改写任何已保存的凭据。
 */
suspend fun headlessLogin(
    protocol: SchoolLoginProtocol,
    captchaRecognizer: CaptchaRecognizer,
    credentials: Credentials,
): HeadlessLoginResult {
    val gateway = object : LoginAutomationGateway {
        override suspend fun requestCaptchaChallenge(studentId: String): ChallengeResult =
            protocol.requestFreshCaptchaChallenge(studentId)

        override suspend fun authenticateMis(
            credentials: Credentials,
            challenge: CaptchaChallenge,
            captchaAnswer: String,
        ): AuthenticationResult = protocol.authenticateMis(credentials, challenge, captchaAnswer)
    }
    return try {
        when (val result = AutomaticLoginCoordinator(gateway, captchaRecognizer).login(credentials)) {
            is AutomaticLoginResult.SessionActive ->
                if (protocol.linkAcademicSystem()) HeadlessLoginResult(AgentLoginOutcome.Ok, result.profile)
                else HeadlessLoginResult(AgentLoginOutcome.Network, null)
            is AutomaticLoginResult.Authenticated ->
                if (protocol.linkAcademicSystem()) HeadlessLoginResult(AgentLoginOutcome.Ok, result.profile)
                else HeadlessLoginResult(AgentLoginOutcome.Network, null)
            is AutomaticLoginResult.ManualRequired ->
                if (result.reason == LoginFailure.NETWORK) HeadlessLoginResult(AgentLoginOutcome.Network, null)
                else HeadlessLoginResult(AgentLoginOutcome.ManualRequired(result.reason), null)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        HeadlessLoginResult(AgentLoginOutcome.Network, null)
    }
}
