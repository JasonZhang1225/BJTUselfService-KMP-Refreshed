# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-09-29（audit 分支修复已通过代码级门禁并准备提交；本分支改动整体仍待真机检验，不代表设备验收完成）
> 当前分支：`audit/1.8.0-2026-09-29`（从 `main`/`413a47a` 检出）。
> 工作区：本分支审计修复、报告与课程日历修复将按用户要求一并提交，并推送到个人远端 `mine`；不推上游 `origin`。所有设备相关验收仍待真机。
> `history_full.md` 是只读历史归档；本文件只记录当前事实、未决事项和下一步。

## 当前目标

- 在提出上游 Issue / PR 之前，先把 1.8.0 的安全与代码味道核完并修掉本轮中危。
- 上轮 P1/P2 安全修复保持有效；Mac 全量清理已验收。iPhone/Android 与带登录态的 CAS/WebView 仍待验。
- 下一步才是对照上游仓库写差异文档和 Issue 草稿。未经要求不 commit / 不 push / 不提 Issue。

## 本阶段已做到

- 2026-09-29 只读复查 1.8.0 HEAD `413a47a`：KMP 无新增高危、无 TLS 回退；上轮 WebView 登出清理、桌面 AES-GCM、CAS 注销、iOS 重装清 Keychain、ONNX、Wrapper SHA-256 均保持。报告：`docs/security/BJTU-KMP-Security-Audit-2026-09-29.md`、`docs/refactor/BJTU-KMP-CodeShit-Audit-2026-09-29.md`。
- 同日检出 `audit/1.8.0-2026-09-29` 并修：Android WebView 清罐后再注入；CI 看 `main`；发布测试对齐 `v1.7.0`；握手/教室/作业课件 `smartRequest` 禁用自动跟随（M4/M5）；WebView 导航强制 https；iOS Cookie 补 Secure。相关 desktopTest 通过。变更随本分支提交。给人看的修复总结：`docs/security/BJTU-KMP-Audit-Fix-Summary-2026-09-29.md`。
- 同日修复课程日历重导入范围错配：iOS/macOS 按完整学期窗口识别旧的 App 管理课程系列，先删除再按新选范围重建；手工日程和考试 marker 不参与清理，重复规则与考试单条导入保持原边界。`.ics` 仍只生成所选周段。共享日历测试、desktopTest 契约检查、iOS Simulator shared 编译、macOS Kotlin 与 Swift helper 编译均通过；未写入真实日历。当前分支改动整体仍待真机检验。
- 2026-09-29 前：秋假空档首页「日程加载中」已修并推 `mine/main`；1.8.0 未签名 IPA 在 Downloads，未真机。v1.7.9 四端已发布。

## 当前痛点（≤8 条）

- **待真机检验**：本分支所有修复（包括安全审计修复、Apple 日历精确范围替换及相关 UI/会话行为）目前只有源码、自动化测试或构建证据，未完成对应设备/系统日历实机验收；不得描述为真机已验证。
- 设备验收仍缺：iPhone 卸载重装 Keychain、带登录态 CAS/WebView 登出、Android 真机验证码；课程日历精确范围也需在独立安全测试日历检验，全学期后窄范围重导入应只保留新范围内 App 管理课程，并保留手工日程/考试。不得操作用户现有日历。
- `AuthenticatedAppShell` 2000 行，Issue 里需诚实写上；本轮不拆。
- Material3 alpha 仍靠 `android.experimental.disableCompileSdkChecks`。
- 上游差异文档 / Issue 草稿尚未写。

## 接下来 1～3 个阶段

1. 本分支已提交并推送，但所有设备相关行为仍标记待真机验收；优先在独立测试日历检验课程精确范围替换。
3. 本分支所有设备相关改动仍待实机验收，尤其是 iPhone/Android 对应清单与独立测试日历中的课程精确范围替换。

## 相关文件

- 本轮：`docs/security/BJTU-KMP-Audit-Fix-Summary-2026-09-29.md`（给人看的总结）、`docs/security/BJTU-KMP-Security-Audit-2026-09-29.md`、`docs/refactor/BJTU-KMP-CodeShit-Audit-2026-09-29.md`
- 上轮：`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`、`docs/refactor/BJTU-KMP-CodeShit-Audit-GLM-2026-09-17.md`
