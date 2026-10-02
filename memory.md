# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-03。
> 当前分支：audit；本地与个人远端 mine 只保留 main、audit，两个分支各自与远端对齐。
> 应用版本统一为 1.8.1；Android/iOS/macOS 构建号为 22。
> main 基线 c82617c；audit 保留安全修复 030fae3 并合入 main，三端包源码提交为 4c7e7dd。最后记录提交只改文档。

## 本阶段已做到

- main 已提交/推送首页并行同步、校历自身超时失败处理、iOS 原生模糊及回顶透明修复；主线全量共享/JVM 561 项通过。
- 修正原先依赖系统月份的物理在线回归测试，用固定 2026-09-23 时间验证课程切换与首页全课程议程；默认应用仍用系统时钟。
- audit 保留 Cookie 清理/https/逐跳白名单/Secure Cookie 安全修改与 Apple 日历精确范围替换；CI 覆盖 main、audit。
- 合并后 audit 桌面共享 576 项、iOS 模拟器 537 项全部通过，Windows Kotlin 编译通过；日志 /tmp/bjtu-181-audit-checks.log。
- GitHub 源码检查成功：main run 37039046269 / c82617c，audit run 37046769022 / 4c7e7dd。
- audit 的 1.8.1 APK、unsigned IPA、DMG 已输出 Downloads（文件名带 audit）；三端版本/架构校验通过，APK 原共享证书签名和 CRC、IPA 无签名和 CRC、DMG 镜像与包内严格签名验证通过。
- 两条 codex 临时分支已删除，旧远端 audit/1.8.0-2026-09-29 已改为 audit；首页工作区已可恢复归档，上游 origin 保留原作者引用。
- 回归报告和三个包的哈希见 docs/security/BJTU-KMP-Audit-Verification-2026-10-03.md；Downloads 有独立 SHA256SUMS 和 manifest。

## 当前痛点

- 仍待真机：iPhone Keychain 卸载重装/CAS 登出、Android WebView 换账号与验证码、Apple 日历范围替换、首页完整原生导航壳。日历验收需用独立测试日历，保留手工事件与考试。
- iOS Keychain 原生用例在系统不可用时会提前返回，计数 0 skipped 不能代替合法签名真机往返。
- Windows DPAPI/Crypt32 与模型原生测试须在 Windows 主机执行；本机 Mac 的该类运行失败是平台不支持，本轮 Windows 证据是编译。
- iOS IPA 需自行签名安装，Mac DMG 为本地 ad-hoc 签名、未公证；本轮没有覆盖 Mac 安装应用或写入真实日历。

## 接下来

1. 按新的 1.8.1 audit 包做设备验收并补充结果。
2. 验收通过后按用户明确要求决定 audit 合入 main 及正式发布；本轮未创建新标签或 GitHub Release。
