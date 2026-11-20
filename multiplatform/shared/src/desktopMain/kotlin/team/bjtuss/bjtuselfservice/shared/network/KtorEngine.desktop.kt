package team.bjtuss.bjtuselfservice.shared.network

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.CIOEngineConfig

private object SchoolCioEngineFactory : HttpClientEngineFactory<CIOEngineConfig> {
    override fun create(block: CIOEngineConfig.() -> Unit): HttpClientEngine = CIO.create {
        https {
            trustManager = SchoolTlsTrustManager.default()
        }
        block()
    }
}

actual fun schoolHttpEngineFactory(): HttpClientEngineFactory<*> = SchoolCioEngineFactory
