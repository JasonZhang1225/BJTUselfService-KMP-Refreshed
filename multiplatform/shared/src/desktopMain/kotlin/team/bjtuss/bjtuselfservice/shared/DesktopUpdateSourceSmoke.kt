package team.bjtuss.bjtuselfservice.shared

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import team.bjtuss.bjtuselfservice.shared.cache.AppPreferences
import team.bjtuss.bjtuselfservice.shared.feature.settings.*
import team.bjtuss.bjtuselfservice.shared.network.*
import team.bjtuss.bjtuselfservice.shared.update.*

/** No accounts or persistent preferences. Live mode only reads public releases and saves to .artifacts. */
@Composable
fun DesktopUpdateSourceSmoke(live: Boolean = false, largeText: Boolean = false) {
    var dark by remember { mutableStateOf(false) }
    var scenario by remember { mutableStateOf("正常") }
    var downloadState by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val transport = remember(live) { if (live) KtorSchoolHttpTransport(schoolHttpEngineFactory()) else null }
    DisposableEffect(transport) { onDispose { transport?.close() } }
    val model = remember {
        SettingsScreenModel(AppPreferences(), { true }, { true }, { true }, { AppUpdateChecker.Result.Unavailable },
            checkReleaseFromSource = { source ->
                if (transport != null) {
                    val started = kotlin.time.TimeSource.Monotonic.markNow()
                    val observed = object : SchoolHttpTransport {
                        override suspend fun execute(request: SchoolHttpRequest): SchoolHttpResponse = error("No school sessions in update probe")
                        override suspend fun executePublic(request: SchoolHttpRequest): SchoolHttpResponse {
                            if (request.url.startsWith("https://api.github.com/")) {
                                println("[UpdateProbe] GitHub request: simulate unreachable host")
                                delay(60_000L)
                                error("Expected checker to time out")
                            }
                            println("[UpdateProbe] Public Gitee metadata request")
                            return transport.executePublic(request)
                        }
                        override fun clearSession() = Unit
                    }
                    AppUpdateChecker.fetchLatest(observed, source).also { result ->
                        val release = (result as? AppUpdateChecker.Result.Success)?.release
                        println("[UpdateProbe] source=${release?.source} tag=${release?.tagName} elapsed_ms=${started.elapsedNow().inWholeMilliseconds}")
                    }
                } else {
                    delay(1200)
                    when (scenario) {
                        "失败" -> AppUpdateChecker.Result.Unavailable
                        else -> {
                            val actual = if (source == UpdateSource.GITEE) source else UpdateSource.GITHUB
                            val base = if (actual == UpdateSource.GITEE) "https://gitee.com/${AppUpdateChecker.GITEE_REPO}" else "https://github.com/${AppUpdateChecker.REPO}"
                            val tag = if (scenario == "无更新") "v1.0.0" else "v9.0.0-KMP"
                            AppUpdateChecker.Result.Success(AppUpdateChecker.Release(
                                tagName = tag, name = "本地界面测试", body = "四端安装包与镜像同步测试。",
                                htmlUrl = "$base/releases/tag/$tag", source = actual,
                                assets = listOf("apk", "ipa", "dmg", "msi").map { ext ->
                                    AppUpdateChecker.ReleaseAsset("app.$ext", "$base/releases/download/$tag/app.$ext")
                                },
                            ))
                        }
                    }
                }
            })
    }
    val state by model.state.collectAsState()
    val downloader = remember(live) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (transport == null) { downloadState = "测试下载地址：$uri"; return }
                // Dialog dismisses before opening; validate the stable repository URL independently.
                require(uri.startsWith("https://gitee.com/${AppUpdateChecker.GITEE_REPO}/releases/download/"))
                println("[UpdateProbe] Installer URL: $uri")
                downloadState = "正在匿名下载…"
                scope.launch {
                    try {
                        val message = withContext(Dispatchers.IO) {
                            val response = transport.executePublic(SchoolHttpRequest(SchoolHttpMethod.GET, uri, largeTransfer = true))
                            check(response.statusCode in 200..299)
                            check(response.body.size > 1024)
                            val directory = File(System.getProperty("user.dir"), ".artifacts/gitee-live-download").apply { mkdirs() }
                            val file = File(directory, uri.substringAfterLast('/'))
                            file.writeBytes(response.body)
                            val hash = MessageDigest.getInstance("SHA-256").digest(response.body).joinToString("") { "%02x".format(it) }
                            println("[UpdateProbe] Downloaded ${file.name} bytes=${file.length()} sha256=$hash")
                            "匿名下载完成：${file.name}（${file.length()} 字节）"
                        }
                        downloadState = message
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { downloadState = "下载失败：${error::class.simpleName}" }
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides downloader,
        LocalDensity provides Density(LocalDensity.current.density, if (largeText) 1.6f else LocalDensity.current.fontScale)) {
        PlatformAppTheme(useDarkTheme = dark, dynamicColorEnabled = false) {
            Surface(Modifier.fillMaxSize()) {
                Column {
                    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton({ dark = !dark }) { Text(if (dark) "浅色" else "深色") }
                        if (live) Text("真实 Gitee · GitHub 模拟超时", modifier = Modifier.padding(8.dp))
                        else listOf("正常", "无更新", "失败").forEach { label -> TextButton({ scenario = label }) { Text(label) } }
                    }
                    if (downloadState.isNotEmpty()) Text(downloadState, modifier = Modifier.padding(8.dp))
                    SettingsWorkspace(model, "测试账号", currentPlatform(), false, {}, Modifier.weight(1f))
                }
                AppUpdateResultDialog(state.updateCheck, model::dismissUpdateCheck, model::postponeUpdate)
            }
        }
    }
}
