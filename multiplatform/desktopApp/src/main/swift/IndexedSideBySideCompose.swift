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
                    // Writer 先 flush 索引再写 finished；短文件探测与看到 marker 之间
                    // 可能刚好刷出尾部完整 24B 记录，必须再读一轮后才能当结束。
                    let drained = (try? index.seekToEnd()) ?? 0
                    try? index.seek(toOffset: 0)
                    if drained >= (count + 1) * 24 { break }
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

    private struct DecodedPicture {
        let pts: Int64
        let buffer: CVPixelBuffer?
    }

    /// 单路解码输出 → 编码线程的有界队列；满则阻塞解码端，限制在途像素缓冲数量。
    private final class PictureQueue {
        private let condition = NSCondition()
        private var items: [DecodedPicture] = []
        private var finished = false
        private var cancelled = false
        private let capacity: Int

        init(capacity: Int) { self.capacity = capacity }

        var isCancelled: Bool {
            condition.lock()
            defer { condition.unlock() }
            return cancelled
        }

        func push(_ picture: DecodedPicture) {
            condition.lock()
            defer { condition.unlock() }
            while items.count >= capacity, !cancelled { condition.wait() }
            if cancelled { return }
            items.append(picture)
            condition.broadcast()
        }

        /// 等到队首可用；队列结束且已取空时返回 nil。
        func peek() -> DecodedPicture? {
            condition.lock()
            defer { condition.unlock() }
            while items.isEmpty, !finished, !cancelled { condition.wait() }
            return items.first
        }

        func pop() -> DecodedPicture? {
            condition.lock()
            defer { condition.unlock() }
            while items.isEmpty, !finished, !cancelled { condition.wait() }
            if items.isEmpty { return nil }
            let picture = items.removeFirst()
            condition.broadcast()
            return picture
        }

        func finish() {
            condition.lock()
            finished = true
            condition.broadcast()
            condition.unlock()
        }

        func cancel() {
            condition.lock()
            cancelled = true
            condition.broadcast()
            condition.unlock()
        }
    }

    /// 一路硬件解码：异步提交、限制在途帧数，输出按回调顺序进入 PictureQueue。
    private final class Decoder {
        private var session: VTDecompressionSession?
        private let format: CMFormatDescription
        private let output: PictureQueue
        private let inFlight: DispatchSemaphore
        private var decodeFailures = 0

        /// 解码器直接输出等比缩放后的 NV12（硬件缩放），省掉逐帧像素传送与 CPU 清屏。
        init?(format: CMFormatDescription, outputWidth: Int, outputHeight: Int, output: PictureQueue, maxInFlight: Int) {
            self.format = format
            self.output = output
            self.inFlight = DispatchSemaphore(value: maxInFlight)
            var callback = VTDecompressionOutputCallbackRecord(
                decompressionOutputCallback: { refcon, _, status, _, imageBuffer, pts, _ in
                    guard let refcon else { return }
                    let decoder = Unmanaged<Decoder>.fromOpaque(refcon).takeUnretainedValue()
                    // 解码失败的帧仍按原时间戳占位，编码端对该半幅填黑，不改变总帧数。
                    let value = pts.isValid ? CMTimeConvertScale(pts, timescale: 10_000_000, method: .roundHalfAwayFromZero).value : Int64.min
                    decoder.output.push(DecodedPicture(pts: value, buffer: status == noErr ? imageBuffer : nil))
                    decoder.inFlight.signal()
                },
                decompressionOutputRefCon: nil
            )
            callback.decompressionOutputRefCon = Unmanaged.passUnretained(self).toOpaque()
            var session: VTDecompressionSession?
            // 与编码输入同为 video-range NV12，拼接只需按平面拷贝。
            let attrs: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferWidthKey: outputWidth,
                kCVPixelBufferHeightKey: outputHeight,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            ]
            let status = VTDecompressionSessionCreate(
                allocator: kCFAllocatorDefault,
                formatDescription: format,
                decoderSpecification: [
                    kVTVideoDecoderSpecification_EnableHardwareAcceleratedVideoDecoder: kCFBooleanTrue as Any,
                ] as CFDictionary,
                imageBufferAttributes: attrs as CFDictionary,
                outputCallback: &callback,
                decompressionSessionOut: &session
            )
            guard status == noErr else { return nil }
            self.session = session
        }

        deinit {
            if let session { VTDecompressionSessionInvalidate(session) }
        }

        func submit(_ sample: CMSampleBuffer, pts: Int64) {
            inFlight.wait()
            guard let session else {
                output.push(DecodedPicture(pts: pts, buffer: nil))
                inFlight.signal()
                return
            }
            var flagsOut = VTDecodeInfoFlags()
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
                // 提交失败不会触发回调：自行占位并归还名额。
                output.push(DecodedPicture(pts: pts, buffer: nil))
                inFlight.signal()
            }
        }

        func finish() {
            if let session { VTDecompressionSessionWaitForAsynchronousFrames(session) }
            output.finish()
        }
    }

    /// 源画面等比放进半幅后的尺寸（偶数，NV12 色度对齐）。
    private static func fittedSize(source: VideoConfig, halfWidth: Int, height: Int) -> (Int, Int) {
        let scale = min(Double(halfWidth) / Double(source.width), Double(height) / Double(source.height))
        let w = max(2, Int((Double(source.width) * scale).rounded()) & ~1)
        let h = max(2, Int((Double(source.height) * scale).rounded()) & ~1)
        return (min(w, halfWidth & ~1), min(h, height & ~1))
    }

    /// NV12 并排拼接：解码器已缩好尺寸，这里只按平面逐行拷贝；letterbox 或缺帧时才填黑。
    private final class Nv12Composer {
        private let width: Int
        private let height: Int
        private let halfWidth: Int

        init(width: Int, height: Int) {
            self.width = width
            self.height = height
            self.halfWidth = (width / 2) & ~1
        }

        /// video-range 黑色：Y=16，CbCr=128。
        static func fillBlack(_ buffer: CVPixelBuffer) {
            CVPixelBufferLockBaseAddress(buffer, [])
            defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
            if let y = CVPixelBufferGetBaseAddressOfPlane(buffer, 0) {
                memset(y, 16, CVPixelBufferGetBytesPerRowOfPlane(buffer, 0) * CVPixelBufferGetHeightOfPlane(buffer, 0))
            }
            if let uv = CVPixelBufferGetBaseAddressOfPlane(buffer, 1) {
                memset(uv, 128, CVPixelBufferGetBytesPerRowOfPlane(buffer, 1) * CVPixelBufferGetHeightOfPlane(buffer, 1))
            }
        }

        func place(_ source: CVPixelBuffer, into destination: CVPixelBuffer, side: Int) {
            let srcW = CVPixelBufferGetWidth(source) & ~1
            let srcH = CVPixelBufferGetHeight(source) & ~1
            let copyW = min(srcW, halfWidth)
            let copyH = min(srcH, height & ~1)
            let x = side * halfWidth + ((halfWidth - copyW) / 2 & ~1)
            let y = ((height - copyH) / 2) & ~1
            CVPixelBufferLockBaseAddress(source, .readOnly)
            CVPixelBufferLockBaseAddress(destination, [])
            defer {
                CVPixelBufferUnlockBaseAddress(destination, [])
                CVPixelBufferUnlockBaseAddress(source, .readOnly)
            }
            for plane in 0..<2 {
                guard let src = CVPixelBufferGetBaseAddressOfPlane(source, plane),
                      let dst = CVPixelBufferGetBaseAddressOfPlane(destination, plane) else { continue }
                let srcStride = CVPixelBufferGetBytesPerRowOfPlane(source, plane)
                let dstStride = CVPixelBufferGetBytesPerRowOfPlane(destination, plane)
                // 两个平面每行字节数都等于像素宽（色度 CbCr 交错、行数减半）。
                let rows = plane == 0 ? copyH : copyH / 2
                let top = plane == 0 ? y : y / 2
                for row in 0..<rows {
                    memcpy(dst.advanced(by: (top + row) * dstStride + x), src.advanced(by: row * srcStride), copyW)
                }
            }
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
        let halfWidth = (width / 2) & ~1
        let leftFit = fittedSize(source: leftConfig, halfWidth: halfWidth, height: height)
        let rightFit = fittedSize(source: rightConfig, halfWidth: halfWidth, height: height)
        // 每路最多 4 帧在解码器内、6 帧在队列中，兼顾吞吐与内存。
        let leftPictures = PictureQueue(capacity: 6)
        let rightPictures = PictureQueue(capacity: 6)
        guard let leftCursor, let rightCursor,
              let leftDecoder = Decoder(format: leftFormat, outputWidth: leftFit.0, outputHeight: leftFit.1,
                                        output: leftPictures, maxInFlight: 4),
              let rightDecoder = Decoder(format: rightFormat, outputWidth: rightFit.0, outputHeight: rightFit.1,
                                         output: rightPictures, maxInFlight: 4) else {
            return fail("无法打开索引或解码器", 2)
        }
        // 两路都铺满半幅时无需逐帧填黑（课件路首帧到达前除外）。
        let fullCover = leftFit == (halfWidth, height) && rightFit == (halfWidth, height)

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
            AVVideoColorPropertiesKey: [
                AVVideoColorPrimariesKey: AVVideoColorPrimaries_ITU_R_709_2,
                AVVideoTransferFunctionKey: AVVideoTransferFunction_ITU_R_709_2,
                AVVideoYCbCrMatrixKey: AVVideoYCbCrMatrix_ITU_R_709_2,
            ],
            AVVideoCompressionPropertiesKey: [
                AVVideoAverageBitRateKey: bitRate,
                AVVideoMaxKeyFrameIntervalKey: 250,
                AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel,
            ],
        ])
        videoIn.expectsMediaDataInRealTime = false
        let pixelAttributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelBufferWidthKey as String: width,
            kCVPixelBufferHeightKey as String: height,
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as CFDictionary,
        ]
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: videoIn,
            sourcePixelBufferAttributes: pixelAttributes
        )
        guard writer.canAdd(videoIn) else { return fail("无法添加视频轨", 2) }
        writer.add(videoIn)
        guard writer.startWriting() else { return fail("无法开始写出", 2) }
        writer.startSession(atSourceTime: .zero)

        let composer = Nv12Composer(width: width, height: height)
        FileHandle.standardError.write(Data("compose NV12 decoder-scaled pipelined\n".utf8))
        var pixelPool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, pixelAttributes as CFDictionary, &pixelPool)
        let totalPts = max(durationSeconds, 0.001) * 10_000_000
        var lastReported = -1
        var frameIndex: Int64 = 0
        var lastPresent = CMTime.invalid
        // 分段计时（纳秒）：消费端等解码 / 等编码器 / 拼接 / append。
        var nsDecode: UInt64 = 0
        var nsWaitReady: UInt64 = 0
        var nsBlit: UInt64 = 0
        var nsAppend: UInt64 = 0
        let nsPullIdle: UInt64 = 0
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

        // 两路各一条喂料线程，异步提交给各自的硬件解码器；编码线程按教师路时间戳配对课件路。
        // .error 必须让整次合成失败，不能与 .end 一并当正常收尾（否则中途坏读会截断却标完成）。
        final class PumpFailure {
            private let lock = NSLock()
            private var failed = false
            func mark() { lock.lock(); failed = true; lock.unlock() }
            var isFailed: Bool { lock.lock(); defer { lock.unlock() }; return failed }
        }
        let pumpFailure = PumpFailure()
        func startPump(cursor: SampleCursor, format: CMFormatDescription, decoder: Decoder, pictures: PictureQueue) {
            let thread = Thread {
                pump: while !pictures.isCancelled {
                    switch cursor.next() {
                    case .sample(let record, let payload):
                        if let sample = makeSampleBuffer(
                            payload: payload,
                            pts100ns: record.pts,
                            duration: CMTime(value: 1, timescale: 25),
                            format: format
                        ) {
                            decoder.submit(sample, pts: record.pts)
                        } else {
                            pictures.push(DecodedPicture(pts: record.pts, buffer: nil))
                        }
                    case .end:
                        break pump
                    case .error:
                        pumpFailure.mark()
                        leftPictures.cancel()
                        rightPictures.cancel()
                        break pump
                    }
                }
                decoder.finish()
            }
            thread.qualityOfService = .userInitiated
            thread.start()
        }
        startPump(cursor: leftCursor, format: leftFormat, decoder: leftDecoder, pictures: leftPictures)
        startPump(cursor: rightCursor, format: rightFormat, decoder: rightDecoder, pictures: rightPictures)
        defer {
            leftPictures.cancel()
            rightPictures.cancel()
        }
        // 以教师路（左）为时钟；课件路保留最近一帧像素缓冲。
        var currentRightBuffer: CVPixelBuffer?

        while true {
            let tDec0 = stamp()
            guard let leftPicture = leftPictures.pop() else { break }
            if pumpFailure.isFailed { return fail("读取索引或样本失败", 3) }
            // 课件路吃完 ≤ 教师 PTS 的画面（通常 0–1 帧）；解码失败的帧沿用上一帧。
            while let head = rightPictures.peek(), head.pts <= leftPicture.pts {
                _ = rightPictures.pop()
                if let buffer = head.buffer { currentRightBuffer = buffer }
            }
            nsDecode &+= stamp() &- tDec0
            let frame = (left: leftPicture.buffer, right: currentRightBuffer)
            let pts = leftPicture.pts

            let tWait0 = stamp()
            while !videoIn.isReadyForMoreMediaData { usleep(1_000) }
            nsWaitReady &+= stamp() &- tWait0
            let tBlit0 = stamp()
            var pixel: CVPixelBuffer?
            if let pixelPool {
                CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pixelPool, &pixel)
            }
            guard let pixel else { return fail("无法分配像素缓冲", 3) }
            if !fullCover || frame.left == nil || frame.right == nil {
                Nv12Composer.fillBlack(pixel)
            }
            if let left = frame.left { composer.place(left, into: pixel, side: 0) }
            if let right = frame.right { composer.place(right, into: pixel, side: 1) }
            CVBufferSetAttachment(pixel, kCVImageBufferColorPrimariesKey, kCVImageBufferColorPrimaries_ITU_R_709_2, .shouldPropagate)
            CVBufferSetAttachment(pixel, kCVImageBufferTransferFunctionKey, kCVImageBufferTransferFunction_ITU_R_709_2, .shouldPropagate)
            CVBufferSetAttachment(pixel, kCVImageBufferYCbCrMatrixKey, kCVImageBufferYCbCrMatrix_ITU_R_709_2, .shouldPropagate)
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

        if pumpFailure.isFailed { return fail("读取索引或样本失败", 3) }
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
        var audioReadFailed = false
        var audioHold: (Record, Data)?
        var timelineBase: Int64?
        var videoDone = false
        var audioDone = false

        func pullAudio() {
            if audioHold != nil || audioEnded || audioReadFailed { return }
            switch audioCursor.next() {
            case .sample(let r, let d):
                if timelineBase == nil { timelineBase = min(0, r.pts) }
                audioHold = (r, d)
            case .end: audioEnded = true
            case .error: audioReadFailed = true
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
                if audioReadFailed { return fail("读取音轨索引或样本失败", 3) }
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
