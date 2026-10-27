// 课程回放的 Windows 原生小程序（只用系统 Media Foundation，不额外打包编解码库）：
//
//   BJTUPlaybackCompositor.exe compose <左.mp4> <右.mp4> <输出.mp4> <宽> <高> <码率>
//                                  [--duration <秒>] [--cpu] [--software] [--adapter auto|amd|nvidia|intel]
//                                  [--without-audio] [--parent-pid <进程号>]
//
// 优先使用共享 D3D11 设备进行解码、缩放与硬件编码；不可用时回退 CPU 管线。
// 左路 AAC 原样写入成片。
//
// 标准输出逐行打印 `progress 0.123`；退出码 0 成功，非 0 失败（stderr 一行原因）。
//
// 说明：
// 1) Media Foundation 的属性名在不同 Windows SDK 里换过名字（MFMediaType_MajorType /
//    MF_MT_MAJOR_TYPE 等），这里直接写死 GUID 取值，保证任何 SDK 都能编译。
// 2) AAC 轨保留原始媒体类型和 AudioSpecificConfig，避免写入器丢失轨道配置。
// 3) 时长：Media Foundation 不给可靠的轨道时长，这里自己读 MP4 的 moov（mvhd/mdhd）；
//    真正的结束点始终是"解码到文件末尾"。

#define COBJMACROS
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <mftransform.h>
#include <mferror.h>
#include <objbase.h>
#include <windows.h>
#include <d3d11.h>
#include <d3d10.h>
#include <dxgi1_2.h>
#include <wrl.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>
#include <chrono>
#include <thread>
#include <cwctype>

namespace {

// 输出时钟固定 25 帧/秒：两路源帧率可能不同（教师路约 25.6、课件路约 34.5），统一重采样到 25。
constexpr int kOutputFps = 25;
constexpr long long kFrameDuration100ns = 10000000LL / kOutputFps;

// Media Foundation 属性（GUID 取值固定，不随 SDK 改名）。
constexpr GUID kAttrMajorType = {0x48eba18e, 0xf8c9, 0x4687, {0xbf, 0x11, 0x0a, 0x74, 0xc9, 0xf9, 0x6a, 0x8f}};
constexpr GUID kAttrSubtype = {0xf7e34c9a, 0x42e8, 0x4714, {0xb7, 0x4b, 0xcb, 0x29, 0xd7, 0x2c, 0x35, 0xe5}};
constexpr GUID kAttrFixedSizeSamples = {0xb8ebefaf, 0xb718, 0x4e04, {0xb0, 0xa9, 0x11, 0x67, 0x75, 0xe3, 0x32, 0x1b}};
constexpr GUID kAttrAllSamplesIndependent = {0xc9173739, 0x5e56, 0x461c, {0xb7, 0x13, 0x46, 0xfb, 0x99, 0x5c, 0xb9, 0x5f}};
constexpr GUID kAttrSampleSize = {0xdad3ab78, 0x1990, 0x408b, {0xbc, 0xe2, 0xeb, 0xa6, 0x73, 0xda, 0xcc, 0x10}};
constexpr GUID kAttrFrameSize = {0x1652c33d, 0xd6b2, 0x4012, {0xb8, 0x34, 0x72, 0x03, 0x08, 0x49, 0xa3, 0x7d}};
constexpr GUID kAttrFrameRate = {0xc459a2e8, 0x3d2c, 0x4e44, {0xb1, 0x32, 0xfe, 0xe5, 0x15, 0x6c, 0x7b, 0xb0}};
constexpr GUID kAttrAvgBitrate = {0x20332624, 0xfb0d, 0x4d9e, {0xbd, 0x0d, 0xcb, 0xf6, 0x78, 0x6c, 0x10, 0x2e}};
constexpr GUID kAttrDefaultStride = {0x644b4e48, 0x1e02, 0x4516, {0xb0, 0xeb, 0xc0, 0x1c, 0xa9, 0xd4, 0x9a, 0xc6}};
constexpr GUID kAttrPresentationDuration = {0x6c990d33, 0xbb8e, 0x477a, {0x85, 0x98, 0x0d, 0x5d, 0x96, 0xfc, 0xd8, 0x8a}};
constexpr GUID kAttrMediaTypeDuration = {0x2c2c4a70, 0x4389, 0x4710, {0x8e, 0xcb, 0x08, 0x84, 0x37, 0x2a, 0x2b, 0xca}};

// 音频属性：源文件解出来的原生类型缺了"每帧采样数"，MP4 写入器会拒收，这里按 AAC-LC 补齐。
constexpr GUID kAttrAudioSampleRate = {0x5faeeae7, 0x0290, 0x4c31, {0x9e, 0x8a, 0xc5, 0x34, 0xf6, 0x8d, 0x9d, 0xba}};
constexpr GUID kAttrAudioChannels = {0x37e48bf5, 0x645e, 0x4c5b, {0x89, 0xde, 0xad, 0xa9, 0xe2, 0x9b, 0x69, 0x6a}};
constexpr GUID kAttrAudioBitsPerSample = {0xf2deb57f, 0x40fa, 0x4764, {0xaa, 0x33, 0xed, 0x4f, 0x2d, 0x1f, 0xf6, 0x69}};
constexpr GUID kAttrAudioBlockAlignment = {0x322de230, 0x9eeb, 0x43bd, {0xab, 0x7a, 0xff, 0x41, 0x22, 0x51, 0x54, 0x1d}};
constexpr GUID kAttrAudioAvgBytesPerSecond = {0x1aab75c8, 0xcfef, 0x451c, {0xab, 0x95, 0xac, 0x03, 0x4b, 0x8e, 0x17, 0x31}};
constexpr GUID kAttrAudioSamplesPerFrame = {0xaab15aac, 0xe13a, 0x4995, {0x92, 0x22, 0x50, 0x1e, 0xa1, 0x5c, 0x68, 0x77}};
constexpr GUID kAttrUserData = {0xb6bc765f, 0x4c3b, 0x40a4, {0xbd, 0x51, 0x25, 0x35, 0xb6, 0x6f, 0xe0, 0x9d}};

std::string describe(HRESULT hr) {
    char buffer[32];
    snprintf(buffer, sizeof(buffer), "0x%08X", static_cast<unsigned int>(hr));
    return std::string(buffer);
}

void reportFailure(const std::string& reason) {
    fprintf(stderr, "%s\n", reason.c_str());
    fflush(stderr);
}

/**
 * 读 MP4 盒子时长（秒）。
 *
 * Media Foundation 不给可靠的轨道时长，而进度条需要知道录像多长，这里自己解析 moov：
 * 优先用 mvhd（影片时长），没有就退回各条轨道的 mdhd（媒体时长）取最大值。
 * 只支持标准 MP4（moov 在文件头，样本在 mdat），学校录播重封装出来的文件正是这种。
 */
class Mp4DurationReader {
public:
    explicit Mp4DurationReader(const std::wstring& path) : stream_(path, std::ios::binary) {}

