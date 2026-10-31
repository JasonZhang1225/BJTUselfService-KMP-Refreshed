import AVFoundation
import CoreImage
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// 课程回放的 macOS 原生小程序（只用系统 AVFoundation，不额外打包编解码库）：
/// - 截帧预览：BJTUVideoFrameHelper <https-url> <秒> <输出.jpg> <最大宽度>
///   退出码：0 成功；64 参数错误；2 无法加载；3 写图失败；4 超时。
/// - 左右并排合成：BJTUVideoFrameHelper compose <左.mp4> <右.mp4> <输出.mp4> <宽> <高> <码率>
/// - 增长索引边下边合：BJTUVideoFrameHelper compose-indexed <左目录> <右目录> <输出.mp4> <宽> <高> <码率>
///   --duration <秒> [--parent-pid <pid>]
///   标准输出逐行打印 `progress 0.123`；退出码 0 成功，非 0 失败（stderr 一行原因）。
@main
enum VideoFrameHelper {
    static func main() {
        let args = CommandLine.arguments
        if args.count >= 8, args[1] == "compose-indexed" {
            guard let width = Int(args[5]), let height = Int(args[6]), let bitRate = Int(args[7]),
                  width > 0, height > 0, bitRate > 0 else { exit(64) }
            guard let durationIndex = args.firstIndex(of: "--duration"),
                  durationIndex + 1 < args.count,
                  let duration = Double(args[durationIndex + 1]), duration > 0 else { exit(64) }
            watchParentIfRequested(args)
            exit(IndexedSideBySideCompose.run(
                leftDir: URL(fileURLWithPath: args[2]),
                rightDir: URL(fileURLWithPath: args[3]),
                output: URL(fileURLWithPath: args[4]),
                width: width,
                height: height,
                bitRate: bitRate,
                durationSeconds: duration
            ))
        }
        if args.count >= 8, args[1] == "compose" {
            guard let width = Int(args[5]), let height = Int(args[6]), let bitRate = Int(args[7]),
                  width > 0, height > 0, bitRate > 0 else { exit(64) }
            if args.count > 8 {
                guard args.count == 10, args[8] == "--parent-pid", Int32(args[9]) != nil else { exit(64) }
            }
            watchParentIfRequested(args)
            exit(SideBySideCompositor.run(
                left: URL(fileURLWithPath: args[2]),
                right: URL(fileURLWithPath: args[3]),
                output: URL(fileURLWithPath: args[4]),
                width: width,
                height: height,
                bitRate: bitRate
            ))
        }
        guard args.count == 5,
              let url = URL(string: args[1]),
              url.scheme == "https",
              let host = url.host?.lowercased(),
              host == "bjtu.edu.cn" || host.hasSuffix(".bjtu.edu.cn"),
              let seconds = Double(args[2]),
              let maxWidth = Double(args[4]), maxWidth > 0 else { exit(64) }
        exit(capture(url: url, seconds: seconds, output: URL(fileURLWithPath: args[3]), maxWidth: maxWidth))
    }

    /// 父进程消失（强退/崩溃）时结束自身，避免脱离托管的编码进程。
    private static func watchParentIfRequested(_ args: [String]) {
        guard let index = args.firstIndex(of: "--parent-pid"),
              index + 1 < args.count,
              let pid = Int32(args[index + 1]),
              pid > 1 else { return }
        DispatchQueue.global(qos: .utility).async {
            while true {
                if kill(pid, 0) != 0 {
                    FileHandle.standardError.write(Data("parent exited\n".utf8))
                    _exit(1)
                }
                usleep(200_000)
            }
        }
    }

