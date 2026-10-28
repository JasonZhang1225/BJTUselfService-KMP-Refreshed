#include "../../main/native/PlaybackGpuSelection.h"

#include <algorithm>
#include <vector>

#define REQUIRE(...) do { if (!(__VA_ARGS__)) return __LINE__; } while (false)

struct Candidate {
    std::uint32_t vendor;
    std::uint64_t memory;
};

int main() {
    using namespace playback_gpu;
    // 即使其他显卡报告更大专用显存，自动选择仍先试 NVIDIA。
    std::vector<Candidate> candidates{{kIntel, 16}, {kAmd, 8}, {kNvidia, 1}};
    std::stable_sort(candidates.begin(), candidates.end(), [](const Candidate& a, const Candidate& b) {
        return before(a.vendor, a.memory, b.vendor, b.memory);
    });
    REQUIRE(candidates[0].vendor == kNvidia);
    REQUIRE(candidates[1].vendor == kIntel);
    REQUIRE(candidates[2].vendor == kAmd);
    REQUIRE(matches(L"auto", kNvidia) && matches(L"auto", kIntel) && matches(L"auto", kAmd));
    REQUIRE(matches(L"intel", kIntel) && !matches(L"intel", kNvidia));

    // 首选 NVIDIA 不能启动时继续尝试 Intel；全部不可用时交给软件回退。
    std::vector<Candidate> fallback{{kNvidia, 1}, {kIntel, 0}};
    std::vector<std::uint32_t> tried;
    const int success = tryAdapters(fallback, [&](const Candidate& candidate) {
        tried.push_back(candidate.vendor);
        return candidate.vendor == kNvidia ? 2 : 0;
    });
    REQUIRE(success == 0 && tried == std::vector<std::uint32_t>({kNvidia, kIntel}));
    REQUIRE(tryAdapters(fallback, [](const Candidate&) { return 2; }) == 2);

    // 处理已经开始后遇到的真实错误不能静默切换并把失败当作成功。
    tried.clear();
    REQUIRE(tryAdapters(fallback, [&](const Candidate& candidate) {
        tried.push_back(candidate.vendor);
        return 1;
    }) == 1);
    REQUIRE(tried == std::vector<std::uint32_t>({kNvidia}));
}