    double seconds() {
        if (!stream_.is_open()) return 0.0;
        long long offset = 0;
        BoxHeader header;
        while (readHeader(offset, header)) {
            if (std::memcmp(header.type, "moov", 4) == 0) return parseMoov(header);
            offset += header.size;
        }
        return 0.0;
    }

private:
    struct BoxHeader {
        long long start = 0;      // 盒子起始偏移
        long long size = 0;       // 盒子总长（含头）
        long long bodyOffset = 0; // 盒体起始偏移
        char type[4] = {0, 0, 0, 0};
    };

    bool readHeader(long long offset, BoxHeader& header) {
        if (offset < 0) return false;
        stream_.clear();
        stream_.seekg(static_cast<std::streamoff>(offset));
        unsigned char head[16];
        stream_.read(reinterpret_cast<char*>(head), 8);
        if (stream_.gcount() != 8) return false;
        long long size = (static_cast<long long>(head[0]) << 24) |
                         (static_cast<long long>(head[1]) << 16) |
                         (static_cast<long long>(head[2]) << 8) |
                         static_cast<long long>(head[3]);
        std::memcpy(header.type, head + 4, 4);
        long long headerSize = 8;
        if (size == 1) {
            // 64 位长度：后 8 字节是真实长度。
            stream_.read(reinterpret_cast<char*>(head), 8);
            if (stream_.gcount() != 8) return false;
            size = 0;
            for (int index = 0; index < 8; index++) {
                size = (size << 8) | head[index];
            }
            headerSize = 16;
        }
        if (size < headerSize) return false;
        header.start = offset;
        header.size = size;
        header.bodyOffset = offset + headerSize;
        return true;
    }

    /** 读 mvhd / mdhd 这类"时间盒"：版本 + 创建/修改时间 + 时间刻度 + 时长。 */
    double readTimeBox(const BoxHeader& box) {
        unsigned char head[4];
        stream_.clear();
        stream_.seekg(static_cast<std::streamoff>(box.bodyOffset));
        stream_.read(reinterpret_cast<char*>(head), 4);
        if (stream_.gcount() != 4) return 0.0;
        const int version = head[0];
        // 跳过创建时间与修改时间（v0 各 4 字节，v1 各 8 字节），接着是时间刻度与时长。
        const int timeBytes = version == 1 ? 8 : 4;
        stream_.seekg(static_cast<std::streamoff>(box.bodyOffset + 4 + timeBytes * 2));
        unsigned char rest[12];
        stream_.read(reinterpret_cast<char*>(rest), 12);
        if (stream_.gcount() != 12) return 0.0;
        unsigned long long timescale = 0;
        for (int index = 0; index < 4; index++) {
            timescale = (timescale << 8) | rest[index];
        }
        unsigned long long duration = 0;
        const int durationBytes = version == 1 ? 8 : 4;
        for (int index = 0; index < durationBytes; index++) {
            duration = (duration << 8) | rest[4 + index];
        }
        if (timescale == 0) return 0.0;
        return static_cast<double>(duration) / static_cast<double>(timescale);
    }

    double parseMoov(const BoxHeader& moov) {
        double movie = 0.0;
        double media = 0.0;
        const long long end = moov.start + moov.size;
        long long offset = moov.bodyOffset;
        BoxHeader child;
        while (offset < end && readHeader(offset, child)) {
            if (std::memcmp(child.type, "mvhd", 4) == 0) {
                movie = readTimeBox(child);
            } else if (std::memcmp(child.type, "trak", 4) == 0) {
                const double track = parseTrack(child);
                if (track > media) media = track;
            }
            offset += child.size;
        }
        return movie > 0.0 ? movie : media;
    }

    double parseTrack(const BoxHeader& trak) {
        const long long end = trak.start + trak.size;
        long long offset = trak.bodyOffset;
        BoxHeader child;
        while (offset < end && readHeader(offset, child)) {
            if (std::memcmp(child.type, "mdia", 4) == 0) {
                const double media = parseMedia(child);
                if (media > 0.0) return media;
            }
            offset += child.size;
        }
        return 0.0;
    }

    double parseMedia(const BoxHeader& mdia) {
        const long long end = mdia.start + mdia.size;
        long long offset = mdia.bodyOffset;
        BoxHeader child;
        while (offset < end && readHeader(offset, child)) {
            if (std::memcmp(child.type, "mdhd", 4) == 0) return readTimeBox(child);
            offset += child.size;
        }
        return 0.0;
    }

    std::ifstream stream_;
};




/** 极简 COM 指针包装：析构自动 Release，避免异常路径泄漏。 */
template <typename T>
class ComPtr {
public:
    ComPtr() = default;
    ~ComPtr() { reset(); }
    ComPtr(const ComPtr&) = delete;
    ComPtr& operator=(const ComPtr&) = delete;
    ComPtr(ComPtr&& other) noexcept : ptr_(other.ptr_) { other.ptr_ = nullptr; }
    ComPtr& operator=(ComPtr&& other) noexcept {
        if (this != &other) {
            reset();
            ptr_ = other.ptr_;
            other.ptr_ = nullptr;
        }
        return *this;
    }
    T** put() {
        reset();
        return &ptr_;
    }
    T* get() const { return ptr_; }
    T* operator->() const { return ptr_; }
    explicit operator bool() const { return ptr_ != nullptr; }
    /** 放弃所有权但不释放（调用方接管指针）。 */
    void detach() { ptr_ = nullptr; }
    void reset() {
        if (ptr_ != nullptr) {
            ptr_->Release();
            ptr_ = nullptr;
        }
    }

private:
    T* ptr_ = nullptr;
};

HRESULT createMediaType(const GUID& majorType, const GUID& subtype, IMFMediaType** out) {
    ComPtr<IMFMediaType> type;
    HRESULT hr = MFCreateMediaType(type.put());
    if (FAILED(hr)) return hr;
    hr = type->SetGUID(kAttrMajorType, majorType);
    if (FAILED(hr)) return hr;
    hr = type->SetGUID(kAttrSubtype, subtype);
    if (FAILED(hr)) return hr;
    *out = type.get();
    type.detach();
    return S_OK;
}

#include "PlaybackLiveSource.h"

/** 解码出来的一帧（统一成 NV12 存放）。 */
struct VideoFrame {
    int width = 0;
    int height = 0;
    long long pts = 0;
    std::vector<uint8_t> nv12;

