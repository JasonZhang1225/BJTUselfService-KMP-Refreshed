#pragma once

// 与 MP4 视频源提供的 stsd 等价。某些硬件解码器需要完整的样本描述才能保持吞吐；
// 渐进输入还没有 MP4 文件，因此直接由录像的 SPS/PPS 生成，不依赖外部文件。
static std::vector<BYTE> makeAvcSampleDescription(
    UINT32 width, UINT32 height, const std::vector<BYTE>& sps, const std::vector<BYTE>& pps) {
    if (!width || !height || width > 65535 || height > 65535 ||
        sps.size() < 4 || sps.size() > 65535 || pps.empty() || pps.size() > 65535) return {};

    std::vector<BYTE> bytes;
    auto u8 = [&bytes](uint8_t value) { bytes.push_back(value); };
    auto u16 = [&u8](uint16_t value) { u8(static_cast<uint8_t>(value >> 8)); u8(static_cast<uint8_t>(value)); };
    auto u32 = [&u8](uint32_t value) {
        u8(static_cast<uint8_t>(value >> 24)); u8(static_cast<uint8_t>(value >> 16));
        u8(static_cast<uint8_t>(value >> 8)); u8(static_cast<uint8_t>(value));
    };
    auto beginBox = [&bytes, &u32](const char* name) {
        const size_t offset = bytes.size();
        u32(0); bytes.insert(bytes.end(), name, name + 4);
        return offset;
    };
    auto finishBox = [&bytes](size_t offset) {
        const uint32_t size = static_cast<uint32_t>(bytes.size() - offset);
        bytes[offset] = static_cast<BYTE>(size >> 24); bytes[offset + 1] = static_cast<BYTE>(size >> 16);
        bytes[offset + 2] = static_cast<BYTE>(size >> 8); bytes[offset + 3] = static_cast<BYTE>(size);
    };

    const size_t stsd = beginBox("stsd");
    u32(0); u32(1);
    const size_t avc1 = beginBox("avc1");
    for (int i = 0; i < 6; ++i) u8(0);
    u16(1); u16(0); u16(0); u32(0); u32(0); u32(0);
    u16(static_cast<uint16_t>(width)); u16(static_cast<uint16_t>(height));
    u32(0x00480000); u32(0x00480000); u32(0); u16(1);
    for (int i = 0; i < 32; ++i) u8(0);
    u16(0x18); u16(0xffff);
    const size_t avcC = beginBox("avcC");
    u8(1); u8(sps[1]); u8(sps[2]); u8(sps[3]); u8(0xff); u8(0xe1);
    u16(static_cast<uint16_t>(sps.size())); bytes.insert(bytes.end(), sps.begin(), sps.end());
    u8(1); u16(static_cast<uint16_t>(pps.size())); bytes.insert(bytes.end(), pps.begin(), pps.end());
    finishBox(avcC); finishBox(avc1); finishBox(stsd);
    return bytes;
}

// 索引由 Kotlin 在完整写出样本后发布。所有字段均为 little-endian。
struct LiveRecord {
    uint64_t offset;
    int64_t pts;
    uint32_t size;
    uint32_t flags;
};
static_assert(sizeof(LiveRecord) == 24, "live record layout");

