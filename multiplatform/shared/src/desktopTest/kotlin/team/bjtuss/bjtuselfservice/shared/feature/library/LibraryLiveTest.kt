package team.bjtuss.bjtuselfservice.shared.feature.library

import java.io.File
import kotlinx.coroutines.runBlocking
import team.bjtuss.bjtuselfservice.shared.auth.Credentials
import team.bjtuss.bjtuselfservice.shared.data.library.DefaultLibraryRepository
import team.bjtuss.bjtuselfservice.shared.data.library.LibraryLoginProtocol
import team.bjtuss.bjtuselfservice.shared.data.library.SchoolLibraryRemoteDataSource
import team.bjtuss.bjtuselfservice.shared.network.createSchoolHttpTransport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 图书馆真实链路联调（默认跳过，只在本机存在 `MisSecret.env` 时运行）。
 *
 * 安全约定：
 * - 凭据文件 `MisSecret.env` 已在 `.gitignore` 里；本测试只让它被测试进程读取，
 *   不打印、不写日志、不进入任何断言消息。
 * - 只做登录 + 读取（用户信息、当前借阅、历史借阅），**不调用续借**，不改变学校系统状态。
 * - 输出只有成功/失败、条数、字段是否可解析等脱敏结果。
 *
 * 文件格式（仓库根目录）：
 * ```
 * LIBRARY_TEST_USERNAME=你的学号
 * LIBRARY_TEST_PASSWORD=你的统一身份认证密码
 * ```
 * 运行：`./gradlew :shared:desktopTest --tests "*LibraryLiveTest*"`
 */
class LibraryLiveTest {

    @Test
    fun liveLibrarySessionReadsLoans() {
        val credentials = loadCredentials() ?: run {
            println("[LibraryLiveTest] 未找到 MisSecret.env，跳过真实链路联调。")
            return
        }
        val transport = createSchoolHttpTransport()
        val protocol = LibraryLoginProtocol(transport, credentialsProvider = { credentials })
        val repository = DefaultLibraryRepository(SchoolLibraryRemoteDataSource(transport), protocol)
        try {
            runBlocking {
                val failure = protocol.ensureSession()
                if (failure != null) {
                    println("[LibraryLiveTest] 登录未完成（不含凭据）：kind=${failure.kind}")
                    return@runBlocking
                }
                val user = repository.loadUser()
                val loans = repository.loadLoans()
                val history = repository.loadHistory()
                println(
                    "[LibraryLiveTest] ok cardStatus=${user.cardStatus} currentLoans=${loans.loans.size}" +
                        " history=${history.loans.size} total=${loans.total}",
                )
                assertTrue(user.cardStatus.isNotBlank())
            }
        } finally {
            runCatching { transport.clearSession() }
            (transport as? team.bjtuss.bjtuselfservice.shared.network.KtorSchoolHttpTransport)?.close()
        }
    }

    /** 只读凭据文件；任何分支都不输出用户名或密码。 */
    private fun loadCredentials(): Credentials? {
        val file = findCredentialsFile() ?: return null
        val values = runCatching {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .associate { line ->
                    line.substringBefore('=').trim() to line.substringAfter('=').trim().trim('"', '\'')
                }
        }.getOrDefault(emptyMap())
        val username = values["LIBRARY_TEST_USERNAME"] ?: values["BJTU_LIBRARY_TEST_USERNAME"] ?: return null
        val password = values["LIBRARY_TEST_PASSWORD"] ?: values["BJTU_LIBRARY_TEST_PASSWORD"] ?: return null
        return Credentials(username, password).takeIf { it.isValid }
    }

    private fun findCredentialsFile(): File? {
        var directory: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(4) {
            val candidate = directory?.resolve("MisSecret.env")
            if (candidate?.isFile == true) return candidate
            directory = directory?.parentFile
        }
        return null
    }
}
