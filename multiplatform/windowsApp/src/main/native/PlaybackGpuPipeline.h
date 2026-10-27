#pragma once

#include "PlaybackGpuSelection.h"

// 内部实现：在公共 MF/COM 工具和 Options 定义之后包含，不依赖厂商 SDK。
bool gpuCheck(HRESULT hr, const char* step) {
    if (SUCCEEDED(hr)) return true;
    reportFailure(std::string(step) + " (" + describe(hr) + ")");
    return false;
}

std::string gpuDisplayName(const DXGI_ADAPTER_DESC1& description) {
    const int size = WideCharToMultiByte(CP_UTF8, 0, description.Description, -1, nullptr, 0, nullptr, nullptr);
    std::string name;
    if (size > 1) {
        name.resize(static_cast<size_t>(size));
        if (WideCharToMultiByte(CP_UTF8, 0, description.Description, -1, name.data(), size, nullptr, nullptr) > 0)
            name.pop_back();
        else name.clear();
    }
    for (char& ch : name) if (static_cast<unsigned char>(ch) < 0x20) ch = ' ';
    if (name.empty()) {
        if (description.VendorId == playback_gpu::kNvidia) return "NVIDIA";
        if (description.VendorId == playback_gpu::kIntel) return "Intel";
        if (description.VendorId == playback_gpu::kAmd) return "AMD";
        return "GPU";
    }
    return name;
}

struct GpuDevice {
    ComPtr<ID3D11Device> device;
    ComPtr<ID3D11DeviceContext> context;
    ComPtr<ID3D11VideoDevice> video;
    ComPtr<ID3D11VideoContext> videoContext;
    ComPtr<IMFDXGIDeviceManager> manager;

    bool open(IDXGIAdapter1* adapter) {
        D3D_FEATURE_LEVEL chosen{};
        const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
        HRESULT hr = D3D11CreateDevice(adapter, D3D_DRIVER_TYPE_UNKNOWN, nullptr,
            D3D11_CREATE_DEVICE_VIDEO_SUPPORT | D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            levels, 2, D3D11_SDK_VERSION, device.put(), &chosen, context.put());
        if (!gpuCheck(hr, "创建D3D11设备失败")) return false;
        ComPtr<ID3D10Multithread> lock;
        if (FAILED(context->QueryInterface(IID_PPV_ARGS(lock.put()))))
            device->QueryInterface(IID_PPV_ARGS(lock.put()));
        if (!lock) return false;
        lock->SetMultithreadProtected(TRUE);
        if (!gpuCheck(device->QueryInterface(IID_PPV_ARGS(video.put())), "获取GPU视频设备失败") ||
            !gpuCheck(context->QueryInterface(IID_PPV_ARGS(videoContext.put())), "获取GPU视频上下文失败")) return false;
        UINT token = 0;
        return gpuCheck(MFCreateDXGIDeviceManager(&token, manager.put()), "创建DXGI设备管理器失败") &&
            gpuCheck(manager->ResetDevice(device.get(), token), "共享GPU设备失败");
    }
};

struct GpuFrame {
    ComPtr<IMFSample> sample;
    ComPtr<ID3D11Texture2D> texture;
    UINT subresource = 0;
    long long pts = 0;
};