class LiveSampleFiles {
public:
    enum class Result { Sample, End, Error, Stopped };
    ~LiveSampleFiles() {
        if (data_ != INVALID_HANDLE_VALUE) CloseHandle(data_);
        if (index_ != INVALID_HANDLE_VALUE) CloseHandle(index_);
    }
    bool open(const std::wstring& directory, const wchar_t* indexName) {
        directory_ = directory;
        constexpr DWORD sharing = FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE;
        data_ = CreateFileW((directory + L"\\samples.data").c_str(), GENERIC_READ, sharing, nullptr,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        index_ = CreateFileW((directory + L"\\" + indexName).c_str(), GENERIC_READ, sharing, nullptr,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        return data_ != INVALID_HANDLE_VALUE && index_ != INVALID_HANDLE_VALUE;
    }
    Result next(LiveRecord& record, std::vector<BYTE>& bytes, const std::atomic<bool>& stopped) {
        LARGE_INTEGER length{};
        while (!stopped.load()) {
            if (!GetFileSizeEx(index_, &length)) return Result::Error;
            if (length.QuadPart >= 0 && static_cast<uint64_t>(length.QuadPart) >= (count_ + 1) * sizeof(LiveRecord)) break;
            if (GetFileAttributesW((directory_ + L"\\finished").c_str()) != INVALID_FILE_ATTRIBUTES) return Result::End;
            Sleep(10);
        }
        if (stopped.load()) return Result::Stopped;
        LARGE_INTEGER position{}; DWORD read = 0;
        position.QuadPart = static_cast<LONGLONG>(count_ * sizeof(LiveRecord));
        if (!SetFilePointerEx(index_, position, nullptr, FILE_BEGIN) ||
            !ReadFile(index_, &record, sizeof(record), &read, nullptr) || read != sizeof(record)) return Result::Error;
        if (!record.size || record.size > 32u * 1024 * 1024 || record.offset > INT64_MAX) return Result::Error;
        bytes.resize(record.size);
        position.QuadPart = static_cast<LONGLONG>(record.offset);
        if (!SetFilePointerEx(data_, position, nullptr, FILE_BEGIN) ||
            !ReadFile(data_, bytes.data(), record.size, &read, nullptr) || read != record.size) return Result::Error;
        count_++;
        return Result::Sample;
    }
    uint64_t count() const { return count_; }
private:
    HANDLE data_ = INVALID_HANDLE_VALUE, index_ = INVALID_HANDLE_VALUE;
    std::wstring directory_;
    uint64_t count_ = 0;
};

class LiveMediaSource;
class LiveMediaStream final : public Microsoft::WRL::RuntimeClass<
    Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
    Microsoft::WRL::ChainInterfaces<IMFMediaStream, IMFMediaEventGenerator>> {
public:
    HRESULT init(LiveMediaSource* source, IMFStreamDescriptor* descriptor,
        const std::wstring& directory, const std::vector<BYTE>& sequence);
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override { return events_->GetEvent(flags, event); }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override { return events_->BeginGetEvent(callback, state); }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override { return events_->EndGetEvent(result, event); }
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID ext, HRESULT status, const PROPVARIANT* value) override {
        return events_->QueueEventParamVar(type, ext, status, value);
    }
    STDMETHODIMP GetMediaSource(IMFMediaSource** source) override;
    STDMETHODIMP GetStreamDescriptor(IMFStreamDescriptor** descriptor) override {
        if (!descriptor) return E_POINTER;
        *descriptor = descriptor_.Get(); (*descriptor)->AddRef(); return S_OK;
    }
    STDMETHODIMP RequestSample(IUnknown* token) override;
    HRESULT started(const PROPVARIANT* position) { return events_->QueueEventParamVar(MEStreamStarted, GUID_NULL, S_OK, position); }
    HRESULT stopped() { return events_->QueueEventParamVar(MEStreamStopped, GUID_NULL, S_OK, nullptr); }
    void shutdown() { stopped_.store(true); if (events_) events_->Shutdown(); }
private:
    LiveMediaSource* source_ = nullptr;
    Microsoft::WRL::ComPtr<IMFMediaEventQueue> events_;
    Microsoft::WRL::ComPtr<IMFStreamDescriptor> descriptor_;
    LiveSampleFiles files_;
    std::vector<BYTE> sequence_;
    std::atomic<bool> stopped_{false};
    std::mutex requestMutex_;
    bool ended_ = false;
};

class LiveMediaSource final : public Microsoft::WRL::RuntimeClass<
    Microsoft::WRL::RuntimeClassFlags<Microsoft::WRL::ClassicCom>,
    Microsoft::WRL::ChainInterfaces<IMFMediaSource, IMFMediaEventGenerator>> {
public:
    HRESULT init(const std::wstring& directory, long long duration100ns) {
        HRESULT hr = MFCreateEventQueue(&events_); if (FAILED(hr)) return hr;
        std::ifstream config(directory + L"\\video-config.bin", std::ios::binary);
        uint32_t fields[4]{};
        config.read(reinterpret_cast<char*>(fields), sizeof(fields));
        if (!config || !fields[0] || !fields[1] || !fields[2] || !fields[3] || fields[2] > 1024 || fields[3] > 1024) return E_FAIL;
        std::vector<BYTE> sps(fields[2]), pps(fields[3]);
        config.read(reinterpret_cast<char*>(sps.data()), sps.size());
        config.read(reinterpret_cast<char*>(pps.data()), pps.size());
        if (!config) return E_FAIL;
        std::vector<BYTE> sequence{0,0,0,1}; sequence.insert(sequence.end(), sps.begin(), sps.end());
        sequence.insert(sequence.end(), {0,0,0,1}); sequence.insert(sequence.end(), pps.begin(), pps.end());
        Microsoft::WRL::ComPtr<IMFMediaType> type;
        hr = MFCreateMediaType(&type); if (FAILED(hr)) return hr;
        type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Video); type->SetGUID(MF_MT_SUBTYPE, MFVideoFormat_H264);
        MFSetAttributeSize(type.Get(), MF_MT_FRAME_SIZE, fields[0], fields[1]);
        MFSetAttributeRatio(type.Get(), MF_MT_FRAME_RATE, 25, 1);
        type->SetUINT32(MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive);
        type->SetBlob(MF_MT_MPEG_SEQUENCE_HEADER, sequence.data(), static_cast<UINT32>(sequence.size()));
        const auto description = makeAvcSampleDescription(fields[0], fields[1], sps, pps);
        if (!description.empty()) {
            hr = type->SetBlob(MF_MT_MPEG4_SAMPLE_DESCRIPTION, description.data(), static_cast<UINT32>(description.size()));
            if (FAILED(hr)) return hr;
        }
        IMFMediaType* types[] = {type.Get()};
        Microsoft::WRL::ComPtr<IMFStreamDescriptor> descriptor;
        hr = MFCreateStreamDescriptor(1, 1, types, &descriptor); if (FAILED(hr)) return hr;
        Microsoft::WRL::ComPtr<IMFMediaTypeHandler> handler;
        hr = descriptor->GetMediaTypeHandler(&handler); if (FAILED(hr)) return hr;
        hr = handler->SetCurrentMediaType(type.Get()); if (FAILED(hr)) return hr;
        IMFStreamDescriptor* streams[] = {descriptor.Get()};
        hr = MFCreatePresentationDescriptor(1, streams, &presentation_); if (FAILED(hr)) return hr;
        hr = presentation_->SelectStream(0); if (FAILED(hr)) return hr;
        if (duration100ns > 0) presentation_->SetUINT64(MF_PD_DURATION, static_cast<UINT64>(duration100ns));
        stream_ = Microsoft::WRL::Make<LiveMediaStream>();
        if (!stream_) return E_OUTOFMEMORY;
        return stream_->init(this, descriptor.Get(), directory, sequence);
    }
    STDMETHODIMP GetEvent(DWORD flags, IMFMediaEvent** event) override { return events_->GetEvent(flags, event); }
    STDMETHODIMP BeginGetEvent(IMFAsyncCallback* callback, IUnknown* state) override { return events_->BeginGetEvent(callback, state); }
    STDMETHODIMP EndGetEvent(IMFAsyncResult* result, IMFMediaEvent** event) override { return events_->EndGetEvent(result, event); }
    STDMETHODIMP QueueEvent(MediaEventType type, REFGUID ext, HRESULT status, const PROPVARIANT* value) override {
        return events_->QueueEventParamVar(type, ext, status, value);
    }
    STDMETHODIMP GetCharacteristics(DWORD* flags) override { if (!flags) return E_POINTER; *flags = 0; return S_OK; }
    STDMETHODIMP CreatePresentationDescriptor(IMFPresentationDescriptor** out) override { return presentation_->Clone(out); }
    STDMETHODIMP Start(IMFPresentationDescriptor*, const GUID*, const PROPVARIANT* position) override {
        if (shutdown_.load()) return MF_E_SHUTDOWN;
        HRESULT hr = events_->QueueEventParamUnk(started_ ? MEUpdatedStream : MENewStream, GUID_NULL, S_OK, stream_.Get());
        if (SUCCEEDED(hr)) hr = stream_->started(position);
        if (SUCCEEDED(hr)) hr = events_->QueueEventParamVar(MESourceStarted, GUID_NULL, S_OK, position);
        started_ = true;
        return hr;
    }
    STDMETHODIMP Stop() override {
        if (shutdown_.load()) return MF_E_SHUTDOWN;
        HRESULT hr = stream_->stopped();
        if (SUCCEEDED(hr)) hr = events_->QueueEventParamVar(MESourceStopped, GUID_NULL, S_OK, nullptr);
        return hr;
    }
    STDMETHODIMP Pause() override { return MF_E_INVALID_STATE_TRANSITION; }
    STDMETHODIMP Shutdown() override {
        if (shutdown_.exchange(true)) return MF_E_SHUTDOWN;
        if (stream_) stream_->shutdown();
        if (events_) events_->Shutdown();
        return S_OK;
    }
    void ended() { if (!shutdown_.load()) events_->QueueEventParamVar(MEEndOfPresentation, GUID_NULL, S_OK, nullptr); }
private:
    Microsoft::WRL::ComPtr<IMFMediaEventQueue> events_;
    Microsoft::WRL::ComPtr<IMFPresentationDescriptor> presentation_;
    Microsoft::WRL::ComPtr<LiveMediaStream> stream_;
    std::atomic<bool> shutdown_{false};
    bool started_ = false;
};

