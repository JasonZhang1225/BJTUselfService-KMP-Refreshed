#pragma once

#include <cstdint>
#include <string>

namespace playback_gpu {

constexpr std::uint32_t kNvidia = 0x10de;
constexpr std::uint32_t kAmd = 0x1002;
constexpr std::uint32_t kIntel = 0x8086;

inline bool matches(const std::wstring& requested, std::uint32_t vendor) {
    return requested == L"auto" || (requested == L"nvidia" && vendor == kNvidia) ||
        (requested == L"amd" && vendor == kAmd) || (requested == L"intel" && vendor == kIntel);
}

// NVIDIA 可用时优先；其他硬件按专用显存排序，显存相同则保留系统枚举顺序。
constexpr bool before(std::uint32_t aVendor, std::uint64_t aMemory,
                      std::uint32_t bVendor, std::uint64_t bMemory) {
    if ((aVendor == kNvidia) != (bVendor == kNvidia)) return aVendor == kNvidia;
    return aMemory > bMemory;
}

// 返回 2 表示该硬件在启动时不可用，应尝试下一块；其他结果直接返回。
template <typename Candidates, typename Attempt>
int tryAdapters(Candidates& candidates, Attempt attempt) {
    for (auto& candidate : candidates) {
        const int result = attempt(candidate);
        if (result != 2) return result;
    }
    return 2;
}

} // namespace playback_gpu