class GpuVideoReader {
public:
    int width = 0, height = 0;
    bool ended = false, failed = false;
    bool open(const std::wstring& path, GpuDevice& gpu, bool indexed = false, long long duration100ns = 0) {
        ComPtr<IMFAttributes> attributes;
        if (!gpuCheck(MFCreateAttributes(attributes.put(), 4), "创建GPU解码属性失败")) return false;
        attributes->SetUnknown(MF_SOURCE_READER_D3D_MANAGER, gpu.manager.get());
        attributes->SetUINT32(MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS, TRUE);
        attributes->SetUINT32(MF_READWRITE_D3D_OPTIONAL, FALSE);
        attributes->SetUINT32(MF_SOURCE_READER_DISABLE_DXVA, FALSE);
        HRESULT openResult = S_OK;
        if (indexed) {
            ComPtr<IMFMediaSource> source;
            openResult = createLiveMediaSource(path, duration100ns, source.put());
            if (SUCCEEDED(openResult)) openResult = MFCreateSourceReaderFromMediaSource(source.get(), attributes.get(), reader_.put());
        } else openResult = MFCreateSourceReaderFromURL(path.c_str(), attributes.get(), reader_.put());
        if (!gpuCheck(openResult, "打开GPU录像源失败")) return false;
        if (!gpuCheck(reader_->SetStreamSelection(MF_SOURCE_READER_ALL_STREAMS, FALSE), "关闭其他轨道失败")) return false;
        ComPtr<IMFMediaType> native;
        if (!gpuCheck(reader_->GetNativeMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, native.put()), "读取视频类型失败")) return false;
        UINT w = 0, h = 0;
        if (!gpuCheck(MFGetAttributeSize(native.get(), MF_MT_FRAME_SIZE, &w, &h), "读取GPU画面尺寸失败")) return false;
        if (!w || !h) { reportFailure("GPU画面尺寸无效"); return false; }
        width = static_cast<int>(w); height = static_cast<int>(h);
        ComPtr<IMFMediaType> decoded;
        if (!gpuCheck(createMediaType(MFMediaType_Video, MFVideoFormat_NV12, decoded.put()), "创建GPU解码类型失败")) return false;
        return gpuCheck(reader_->SetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, nullptr, decoded.get()), "设置GPU NV12解码失败") &&
            gpuCheck(reader_->SetStreamSelection(MF_SOURCE_READER_FIRST_VIDEO_STREAM, TRUE), "选择GPU视频轨失败");
    }
    bool advance(long long target) {
        while (true) {
            if (!hasNext_ && !read(next_)) return !failed;
            hasNext_ = true;
            if (next_.pts > target && hasCurrent_) return true;
            current_ = std::move(next_); hasCurrent_ = true; hasNext_ = false;
        }
    }
    const GpuFrame* current() const { return hasCurrent_ ? &current_ : nullptr; }
    bool exhausted() const { return ended && !hasNext_; }
private:
    bool read(GpuFrame& frame) {
        if (ended || failed) return false;
        while (true) {
            DWORD stream = 0, flags = 0;
            long long pts = 0;
            ComPtr<IMFSample> sample;
            HRESULT hr = reader_->ReadSample(MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0, &stream, &flags, &pts, sample.put());
            if (FAILED(hr)) { failed = true; gpuCheck(hr, "GPU解码失败"); return false; }
            if (flags & MF_SOURCE_READERF_ERROR) {
                failed = true; reportFailure("GPU解码中断"); return false;
            }
            if (flags & MF_SOURCE_READERF_CURRENTMEDIATYPECHANGED) {
                ComPtr<IMFMediaType> current;
                UINT w = 0, h = 0; GUID subtype{};
                const HRESULT typeHr = reader_->GetCurrentMediaType(MF_SOURCE_READER_FIRST_VIDEO_STREAM, current.put());
                const HRESULT sizeHr = SUCCEEDED(typeHr) ? MFGetAttributeSize(current.get(), MF_MT_FRAME_SIZE, &w, &h) : typeHr;
                const HRESULT subtypeHr = SUCCEEDED(typeHr) ? current->GetGUID(MF_MT_SUBTYPE, &subtype) : typeHr;
                // 解码器可将 1080 行缓冲对齐到 1088；处理器仍按原始可见尺寸裁剪。
                const UINT maxW = (static_cast<UINT>(width) + 63u) & ~63u;
                const UINT maxH = (static_cast<UINT>(height) + 63u) & ~63u;
                if (FAILED(typeHr) || FAILED(sizeHr) || FAILED(subtypeHr) || subtype != MFVideoFormat_NV12 ||
                    w < static_cast<UINT>(width) || w > maxW || h < static_cast<UINT>(height) || h > maxH) {
                    fprintf(stderr, "gpu type change: requested=%dx%d actual=%ux%u subtype=0x%08X hr=%08X/%08X/%08X\n",
                        width, height, w, h, subtype.Data1, static_cast<unsigned>(typeHr),
                        static_cast<unsigned>(sizeHr), static_cast<unsigned>(subtypeHr));
                    failed = true; reportFailure("GPU画面格式发生不兼容变化"); return false;
                }
            }
            if (flags & MF_SOURCE_READERF_ENDOFSTREAM) ended = true;
            if (!sample) { if (ended) return false; continue; }
            ComPtr<IMFMediaBuffer> buffer;
            ComPtr<IMFDXGIBuffer> dxgi;
            if (FAILED(sample->GetBufferByIndex(0, buffer.put())) ||
                FAILED(buffer->QueryInterface(IID_PPV_ARGS(dxgi.put()))) ||
                FAILED(dxgi->GetResource(IID_PPV_ARGS(frame.texture.put())))) {
                failed = true; reportFailure("解码器没有提供GPU纹理"); return false;
            }
            dxgi->GetSubresourceIndex(&frame.subresource);
            frame.pts = pts; frame.sample = std::move(sample);
            return true;
        }
    }
    ComPtr<IMFSourceReader> reader_;
    GpuFrame current_, next_;
    bool hasCurrent_ = false, hasNext_ = false;
};

