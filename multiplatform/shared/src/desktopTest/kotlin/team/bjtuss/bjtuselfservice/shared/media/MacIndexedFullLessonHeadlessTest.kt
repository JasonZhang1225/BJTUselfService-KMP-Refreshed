package team.bjtuss.bjtuselfservice.shared.media

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 无 Compose 窗口的整节 indexed 合成：避免 GUI+下载+编码同进程被 jetsam。
 * 启用：`BJTU_INDEXED_FULL=1 ./gradlew :shared:desktopTest --tests '*MacIndexedFullLessonHeadlessTest*'`
 *
 * 产物：`.artifacts/macos-playback-p1/ui-harness-live-indexed/headless/`
 */
class MacIndexedFullLessonHeadlessTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun findHelper(): File? = listOf(
        File("../desktopApp/build/generated/video-frame/BJTUVideoFrameHelper"),
        File("../desktopApp/build/compose/binaries/main/app/BJTUselfServiceKMP.app/Contents/Resources/VideoFrame/BJTUVideoFrameHelper"),
    ).map { it.canonicalFile }.firstOrNull { it.canExecute() }

    @Test
    fun composesFullLessonHeadless() = runBlocking {
        if (System.getenv("BJTU_INDEXED_FULL") != "1") return@runBlocking
        val helper = findHelper() ?: error("missing BJTUVideoFrameHelper")
        val root = File("../../.artifacts/macos-playback-p1/ui-harness-live-indexed").canonicalFile
        val streamsFile = File(root, "streams.json")
        assertTrue(streamsFile.isFile, "missing streams.json")
        val outDir = File(root, "headless").also {
            it.mkdirs()
        }
        val work = File(outDir, "work").also {
            it.deleteRecursively()
            check(it.mkdirs())
        }
        val left = File(work, "mac-input-0").also { check(it.mkdirs()) }
        val right = File(work, "mac-input-1").also { check(it.mkdirs()) }
        val teacherTs = File(outDir, "teacher-full.ts")
        val screenTs = File(outDir, "screen-full.ts")

        val payload = json.parseToJsonElement(streamsFile.readText()).jsonObject
        val streams = payload["streams"]!!.jsonArray
        val teacherUrl = streams[0].jsonObject["url"]!!.jsonPrimitive.content
        val screenUrl = streams[1].jsonObject["url"]!!.jsonPrimitive.content

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        if (!teacherTs.isFile || teacherTs.length() < 100_000_000L) {
            downloadPlaylist(client, teacherUrl, teacherTs, File(outDir, "teacher-dl.log"))
        }
        if (!screenTs.isFile || screenTs.length() < 100_000_000L) {
            downloadPlaylist(client, screenUrl, screenTs, File(outDir, "screen-dl.log"))
        }
        File(outDir, "download-sizes.txt").writeText(
            "teacher=${teacherTs.length()}\nscreen=${screenTs.length()}\n",
        )

        val leftIn = WindowsIndexedTsInput(left, needsAudio = true)
        val rightIn = WindowsIndexedTsInput(right, needsAudio = false)
        streamTsInto(teacherTs, leftIn)
        leftIn.finish()
        streamTsInto(screenTs, rightIn)
        rightIn.finish()

        val out = File(outDir, "full-indexed.mp4")
        val err = File(outDir, "compose.stderr.txt")
        val stdout = File(outDir, "compose.stdout.txt")
        out.delete()
        val started = System.nanoTime()
        val child = ProcessBuilder(
            helper.absolutePath, "compose-indexed",
            left.absolutePath, right.absolutePath, out.absolutePath,
            "1280", "360", "1200000", "--duration", "7022",
        ).redirectError(err).redirectOutput(stdout).start()
        assertTrue(child.waitFor(90, TimeUnit.MINUTES), "helper timeout")
        val wallMs = (System.nanoTime() - started) / 1_000_000L
        File(outDir, "compose.wall-ms.txt").writeText(wallMs.toString())
        assertEquals(0, child.exitValue(), "stderr=${err.readText().take(2000)}")
        assertTrue(out.isFile && out.length() > 50_000_000L, "output too small: ${out.length()}")
        System.err.println("mac-indexed-full wallMs=$wallMs size=${out.length()} stderr=${err.readText().trim()}")
    }

    private suspend fun streamTsInto(ts: File, input: WindowsIndexedTsInput) = withContext(Dispatchers.IO) {
        val buf = ByteArray(256 * 1024)
        BufferedInputStream(FileInputStream(ts), 256 * 1024).use { stream ->
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                if (n == buf.size) {
                    input.append(buf)
                } else {
                    input.append(buf.copyOf(n))
                }
            }
        }
    }

    private suspend fun downloadPlaylist(
        client: HttpClient,
        playlistUrl: String,
        output: File,
        log: File,
    ) = withContext(Dispatchers.IO) {
        val playlist = httpGetText(client, playlistUrl)
        val base = playlistUrl.substringBeforeLast('/') + "/"
        val segments = playlist.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        check(segments.isNotEmpty()) { "empty playlist" }
        output.parentFile.mkdirs()
        val tmp = File(output.parentFile, output.name + ".partial")
        tmp.outputStream().use { out ->
            // 有限并发：减轻本机内存与链路压力。
            val batch = 4
            var index = 0
            while (index < segments.size) {
                val end = minOf(index + batch, segments.size)
                val chunk = segments.subList(index, end)
                val bodies = chunk.map { name ->
                    async(Dispatchers.IO) {
                        val url = if (name.startsWith("http")) name else base + name
                        httpGetBytes(client, url)
                    }
                }.awaitAll()
                bodies.forEach { out.write(it) }
                index = end
                if (index % 40 == 0 || index == segments.size) {
                    log.appendText("$index/${segments.size} bytes=${tmp.length()}\n")
                }
            }
        }
        check(tmp.renameTo(output) || (output.delete() && tmp.renameTo(output)))
    }

    private fun httpGetText(client: HttpClient, url: String): String {
        val req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(60)).build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()} for playlist" }
        return res.body()
    }

    private fun httpGetBytes(client: HttpClient, url: String): ByteArray {
        val req = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(120)).build()
        val res = client.send(req, HttpResponse.BodyHandlers.ofByteArray())
        check(res.statusCode() in 200..299) { "HTTP ${res.statusCode()}" }
        return res.body()
    }
}