    uint8_t* luma() { return nv12.data(); }
    const uint8_t* luma() const { return nv12.data(); }
    uint8_t* chroma() { return nv12.data() + static_cast<size_t>(width) * height; }
    const uint8_t* chroma() const { return nv12.data() + static_cast<size_t>(width) * height; }
};

/**
 * 一路录像的解码端：找到视频轨，按 NV12（退化时 I420）输出解出来的帧，并按时间戳推进。
 * 解码固定走软件路径：硬件解码会给出 D3D11 纹理，拿不到系统内存里的像素。
 */
class VideoDecoder {
public:
    bool open(const std::wstring& path, bool indexed = false, long long duration100ns = 0) {
        ComPtr<IMFAttributes> attributes;
        HRESULT hr = MFCreateAttributes(attributes.put(), 0);
        if (FAILED(hr)) return failHr("创建属性集失败", hr);

        UINT32 processing = 1;
        attributes->SetUINT32(MF_SOURCE_READER_ENABLE_VIDEO_PROCESSING, processing);
        if (indexed) {
            ComPtr<IMFMediaSource> source;
            hr = createLiveMediaSource(path, duration100ns, source.put());
            if (SUCCEEDED(hr)) hr = MFCreateSourceReaderFromMediaSource(source.get(), attributes.get(), reader_.put());
        } else {
            hr = MFCreateSourceReaderFromURL(path.c_str(), attributes.get(), reader_.put());
        }
        if (FAILED(hr)) return failHr("打开录像失败", hr);

        // 找视频轨：原生类型里 major type 是 video 的那条。
        for (DWORD index = 0;; index++) {
            ComPtr<IMFMediaType> native;
            hr = reader_->GetNativeMediaType(index, 0, native.put());
            if (FAILED(hr)) break;
            GUID major{};
            if (FAILED(native->GetGUID(kAttrMajorType, &major))) continue;
            if (major != MFMediaType_Video) continue;

            UINT64 frameSize = 0;
            if (FAILED(native->GetUINT64(kAttrFrameSize, &frameSize))) {
                return fail("读不到录像画面尺寸");
            }
            // MF_MT_FRAME_SIZE：HI32 是宽，LO32 是高。
            width_ = static_cast<int>(frameSize >> 32);
            height_ = static_cast<int>(frameSize & 0xFFFFFFFFull);
            if (width_ <= 0 || height_ <= 0) return fail("录像画面尺寸非法");
            bufferHeight_ = height_;

            UINT64 frameRate = 0;
            if (SUCCEEDED(native->GetUINT64(kAttrFrameRate, &frameRate))) {
                // MF_MT_FRAME_RATE：HI32 是分子，LO32 是分母；但老版本写入器会用相反顺序，
                // 出现"分母 > 分子"时按旧顺序纠正。
                UINT32 low = static_cast<UINT32>(frameRate & 0xFFFFFFFFull);
                UINT32 high = static_cast<UINT32>(frameRate >> 32);
                if (low > high) {
                    frameRateNumerator_ = low;
                    frameRateDenominator_ = high;
                } else {
                    frameRateNumerator_ = high;
                    frameRateDenominator_ = low;
                }
            }

            streamIndex_ = index;
            if (!configureOutputType()) return false;
            readDuration(native.get());

            return true;
        }
        return fail("录像里没有画面轨");
    }

    IMFSourceReader* reader() const { return reader_.get(); }

    /** 读取下一帧；到流末尾返回 false。 */
    bool read(VideoFrame& frame) {
        if (exhausted_ || failed_) return false;
        ComPtr<IMFSample> sample;
        DWORD actualStream = 0;
        DWORD flags = 0;
        long long pts = 0;
        HRESULT hr;
        while (true) {
            hr = reader_->ReadSample(streamIndex_, 0, &actualStream, &flags, &pts, sample.put());
            if (hr == MF_E_END_OF_STREAM) { exhausted_ = true; return false; }
            if (FAILED(hr)) { failed_ = true; return failHr("读取画面失败", hr); }
            if (flags & MF_SOURCE_READERF_ERROR) { failed_ = true; return fail("解码中断"); }
            if (flags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) {
                ComPtr<IMFMediaType> current;
                UINT w = 0, h = 0; GUID subtype{};
                const HRESULT typeHr = reader_->GetCurrentMediaType(streamIndex_, current.put());
                const HRESULT sizeHr = SUCCEEDED(typeHr) ? MFGetAttributeSize(current.get(), MF_MT_FRAME_SIZE, &w, &h) : typeHr;
                const HRESULT subtypeHr = SUCCEEDED(typeHr) ? current->GetGUID(MF_MT_SUBTYPE, &subtype) : typeHr;
                const UINT maxW = (static_cast<UINT>(width_) + 63u) & ~63u;
                const UINT maxH = (static_cast<UINT>(height_) + 63u) & ~63u;
                if (FAILED(typeHr) || FAILED(sizeHr) || FAILED(subtypeHr) ||
                    subtype != (layoutIsNv12_ ? MFVideoFormat_NV12 : MFVideoFormat_I420) ||
                    w < static_cast<UINT>(width_) || w > maxW || h < static_cast<UINT>(height_) || h > maxH) {
                    fprintf(stderr, "cpu type change: requested=%dx%d actual=%ux%u subtype=0x%08X hr=%08X/%08X/%08X\n",
                        width_, height_, w, h, subtype.Data1, static_cast<unsigned>(typeHr),
                        static_cast<unsigned>(sizeHr), static_cast<unsigned>(subtypeHr));
                    failed_ = true; return fail("画面格式发生不兼容变化");
                }
                readCurrentStride();
            }
            if (flags & MF_SOURCE_READERF_ENDOFSTREAM) exhausted_ = true;
            if (sample) break;
            if (exhausted_) return false;
            // 成功但无样本可能是 stream tick，不能当作结束。
        }

        ComPtr<IMFMediaBuffer> buffer;
        hr = sample->GetBufferByIndex(0, buffer.put());
        if (FAILED(hr)) {
            failed_ = true;
            failHr("取画面缓冲失败", hr);
            return false;
        }
        BYTE* data = nullptr;
        DWORD maxLength = 0;
        DWORD currentLength = 0;
        hr = buffer->Lock(&data, &maxLength, &currentLength);
        if (FAILED(hr)) {
            failed_ = true;
            failHr("锁定画面缓冲失败", hr);
            return false;
        }
        frame.width = width_;
        frame.height = height_;
        frame.pts = pts;
        frame.nv12.assign(static_cast<size_t>(width_) * height_ * 3 / 2, 0);
        const bool copied = copyToNv12(data, currentLength != 0 ? currentLength : maxLength, frame);
        buffer->Unlock();
        if (!copied) { failed_ = true; return fail("解码画面缓冲不完整"); }
        return true;
    }

    /** 推进到目标时间：保留 pts <= target 的最新一帧。 */
    bool advanceTo(long long targetPts) {
        while (true) {
            if (!hasNext_) {
                hasNext_ = read(nextFrame_);
                if (!hasNext_) return hasCurrent_;
            }
            if (nextFrame_.pts > targetPts) return hasCurrent_;
            currentFrame_ = nextFrame_;
            hasCurrent_ = true;
            hasNext_ = false;
        }
    }

    const VideoFrame* current() const { return hasCurrent_ ? &currentFrame_ : nullptr; }

