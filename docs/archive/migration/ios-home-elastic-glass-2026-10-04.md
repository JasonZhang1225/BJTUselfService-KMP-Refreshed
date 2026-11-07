# iOS 首页玻璃渐显定版（2026-10-05）

## 问题与原因

首页渐显效果恢复后，短首页上拖时日程文字进入标题栏仍清晰可见。长列表滚动或孤立材质组件测试没有覆盖短首页的弹性滚动。

在 iPhone 17 Pro / iOS 27 模拟器实际渲染共享 `HomeWorkspace`（模拟数据、无业务网络）后，常规 `LazyListState.scrollBy` 的消耗始终为 0：页面不足以形成常规滚动。iOS 原生 Cupertino overscroll 会通过布局层真实移动内容，逻辑列表偏移仍为 0。原上报只读逻辑偏移，因此材质一直收到 0，而内容已弹入标题栏。

## 定版实现

- 原生玻璃采用连续 alpha 渐显：顶部材质的透明度随滚动进度连续变化，保持真实模糊和渐隐。
- 首页将同一 overscroll 效果的事件处理保留在 LazyColumn，视觉节点渲染在外部，使用公开的 `withoutVisualEffect` API 保证只渲染一次。
- 观察视口与内容的实际布局位置，以 `逻辑滚动 + 视口位置 - 内容位置` 折算真实上滚量，向下拖动钳制为 0。短页可以有材质进度，回弹位置每帧归零时材质也每帧淡出。
- 原生顶部净空未启用、或宽屏时沿用逻辑偏移上报；页面数据、请求与渐显算法未改变。
- 依赖源码依据：当前 [Foundation 1.12.0-beta03 sources](https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation/1.12.0-beta03/foundation-1.12.0-beta03-sources.jar) 中 `CupertinoOverscrollEffect.ios.kt` 的 `placeWithLayer(offset())`，以及 `Overscroll.kt` 的 `withoutVisualEffect` 公开包装。

用户确认这一版本同时恢复了连续渐显和首页模糊，将其指定为模糊效果定版。

## 验证

- 实际共享首页 + iOS Compose/Skia 渲染 + 当前原生玻璃组件，通过公开 OverscrollEffect API 及节点指针事件模拟短页上拖/释放，无私有系统 API、无登录或真实数据写入。
- 列表逻辑偏移保持 0；实测上报为 `0,21,42,63,83,103,123,142,161...` 像素，对应 `0,0.135,0.269,0.404,0.532,0.660,0.788,0.910,1...`，回弹过程中逐帧降至 0。
- 画面证据显示进入标题栏后的首页文字被真实模糊，标题保持清晰；回弹到 0 后完全透明。渲染验证使用模拟数据，不登录、不访问业务网络、不写入真实数据；最终效果另由用户确认有效。
- 诊断入口、额外模糊层、注入参数和临时源码已移除。
- `:shared:desktopTest`：581 项，0 失败；`:shared:iosSimulatorArm64Test`：541 项，0 失败。
- Android `:androidApp:compileReleaseKotlin` 检查通过。
- 实际 iPhoneOS Release 构建通过；Apple 元数据、ZIP CRC、UTF-8 路径、可执行权限、1.8.1（22）、无签名/provisioning/PlugIns 检查通过。

## 交付

- `BJTUSelfService-KMP-1.8.1-audit-fix4-iOS-unsigned.ipa`：41,833,085 字节。
- SHA-256：`2a11f8507fcea07a4dfd2e22d2d23cd0327f43fb685ec1f828ec7bebbb748b03`。
- Downloads 下 `BJTUselfServiceKMP-1.8.1-audit-fix4-verification` 保存模拟首页阶段截图、回弹截图和进度日志，README 明确模拟数据与验证边界。
- 版本为 1.8.1（22）；本轮只交付 iOS 新包，Android/Mac 沿用既有测试包。