    private static func capture(url: URL, seconds: Double, output: URL, maxWidth: Double) -> Int32 {
        let item = AVPlayerItem(url: url)
        let videoOutput = AVPlayerItemVideoOutput(pixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        ])
        item.add(videoOutput)
        let player = AVPlayer(playerItem: item)
        player.isMuted = true
        var seekRequested = false
        var seekFinished = false
        let deadline = Date().addingTimeInterval(25)
        while Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.1))
            if item.status == .failed { return 2 }
            if item.status == .readyToPlay && !seekRequested {
                seekRequested = true
                let duration = item.duration.seconds
                // 开头常是课前空镜：默认取第 10 分钟，短视频取中间。
                let target = duration.isFinite && duration > 0 ? min(seconds, duration * 0.5) : seconds
                player.seek(
                    to: CMTime(seconds: target, preferredTimescale: 600),
                    toleranceBefore: .positiveInfinity,
                    toleranceAfter: .positiveInfinity
                ) { _ in
                    seekFinished = true
                    player.play()
                }
            }
            guard seekFinished else { continue }
            let time = videoOutput.itemTime(forHostTime: CACurrentMediaTime())
            guard videoOutput.hasNewPixelBuffer(forItemTime: time),
                  let buffer = videoOutput.copyPixelBuffer(forItemTime: time, itemTimeForDisplay: nil) else { continue }
            player.pause()
            var image = CIImage(cvPixelBuffer: buffer)
            let scale = min(1.0, maxWidth / image.extent.width)
            image = image.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            guard let cgImage = CIContext().createCGImage(image, from: image.extent),
                  let destination = CGImageDestinationCreateWithURL(
                      output as CFURL, UTType.jpeg.identifier as CFString, 1, nil
                  ) else { return 3 }
            CGImageDestinationAddImage(
                destination, cgImage, [kCGImageDestinationLossyCompressionQuality: 0.75] as CFDictionary
            )
            return CGImageDestinationFinalize(destination) ? 0 : 3
        }
        return 4
    }
}