    /** 已经读到文件末尾（后面只剩最后一帧可以继续用）。 */
    bool exhausted() const { return exhausted_ && !hasNext_; }
    bool failed() const { return failed_; }

    long long duration() const { return duration_; }

private:
    bool fail(const std::string& reason) {
        reportFailure(reason);
        return false;
    }
    bool failHr(const std::string& reason, HRESULT hr) {
        reportFailure(reason + " (" + describe(hr) + ")");
        return false;
    }

    bool configureOutputType() {
        // 优先 NV12；系统解码器不支持时退回 I420（读取时再转成 NV12）。
        const GUID candidates[] = {MFVideoFormat_NV12, MFVideoFormat_I420};
        HRESULT lastFailure = E_FAIL;
        for (int slot = 0; slot < 2; slot++) {
            const GUID& subtype = candidates[slot];
            ComPtr<IMFMediaType> type;
            HRESULT hr = createMediaType(MFMediaType_Video, subtype, type.put());
            if (FAILED(hr)) {
                lastFailure = hr;
                continue;
            }
            UINT32 fixed = 1;
            UINT32 independent = 1;
            UINT32 sampleSize = static_cast<UINT32>(width_ * height_ * 3 / 2);
            type->SetUINT32(kAttrFixedSizeSamples, fixed);
            type->SetUINT32(kAttrAllSamplesIndependent, independent);
            type->SetUINT32(kAttrSampleSize, sampleSize);
            // 帧率与 stride 不要写进解码输出类型：系统解码器对这两项比较挑剔，写错会 E_INVALIDARG。
            hr = reader_->SetCurrentMediaType(streamIndex_, nullptr, type.get());
            if (SUCCEEDED(hr)) {
                layoutIsNv12_ = (subtype == MFVideoFormat_NV12);
                readCurrentStride();
                return true;
            }
            lastFailure = hr;
        }
        return failHr("设置解码输出格式失败", lastFailure);
    }

    void readCurrentStride() {
        stride_ = width_;
        ComPtr<IMFMediaType> current;
        if (FAILED(reader_->GetCurrentMediaType(streamIndex_, current.put()))) return;
        UINT currentWidth = 0, currentHeight = 0;
        if (SUCCEEDED(MFGetAttributeSize(current.get(), MF_MT_FRAME_SIZE, &currentWidth, &currentHeight)) &&
            currentHeight >= static_cast<UINT>(height_) && currentHeight <= ((static_cast<UINT>(height_) + 63u) & ~63u)) {
            bufferHeight_ = static_cast<int>(currentHeight);
        }
        UINT32 value = 0;
        if (SUCCEEDED(current->GetUINT32(kAttrDefaultStride, &value)) && value > 0) {
            stride_ = static_cast<int>(value);
        }
        if (stride_ < width_) stride_ = width_;
    }

    void readDuration(IMFMediaType* native) {
        duration_ = 0;
        // 先看轨道自身的时长（解封装器一般会填），再退回演示时长属性。
        UINT64 mediaDuration = 0;
        if (native != nullptr &&
            SUCCEEDED(native->GetUINT64(kAttrMediaTypeDuration, &mediaDuration)) &&
            mediaDuration > 0) {
            duration_ = static_cast<long long>(mediaDuration);
        }
        PROPVARIANT value;
        PropVariantInit(&value);
        if (duration_ <= 0 &&
            SUCCEEDED(reader_->GetPresentationAttribute(streamIndex_, kAttrPresentationDuration, &value)) &&
            value.vt == VT_UI8) {
            duration_ = static_cast<long long>(value.uhVal.QuadPart);
        }
        PropVariantClear(&value);
        if (duration_ <= 0) {
            // _metadata 不可信时先按一小时估算，真正的结束点以解码到文件末尾为准。
            duration_ = static_cast<long long>(3600.0 * 10000000.0);
        }
    }

    bool copyToNv12(const BYTE* data, DWORD length, VideoFrame& frame) {
        const int width = width_;
        const int height = height_;
        int stride = stride_;
        int bufferHeight = bufferHeight_;
        const size_t tight = static_cast<size_t>(width) * height * 3 / 2;
        if (length < tight) return false;
        if (static_cast<size_t>(stride) * bufferHeight * 3 / 2 > length) bufferHeight = height;
        if (static_cast<size_t>(stride) * height * 3 / 2 > length) {
            stride = width;  // 缓冲不够宽，说明没有对齐填充
        }

        uint8_t* dstY = frame.luma();
        const uint8_t* srcY = data;
        for (int row = 0; row < height; row++) {
            memcpy(dstY + static_cast<size_t>(row) * width,
                   srcY + static_cast<size_t>(row) * stride, width);
        }

        uint8_t* dstUv = frame.chroma();
        if (layoutIsNv12_) {
            const uint8_t* srcUv = data + static_cast<size_t>(stride) * bufferHeight;
            const int chromaHeight = height / 2;
            for (int row = 0; row < chromaHeight; row++) {
                memcpy(dstUv + static_cast<size_t>(row) * width,
                       srcUv + static_cast<size_t>(row) * stride, width);
            }
        } else {
            // I420：Y + U + V 三块平面，转成 NV12（UV 交错）。
            const int chromaWidth = width / 2;
            const int chromaHeight = height / 2;
            const int chromaStride = stride / 2;
            const uint8_t* srcU = data + static_cast<size_t>(stride) * bufferHeight;
            const uint8_t* srcV = srcU + static_cast<size_t>(chromaStride) * (bufferHeight / 2);
            for (int row = 0; row < chromaHeight; row++) {
                for (int column = 0; column < chromaWidth; column++) {
                    dstUv[static_cast<size_t>(row) * width + column * 2] =
                        srcU[static_cast<size_t>(row) * chromaStride + column];
                    dstUv[static_cast<size_t>(row) * width + column * 2 + 1] =
                        srcV[static_cast<size_t>(row) * chromaStride + column];
                }
            }
        }
        return true;
    }

