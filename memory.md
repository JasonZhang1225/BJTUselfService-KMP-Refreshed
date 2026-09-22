# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-09-22（iOS 标题栏玻璃跟手修正已提交并推送 `mine/Liquid`）
> 当前分支：`Liquid`，HEAD `5ced798`，已与 `mine/Liquid` 对齐（`v1.7.8-Liquid` 标签仍在 `fcfdb09`，Release 已按该提交发布）。
> 工作区：干净。
> `history_full.md` 是只读历史归档；本文件只记录当前事实、未决事项和下一步，不重复历史细节。

## 当前目标

- 安全复查遗留项修复：按报告第六节优先级推进（用户明确「后续来修」，尚未开始）。
- Liquid 视觉最终验收：各类 sheet 审美、边缘返回、减少透明度、iOS 26 以下回退壳、iPad 宽屏、首页卡片交互，待用户主观确认。
- iOS 真机签名安装：unsigned IPA 需开发者证书重签名后才能上真机。

## 本阶段已做到

- 2026-09-21 完成只读安全复查（1.7.8-Liquid）：上轮安全修复全部保持、无回归、无高危；新增中危 M1（WebView Cookie 登出不清理）、M2（iOS Keychain 卸载残留）、M3（macOS 无卸载清理）、M4（桌面/Windows 明文缓存）+ 低危 L1-L10，报告在 `docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`。
- Liquid 里程碑与 1.7.8-Liquid 四端发布已归档至 `history_full.md`「M17 Liquid」节；四端产物在 `/Users/zjg/Downloads`。
- GitHub Release `v1.7.8-Liquid` 已正式发布，自动流水线 run `35579588977` 全部成功，发布页为 `https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/releases/tag/v1.7.8-Liquid`；发布正文已补齐，包含四个 CI 产物。

## 当前痛点（≤8 条）

- 安全复查 4 个中危均未修，M1（登出清 WebView Cookie）改动小收益大、最优先。
- iOS 模拟器因签名身份更换替换了 App 容器，无可读取 Keychain 登录态，自动登录视觉复现待用户重新登录。
- iOS 标题栏真玻璃全页铺开（本轮提交）：`fcfdb09` 已推 `mine/Liquid`，tag `v1.7.8-Liquid` 改绑过去，kmp-package 四件全绿换新。玻璃跟手：桥接改连续 `scrollProgress`，各页上报真实偏移（`LocalReportTopScroll`，首项之后视为全盖）；首页首项是整张高卡，通用上报在其进栏时仍读 0，改为读首项 `offset` 负值（静止 0/上滑即有值/过滚仍 0，其他页不动；之前 `beforeContentPadding - offset` 静止也糊的方案已撤回）。课表/课件/教室详情/占用详情用 `staticTopBar` 保持静态栏；邮箱列表去重刷新图标。短内容留栏下、横幅收进列表首项；其余平台零影响。真机滚动态用户已确认完美。
- 桌面端 `AppLog` 分发构建默认开启（低危 L3），与 Android release 不对齐。
- Compose Material3 alpha 依赖 compileSdk 37 豁免开关（`android.experimental.disableCompileSdkChecks`），正式版后应移除。

## 接下来 1～3 个阶段

1. 修 M1：登出/会话失效时清 Android `CookieManager` 与 iOS `WKWebsiteDataStore`，补回归测试。
2. 修 M3/M4：macOS 卸载残留（脚本 + 全量清除补删文件/prefs/Keychain 条目）与桌面/Windows 明文缓存处理。
3. Liquid 视觉验收收口 + iOS 真机重签名安装验证。

## 相关文件

- 安全复查报告：`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`（本轮）、`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-17.md`（上轮）。
- Liquid 计划与发布：`docs/migration/m17-apple-liquid-glass-shell-plan.md`、`docs/releases/v1.7.8-Liquid.md`。

## 维护规则

- 每轮开始先确认 `git branch --show-current`、工作区状态和本文件；事实以当前源码、命令输出和当前截图为准。
- 已完成历史只进 `history_full.md`，实时文件只保留当前接续需要的结论；过期的“已落地/已实机/当前方案”必须删除或改成历史语气。
- 事实与计划分开写，明确验证范围；不记录账号、密码、Cookie、令牌、验证码或其他敏感会话内容。
