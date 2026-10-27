#include "../../main/native/PlaybackGpuSelection.h"

#include <algorithm>
#include <cassert>
#include <vector>

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
    assert(candidates[0].vendor == kNvidia);
    assert(candidates[1].vendor == kIntel);
    assert(candidates[2].vendor == kAmd);
    assert(matches(L"auto", kNvidia) && matches(L"auto", kIntel) && matches(L"auto", kAmd));
    assert(matches(L"intel", kIntel) && !matches(L"intel", kNvidia));

    // 首选 NVIDIA 不能启动时继续尝试 Intel；全部不可用时交给软件回退。
    std::vector<Candidate> fallback{{kNvidia, 1}, {kIntel, 0}};
    std::vector<std::uint32_t> tried;
    const int success = tryAdapters(fallback, [&](const Candidate& candidate) {
        tried.push_back(candidate.vendor);
        return candidate.vendor == kNvidia ? 2 : 0;
    });
    assert(success == 0 && tried == std::vector<std::uint32_t>({kNvidia, kIntel}));
    assert(tryAdapters(fallback, [](const Candidate&) { return 2; }) == 2);

    // 处理已经开始后遇到的真实错误不能静默切换并把失败当作成功。
    tried.clear();
    assert(tryAdapters(fallback, [&](const Candidate& candidate) {
        tried.push_back(candidate.vendor);
        return 1;
    }) == 1);
    assert(tried == std::vector<std::uint32_t>({kNvidia}));
}
