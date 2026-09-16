package team.bjtuss.bjtuselfservice.shared.auth

/**
 * 在业务请求发现 CAS/教务会话过期时，复用当前进程中的凭据恢复学校会话。
 *
 * 这个恢复入口不改变登录页状态，因此刷新时成功恢复不会销毁当前页面；恢复成功后
 * 还要重新链接教务系统，确保 MIS/CAS 有效但 aa Cookie 已单独过期时也能继续工作。
 * 凭据只由调用方以内存闭包提供，本类不落盘、不记录其内容。
 */
class SchoolSessionRecovery(
    private val protocol: SchoolLoginProtocol,
    private val captchaRecognizer: CaptchaRecognizer,
    private val credentialsProvider: () -> Credentials?,
) {
    suspend fun attempt(): Boolean {
        val credentials = credentialsProvider()?.takeIf { it.isValid } ?: return false
        val gateway = object : LoginAutomationGateway {
            override suspend fun requestCaptchaChallenge(studentId: String): ChallengeResult =
                protocol.requestFreshCaptchaChallenge(studentId)

            override suspend fun authenticateMis(
                credentials: Credentials,
                challenge: CaptchaChallenge,
                captchaAnswer: String,
            ): AuthenticationResult = protocol.authenticateMis(credentials, challenge, captchaAnswer)
        }
        val result = try {
            AutomaticLoginCoordinator(
                gateway = gateway,
                captchaRecognizer = captchaRecognizer,
            ).login(credentials)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        return when (result) {
            is AutomaticLoginResult.SessionActive,
            is AutomaticLoginResult.Authenticated,
            -> protocol.linkAcademicSystem()
            is AutomaticLoginResult.ManualRequired -> false
        }
    }
}
