package team.bjtuss.bjtuselfservice.shared.data.classroom

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import team.bjtuss.bjtuselfservice.shared.domain.classroom.ClassroomBuildingInfo
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpMethod
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpRequest
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpResponse
import team.bjtuss.bjtuselfservice.shared.network.SchoolHttpTransport

/**
 * v1.7.0 的既有第三方明文 HTTP 接口（见冻结 Android 工程 `ApiConstant`）。
 * 公开、无需学校登录或任何凭据。已实测（2026-07-30）：
 * - `http://yaya.csoci.com:2333/api/classnum/?building=思源楼` 返回 200。
 * - 同 origin 的 https 不可达（TLS 握手被服务器拒绝），不存在可替代的加密端点。
 *
 * 安全边界（CLAUDE.md 网络与安全红线）：
 * - 只构造并允许该精确 origin 的请求；不自动跟随重定向。3xx 或最终 URL
 *   离开该 origin 即失败，避免中间人对明文响应注入 302 后再检查 finalUrl。
 * - iOS 仅对 `yaya.csoci.com` 添加域名级 ATS 例外，绝不使用
 *   `NSAllowsArbitraryLoads`；ATS 无法约束端口和路径，因此仍由本数据源强制锁定
 *   `:2333/api/classnum/`，且使用独立 transport，不携带学校登录 Cookie。
 */
const val CLASSROOM_CAPACITY_ORIGIN = "http://yaya.csoci.com:2333"

private const val CLASSROOM_CAPACITY_PATH = "/api/classnum/"

enum class ClassroomRemoteFailure {
    /** 网络层失败（不可达、超时、非 2xx、重定向出白名单 origin）。 */
    NETWORK,

    /** 响应结构不符合预期。 */
    PARSE,

    /**
     * 系统安全策略（如 Apple ATS）阻止明文 HTTP 通道。
     * 与 NETWORK 区分，界面给出“第三方明文接口不可用”的明确说明。
     */
    SECURE_CHANNEL_UNAVAILABLE,
}

class ClassroomRemoteException(
    val reason: ClassroomRemoteFailure,
) : Exception("Classroom request failed: ${reason.name}")

interface ClassroomRemoteDataSource {
    suspend fun fetchBuildingInfo(buildingName: String): ClassroomBuildingInfo
}

/**
 * 当前平台是否允许访问精确的第三方明文教室接口。
 * iOS/desktop 仅在本数据源的精确 origin 边界内允许；Android 仍保持 cleartext 禁用。
 */
expect val classroomLegacyHttpAvailable: Boolean

class SchoolClassroomRemoteDataSource(
    private val transport: SchoolHttpTransport,
    private val legacyHttpAvailable: Boolean = classroomLegacyHttpAvailable,
) : ClassroomRemoteDataSource {

    override suspend fun fetchBuildingInfo(buildingName: String): ClassroomBuildingInfo {
        require(buildingName.isNotBlank()) { "building name must not be blank" }
        if (!legacyHttpAvailable) secureChannel()
        // 教学楼名是中文，必须百分号编码进查询参数。
        val url = "$CLASSROOM_CAPACITY_ORIGIN$CLASSROOM_CAPACITY_PATH?building=" +
            buildingName.encodeURLParameter()
        val response = executeWithoutRedirects(
            SchoolHttpRequest(
                method = SchoolHttpMethod.GET,
                url = url,
                headers = mapOf("Accept" to "application/json;q=0.9,*/*;q=0.5"),
            ),
        )
        if (response.statusCode in 300..399) network()
        if (response.statusCode !in 200..299) network()
        // 不自动跟随。3xx 或最终 URL 离开精确 origin 都视为安全边界违反，
        // 避免中间人把明文 yaya 请求 302 到任意主机之后再检查 finalUrl。
        if (!response.finalUrl.isAllowedClassroomUrl()) network()
        return when (val parsed = parseClassroomCapacityJson(buildingName, response.bodyText())) {
            is ClassroomJsonParseResult.Failure -> parse()
            is ClassroomJsonParseResult.Success -> parsed.info
        }
    }

    private suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse = try {
        transport.executeWithoutRedirects(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (error.isSecureChannelBlocked()) secureChannel() else network()
    }
}

/**
 * 只允许精确 origin `http://yaya.csoci.com:2333`（显式默认端口 `http://yaya.csoci.com:2333`）。
 * 不允许 https（服务器不支持 TLS）、不允许其他主机、不允许隐含端口形式逃逸。
 */
internal fun String.isAllowedClassroomUrl(): Boolean {
    if (!startsWith("http://", ignoreCase = true)) return false
    val authority = removePrefix("http://")
        .substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').lowercase()
    return authority == "yaya.csoci.com:2333"
}

/**
 * 识别 Apple ATS 类安全策略拒绝。共享层只能看异常消息形态，平台 transport 若能把
 * ATS 错误显式包装，应优先使用显式错误类型；这里作为兜底识别，命中时归为
 * 安全通道不可用而非普通网络失败。
 */
private fun Throwable.isSecureChannelBlocked(): Boolean {
    val chain = generateSequence(this) { it.cause }
    return chain.any { error ->
        val message = error.message.orEmpty()
        message.contains("App Transport Security", ignoreCase = true) ||
            message.contains("ATS", ignoreCase = false) && message.contains("cleartext", ignoreCase = true) ||
            message.contains("cleartext", ignoreCase = true) && message.contains("not permitted", ignoreCase = true)
    }
}

private fun network(): Nothing = throw ClassroomRemoteException(ClassroomRemoteFailure.NETWORK)
private fun parse(): Nothing = throw ClassroomRemoteException(ClassroomRemoteFailure.PARSE)
private fun secureChannel(): Nothing =
    throw ClassroomRemoteException(ClassroomRemoteFailure.SECURE_CHANNEL_UNAVAILABLE)