    ComPtr<IMFSourceReader> reader_;
    DWORD streamIndex_ = 0;
    int width_ = 0;
    int height_ = 0;
    int bufferHeight_ = 0;
    int stride_ = 0;
    bool layoutIsNv12_ = true;
    UINT32 frameRateNumerator_ = 25;
    UINT32 frameRateDenominator_ = 1;
    long long duration_ = 0;
    bool exhausted_ = false;
    bool failed_ = false;
    VideoFrame currentFrame_;
    VideoFrame nextFrame_;
    bool hasCurrent_ = false;
    bool hasNext_ = false;
};

/**
 * box 滤波缩放：把源区域 [sx0,sy0)-(sx1,sy1) 映射到 dst 的 (dstWidth, dstHeight)。
 * 两遍可分离实现，常见整数倍缩放（2/3/4 倍）下速度足够。
 */
void resamplePlane(const uint8_t* src, int srcWidth, int srcHeight, int srcStride, int bytesPerPixel,
                   uint8_t* dst, int dstWidth, int dstHeight, int dstStride,
                   double sx0, double sy0, double sx1, double sy1,
                   std::vector<uint8_t>& scratch) {
    std::vector<int> columnStart(static_cast<size_t>(dstWidth));
    std::vector<int> columnEnd(static_cast<size_t>(dstWidth));
    for (int column = 0; column < dstWidth; column++) {
        double begin = sx0 + (sx1 - sx0) * column / dstWidth;
        double end = sx0 + (sx1 - sx0) * (column + 1) / dstWidth;
        int start = static_cast<int>(std::floor(begin));
        int stop = static_cast<int>(std::floor(end));
        start = std::max(0, std::min(start, srcWidth));
        stop = std::max(start + 1, std::min(stop, srcWidth));
        columnStart[static_cast<size_t>(column)] = start;
        columnEnd[static_cast<size_t>(column)] = stop;
    }
    std::vector<int> rowStart(static_cast<size_t>(dstHeight));
    std::vector<int> rowEnd(static_cast<size_t>(dstHeight));
    for (int row = 0; row < dstHeight; row++) {
        double begin = sy0 + (sy1 - sy0) * row / dstHeight;
        double end = sy0 + (sy1 - sy0) * (row + 1) / dstHeight;
        int start = static_cast<int>(std::floor(begin));
        int stop = static_cast<int>(std::floor(end));
        start = std::max(0, std::min(start, srcHeight));
        stop = std::max(start + 1, std::min(stop, srcHeight));
        rowStart[static_cast<size_t>(row)] = start;
        rowEnd[static_cast<size_t>(row)] = stop;
    }

    // 横向：源分辨率的每一行先缩成 dstWidth 列。
    const size_t scratchStride = static_cast<size_t>(dstWidth) * bytesPerPixel;
    scratch.assign(scratchStride * srcHeight, 0);
    for (int row = 0; row < srcHeight; row++) {
        const uint8_t* srcRow = src + static_cast<size_t>(row) * srcStride;
        uint8_t* scratchRow = scratch.data() + static_cast<size_t>(row) * scratchStride;
        for (int column = 0; column < dstWidth; column++) {
            const int start = columnStart[static_cast<size_t>(column)];
            const int count = columnEnd[static_cast<size_t>(column)] - start;
            for (int component = 0; component < bytesPerPixel; component++) {
                int sum = 0;
                for (int offset = 0; offset < count; offset++) {
                    sum += srcRow[static_cast<size_t>(start + offset) * bytesPerPixel + component];
                }
                scratchRow[static_cast<size_t>(column) * bytesPerPixel + component] =
                    static_cast<uint8_t>((sum + count / 2) / count);
            }
        }
    }

    // 纵向：把临时缓冲缩成 dstHeight 行。
    for (int row = 0; row < dstHeight; row++) {
        const int start = rowStart[static_cast<size_t>(row)];
        const int count = rowEnd[static_cast<size_t>(row)] - start;
        uint8_t* dstRow = dst + static_cast<size_t>(row) * dstStride;
        for (int column = 0; column < dstWidth; column++) {
            for (int component = 0; component < bytesPerPixel; component++) {
                int sum = 0;
                for (int offset = 0; offset < count; offset++) {
                    sum += scratch[(static_cast<size_t>(start + offset) * scratchStride) +
                                   static_cast<size_t>(column) * bytesPerPixel + component];
                }
                dstRow[static_cast<size_t>(column) * bytesPerPixel + component] =
                    static_cast<uint8_t>((sum + count / 2) / count);
            }
        }
    }

}

/** 把左右两帧并排放进一张 NV12 输出画面。 */
class SideBySideComposer {
public:
    SideBySideComposer(int width, int height) : width_(width), height_(height) {
        output_.assign(static_cast<size_t>(width) * height * 3 / 2, 0);
    }

    uint8_t* output() { return output_.data(); }
    size_t outputSize() const { return output_.size(); }

    void compose(const VideoFrame& left, const VideoFrame& right) {
        // 先把整幅画面填成黑（letterbox 区域）。
        memset(output_.data(), 16, static_cast<size_t>(width_) * height_);
        memset(output_.data() + static_cast<size_t>(width_) * height_, 128,
               static_cast<size_t>(width_) * height_ / 2);
        place(left, 0);
        place(right, width_ / 2);
    }

private:
    void place(const VideoFrame& frame, int xOffset) {
        const int sourceWidth = frame.width;
        const int sourceHeight = frame.height;
        if (sourceWidth <= 0 || sourceHeight <= 0) return;
        const int halfWidth = width_ / 2;
        const double scale = std::min(static_cast<double>(halfWidth) / sourceWidth,
                                      static_cast<double>(height_) / sourceHeight);
        int targetWidth = static_cast<int>(std::lround(sourceWidth * scale));
        int targetHeight = static_cast<int>(std::lround(sourceHeight * scale));
        targetWidth = std::max(2, std::min(targetWidth, halfWidth)) & ~1;
        targetHeight = std::max(2, std::min(targetHeight, height_)) & ~1;
        const int dx = ((halfWidth - targetWidth) / 2) & ~1;
        const int dy = ((height_ - targetHeight) / 2) & ~1;


        uint8_t* luma = output_.data();
        resamplePlane(frame.luma(), sourceWidth, sourceHeight, sourceWidth, 1,
                      luma + static_cast<size_t>(dy) * width_ + xOffset + dx,
                      targetWidth, targetHeight, width_,
                      0.0, 0.0, static_cast<double>(sourceWidth), static_cast<double>(sourceHeight),
                      lumaScratch_);

        const int chromaWidth = targetWidth / 2;
        const int chromaHeight = targetHeight / 2;
        if (chromaWidth <= 0 || chromaHeight <= 0) return;
        uint8_t* chroma = output_.data() + static_cast<size_t>(width_) * height_;
        // NV12 的色度平面每行仍是 width 个字节（只是行数减半），所以横向偏移按像素算、
        // 行步长按整幅宽度算；色度平面起点比亮度平面靠后 width*height 个字节。
        resamplePlane(frame.chroma(), sourceWidth / 2, sourceHeight / 2, sourceWidth, 2,
                      chroma + static_cast<size_t>(dy / 2) * width_ + xOffset + dx,
                      chromaWidth, chromaHeight, width_,
                      0.0, 0.0, static_cast<double>(sourceWidth / 2), static_cast<double>(sourceHeight / 2),
                      chromaScratch_);

    }

