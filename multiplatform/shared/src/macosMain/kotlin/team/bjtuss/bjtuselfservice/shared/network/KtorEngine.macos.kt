package team.bjtuss.bjtuselfservice.shared.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

/** macOS 原生端用 Darwin 引擎（URLSession），与 iOS 端同一套实现。 */
actual fun schoolHttpEngineFactory(): HttpClientEngineFactory<*> = Darwin
