package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class PlaybackHelperProcessTest {
    private fun removeFixture(directory: File) {
        val target = directory.canonicalFile
        check(target.parentFile == File(System.getProperty("java.io.tmpdir")).canonicalFile)
        check(target.name.startsWith("playback-pipes-") || target.name.startsWith("playback-cancel-"))
        target.deleteRecursively()
    }
    private fun fixture(directory: File, mode: String): Process {
        val source = File(directory, "PlaybackFixture.java").apply { writeText("""
            public class PlaybackFixture {
                public static void main(String[] args) throws Exception {
                    if (args[0].equals("hang")) { while (true) Thread.sleep(1000); }
                    if (args[0].equals("devices")) {
                        System.out.println("device NVIDIA GeForce - hardware");
                        System.out.println("status retrying");
                        System.out.println("device Intel Graphics - hardware");
                        System.out.println("device CPU - software");
                        return;
                    }
                    System.err.print("x".repeat(262144));
                    System.err.print("diagnostic-tail");
                    System.out.println("progress 0.5");
                }
            }
        """.trimIndent()) }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.absolutePath, source.absolutePath))
        val binary = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/$binary").absolutePath,
            "-cp", directory.absolutePath, "PlaybackFixture", mode).start()
    }

    @Test fun drainsLargeDiagnosticsWithoutBlockingProgress() = runBlocking {
        val directory = Files.createTempDirectory("playback-pipes-").toFile()
        val process = fixture(directory, "logs")
        try {
            val progress = mutableListOf<Float>()
            val result = withTimeout(5000) { kotlinx.coroutines.withContext(Dispatchers.IO) { awaitPlaybackHelper(process, progress::add) } }
            assertEquals(0, result.code)
            assertEquals(listOf(.5f), progress)
            assertTrue(result.diagnostic.endsWith("diagnostic-tail"))
            assertTrue(result.diagnostic.length <= 8192)
        } finally { process.destroyForcibly(); removeFixture(directory) }
    }

    @Test fun cancellationKillsHelperBeforeFirstProgress() = runBlocking {
        val directory = Files.createTempDirectory("playback-cancel-").toFile()
        val process = fixture(directory, "hang")
        try {
            val job = async(Dispatchers.IO) { awaitPlaybackHelper(process, {}) }
            delay(150)
            withTimeout(3000) { job.cancelAndJoin() }
            assertFalse(process.isAlive)
        } finally { process.destroyForcibly(); removeFixture(directory) }
    }

    @Test fun reportsDeviceSwitchesSeparatelyFromStatus() = runBlocking {
        val directory = Files.createTempDirectory("playback-pipes-").toFile()
        val process = fixture(directory, "devices")
        try {
            val devices = mutableListOf<String?>()
            val statuses = mutableListOf<String?>()
            val result = withTimeout(5000) {
                kotlinx.coroutines.withContext(Dispatchers.IO) { awaitPlaybackHelper(process, {}, statuses::add, devices::add) }
            }
            assertEquals(0, result.code)
            assertEquals(listOf<String?>("NVIDIA GeForce - hardware", "Intel Graphics - hardware", "CPU - software"), devices)
            assertEquals(listOf<String?>("retrying"), statuses)
        } finally { process.destroyForcibly(); removeFixture(directory) }
    }
}