    int width_;
    int height_;
    std::vector<uint8_t> output_;
    std::vector<uint8_t> lumaScratch_;
    std::vector<uint8_t> chromaScratch_;
};

/** 输出 MP4：画面走 H.264 编码器，声音沿用左路的 AAC 压缩流（不重编码）。 */
class Mp4Writer {
public:
    bool open(const std::wstring& path, int width, int height, int bitrate, bool preferHardware,
              IMFMediaType* audioType, IMFDXGIDeviceManager* manager = nullptr) {
        if (manager) return create(path, width, height, bitrate, true, audioType, manager);
        // 新版 Windows 的 Sink Writer 匹配不到旧式软件编码器，但硬件编码器正常：
        // 先试硬件，失败再退回软件（老版本 Windows 只有软件路径可用）。
        if (preferHardware && create(path, width, height, bitrate, true, audioType)) return true;
        return create(path, width, height, bitrate, false, audioType);
    }

    /** 加一条声音轨：输入输出都是 AAC 压缩流（直通，不重编码）。 */
    bool addAudioStream(IMFMediaType* aacType) {
        // 注意：不能先 CopyAllItems 再 AddStream——复制出来的类型会丢掉类型身份，
        // 写入器会以 MF_E_INVALIDTYPE 拒收，直接用原类型即可。
        HRESULT hr = writer_->AddStream(aacType, &audioStream_);
        if (FAILED(hr)) return failHr("添加声音轨失败", hr);
        hr = writer_->SetInputMediaType(audioStream_, aacType, nullptr);
        if (FAILED(hr)) return failHr("设置声音输入类型失败", hr);
        return true;
    }

    bool begin() {
        HRESULT hr = writer_->BeginWriting();
        if (FAILED(hr)) return failHr("开始写入失败", hr);
        return true;
    }

    bool writeVideo(long long pts, const uint8_t* data, size_t size) {
        // 每帧单独建缓冲：写入器可能异步取样，共用一块缓冲会被下一帧覆盖。
        // 用 MFCreateMemoryBuffer（Vista 就有）而不是新版的 MFCreateMediaBufferFromMediaType，
        // 免得在旧系统上因为导入符号缺失而起不来。
        ComPtr<IMFMediaBuffer> buffer;
        HRESULT hr = MFCreateMemoryBuffer(static_cast<DWORD>(size), buffer.put());
        if (FAILED(hr)) return failHr("创建画面缓冲失败", hr);

        BYTE* target = nullptr;
        DWORD maxLength = 0;
        DWORD currentLength = 0;
        hr = buffer->Lock(&target, &maxLength, &currentLength);
        if (FAILED(hr)) return failHr("锁定画面缓冲失败", hr);
        const size_t copySize = std::min<size_t>(size, maxLength);

        if (copySize > 0 && target != nullptr) {
            memcpy(target, data, copySize);
        }
        buffer->SetCurrentLength(static_cast<DWORD>(copySize));
        buffer->Unlock();

        ComPtr<IMFSample> sample;
        hr = MFCreateSample(sample.put());
        if (FAILED(hr)) return failHr("创建画面样本失败", hr);
        hr = sample->AddBuffer(buffer.get());
        if (FAILED(hr)) return failHr("挂接画面缓冲失败", hr);
        hr = sample->SetSampleTime(pts);
        if (FAILED(hr)) return failHr("设置画面时间戳失败", hr);
        hr = sample->SetSampleDuration(kFrameDuration100ns);
        if (FAILED(hr)) return failHr("设置画面时长失败", hr);
        hr = writer_->WriteSample(videoStream_, sample.get());
        if (FAILED(hr)) return failHr("写入画面失败", hr);
        return true;
    }

    bool writeAudio(IMFSample* sample) {

        HRESULT hr = writer_->WriteSample(audioStream_, sample);
        if (FAILED(hr)) return failHr("写入声音失败", hr);
        return true;
    }

    bool writeGpuVideo(IMFSample* sample, long long pts) {
        sample->SetSampleTime(pts);
        sample->SetSampleDuration(kFrameDuration100ns);
        HRESULT hr = writer_->WriteSample(videoStream_, sample);
        return SUCCEEDED(hr) || failHr("写入GPU画面失败", hr);
    }

    bool finish() {
        HRESULT hr = writer_->Finalize();
        if (FAILED(hr)) return failHr("结束写入失败", hr);
        return true;
    }

private:
    bool create(const std::wstring& path, int width, int height, int bitrate, bool hardware,
                IMFMediaType* audioType, IMFDXGIDeviceManager* manager = nullptr) {
        ComPtr<IMFAttributes> attributes;
        HRESULT hr = MFCreateAttributes(attributes.put(), 0);
        if (FAILED(hr)) return failHr("创建属性集失败", hr);
        UINT32 disableThrottling = 1;
        attributes->SetUINT32(MF_SINK_WRITER_DISABLE_THROTTLING, disableThrottling);
        if (manager) {
            attributes->SetUnknown(MF_SINK_WRITER_D3D_MANAGER, manager);
            attributes->SetUINT32(MF_READWRITE_D3D_OPTIONAL, FALSE);
        }
        if (hardware) {
            UINT32 enableHardware = 1;
            attributes->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, enableHardware);
        }
        writer_.reset();
        hr = MFCreateSinkWriterFromURL(path.c_str(), nullptr, attributes.get(), writer_.put());
        if (FAILED(hr)) return failHr("创建 MP4 写入器失败", hr);

        // 声音轨先加：MP4 写入器按加入顺序建立轨道，先声音后画面更稳。
        if (audioType != nullptr && !addAudioStream(audioType)) return false;