class GpuComposer {
public:
    bool open(GpuDevice& gpu, const GpuVideoReader& left, const GpuVideoReader& right, int width, int height) {
        gpu_ = &gpu; width_ = width; height_ = height;
        D3D11_VIDEO_PROCESSOR_CONTENT_DESC content{};
        content.InputFrameFormat = D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE;
        content.InputWidth = std::max(left.width, right.width); content.InputHeight = std::max(left.height, right.height);
        content.InputFrameRate = {kOutputFps, 1}; content.OutputFrameRate = {kOutputFps, 1};
        content.OutputWidth = width; content.OutputHeight = height; content.Usage = D3D11_VIDEO_USAGE_OPTIMAL_SPEED;
        if (!gpuCheck(gpu.video->CreateVideoProcessorEnumerator(&content, enumeration_.put()), "创建GPU视频处理枚举器失败")) return false;
        D3D11_VIDEO_PROCESSOR_CAPS caps{};
        if (!gpuCheck(enumeration_->GetVideoProcessorCaps(&caps), "读取GPU处理能力失败")) return false;
        if (caps.MaxInputStreams < 2) { reportFailure("GPU不支持双路视频处理"); return false; }
        if (!gpuCheck(gpu.video->CreateVideoProcessor(enumeration_.get(), 0, processor_.put()), "创建GPU视频处理器失败")) return false;
        D3D11_VIDEO_PROCESSOR_COLOR_SPACE color{};
        color.YCbCr_Matrix = 1; color.Nominal_Range = D3D11_VIDEO_PROCESSOR_NOMINAL_RANGE_16_235;
        gpu.videoContext->VideoProcessorSetOutputColorSpace(processor_.get(), &color);
        D3D11_VIDEO_COLOR black{}; black.YCbCr.Y = 16.0f / 255; black.YCbCr.Cb = .5f; black.YCbCr.Cr = .5f; black.YCbCr.A = 1;
        gpu.videoContext->VideoProcessorSetOutputBackgroundColor(processor_.get(), TRUE, &black);
        const RECT canvas{0, 0, width, height};
        gpu.videoContext->VideoProcessorSetOutputTargetRect(processor_.get(), TRUE, &canvas);
        for (UINT i = 0; i < 2; i++) {
            const auto& source = i == 0 ? left : right;
            const float scale = std::min((width / 2.0f) / source.width, height / float(source.height));
            const int w = int(source.width * scale) & ~1, h = int(source.height * scale) & ~1;
            const int x = int(i) * (width / 2) + ((width / 2 - w) / 2 & ~1), y = ((height - h) / 2) & ~1;
            const RECT input{0, 0, source.width, source.height}, output{x, y, x + w, y + h};
            gpu.videoContext->VideoProcessorSetStreamFrameFormat(processor_.get(), i, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
            gpu.videoContext->VideoProcessorSetStreamAutoProcessingMode(processor_.get(), i, FALSE);
            gpu.videoContext->VideoProcessorSetStreamColorSpace(processor_.get(), i, &color);
            gpu.videoContext->VideoProcessorSetStreamSourceRect(processor_.get(), i, TRUE, &input);
            gpu.videoContext->VideoProcessorSetStreamDestRect(processor_.get(), i, TRUE, &output);
            gpu.videoContext->VideoProcessorSetStreamAlpha(processor_.get(), i, TRUE, 1.0f);
        }
        ComPtr<IMFMediaType> type;
        if (!gpuCheck(createMediaType(MFMediaType_Video, MFVideoFormat_NV12, type.put()), "创建GPU输出类型失败")) return false;
        MFSetAttributeSize(type.get(), MF_MT_FRAME_SIZE, width, height);
        MFSetAttributeRatio(type.get(), MF_MT_FRAME_RATE, kOutputFps, 1);
        MFSetAttributeRatio(type.get(), MF_MT_PIXEL_ASPECT_RATIO, 1, 1);
        type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        ComPtr<IMFAttributes> attributes;
        if (!gpuCheck(MFCreateAttributes(attributes.put(), 2), "创建GPU输出属性失败")) return false;
        attributes->SetUINT32(MF_SA_D3D11_BINDFLAGS, D3D11_BIND_RENDER_TARGET);
        attributes->SetUINT32(MF_SA_D3D11_USAGE, D3D11_USAGE_DEFAULT);
        return gpuCheck(MFCreateVideoSampleAllocatorEx(IID_PPV_ARGS(allocator_.put())), "创建GPU输出池失败") &&
            gpuCheck(allocator_->SetDirectXManager(gpu.manager.get()), "关联GPU输出池失败") &&
            gpuCheck(allocator_->InitializeSampleAllocatorEx(4, 16, attributes.get(), type.get()), "分配GPU输出池失败");
    }
    bool render(const GpuFrame& left, const GpuFrame& right, IMFSample** result, UINT frame) {
        ComPtr<IMFSample> sample;
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(30);
        HRESULT hr;
        while ((hr = allocator_->AllocateSample(sample.put())) == MF_E_SAMPLEALLOCATOR_EMPTY && std::chrono::steady_clock::now() < deadline)
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        if (!gpuCheck(hr, "GPU输出池等待超时")) return false;
        ComPtr<IMFMediaBuffer> buffer;
        ComPtr<IMFDXGIBuffer> dxgi;
        ComPtr<ID3D11Texture2D> texture;
        if (!gpuCheck(sample->GetBufferByIndex(0, buffer.put()), "获取GPU输出失败") ||
            !gpuCheck(buffer->QueryInterface(IID_PPV_ARGS(dxgi.put())), "获取DXGI输出失败") ||
            !gpuCheck(dxgi->GetResource(IID_PPV_ARGS(texture.put())), "获取输出纹理失败")) return false;
        UINT subresource = 0; dxgi->GetSubresourceIndex(&subresource);
        D3D11_TEXTURE2D_DESC textureDesc{}; texture->GetDesc(&textureDesc);
        D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC outputDesc{};
        if (textureDesc.ArraySize > 1) {
            outputDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2DARRAY;
            outputDesc.Texture2DArray.MipSlice = 0; outputDesc.Texture2DArray.FirstArraySlice = subresource; outputDesc.Texture2DArray.ArraySize = 1;
        } else { outputDesc.ViewDimension = D3D11_VPOV_DIMENSION_TEXTURE2D; outputDesc.Texture2D.MipSlice = 0; }
        ComPtr<ID3D11VideoProcessorOutputView> output;
        if (!gpuCheck(gpu_->video->CreateVideoProcessorOutputView(texture.get(), enumeration_.get(), &outputDesc, output.put()), "创建GPU输出视图失败")) return false;
        ComPtr<ID3D11VideoProcessorInputView> views[2];
        const GpuFrame* inputs[] = {&left, &right};
        D3D11_VIDEO_PROCESSOR_STREAM streams[2]{};
        for (int i = 0; i < 2; i++) {
            D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC desc{};
            desc.ViewDimension = D3D11_VPIV_DIMENSION_TEXTURE2D; desc.Texture2D.ArraySlice = inputs[i]->subresource;
            if (!gpuCheck(gpu_->video->CreateVideoProcessorInputView(inputs[i]->texture.get(), enumeration_.get(), &desc, views[i].put()), "创建GPU输入视图失败")) return false;
            streams[i].Enable = TRUE; streams[i].InputFrameOrField = frame; streams[i].pInputSurface = views[i].get();
        }
        if (!gpuCheck(gpu_->videoContext->VideoProcessorBlt(processor_.get(), output.get(), frame, 2, streams), "GPU画面拼接失败")) return false;
        gpu_->context->Flush();
        buffer->SetCurrentLength(static_cast<DWORD>(width_ * height_ * 3 / 2));
        *result = sample.get(); sample.detach();
        return true;
    }
private:
    GpuDevice* gpu_ = nullptr;
    int width_ = 0, height_ = 0;
    ComPtr<ID3D11VideoProcessorEnumerator> enumeration_;
    ComPtr<ID3D11VideoProcessor> processor_;
    ComPtr<IMFVideoSampleAllocatorEx> allocator_;
};

int composeGpuAdapter(const Options& options, IDXGIAdapter1* adapter) {
    GpuDevice gpu;
    if (!gpu.open(adapter)) return 2;
    GpuVideoReader left, right;
    if (!left.open(options.left, gpu, options.indexed, options.durationSeconds * 10000000LL) ||
        !right.open(options.right, gpu, options.indexed, options.durationSeconds * 10000000LL)) return 2;
    GpuComposer composer;
    if (!composer.open(gpu, left, right, options.width, options.height)) return 2;
    AudioTrack audio;
    const bool hasAudio = options.withAudio && audio.open(options.left, options.indexed);
    if (audio.failed()) return 1;
    ComPtr<IMFMediaType> audioType;
    if (hasAudio && !audio.buildSinkType(audioType.put())) return 1;
    Mp4Writer writer;
    if (!writer.open(options.output, options.width, options.height, options.bitrate, true, audioType.get(), gpu.manager.get()) || !writer.begin()) return 2;
    DXGI_ADAPTER_DESC1 description{}; adapter->GetDesc1(&description);
    fprintf(stderr, "gpu vendor=0x%04X adapter=%ls pool=16\n", description.VendorId, description.Description);
    const std::string deviceName = gpuDisplayName(description);
    printf("device %s · 硬件加速\n", deviceName.c_str()); fflush(stdout);
    const auto started = std::chrono::steady_clock::now();
    const double duration = options.indexed ? static_cast<double>(options.durationSeconds) :
        std::max(Mp4DurationReader(options.left).seconds(), Mp4DurationReader(options.right).seconds());
    ComPtr<IMFSample> pending;
    bool hasPending = false;
    long long written = 0;
    const long long maxFrames = 24LL * 3600 * kOutputFps;
    bool complete = false;
    for (long long index = 0; index < maxFrames; index++) {
        const long long pts = index * kFrameDuration100ns;
        if (!left.advance(pts) || !right.advance(pts)) return written == 0 ? 2 : 1;
        const auto* a = left.current(); const auto* b = right.current();
        if (!a || !b) return written == 0 ? 2 : 1;
        if (hasAudio) while (true) {
            if (!hasPending) { if (!audio.read(pending.put())) break; hasPending = true; }
            LONGLONG at = 0; pending->GetSampleTime(&at);
            if (at > pts) break;
            if (!writer.writeAudio(pending.get())) return 1;
            hasPending = false;
        }
        ComPtr<IMFSample> output;
        if (!composer.render(*a, *b, output.put(), static_cast<UINT>(index)) || !writer.writeGpuVideo(output.get(), pts))
            return written == 0 ? 2 : 1;
        written++;
        if (index % 25 == 0) { printf("progress %.4f\n", std::min(.9999, (index / double(kOutputFps)) / std::max(duration, .001))); fflush(stdout); }
        if (left.exhausted() && right.exhausted()) { complete = true; break; }
    }
    if (!complete) { reportFailure("录像超过 24 小时，已中止"); return 1; }
    if (hasAudio) {
        if (hasPending && !writer.writeAudio(pending.get())) return 1;
        while (audio.read(pending.put())) if (!writer.writeAudio(pending.get())) return 1;
    }
    if (audio.failed() || !writer.finish()) return 1;
    fprintf(stderr, "gpu frames=%lld elapsedMs=%lld\n", written,
        static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count()));
    printf("progress 1.000\n"); fflush(stdout);
    return 0;
}