HRESULT LiveMediaStream::init(LiveMediaSource* source, IMFStreamDescriptor* descriptor,
    const std::wstring& directory, const std::vector<BYTE>& sequence) {
    source_ = source; descriptor_ = descriptor; sequence_ = sequence;
    if (!files_.open(directory, L"video.idx")) return E_FAIL;
    return MFCreateEventQueue(&events_);
}
HRESULT LiveMediaStream::GetMediaSource(IMFMediaSource** out) {
    if (!out) return E_POINTER;
    if (stopped_.load()) return MF_E_SHUTDOWN;
    *out = source_; source_->AddRef(); return S_OK;
}
HRESULT LiveMediaStream::RequestSample(IUnknown* token) {
    std::lock_guard<std::mutex> lock(requestMutex_);
    if (stopped_.load()) return MF_E_SHUTDOWN;
    if (ended_) return MF_E_END_OF_STREAM;
    LiveRecord record{}; std::vector<BYTE> bytes;
    const auto next = files_.next(record, bytes, stopped_);
    if (next == LiveSampleFiles::Result::Stopped) return MF_E_SHUTDOWN;
    if (next == LiveSampleFiles::Result::End) {
        ended_ = true;
        events_->QueueEventParamVar(MEEndOfStream, GUID_NULL, S_OK, nullptr);
        source_->ended(); return S_OK;
    }
    if (next != LiveSampleFiles::Result::Sample) return E_FAIL;
    for (size_t at = 0; at < bytes.size();) {
        if (bytes.size() - at < 4) return E_FAIL;
        uint32_t length = (uint32_t(bytes[at]) << 24) | (uint32_t(bytes[at+1]) << 16) |
            (uint32_t(bytes[at+2]) << 8) | bytes[at+3];
        if (!length || length > bytes.size() - at - 4) return E_FAIL;
        bytes[at] = 0; bytes[at+1] = 0; bytes[at+2] = 0; bytes[at+3] = 1; at += 4 + length;
    }
    if (files_.count() == 1) bytes.insert(bytes.begin(), sequence_.begin(), sequence_.end());
    Microsoft::WRL::ComPtr<IMFMediaBuffer> buffer;
    HRESULT hr = MFCreateMemoryBuffer(static_cast<DWORD>(bytes.size()), &buffer); if (FAILED(hr)) return hr;
    BYTE* destination = nullptr; DWORD max = 0, current = 0;
    hr = buffer->Lock(&destination, &max, &current); if (FAILED(hr)) return hr;
    memcpy(destination, bytes.data(), bytes.size()); buffer->Unlock();
    hr = buffer->SetCurrentLength(static_cast<DWORD>(bytes.size())); if (FAILED(hr)) return hr;
    Microsoft::WRL::ComPtr<IMFSample> sample;
    hr = MFCreateSample(&sample); if (FAILED(hr)) return hr;
    sample->AddBuffer(buffer.Get()); sample->SetSampleTime(record.pts); sample->SetSampleDuration(kFrameDuration100ns);
    if (record.flags) sample->SetUINT32(MFSampleExtension_CleanPoint, TRUE);
    if (files_.count() == 1) sample->SetUINT32(MFSampleExtension_Discontinuity, TRUE);
    if (token) sample->SetUnknown(MFSampleExtension_Token, token);
    return events_->QueueEventParamUnk(MEMediaSample, GUID_NULL, S_OK, sample.Get());
}