        ComPtr<IMFMediaType> target;
        hr = createMediaType(MFMediaType_Video, MFVideoFormat_H264, target.put());
        if (FAILED(hr)) return failHr("创建视频类型失败", hr);
        UINT32 bitrateValue = static_cast<UINT32>(bitrate);
        target->SetUINT32(kAttrAvgBitrate, bitrateValue);
        // MF_MT_FRAME_SIZE：HI32 是宽，LO32 是高。
        UINT64 frameSize = (static_cast<UINT64>(height) & 0xFFFFFFFFull) |
                           (static_cast<UINT64>(width) << 32);
        target->SetUINT64(kAttrFrameSize, frameSize);
        // MF_MT_FRAME_RATE：HI32 是分子，LO32 是分母（25/1）。
        UINT64 frameRate = (static_cast<UINT64>(kOutputFps) << 32) |
                           static_cast<UINT64>(1);
        target->SetUINT64(kAttrFrameRate, frameRate);
        target->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        MFSetAttributeRatio(target.get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        hr = writer_->AddStream(target.get(), &videoStream_);
        if (FAILED(hr)) return failHr("添加视频轨失败", hr);

        ComPtr<IMFMediaType> input;
        hr = createMediaType(MFMediaType_Video, MFVideoFormat_NV12, input.put());
        if (FAILED(hr)) return failHr("创建视频输入类型失败", hr);
        UINT32 inputFixed = 1;
        UINT32 inputIndependent = 1;
        UINT32 inputSampleSize = static_cast<UINT32>(width * height * 3 / 2);
        input->SetUINT32(kAttrFixedSizeSamples, inputFixed);
        input->SetUINT32(kAttrAllSamplesIndependent, inputIndependent);
        input->SetUINT32(kAttrSampleSize, inputSampleSize);
        input->SetUINT64(kAttrFrameSize, frameSize);
        input->SetUINT64(kAttrFrameRate, frameRate);
        input->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        MFSetAttributeRatio(input.get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        hr = writer_->SetInputMediaType(videoStream_, input.get(), nullptr);
        if (FAILED(hr)) return failHr("设置视频输入类型失败", hr);
        return true;
    }

    bool failHr(const std::string& reason, HRESULT hr) {
        reportFailure(reason + " (" + describe(hr) + ")");
        return false;
    }

    ComPtr<IMFSinkWriter> writer_;
    DWORD videoStream_ = 0;
    DWORD audioStream_ = 0;
};

/** 左路的音轨：直接取压缩的 AAC 样本搬进输出文件。 */
class AudioTrack {
public:
    /** 只选声音轨，按样本流式搬运；不把整条音轨或未消费的视频轨缓存在内存。 */
    bool open(const std::wstring& path, bool indexed = false) {
        indexed_ = indexed;
        if (indexed_) return live_.open(path);
        HRESULT hr = MFCreateSourceReaderFromURL(path.c_str(), nullptr, reader_.put());
        if (FAILED(hr)) { failed_ = true; reportFailure("打开音轨失败 (" + describe(hr) + ")"); return false; }
        reader_->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE);
        for (DWORD index = 0;; index++) {
            ComPtr<IMFMediaType> native;
            if (FAILED(reader_->GetNativeMediaType(index, 0, native.put()))) break;
            GUID major{};
            if (FAILED(native->GetGUID(kAttrMajorType, &major))) continue;
            if (major != MFMediaType_Audio) continue;
            nativeType_ = std::move(native);
            streamIndex_ = index;
            break;
        }
        if (!nativeType_) return false;  // 没有音轨也不致命
        hr = reader_->SetCurrentMediaType(streamIndex_, nullptr, nativeType_.get());
        if (SUCCEEDED(hr)) hr = reader_->SetStreamSelection(streamIndex_, TRUE);
        if (FAILED(hr)) {
            failed_ = true; reportFailure("配置压缩音轨失败 (" + describe(hr) + ")"); return false;
        }
        return readNext(first_.put());
    }

    bool failed() const { return indexed_ ? live_.failed() : failed_; }

    /** 保留源的真实 AAC 配置，交给 MP4 sink 无损写入。 */
    bool buildSinkType(IMFMediaType** out) {
        if (indexed_) return live_.buildSinkType(out);
        ComPtr<IMFMediaType> type;
        HRESULT hr = MFCreateMediaType(type.put());
        if (FAILED(hr)) return false;
        if (!nativeType_ || FAILED(nativeType_->CopyAllItems(type.get()))) return false;
        // 使用源的真实 AAC 子类型和 AudioSpecificConfig，不能猜 GUID 或丢掉 MF_MT_USER_DATA。
        // WAVE_FORMAT_MPEG_HEAAC 是 0x1610，旧硬编码的 0x61 不是 AAC。
        GUID subtype{};
        if (FAILED(type->GetGUID(MF_MT_SUBTYPE, &subtype)) || subtype != MFAudioFormat_AAC) return false;
        type->SetUINT32(kAttrAudioSamplesPerFrame, 1024);

        *out = type.get();
        type.detach();
        return true;
    }

