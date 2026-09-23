# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-09-23（安全审计 P1/P2 修复完成，待 Mac/iPhone 真机验收）
> 当前分支：`Liquid`，HEAD `8767173`；安全修复推送到 `codex/security-p1-p2`，草稿 PR #4 指向 `Liquid`，未合并。
> 工作区：保留用户已有未跟踪 `.workbuddy/`，未修改。
> `history_full.md` 是只读历史归档；本文件只记录当前事实、未决事项和下一步，不重复历史细节。

## 当前目标

- 安全审计 P1/P2 代码修复已完成，状态为“待真机验证”；用户稍后到 Mac 测试后再更新报告并决定 PR 合并。
- P0 凭据轮换由用户处理，用户确认暂按已解决。
- 本轮按用户确认将最低 iOS 版本从 15.0 提高到 16.0。
- iOS 真机安装需要 Mac/Xcode 与可用签名；设备端验收步骤列于 PR #4。

## 本阶段已做到

- 2026-09-21 完成只读安全复查（1.7.8-Liquid）：上轮安全修复全部保持、无回归、无高危；新增中危 M1（WebView Cookie 登出不清理）、M2（iOS Keychain 卸载残留）、M3（macOS 无卸载清理）、M4（桌面/Windows 明文缓存）+ 低危 L1-L10，报告在 `docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`。
- 2026-09-23 完成 M1-M4 与审查低危项的本轮修复：登出清理平台 WebView 数据并请求 CAS 注销；桌面缓存 AES-GCM 加密、旧库升级时重建；macOS 全量清理截断 WAL/VACUUM、删除凭据和偏好标记并退出；iOS 缺少安装内“记住密码”标记时清除残留 Keychain 项。另修正验证码放弃路径的清理竞态、phyvlab 外链改写、debug 安全测试页面导出权限、Wrapper 哈希、OkHttp 固定版本、验证码 ONNX 迁移、桌面日志默认开启和旧 Liquid 标签误触发旧发布流水线。
- 最新 CI `35825484777` 通过：Android `:shared:compileAndroidMain :androidApp:compileDebugKotlin` 编译成功；iOS 模拟器 503 项测试 0 失败，桌面 536 项通过；Xcode Debug/Release deployment target 均为 16.0。原生 Keychain 测试在 CI 因 OSStatus `-25291` 无可用服务而未实际执行，须真机验收。
- 用户确认最低 iOS 版本提到 16.0；L9 DPAPI 编译期附加熵作为可选残余项保留（同用户偏好存储的随机熵无法提升其威胁边界）。
- Liquid 里程碑与 1.7.8-Liquid 四端发布已归档至 `history_full.md`「M17 Liquid」节；四端产物在 `/Users/zjg/Downloads`。
- GitHub Release `v1.7.8-Liquid` 已正式发布，自动流水线 run `35579588977` 全部成功，发布页为 `https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/releases/tag/v1.7.8-Liquid`；发布正文已补齐，包含四个 CI 产物。

## 当前痛点（≤8 条）

- 安全审计报告当前状态为待真机验证：iPhone Keychain 卸载重装清理、带登录态的 CAS/WebView 登出、Android 真机验证码效果、macOS 全量清理体验。
- 草稿 PR #4 `https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/pull/4` 待上述设备验收后再合并；CI 中 unsigned iOS 测试宿主的 Keychain 未覆盖。
- iOS 标题栏真玻璃全页铺开（本轮提交）：`fcfdb09` 已推 `mine/Liquid`，tag `v1.7.8-Liquid` 改绑过去，kmp-package 四件全绿换新。玻璃跟手：桥接改连续 `scrollProgress`，各页上报真实偏移（`LocalReportTopScroll`，首项之后视为全盖）；首页首项是整张高卡，通用上报在其进栏时仍读 0，改为读首项 `offset` 负值（静止 0/上滑即有值/过滚仍 0，其他页不动；之前 `beforeContentPadding - offset` 静止也糊的方案已撤回）。课表/课件/教室详情/占用详情用 `staticTopBar` 保持静态栏；邮箱列表去重刷新图标。短内容留栏下、横幅收进列表首项；其余平台零影响。真机滚动态用户已确认完美。
- Compose Material3 alpha 依赖 compileSdk 37 豁免开关（`android.experimental.disableCompileSdkChecks`），正式版后应移除。

## 接下来 1～3 个阶段

1. 用户稍后在 Mac/iPhone 按 PR #4 验收清单进行真机测试，记录结果并更新安全审计状态。
2. 根据设备结果修复必要问题；验收通过后更新 PR 状态并决定合并。
3. 单独处理 Material3 alpha/compileSdk 豁免与其他未决的产品支持问题。

## 相关文件

- 安全复查报告：`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`（本轮）、`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-17.md`（上轮）。
- Liquid 计划与发布：`docs/migration/m17-apple-liquid-glass-shell-plan.md`、`docs/releases/v1.7.8-Liquid.md`。

## 维护规则

- 每轮开始先确认 `git branch --show-current`、工作区状态和本文件；事实以当前源码、命令输出和当前截图为准。
- 已完成历史只进 `history_full.md`，实时文件只保留当前接续需要的结论；过期的“已落地/已实机/当前方案”必须删除或改成历史语气。
- 事实与计划分开写，明确验证范围；不记录账号、密码、Cookie、令牌、验证码或其他敏感会话内容。
