package team.bjtuss.bjtuselfservice.shared.network

import android.net.http.X509TrustManagerExtensions
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class AndroidSchoolTlsTrustManager(
    delegate: X509TrustManager = systemDefaultX509TrustManager(),
    supplementaryIntermediates: List<X509Certificate> = defaultSchoolSupplementaryIntermediates(),
) : SchoolTlsTrustManager(delegate, supplementaryIntermediates) {
    private val delegateExtensions = X509TrustManagerExtensions(delegate)

    /**
     * OkHttp 的 AndroidCertificateChainCleaner 会将自定义 TrustManager 包装在
     * [X509TrustManagerExtensions] 中，并反射调用此 3 参数重载。
     */
    @Suppress("unused")
    fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        host: String,
    ): List<X509Certificate> = verifyWithCompletion(chain) { candidate ->
        delegateExtensions.checkServerTrusted(candidate, authType, host)
    }

    companion object {
        private val sharedDefault by lazy { AndroidSchoolTlsTrustManager() }

        fun default(): AndroidSchoolTlsTrustManager = sharedDefault
    }
}

private object SchoolOkHttpEngineFactory : HttpClientEngineFactory<OkHttpConfig> {
    override fun create(block: OkHttpConfig.() -> Unit): HttpClientEngine = OkHttp.create {
        config {
            val trustManager = AndroidSchoolTlsTrustManager.default()
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustManager), null)
            }
            sslSocketFactory(sslContext.socketFactory, trustManager)
        }
        block()
    }
}

actual fun schoolHttpEngineFactory(): HttpClientEngineFactory<*> = SchoolOkHttpEngineFactory
