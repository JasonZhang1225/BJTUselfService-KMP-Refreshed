import AVFoundation
import AudioToolbox
import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

/// 增长索引目录边下边合（对齐 Windows `compose-indexed` 文件约定）。
/// 左路 AAC 原样直通；画面按两路 video.idx 锁步推进。
enum IndexedSideBySideCompose {
    private struct Record {
        var offset: UInt64
        var pts: Int64
        var size: UInt32
        var flags: UInt32
    }

    private final class SampleCursor {
        let directory: URL
        private let data: FileHandle
        private let index: FileHandle
        private var count: UInt64 = 0

        init?(directory: URL, indexName: String) {
            self.directory = directory
            guard let data = try? FileHandle(forReadingFrom: directory.appendingPathComponent("samples.data")),
                  let index = try? FileHandle(forReadingFrom: directory.appendingPathComponent(indexName)) else {
                return nil
            }
            self.data = data
            self.index = index
        }

        deinit {
            try? data.close()
            try? index.close()
        }

        enum Outcome { case sample(Record, Data); case end; case error }

        func next() -> Outcome {
            while true {
                let indexSize = (try? index.seekToEnd()) ?? 0
                try? index.seek(toOffset: 0)
                if indexSize >= (count + 1) * 24 { break }
                if FileManager.default.fileExists(atPath: directory.appendingPathComponent("finished").path) {
                    return .end
                }
                usleep(10_000)
            }
            do {
                try index.seek(toOffset: count * 24)
                guard let raw = try index.read(upToCount: 24), raw.count == 24 else { return .error }
                let record = Record(
                    offset: raw.leUInt64(0),
                    pts: raw.leInt64(8),
                    size: raw.leUInt32(16),
                    flags: raw.leUInt32(20)
                )
                guard record.size > 0, record.size <= 32 * 1024 * 1024 else { return .error }
                try data.seek(toOffset: record.offset)
                guard let bytes = try data.read(upToCount: Int(record.size)),
                      bytes.count == Int(record.size) else { return .error }
                count += 1
                return .sample(record, bytes)
            } catch {
                return .error
            }
        }
    }

    private struct VideoConfig {
        let width: Int32
        let height: Int32
        let sps: Data
        let pps: Data
    }

    private static func waitConfig(_ directory: URL) -> VideoConfig? {
        let url = directory.appendingPathComponent("video-config.bin")
        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline {
            if let data = try? Data(contentsOf: url), data.count >= 16 {
                let width = data.leInt32(0)
                let height = data.leInt32(4)
                let spsSize = Int(data.leInt32(8))
                let ppsSize = Int(data.leInt32(12))
                guard width > 0, height > 0, spsSize > 0, ppsSize > 0,
                      data.count >= 16 + spsSize + ppsSize else {
                    usleep(20_000)
                    continue
                }
                return VideoConfig(
                    width: width,
                    height: height,
                    sps: data.subdata(in: 16..<(16 + spsSize)),
                    pps: data.subdata(in: (16 + spsSize)..<(16 + spsSize + ppsSize))
                )
            }
            usleep(20_000)
        }
        return nil
    }

