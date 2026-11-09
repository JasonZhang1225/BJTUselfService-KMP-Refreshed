package team.bjtuss.bjtuselfservice.shared.feature.phyvlab

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import team.bjtuss.bjtuselfservice.shared.data.phyvlab.PhyVlabCaptchaProof

@Composable
internal actual fun PhyVlabCaptchaWeb(
    captchaId: String,
    onProof: (PhyVlabCaptchaProof) -> Unit,
    onUnavailable: () -> Unit,
) {
    var opened by remember { mutableStateOf(false) }
    Button(onClick = { opened = true }, enabled = !opened) { Text("在浏览器中打开验证码") }
    if (opened) Text("在浏览器中完成学校点选验证，应用会自动继续登录。")
    LaunchedEffect(opened, captchaId) {
        if (!opened) return@LaunchedEffect
        val proof = try {
            withContext(Dispatchers.IO) { openCaptchaPage(captchaId) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (proof == null) onUnavailable() else onProof(proof)
    }
}

/** One-time loopback endpoint. Only the challenge proof crosses this boundary. */
private suspend fun openCaptchaPage(captchaId: String): PhyVlabCaptchaProof? {
    val proof = CompletableDeferred<PhyVlabCaptchaProof>()
    val endpoint = "/captcha/${UUID.randomUUID()}"
    val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val authority = "127.0.0.1:${server.address.port}"
    val origin = "http://$authority"
    try {
        server.createContext(endpoint) { exchange ->
            exchange.use {
                when {
                    exchange.requestURI.rawPath != endpoint ||
                        exchange.requestHeaders.getFirst("Host") != authority -> exchange.reply(404)
                    exchange.requestMethod == "GET" ->
                        exchange.reply(200, phyVlabCaptchaHtml(captchaId, desktopCallback = true))
                    exchange.requestMethod == "POST" &&
                        exchange.requestHeaders.getFirst("Origin") == origin &&
                        exchange.requestHeaders.getFirst("Content-Type") == "application/json" -> {
                        val data = exchange.requestBody.readNBytes(8193)
                        val answer = if (data.size <= 8192) PhyVlabCaptchaProof.parse(data.decodeToString()) else null
                        if (answer == null || proof.isCompleted) {
                            exchange.reply(400)
                        } else {
                            exchange.reply(204)
                            proof.complete(answer)
                        }
                    }
                    else -> exchange.reply(405)
                }
            }
        }
        server.start()
        Desktop.getDesktop().browse(URI("$origin$endpoint"))
        return withTimeoutOrNull(180_000) { proof.await() }
    } finally {
        server.stop(0)
    }
}

private fun HttpExchange.reply(status: Int, content: String? = null) {
    responseHeaders.set("Cache-Control", "no-store")
    responseHeaders.set("X-Content-Type-Options", "nosniff")
    responseHeaders.set("X-Frame-Options", "DENY")
    if (content == null) {
        sendResponseHeaders(status, -1)
    } else {
        val bytes = content.encodeToByteArray()
        responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.write(bytes)
    }
}