HRESULT createLiveMediaSource(const std::wstring& directory, long long duration100ns, IMFMediaSource** out) {
    if (!out) return E_POINTER;
    auto source = Microsoft::WRL::Make<LiveMediaSource>();
    if (!source) return E_OUTOFMEMORY;
    HRESULT hr = source->init(directory, duration100ns);
    return FAILED(hr) ? hr : source.CopyTo(out);
}

/** 从同一索引流直通 AAC，样本数据不经过解码。 */
class LiveAudioTrack {
public:
    bool open(const std::wstring& directory) {
        if (GetFileAttributesW((directory + L"\\no-audio").c_str()) != INVALID_FILE_ATTRIBUTES) return false;
        std::ifstream config(directory + L"\\audio-config.bin", std::ios::binary);
        uint32_t fields[3]{}; config.read(reinterpret_cast<char*>(fields), sizeof(fields));
        if (!config || fields[0] < 8000 || fields[0] > 192000 || fields[1] < 1 || fields[1] > 8 || !fields[2] || fields[2] > 64) {
            failed_ = true; reportFailure("渐进音轨配置无效"); return false;
        }
        rate_ = fields[0]; channels_ = fields[1];
        specific_.resize(fields[2]); config.read(reinterpret_cast<char*>(specific_.data()), specific_.size());
        if (!config || !files_.open(directory, L"audio.idx")) { failed_ = true; reportFailure("无法读取渐进音轨"); return false; }
        return true;
    }
    bool failed() const { return failed_; }
    bool buildSinkType(IMFMediaType** out) {
        ComPtr<IMFMediaType> type;
        HRESULT hr = MFCreateMediaType(type.put()); if (FAILED(hr)) return false;
        type->SetGUID(MF_MT_MAJOR_TYPE, MFMediaType_Audio); type->SetGUID(MF_MT_SUBTYPE, MFAudioFormat_AAC);
        type->SetUINT32(MF_MT_AUDIO_NUM_CHANNELS, channels_); type->SetUINT32(MF_MT_AUDIO_SAMPLES_PER_SECOND, rate_);
        type->SetUINT32(MF_MT_AUDIO_AVG_BYTES_PER_SECOND, 16000); type->SetUINT32(MF_MT_AUDIO_BLOCK_ALIGNMENT, 1);
        type->SetUINT32(MF_MT_AUDIO_BITS_PER_SAMPLE, 16); type->SetUINT32(MF_MT_AAC_PAYLOAD_TYPE, 0);
        type->SetUINT32(MF_MT_AAC_AUDIO_PROFILE_LEVEL_INDICATION, 0);
        type->SetUINT32(kAttrAudioSamplesPerFrame, 1024);
        std::vector<BYTE> user(12, 0); user.insert(user.end(), specific_.begin(), specific_.end());
        type->SetBlob(MF_MT_USER_DATA, user.data(), static_cast<UINT32>(user.size()));
        *out = type.get(); type.detach(); return true;
    }
    bool read(IMFSample** output) {
        LiveRecord record{}; std::vector<BYTE> bytes;
        const auto state = files_.next(record, bytes, stopped_);
        if (state == LiveSampleFiles::Result::End) return false;
        if (state != LiveSampleFiles::Result::Sample) { failed_ = true; reportFailure("渐进音轨读取失败"); return false; }
        ComPtr<IMFMediaBuffer> buffer;
        HRESULT hr = MFCreateMemoryBuffer(static_cast<DWORD>(bytes.size()), buffer.put());
        BYTE* dst = nullptr; DWORD max = 0, current = 0;
        if (SUCCEEDED(hr)) hr = buffer->Lock(&dst, &max, &current);
        if (FAILED(hr)) { failed_ = true; return false; }
        memcpy(dst, bytes.data(), bytes.size()); buffer->Unlock(); buffer->SetCurrentLength(static_cast<DWORD>(bytes.size()));
        ComPtr<IMFSample> sample; hr = MFCreateSample(sample.put());
        if (FAILED(hr)) { failed_ = true; return false; }
        sample->AddBuffer(buffer.get()); sample->SetSampleTime(record.pts);
        const uint64_t n = files_.count() - 1;
        sample->SetSampleDuration(((n + 1) * 1024ULL * 10000000ULL / rate_) - (n * 1024ULL * 10000000ULL / rate_));
        *output = sample.get(); sample.detach(); return true;
    }
private:
    LiveSampleFiles files_;
    std::atomic<bool> stopped_{false};
    uint32_t rate_ = 0, channels_ = 0;
    std::vector<BYTE> specific_;
    bool failed_ = false;
};