int composeWithGpu(const Options& options) {
    ComPtr<IDXGIFactory1> factory;
    if (FAILED(CreateDXGIFactory1(IID_PPV_ARGS(factory.put())))) return 2;
    std::vector<ComPtr<IDXGIAdapter1>> adapters;
    for (UINT i = 0;; i++) {
        ComPtr<IDXGIAdapter1> adapter;
        const HRESULT enumeration = factory->EnumAdapters1(i, adapter.put());
        if (enumeration == DXGI_ERROR_NOT_FOUND) break;
        if (FAILED(enumeration) || !adapter) return 2;
        DXGI_ADAPTER_DESC1 desc{};
        if (FAILED(adapter->GetDesc1(&desc))) continue;
        if (desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE) continue;
        if (!playback_gpu::matches(options.adapter, desc.VendorId)) continue;
        adapters.push_back(std::move(adapter));
    }
    std::stable_sort(adapters.begin(), adapters.end(), [](const auto& a, const auto& b) {
        DXGI_ADAPTER_DESC1 x{}, y{}; a->GetDesc1(&x); b->GetDesc1(&y);
        return playback_gpu::before(x.VendorId, x.DedicatedVideoMemory, y.VendorId, y.DedicatedVideoMemory);
    });
    const int result = playback_gpu::tryAdapters(adapters, [&](const auto& adapter) {
        const int attempt = composeGpuAdapter(options, adapter.get());
        if (attempt == 2) DeleteFileW(options.output.c_str());
        return attempt;
    });
    if (result != 2) return result;
    if (options.adapter != L"auto") { reportFailure("所选GPU硬件管线不可用"); return 1; }
    return 2;
}
