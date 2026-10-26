package team.bjtuss.bjtuselfservice.shared.media

import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * 本机诊断：BJTU_REMUX_IN="a.ts b.ts" BJTU_REMUX_OUT=/path/out.mp4 \
 *   ./gradlew :shared:desktopTest --tests '*RemuxSampleProbeTest'
 */
class RemuxSampleProbeTest {
    @Test
    fun remuxLocalSegments() = runBlocking {
        val inputs = System.getenv("BJTU_REMUX_IN")?.split(' ')?.filter(String::isNotBlank) ?: return@runBlocking
        val out = File(System.getenv("BJTU_REMUX_OUT") ?: return@runBlocking)
        val data = File(out.path + ".data")
        data.outputStream().use { stream ->
            val remuxer = TsToMp4Remuxer { stream.write(it) }
            inputs.forEach { remuxer.feed(File(it).readBytes()) }
            remuxer.finish()
            stream.close()
            out.outputStream().use { o ->
                o.write(remuxer.buildHeader())
                data.inputStream().use { it.copyTo(o) }
            }
            println("REMUX video=${remuxer.videoSampleCount} audio=${remuxer.audioSampleCount} data=${remuxer.dataSize}")
        }
        data.delete()
    }
}
