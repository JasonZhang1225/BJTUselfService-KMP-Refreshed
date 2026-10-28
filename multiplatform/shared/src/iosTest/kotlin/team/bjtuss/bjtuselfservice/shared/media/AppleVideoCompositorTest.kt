package team.bjtuss.bjtuselfservice.shared.media

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.tracksWithMediaType
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import team.bjtuss.bjtuselfservice.shared.domain.courseware.PlaybackCompositeQuality
import platform.Foundation.NSUUID

@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
class AppleVideoCompositorTest {
    private suspend fun remuxTo(path: String) {
        val data = ByteArrayBuilder()
        val remuxer = TsToMp4Remuxer { data.append(it) }
        remuxer.feed(Base64.decode(SYNTHETIC_TS_0))
        remuxer.feed(Base64.decode(SYNTHETIC_TS_1))
        remuxer.finish()
        AppendFile(Path(path)).use { out ->
            out.append(remuxer.buildHeader())
            out.append(data.toByteArray())
        }
    }

    @Test
    fun composesTwoRemuxedStreamsSideBySideWithAudio() = runBlocking {
        val dir = NSTemporaryDirectory().trimEnd('/') + "/compositor-test-" + NSUUID().UUIDString
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        try {
            val left = "$dir/left.mp4"
            val right = "$dir/right.mp4"
            val output = "$dir/out.mp4"
            remuxTo(left)
            remuxTo(right)
            // 先确认 AVFoundation 能读纯 Kotlin 转出来的 MP4。
            val source = AVURLAsset(uRL = NSURL.fileURLWithPath(left), options = null)
            assertEquals(1, source.tracksWithMediaType(AVMediaTypeVideo).size)
            val progress = mutableListOf<Float>()
            val quality = PlaybackCompositeQuality.HIGH
            AppleVideoCompositor.composeSideBySide(left, right, output, quality, { progress += it }, {})
            val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(output), options = null)
            val video = asset.tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack
            assertEquals(quality.width.toDouble() to quality.height.toDouble(), video.naturalSize.useContents { width to height })
            assertEquals(1, asset.tracksWithMediaType(AVMediaTypeAudio).size)
            val seconds = CMTimeGetSeconds(asset.duration)
            assertTrue(seconds in 3.5..4.5, "duration $seconds")
            assertEquals(1f, progress.last())
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(dir, null)
        }
    }
}
