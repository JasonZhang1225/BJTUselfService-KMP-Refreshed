package team.bjtuss.bjtuselfservice.shared.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Headers as KtorHeaders
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

expect fun schoolHttpEngineFactory(): HttpClientEngineFactory<*>

fun createSchoolHttpTransport(): SchoolHttpTransport = KtorSchoolHttpTransport(
    engineFactory = schoolHttpEngineFactory(),
)

class KtorSchoolHttpTransport(
    private val engineFactory: HttpClientEngineFactory<*>,
) : SchoolHttpTransport {
    private var cookieStorage = AcceptAllCookiesStorage()
    private var client = newClient(cookieStorage, sessionScoped = true)
    private var rawClient = newClient(cookieStorage, sessionScoped = true, followRedirects = false)
    /**
     * 公开页旁路：无 Cookie、不进 [requestMutex]、更短超时。
     * 仅用于 bksy 校历等不依赖登录的页面；切勿用它拉 aa/CAS。
     */
    private val publicClient = newClient(AcceptAllCookiesStorage(), sessionScoped = false)
    /**
     * 登录后课表/作业/考试/首页会并行刷新，共享同一 Cookie jar。
     * Ktor AcceptAllCookiesStorage 非线程安全：并发 execute 会偶发 NETWORK 失败
     *（单独拉课表稳定成功，并发则大量失败——已用 LiveCourseScheduleProbe 复现）。
     * 串行化会话请求，换正确性；模块仍可并行编排，只是底层排队。
     */
    private val requestMutex = Mutex()

    companion object {
        /**
         * 与原 Android App 登录请求一致的浏览器标识。学校 CAS 对缺少
         * 浏览器 User-Agent 的验证码提交会判定“认证码错误”，必须保留。
         */
        const val SCHOOL_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36 Edg/142.0.0.0"
    }

    override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = requestMutex.withLock {
        executeOn(client, request)
    }

    override suspend fun executeWithoutRedirects(request: SchoolHttpRequest): SchoolHttpResponse =
        requestMutex.withLock {
            executeOn(rawClient, request)
        }

    override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse =
        // 故意不拿 requestMutex：公开页挂起不得堵住 aa 会话查询。
        executeOn(publicClient, request)

    override fun clearSession() {
        client.close()
        rawClient.close()
        cookieStorage = AcceptAllCookiesStorage()
        client = newClient(cookieStorage, sessionScoped = true)
        rawClient = newClient(cookieStorage, sessionScoped = true, followRedirects = false)
    }

    private suspend fun executeOn(
        httpClient: HttpClient,
        request: SchoolHttpRequest,
    ): SchoolHttpResponse {
        try {
            val response = httpClient.request(request.url) {
                method = when (request.method) {
                    SchoolHttpMethod.GET -> HttpMethod.Get
                    SchoolHttpMethod.POST -> HttpMethod.Post
                }
                headers {
                    request.headers.forEach { (name, value) ->
                        // ByteArrayContent 会根据 rawBodyContentType 写入 Content-Type；
                        // 跳过调用方重复提供的同名头，避免 Ktor 合并出两个 Content-Type。
                        if (request.rawBody == null || !name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                            append(name, value)
                        }
                    }
                }
                if (request.rawBody != null) {
                    setBody(
                        ByteArrayContent(
                            bytes = request.rawBody,
                            contentType = ContentType.parse(request.rawBodyContentType),
                        ),
                    )
                } else if (request.multipartFiles.isNotEmpty()) {
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                request.formFields.forEach { (name, value) ->
                                    append(name, value)
                                }
                                request.multipartFiles.forEach { file ->
                                    append(
                                        file.fieldName,
                                        file.bytes,
                                        KtorHeaders.build {
                                            append(
                                                HttpHeaders.ContentDisposition,
                                                "filename=\"${file.fileName.safeMultipartFileName()}\"",
                                            )
                                            append(HttpHeaders.ContentType, file.contentType)
                                        },
                                    )
                                }
                            },
                        ),
                    )
                } else if (request.formFields.isNotEmpty()) {
                    setBody(
                        FormDataContent(
                            Parameters.build {
                                request.formFields.forEach { (name, value) -> append(name, value) }
                            },
                        ),
                    )
                }
            }
            return SchoolHttpResponse(
                statusCode = response.status.value,
                finalUrl = response.call.request.url.toString(),
                headers = response.headers.entries().associate { it.key to it.value },
                body = response.bodyAsBytes(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // 只记录可定位问题的形状信息：异常类型+消息、方法、脱敏 URL、是否 multipart、
            // 文件字节数。不记录文件名、正文、Cookie 或会话值。
            println(
                "SchoolHttpRequest failed: method=${request.method} " +
                    "url=${request.url.substringBefore('?')} " +
                    "multipart=${request.multipartFiles.isNotEmpty()} " +
                    "bytes=${request.multipartFiles.sumOf { it.bytes.size }} " +
                    "cause=${error::class.simpleName}: ${error.message?.take(160)}",
            )
            throw SchoolNetworkException("School request failed", error)
        }
    }

    override suspend fun sessionCookiesFor(url: String): List<SchoolSessionCookie> =
        requestMutex.withLock {
            cookieStorage.get(Url(url)).map { cookie ->
                SchoolSessionCookie(
                    name = cookie.name,
                    value = cookie.value,
                    path = cookie.path ?: "/",
                    secure = cookie.secure,
                )
            }
        }

    private fun newClient(
        storage: AcceptAllCookiesStorage,
        sessionScoped: Boolean,
        followRedirects: Boolean = true,
    ): HttpClient = HttpClient(engineFactory) {
        this.followRedirects = followRedirects
        install(HttpCookies) {
            this.storage = storage
        }
        install(UserAgent) {
            agent = SCHOOL_USER_AGENT
        }
        install(HttpTimeout) {
            if (sessionScoped) {
                // 智慧教学平台在递归拉取课件资源树时响应较慢（真实观察到个别子
                // 文件夹请求触发默认超时）；给明文旧链路与常规请求留出足够余量。
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
            } else {
                // 公开页（如 bksy 校历）：代理下挂起时尽快失败，别拖业务 UI。
                requestTimeoutMillis = 8_000
                connectTimeoutMillis = 5_000
                socketTimeoutMillis = 8_000
            }
        }
    }
}

private fun String.safeMultipartFileName(): String =
    substringAfterLast('/').substringAfterLast('\\').replace('"', '_').ifBlank { "upload.bin" }