    /** 取下一个压缩音频样本；到末尾返回 false。 */
    bool read(IMFSample** sample) {
        if (indexed_) return live_.read(sample);
        if (first_) { *sample = first_.get(); first_.detach(); return true; }
        return readNext(sample);
    }

private:
    bool readNext(IMFSample** result) {
        if (ended_ || failed_) return false;
        while (true) {
            ComPtr<IMFSample> sample;
            DWORD stream = 0, flags = 0;
            LONGLONG pts = 0;
            HRESULT hr = reader_->ReadSample(streamIndex_, 0, &stream, &flags, &pts, sample.put());
            if (hr == MF_E_END_OF_STREAM) { ended_ = true; return false; }
            if (FAILED(hr)) { failed_ = true; reportFailure("读取声音失败 (" + describe(hr) + ")"); return false; }
            if (flags & (MF_SOURCE_READERF_ERROR | MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED)) {
                failed_ = true; reportFailure("声音读取中断或音频格式发生变化"); return false;
            }
            if (flags & MF_SOURCE_READERF_ENDOFSTREAM) ended_ = true;
            if (!sample) { if (ended_) return false; continue; }
            *result = sample.get(); sample.detach(); return true;
        }
    }
    ComPtr<IMFSourceReader> reader_;
    ComPtr<IMFSample> first_;
    DWORD streamIndex_ = 0;
    bool failed_ = false, ended_ = false;
    bool indexed_ = false;
    LiveAudioTrack live_;
    ComPtr<IMFMediaType> nativeType_;
};

struct Options {
    std::wstring left;
    std::wstring right;
    std::wstring output;
    int width = 0;
    int height = 0;
    int bitrate = 0;
    bool cpuOnly = false;
    bool softwareOnly = false;
    bool withAudio = true;
    bool indexed = false;
    std::wstring adapter = L"auto";
    DWORD parentPid = 0;
    // 已知时长（秒）：用来算进度百分比，缺省时按一小时估算。
    long long durationSeconds = 0;
};

void printUsage() {
    fprintf(stderr,
            "用法：BJTUPlaybackCompositor.exe compose <左.mp4> <右.mp4> <输出.mp4> <宽> <高> <码率>"
            " [--duration <秒>] [--cpu] [--software] [--adapter auto|amd|nvidia|intel]"
            " [--without-audio] [--parent-pid <进程号>]\n");
}

bool parseOptions(int argc, wchar_t** argv, Options& options) {
    if (argc < 8 || (std::wstring(argv[1]) != L"compose" && std::wstring(argv[1]) != L"compose-indexed")) {
        printUsage();
        return false;
    }
    options.indexed = std::wstring(argv[1]) == L"compose-indexed";
    options.left = argv[2];
    options.right = argv[3];
    options.output = argv[4];
    options.width = _wtoi(argv[5]);
    options.height = _wtoi(argv[6]);
    options.bitrate = _wtoi(argv[7]);
    for (int index = 8; index < argc; index++) {
        const std::wstring argument = argv[index];
        if (argument == L"--cpu") {
            options.cpuOnly = true;
        } else if (argument == L"--software") {
            options.softwareOnly = true;
        } else if (argument == L"--with-audio") {
            options.withAudio = true;
        } else if (argument == L"--without-audio") {
            options.withAudio = false;
        } else if (argument == L"--adapter" && index + 1 < argc) {
            options.adapter = argv[++index];
        } else if (argument == L"--duration" && index + 1 < argc) {
            options.durationSeconds = _wtoi(argv[++index]);
        } else if (argument == L"--parent-pid" && index + 1 < argc) {
            wchar_t* end = nullptr;
            unsigned long pid = wcstoul(argv[++index], &end, 10);
            if (!end || *end || !pid) return false;
            options.parentPid = static_cast<DWORD>(pid);
        } else {
            printUsage();
            return false;
        }
    }
    if (options.width <= 0 || options.height <= 0 || options.bitrate <= 0 ||
        (options.adapter != L"auto" && options.adapter != L"amd" && options.adapter != L"nvidia" && options.adapter != L"intel")) {
        printUsage();
        return false;
    }
    return true;
}

#include "PlaybackGpuPipeline.h"

int composeCpu(const Options& options) {
    AudioTrack audio;
    const bool hasAudio = options.withAudio && audio.open(options.left, options.indexed);
    if (audio.failed()) return 1;
    ComPtr<IMFMediaType> audioType;
    if (hasAudio) {
        if (!audio.buildSinkType(audioType.put())) {
            reportFailure("准备声音轨失败");
            return 1;
        }
    }


    VideoDecoder left;
    VideoDecoder right;
    if (!left.open(options.left, options.indexed, options.durationSeconds * 10000000LL)) return 1;
    if (!right.open(options.right, options.indexed, options.durationSeconds * 10000000LL)) return 1;

    // 进度用的总帧数：优先用调用方给的时长，其次读 MP4 自己的 moov，最后退回 MF 元数据。
    // 时长只用来算进度，真正的结束点是"两路都解码到文件末尾"。
    long long duration100ns = 0;
    if (options.durationSeconds > 0) {
        duration100ns = options.durationSeconds * 10000000LL;
    } else {
        const double leftSeconds = Mp4DurationReader(options.left).seconds();
        const double rightSeconds = Mp4DurationReader(options.right).seconds();
        const double seconds = std::max(leftSeconds, rightSeconds);
        if (seconds > 0.0) duration100ns = static_cast<long long>(seconds * 10000000.0);
    }
    if (duration100ns <= 0) {
        duration100ns = std::max(left.duration(), right.duration());
    }
    const long long totalFrames = duration100ns > 0 ? duration100ns / kFrameDuration100ns : 0;


    Mp4Writer writer;
    if (!writer.open(options.output, options.width, options.height, options.bitrate,
                     !options.softwareOnly, audioType.get())) {
        return 1;
    }
    if (!writer.begin()) return 1;

    SideBySideComposer composer(options.width, options.height);
    ComPtr<IMFSample> pendingAudio;
    bool hasPendingAudio = false;
    double lastReported = -1.0;

    // 上限只是防死循环：一天最多 216 万帧，正常录像远远到不了。
    const long long maxFrames = 24LL * 3600 * kOutputFps;
    long long framesWritten = 0;
    for (long long index = 0; index < maxFrames; index++) {
        const long long pts = index * kFrameDuration100ns;

        // 声音先写、画面后写：写入器要求所有样本的时间戳整体递增，
        // 先写画面再补更早的声音样本会让 MP4 写入器卡住不动。
        // 另外第一次写声音要早于第一次解码，避免解码器和 AAC 写入器抢同一批变换。
        if (hasAudio) {
            while (true) {
                if (!hasPendingAudio) {
                    if (!audio.read(pendingAudio.put())) break;
                    hasPendingAudio = true;
                }
                long long sampleTime = 0;
                pendingAudio->GetSampleTime(&sampleTime);
                if (sampleTime > pts) break;  // 留给下一帧
                if (!writer.writeAudio(pendingAudio.get())) return 1;
                hasPendingAudio = false;
            }
        }

        left.advanceTo(pts);
        right.advanceTo(pts);
        if (left.failed() || right.failed()) return 1;
        const VideoFrame* leftImage = left.current();
        const VideoFrame* rightImage = right.current();

        if (leftImage == nullptr && rightImage == nullptr) break;
        if (leftImage == nullptr) leftImage = rightImage;
        if (rightImage == nullptr) rightImage = leftImage;

        composer.compose(*leftImage, *rightImage);

        if (!writer.writeVideo(pts, composer.output(), composer.outputSize())) return 1;
        framesWritten = index + 1;

        // 两路都读到末尾就停：判定放在写完之后，否则最后一帧会被丢掉
        // （"读到文件末尾"是在 advanceTo 里读下一帧时才发生的）。
        if (left.exhausted() && right.exhausted()) break;

        const double progress = totalFrames > 0
            ? std::min(1.0, static_cast<double>(index + 1) / static_cast<double>(totalFrames))
            : 0.0;
        if (progress - lastReported >= 0.001 || progress >= 1.0) {
            lastReported = progress;
            printf("progress %.3f\n", progress);
            fflush(stdout);
        }
    }
    if (framesWritten >= maxFrames) {
        reportFailure("录像超过 24 小时，已中止");
        return 1;
    }

    if (hasAudio) {
        if (hasPendingAudio && !writer.writeAudio(pendingAudio.get())) return 1;
        while (audio.read(pendingAudio.put())) if (!writer.writeAudio(pendingAudio.get())) return 1;
    }
    if (audio.failed()) return 1;
    if (!writer.finish()) return 1;
    printf("progress 1.000\n");
    fflush(stdout);
    return 0;
}

}  // namespace

int wmain(int argc, wchar_t** argv) {
    Options options;
    if (!parseOptions(argc, argv, options)) return 64;
    if (options.parentPid) {
        HANDLE parent = OpenProcess(SYNCHRONIZE, FALSE, options.parentPid);
        if (!parent) { reportFailure("无法监视应用进程"); return 1; }
        std::thread([parent] {
            const DWORD state = WaitForSingleObject(parent, INFINITE);
            CloseHandle(parent);
            if (state == WAIT_OBJECT_0) TerminateProcess(GetCurrentProcess(), 1);
        }).detach();
    }
    const HRESULT initialized = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    if (FAILED(initialized)) { reportFailure("初始化COM失败"); return 1; }
    HRESULT startup = MFStartup(MF_VERSION, MFSTARTUP_LITE);
    if (FAILED(startup)) {
        reportFailure("启动 Media Foundation 失败 (" + describe(startup) + ")");
        CoUninitialize();
        return 1;
    }
    int code = 1;
    try {
        code = (options.softwareOnly || options.cpuOnly) ? 2 : composeWithGpu(options);
        if (code == 2) {
            fprintf(stderr, "gpu unavailable; using CPU pipeline\n");
            printf("status 硬件合成不可用，使用兼容模式\n"); fflush(stdout);
            printf("device %s\n", options.softwareOnly ? "CPU · 软件合成" : "CPU · 画面处理（编码器自动选择）");
            fflush(stdout);
            code = composeCpu(options);
        }
    } catch (const std::exception& error) {
        reportFailure(std::string("合成失败: ") + error.what());
        code = 1;
    }
    MFShutdown();
    CoUninitialize();
    if (code != 0) {
        DeleteFileW(options.output.c_str());
    }
    return code;
}