/// 两路 MP4 → 左右并排 H.264 MP4（声音取左路，原样拷贝不重新编码）。
enum SideBySideCompositor {
    static func run(left: URL, right: URL, output: URL, width: Int, height: Int, bitRate: Int) -> Int32 {
        let leftAsset = AVURLAsset(url: left)
        let rightAsset = AVURLAsset(url: right)
        guard let leftVideo = leftAsset.tracks(withMediaType: .video).first,
              let rightVideo = rightAsset.tracks(withMediaType: .video).first else {
            return fail("找不到视频轨道", 2)
        }
        let composition = AVMutableComposition()
        guard let compLeft = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let compRight = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            return fail("无法创建合成轨道", 2)
        }
        do {
            try compLeft.insertTimeRange(CMTimeRange(start: .zero, duration: leftAsset.duration), of: leftVideo, at: .zero)
            try compRight.insertTimeRange(CMTimeRange(start: .zero, duration: rightAsset.duration), of: rightVideo, at: .zero)
        } catch {
            return fail("插入视频失败", 2)
        }
        var compAudio: AVMutableCompositionTrack?
        if let audio = leftAsset.tracks(withMediaType: .audio).first ?? rightAsset.tracks(withMediaType: .audio).first,
           let track = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) {
            let source = leftAsset.tracks(withMediaType: .audio).isEmpty ? rightAsset : leftAsset
            try? track.insertTimeRange(CMTimeRange(start: .zero, duration: source.duration), of: audio, at: .zero)
            compAudio = track
        }
        let duration = CMTimeMaximum(leftAsset.duration, rightAsset.duration)
        let halfWidth = CGFloat(width) / 2
        func layer(_ track: AVMutableCompositionTrack, source: AVAssetTrack, x: CGFloat) -> AVMutableVideoCompositionLayerInstruction {
            let instruction = AVMutableVideoCompositionLayerInstruction(assetTrack: track)
            let size = source.naturalSize.applying(source.preferredTransform)
            let w = abs(size.width), h = abs(size.height)
            let scale = min(halfWidth / w, CGFloat(height) / h)
            let dx = x + (halfWidth - w * scale) / 2
            let dy = (CGFloat(height) - h * scale) / 2
            instruction.setTransform(
                source.preferredTransform.concatenating(CGAffineTransform(scaleX: scale, y: scale)).concatenating(CGAffineTransform(translationX: dx, y: dy)),
                at: .zero
            )
            return instruction
        }
        let instruction = AVMutableVideoCompositionInstruction()
        instruction.timeRange = CMTimeRange(start: .zero, duration: duration)
        instruction.backgroundColor = CGColor(red: 0, green: 0, blue: 0, alpha: 1)
        instruction.layerInstructions = [layer(compLeft, source: leftVideo, x: 0), layer(compRight, source: rightVideo, x: halfWidth)]
        let videoComposition = AVMutableVideoComposition()
        videoComposition.renderSize = CGSize(width: width, height: height)
        videoComposition.frameDuration = CMTime(value: 1, timescale: 25)
        videoComposition.instructions = [instruction]

        let reader: AVAssetReader
        let writer: AVAssetWriter
        do {
            reader = try AVAssetReader(asset: composition)
            try? FileManager.default.removeItem(at: output)
            writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
        } catch {
            return fail("无法打开读写器", 2)
        }
        let videoOut = AVAssetReaderVideoCompositionOutput(
            videoTracks: [compLeft, compRight],
            videoSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange]
        )
        videoOut.videoComposition = videoComposition
        videoOut.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOut) else { return fail("无法读取合成画面", 2) }
        reader.add(videoOut)
        let videoIn = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: width,
            AVVideoHeightKey: height,
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoMaxKeyFrameIntervalKey: 250,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
        ])
        videoIn.expectsMediaDataInRealTime = false
        writer.add(videoIn)
        var audioOut: AVAssetReaderTrackOutput?
        var audioIn: AVAssetWriterInput?
        if let compAudio, let format = compAudio.formatDescriptions.first {
            let out = AVAssetReaderTrackOutput(track: compAudio, outputSettings: nil)
            if reader.canAdd(out) {
                reader.add(out)
                let input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: (format as! CMFormatDescription))
                input.expectsMediaDataInRealTime = false
                if writer.canAdd(input) {
                    writer.add(input)
                    audioOut = out
                    audioIn = input
                }
            }
        }
        guard reader.startReading(), writer.startWriting() else { return fail("无法开始合成", 2) }
        writer.startSession(atSourceTime: .zero)

        let total = max(CMTimeGetSeconds(duration), 0.001)
        var videoDone = false
        var audioDone = audioOut == nil
        var lastReported = -1
        while !(videoDone && audioDone) {
            var progressed = false
            if !videoDone && videoIn.isReadyForMoreMediaData {
                if let sample = videoOut.copyNextSampleBuffer() {
                    if !videoIn.append(sample) { return abortWith(writer, reader, "写入画面失败") }
                    let seconds = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(sample))
                    let percent = Int(seconds / total * 1000)
                    if percent != lastReported {
                        lastReported = percent
                        print(String(format: "progress %.3f", min(1, seconds / total)))
                        fflush(stdout)
                    }
                } else {
                    videoIn.markAsFinished()
                    videoDone = true
                }
                progressed = true
            }
            if !audioDone, let audioIn, let audioOut, audioIn.isReadyForMoreMediaData {
                if let sample = audioOut.copyNextSampleBuffer() {
                    if !audioIn.append(sample) { return abortWith(writer, reader, "写入声音失败") }
                } else {
                    audioIn.markAsFinished()
                    audioDone = true
                }
                progressed = true
            }
            if reader.status == .failed { return abortWith(writer, reader, "读取失败") }
            if !progressed { usleep(2_000) }
        }
        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting { semaphore.signal() }
        semaphore.wait()
        guard writer.status == .completed else { return fail("写出文件失败", 3) }
        print("progress 1.000")
        return 0
    }

    private static func abortWith(_ writer: AVAssetWriter, _ reader: AVAssetReader, _ reason: String) -> Int32 {
        reader.cancelReading()
        writer.cancelWriting()
        return fail(reason, 3)
    }

    private static func fail(_ reason: String, _ code: Int32) -> Int32 {
        FileHandle.standardError.write((reason + "\n").data(using: .utf8)!)
        return code
    }
}