    private static func formatDescription(_ config: VideoConfig) -> CMVideoFormatDescription? {
        var format: CMFormatDescription?
        let status = config.sps.withUnsafeBytes { spsPtr -> OSStatus in
            config.pps.withUnsafeBytes { ppsPtr -> OSStatus in
                guard let spsBase = spsPtr.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let ppsBase = ppsPtr.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                    return -1
                }
                var pointers: [UnsafePointer<UInt8>] = [spsBase, ppsBase]
                var sizes: [Int] = [config.sps.count, config.pps.count]
                return CMVideoFormatDescriptionCreateFromH264ParameterSets(
                    allocator: kCFAllocatorDefault,
                    parameterSetCount: 2,
                    parameterSetPointers: &pointers,
                    parameterSetSizes: &sizes,
                    nalUnitHeaderLength: 4,
                    formatDescriptionOut: &format
                )
            }
        }
        return status == noErr ? format : nil
    }

    private struct AudioConfig {
        let sampleRate: Int32
        let channels: Int32
        let specific: Data
    }

    /// 左路音轨：等 `audio-config.bin`，或确认 `no-audio`。
    private static func waitAudioConfig(_ directory: URL) -> AudioConfig? {
        let configURL = directory.appendingPathComponent("audio-config.bin")
        let noneURL = directory.appendingPathComponent("no-audio")
        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline {
            if FileManager.default.fileExists(atPath: noneURL.path) { return nil }
            if let data = try? Data(contentsOf: configURL), data.count >= 12 {
                let rate = data.leInt32(0)
                let channels = data.leInt32(4)
                let specificSize = Int(data.leInt32(8))
                guard rate >= 8000, rate <= 192_000, channels >= 1, channels <= 8,
                      specificSize > 0, specificSize <= 64,
                      data.count >= 12 + specificSize else {
                    usleep(20_000)
                    continue
                }
                return AudioConfig(
                    sampleRate: rate,
                    channels: channels,
                    specific: data.subdata(in: 12..<(12 + specificSize))
                )
            }
            usleep(20_000)
        }
        return nil
    }

    private static func audioFormatDescription(_ config: AudioConfig) -> CMAudioFormatDescription? {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: Float64(config.sampleRate),
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: 0,
            mBytesPerPacket: 0,
            mFramesPerPacket: 1024,
            mBytesPerFrame: 0,
            mChannelsPerFrame: UInt32(config.channels),
            mBitsPerChannel: 0,
            mReserved: 0
        )
        var format: CMAudioFormatDescription?
        let status = config.specific.withUnsafeBytes { raw -> OSStatus in
            CMAudioFormatDescriptionCreate(
                allocator: kCFAllocatorDefault,
                asbd: &asbd,
                layoutSize: 0,
                layout: nil,
                magicCookieSize: config.specific.count,
                magicCookie: raw.baseAddress,
                extensions: nil,
                formatDescriptionOut: &format
            )
        }
        return status == noErr ? format : nil
    }

    private static func makeSampleBuffer(
        payload: Data,
        pts100ns: Int64,
        duration: CMTime,
        format: CMFormatDescription
    ) -> CMSampleBuffer? {
        var block: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: payload.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: payload.count,
            flags: 0,
            blockBufferOut: &block
        )
        guard status == noErr, let block else { return nil }
        status = payload.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(
                with: raw.baseAddress!,
                blockBuffer: block,
                offsetIntoDestination: 0,
                dataLength: payload.count
            )
        }
        guard status == noErr else { return nil }
        var timing = CMSampleTimingInfo(
            duration: duration,
            presentationTimeStamp: CMTime(value: pts100ns, timescale: 10_000_000),
            decodeTimeStamp: .invalid
        )
        var size = payload.count
        var sample: CMSampleBuffer?
        status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: block,
            formatDescription: format,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &size,
            sampleBufferOut: &sample
        )
        return status == noErr ? sample : nil
    }

    private final class Decoder {
        private var session: VTDecompressionSession?
        private let format: CMFormatDescription
        private let lock = NSLock()
        private var lastBuffer: CVPixelBuffer?
        private var decodeFailures = 0

        init?(format: CMFormatDescription) {
            self.format = format
            var callback = VTDecompressionOutputCallbackRecord(
                decompressionOutputCallback: { refcon, _, status, _, imageBuffer, _, _ in
                    guard let refcon else { return }
                    let decoder = Unmanaged<Decoder>.fromOpaque(refcon).takeUnretainedValue()
                    guard status == noErr, let imageBuffer else { return }
                    decoder.lock.lock()
                    decoder.lastBuffer = imageBuffer
                    decoder.lock.unlock()
                },
                decompressionOutputRefCon: nil
            )
            let unmanaged = Unmanaged.passUnretained(self)
            callback.decompressionOutputRefCon = unmanaged.toOpaque()
            var session: VTDecompressionSession?
            // NV12：硬件解码器原生输出，再交给 VTPixelTransfer 缩放到半幅。
            let candidates: [[CFString: Any]] = [
                [
                    kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                    kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
                ],
                [
                    kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                    kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
                ],
                [
                    kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                    kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
                ],
            ]
            var status: OSStatus = -1
            for attrs in candidates {
                status = VTDecompressionSessionCreate(
                    allocator: kCFAllocatorDefault,
                    formatDescription: format,
                    decoderSpecification: [
                        kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: kCFBooleanTrue as Any,
                    ] as CFDictionary,
                    imageBufferAttributes: attrs as CFDictionary,
                    outputCallback: &callback,
                    decompressionSessionOut: &session
                )
                if status == noErr { break }
            }
            guard status == noErr else { return nil }
            self.session = session
        }

        deinit {
            if let session { VTDecompressionSessionInvalidate(session) }
        }

        func decode(_ sample: CMSampleBuffer) -> CVPixelBuffer? {
            lock.lock()
            lastBuffer = nil
            lock.unlock()
            guard let session else { return nil }
            var flagsOut = VTDecodeInfoFlags()
            // 异步提交 + 等待：修黑场时关掉异步导致流水线饿死；现重新启用。
            let status = VTDecompressionSessionDecodeFrame(
                session,
                sampleBuffer: sample,
                flags: [._EnableAsynchronousDecompression],
                frameRefcon: nil,
                infoFlagsOut: &flagsOut
            )
            if status != noErr {
                decodeFailures += 1
                if decodeFailures <= 5 {
                    FileHandle.standardError.write(Data("vt-decode status=\(status)\n".utf8))
                }
                return nil
            }
            VTDecompressionSessionWaitForAsynchronousFrames(session)
            lock.lock()
            let out = lastBuffer
            lock.unlock()
            return out
        }
    }

    /// VT 像素传送：源帧 letterbox 缩到半幅 BGRA，再拷进成片左右区。
    private final class HalfBlitter {
        private var transfer: VTPixelTransferSession?
        private var halfPool: CVPixelBufferPool?
        private let halfWidth: Int
        private let height: Int

        init?(halfWidth: Int, height: Int) {
            self.halfWidth = halfWidth
            self.height = height
            var session: VTPixelTransferSession?
            guard VTPixelTransferSessionCreate(
                allocator: kCFAllocatorDefault,
                pixelTransferSessionOut: &session
            ) == noErr, let session else {
                return nil
            }
            self.transfer = session
            VTSessionSetProperty(session, key: kVTPixelTransferPropertyKey_ScalingMode, value: kVTScalingMode_Letterbox)
            VTSessionSetProperty(
                session,
                key: kVTPixelTransferPropertyKey_DestinationColorPrimaries,
                value: kCVImageBufferColorPrimaries_ITU_R_709_2
            )
            CVPixelBufferPoolCreate(
                kCFAllocatorDefault, nil,
                [
                    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                    kCVPixelBufferWidthKey as String: halfWidth,
                    kCVPixelBufferHeightKey as String: height,
                    kCVPixelBufferIOSurfacePropertiesKey as String: [:] as CFDictionary,
                ] as CFDictionary,
                &halfPool
            )
        }

        deinit {
            if let transfer { VTPixelTransferSessionInvalidate(transfer) }
        }

        func blit(source: CVPixelBuffer, into destination: CVPixelBuffer, xOffset: Int) -> Bool {
            guard let transfer else { return false }
            var half: CVPixelBuffer?
            if let halfPool {
                CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, halfPool, &half)
            }
            if half == nil {
                CVPixelBufferCreate(
                    kCFAllocatorDefault, halfWidth, height,
                    kCVPixelFormatType_32BGRA, nil, &half
                )
            }
            guard let half else { return false }
            // 先清空半幅，letterbox 黑边才正确。
            CVPixelBufferLockBaseAddress(half, [])
            if let base = CVPixelBufferGetBaseAddress(half) {
                memset(base, 0, CVPixelBufferGetDataSize(half))
            }
            CVPixelBufferUnlockBaseAddress(half, [])
            guard VTPixelTransferSessionTransferImage(transfer, from: source, to: half) == noErr else { return false }

            CVPixelBufferLockBaseAddress(half, .readOnly)
            CVPixelBufferLockBaseAddress(destination, [])
            defer {
                CVPixelBufferUnlockBaseAddress(destination, [])
                CVPixelBufferUnlockBaseAddress(half, .readOnly)
            }
            guard let srcBase = CVPixelBufferGetBaseAddress(half),
                  let dstBase = CVPixelBufferGetBaseAddress(destination) else { return false }
            let srcStride = CVPixelBufferGetBytesPerRow(half)
            let dstStride = CVPixelBufferGetBytesPerRow(destination)
            let rowBytes = halfWidth * 4
            let xBytes = xOffset * 4
            for y in 0..<height {
                memcpy(
                    dstBase.advanced(by: y * dstStride + xBytes),
                    srcBase.advanced(by: y * srcStride),
                    rowBytes
                )
            }
            return true
        }
    }

    static func run(
        leftDir: URL,
        rightDir: URL,
        output: URL,
        width: Int,
        height: Int,
        bitRate: Int,
        durationSeconds: Double
    ) -> Int32 {
        FileHandle.standardError.write(Data("device Apple VideoToolbox · indexed\n".utf8))
        guard let leftConfig = waitConfig(leftDir),
              let rightConfig = waitConfig(rightDir),
              let leftFormat = formatDescription(leftConfig),
              let rightFormat = formatDescription(rightConfig) else {
            return fail("等待画面配置失败", 2)
        }
        // 同写出器边编边写 AAC 会触发 AVAssetWriter 交织死锁；改为：画面边下边合 → 收尾直通挂左路 AAC。
        let audioConfig = waitAudioConfig(leftDir)
        let forceSilent = ProcessInfo.processInfo.environment["BJTU_INDEXED_FORCE_SILENT"] == "1"
        let wantAudio = !forceSilent && audioConfig != nil
        if wantAudio, audioFormatDescription(audioConfig!) == nil {
            return fail("无法创建音轨格式", 2)
        }

        let openDeadline = Date().addingTimeInterval(120)
        var leftCursor: SampleCursor?
        var rightCursor: SampleCursor?
        while Date() < openDeadline, leftCursor == nil || rightCursor == nil {
            if leftCursor == nil { leftCursor = SampleCursor(directory: leftDir, indexName: "video.idx") }
            if rightCursor == nil { rightCursor = SampleCursor(directory: rightDir, indexName: "video.idx") }
            if leftCursor == nil || rightCursor == nil { usleep(20_000) }
        }
        guard let leftCursor, let rightCursor,
              let leftDecoder = Decoder(format: leftFormat),
              let rightDecoder = Decoder(format: rightFormat) else {
            return fail("无法打开索引或解码器", 2)
        }

        let videoOnly = output.deletingPathExtension().appendingPathExtension("video-only.mp4")
        try? FileManager.default.removeItem(at: videoOnly)
        try? FileManager.default.removeItem(at: output)
        let writer: AVAssetWriter
        do {
            writer = try AVAssetWriter(outputURL: videoOnly, fileType: .mp4)
        } catch {
            return fail("无法创建写出器", 2)
        }
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
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoIn,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
            ]
        )
        guard writer.canAdd(videoIn) else { return fail("无法添加视频轨", 2) }
        writer.add(videoIn)
        guard writer.startWriting() else { return fail("无法开始写出", 2) }
        writer.startSession(atSourceTime: .zero)

        guard let blitter = HalfBlitter(halfWidth: width / 2, height: height) else {
            return fail("无法创建 VT 像素传送", 2)
        }
        FileHandle.standardError.write(Data("compose VTPixelTransfer\n".utf8))
        var pixelPool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(
            kCFAllocatorDefault, nil,
            [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: width,
                kCVPixelBufferHeightKey as String: height,
                kCVPixelBufferIOSurfacePropertiesKey as String: [:] as CFDictionary,
            ] as CFDictionary,
            &pixelPool
        )
        let totalPts = max(durationSeconds, 0.001) * 10_000_000
        var lastReported = -1
        var frameIndex: Int64 = 0
        var lastPresent = CMTime.invalid
        var leftEnded = false
        var rightEnded = false
        var leftHold: (Record, Data)?
        var rightHold: (Record, Data)?
        // 以教师路（左）为时钟；课件路保留最近一帧像素缓冲。
        var currentRightBuffer: CVPixelBuffer?
        let decodeQueue = DispatchQueue(label: "bjtu.indexed.decode", attributes: .concurrent)
        // 分段计时（纳秒）：定位卡在解码 / 等编码器 / blit / append。
        var nsDecode: UInt64 = 0
        var nsWaitReady: UInt64 = 0
        var nsBlit: UInt64 = 0
        var nsAppend: UInt64 = 0
        var nsPullIdle: UInt64 = 0
        func stamp() -> UInt64 { DispatchTime.now().uptimeNanoseconds }
        func reportTiming(tag: String) {
            let total = max(1, nsDecode &+ nsWaitReady &+ nsBlit &+ nsAppend &+ nsPullIdle)
            func pct(_ v: UInt64) -> Double { Double(v) * 100.0 / Double(total) }
            let line = String(
                format: "timing %@ frames=%lld decode=%.1f%% waitReady=%.1f%% blit=%.1f%% append=%.1f%% pullIdle=%.1f%%\n",
                tag, frameIndex, pct(nsDecode), pct(nsWaitReady), pct(nsBlit), pct(nsAppend), pct(nsPullIdle)
            )
            FileHandle.standardError.write(Data(line.utf8))
        }

        func pullLeft() {
            if leftHold != nil || leftEnded { return }
            switch leftCursor.next() {
            case .sample(let r, let d): leftHold = (r, d)
            case .end: leftEnded = true
            case .error: leftEnded = true
            }
        }
        func pullRight() {
            if rightHold != nil || rightEnded { return }
            switch rightCursor.next() {
            case .sample(let r, let d): rightHold = (r, d)
            case .end: rightEnded = true
            case .error: rightEnded = true
            }
        }

        func decodeVideo(_ hold: (Record, Data), format: CMFormatDescription, decoder: Decoder) -> CVPixelBuffer? {
            guard let sb = makeSampleBuffer(
                payload: hold.1,
                pts100ns: hold.0.pts,
                duration: CMTime(value: 1, timescale: 25),
                format: format
            ) else { return nil }
            return decoder.decode(sb)
        }

        while true {
            let tPull0 = stamp()
            pullLeft()
            if leftHold == nil {
                if leftEnded { break }
                usleep(5_000)
                nsPullIdle &+= stamp() &- tPull0
                continue
            }
            nsPullIdle &+= stamp() &- tPull0
            let pts = leftHold!.0.pts
            let leftSample = leftHold!
            leftHold = nil

            // 课件路先吃完 ≤ 教师 PTS 的样本（通常 0–1 帧），再与教师路并行解码。
            var rightSample: (Record, Data)?
            while true {
                pullRight()
                guard let hold = rightHold else { break }
                if hold.0.pts > pts { break }
                rightSample = hold
                rightHold = nil
            }

            var leftBuffer: CVPixelBuffer?
            var rightBuffer: CVPixelBuffer?
            let resultLock = NSLock()
            let group = DispatchGroup()
            let tDec0 = stamp()
            group.enter()
            decodeQueue.async {
                let buf = decodeVideo(leftSample, format: leftFormat, decoder: leftDecoder)
                resultLock.lock()
                leftBuffer = buf
                resultLock.unlock()
                group.leave()
            }
            if let rightSample {
                group.enter()
                decodeQueue.async {
                    let buf = decodeVideo(rightSample, format: rightFormat, decoder: rightDecoder)
                    resultLock.lock()
                    rightBuffer = buf
                    resultLock.unlock()
                    group.leave()
                }
            }
            group.wait()
            nsDecode &+= stamp() &- tDec0
            if let rightBuffer { currentRightBuffer = rightBuffer }

            let tWait0 = stamp()
            while !videoIn.isReadyForMoreMediaData { usleep(1_000) }
            nsWaitReady &+= stamp() &- tWait0
            let tBlit0 = stamp()
            var pixel: CVPixelBuffer?
            if let pixelPool {
                CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pixelPool, &pixel)
            }
            if pixel == nil {
                CVPixelBufferCreate(
                    kCFAllocatorDefault, width, height,
                    kCVPixelFormatType_32BGRA, nil, &pixel
                )
            }
            guard let pixel else { return fail("无法分配像素缓冲", 3) }
            CVPixelBufferLockBaseAddress(pixel, [])
            if let base = CVPixelBufferGetBaseAddress(pixel) {
                memset(base, 0, CVPixelBufferGetDataSize(pixel))
            }
            CVPixelBufferUnlockBaseAddress(pixel, [])

            if let leftBuffer {
                _ = blitter.blit(source: leftBuffer, into: pixel, xOffset: 0)
            }
            if let right = currentRightBuffer {
                _ = blitter.blit(source: right, into: pixel, xOffset: width / 2)
            }
            nsBlit &+= stamp() &- tBlit0

            let present = CMTime(value: max(0, pts), timescale: 10_000_000)
            // 增长索引若出现 PTS 回绕，跳过该帧，避免 AVAssetWriter 直接失败。
            if frameIndex > 0, present <= lastPresent {
                continue
            }
            let tApp0 = stamp()
            if !adaptor.append(pixel, withPresentationTime: present) {
                let detail = writer.error?.localizedDescription ?? "status=\(writer.status.rawValue)"
                return fail("写入画面失败: \(detail) pts=\(pts)", 3)
            }
            nsAppend &+= stamp() &- tApp0
            lastPresent = present
            frameIndex += 1
            let videoProgress = min(0.95, Double(max(0, pts)) / totalPts * 0.95)
            let percent = Int(videoProgress * 1000)
            if percent != lastReported {
                lastReported = percent
                print(String(format: "progress %.3f", videoProgress))
                fflush(stdout)
                if percent > 0, percent % 50 == 0 { reportTiming(tag: String(format: "%.0f%%", videoProgress * 100)) }
            }
        }

        reportTiming(tag: "end-video")
        videoIn.markAsFinished()
        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting { semaphore.signal() }
        semaphore.wait()
        guard writer.status == .completed else {
            let detail = writer.error?.localizedDescription ?? "unknown"
            return fail("写出画面失败: \(detail)", 3)
        }

        if wantAudio, let audioConfig {
            print("progress 0.960")
            fflush(stdout)
            let muxCode = muxIndexedAudio(video: videoOnly, leftDir: leftDir, audioConfig: audioConfig, output: output)
            try? FileManager.default.removeItem(at: videoOnly)
            if muxCode != 0 { return muxCode }
        } else {
            do {
                try FileManager.default.moveItem(at: videoOnly, to: output)
            } catch {
                return fail("移动成片失败", 3)
            }
        }
        print("progress 1.000")
        fflush(stdout)
        return 0
    }

    /// 无声成片 + 左路索引 AAC → 最终成片（视频直通、AAC 原样）。
    private static func muxIndexedAudio(
        video: URL,
        leftDir: URL,
        audioConfig: AudioConfig,
        output: URL
    ) -> Int32 {
        guard let audioFormat = audioFormatDescription(audioConfig) else {
            return fail("无法创建音轨格式", 2)
        }
        let openDeadline = Date().addingTimeInterval(120)
        var audioCursor: SampleCursor?
        while Date() < openDeadline, audioCursor == nil {
            audioCursor = SampleCursor(directory: leftDir, indexName: "audio.idx")
            if audioCursor == nil { usleep(20_000) }
        }
        guard let audioCursor else { return fail("无法打开音轨索引", 2) }

        let asset = AVAsset(url: video)
        guard let videoTrack = asset.tracks(withMediaType: .video).first else {
            return fail("无声成片缺少画面轨", 3)
        }
        let reader: AVAssetReader
        do {
            reader = try AVAssetReader(asset: asset)
        } catch {
            return fail("无法读取无声成片", 3)
        }
        let videoOut = AVAssetReaderTrackOutput(track: videoTrack, outputSettings: nil)
        videoOut.alwaysCopiesSampleData = false
        guard reader.canAdd(videoOut) else { return fail("无法读取画面轨", 3) }
        reader.add(videoOut)

        try? FileManager.default.removeItem(at: output)
        let writer: AVAssetWriter
        do {
            writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
        } catch {
            return fail("无法创建成片写出器", 3)
        }
        guard let videoFormat = videoTrack.formatDescriptions.first else {
            return fail("无声成片缺少画面格式", 3)
        }
        let videoIn = AVAssetWriterInput(
            mediaType: .video,
            outputSettings: nil,
            sourceFormatHint: (videoFormat as! CMFormatDescription)
        )
        videoIn.expectsMediaDataInRealTime = false
        let audioIn = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: audioFormat)
        audioIn.expectsMediaDataInRealTime = false
        guard writer.canAdd(videoIn) else { return fail("无法添加成片画面轨", 3) }
        writer.add(videoIn)
        guard writer.canAdd(audioIn) else { return fail("无法添加成片音轨", 3) }
        writer.add(audioIn)
        guard reader.startReading(), writer.startWriting() else { return fail("无法开始挂音轨", 3) }
        writer.startSession(atSourceTime: .zero)

        let audioDuration = CMTime(value: 1024, timescale: CMTimeScale(audioConfig.sampleRate))
        var audioEnded = false
        var audioHold: (Record, Data)?
        var timelineBase: Int64?
        var videoDone = false
        var audioDone = false

        func pullAudio() {
            if audioHold != nil || audioEnded { return }
            switch audioCursor.next() {
            case .sample(let r, let d):
                if timelineBase == nil { timelineBase = min(0, r.pts) }
                audioHold = (r, d)
            case .end: audioEnded = true
            case .error: audioEnded = true
            }
        }

        while !(videoDone && audioDone) {
            var progressed = false
            if !videoDone, videoIn.isReadyForMoreMediaData {
                if let sample = videoOut.copyNextSampleBuffer() {
                    if !videoIn.append(sample) { return fail("挂音轨时写入画面失败", 3) }
                    progressed = true
                } else {
                    videoIn.markAsFinished()
                    videoDone = true
                    progressed = true
                }
            }
            if !audioDone, audioIn.isReadyForMoreMediaData {
                pullAudio()
                if let hold = audioHold {
                    let base = timelineBase ?? 0
                    guard let sample = makeSampleBuffer(
                        payload: hold.1,
                        pts100ns: hold.0.pts - base,
                        duration: audioDuration,
                        format: audioFormat
                    ) else {
                        return fail("构造音轨样本失败", 3)
                    }
                    if !audioIn.append(sample) { return fail("写入音轨失败", 3) }
                    audioHold = nil
                    progressed = true
                } else if audioEnded {
                    audioIn.markAsFinished()
                    audioDone = true
                    progressed = true
                }
            }
            if !progressed { usleep(2_000) }
        }

        let semaphore = DispatchSemaphore(value: 0)
        writer.finishWriting { semaphore.signal() }
        semaphore.wait()
        guard writer.status == .completed else {
            let detail = writer.error?.localizedDescription ?? "unknown"
            return fail("挂音轨写出失败: \(detail)", 3)
        }
        return 0
    }

    private static func fail(_ reason: String, _ code: Int32) -> Int32 {
        FileHandle.standardError.write((reason + "\n").data(using: .utf8)!)
        return code
    }
}

private extension Data {
    func leUInt64(_ offset: Int) -> UInt64 {
        withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt64.self) }
    }
    func leInt64(_ offset: Int) -> Int64 {
        withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: Int64.self) }
    }
    func leUInt32(_ offset: Int) -> UInt32 {
        withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self) }
    }
    func leInt32(_ offset: Int) -> Int32 {
        withUnsafeBytes { $0.loadUnaligned(fromByteOffset: offset, as: Int32.self) }
    }
}
