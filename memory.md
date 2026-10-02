# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-03。
> 当前工作分支：`audit`，合入 `main@c82617c` 的 1.8.1 / Build 22，并保留 `030fae3` 的安全审计与课程日历修复。
> 本地与个人远端 `mine/main` 已对齐 c82617c；audit 合并、回归和三端打包正在进行。

## 本阶段已做到

- main 已提交并推送首页并行同步、公开校历超时失败处理、iOS 原生模糊与回顶透明修复。
- 四端应用版本统一 1.8.1；Android/iOS/macOS Build 22；README 与版本测试同步。
- main 全量共享/JVM 561 项测试通过；Windows Kotlin 编译通过。月份依赖测试使用固定 2026-09-23 时间。
- CI 覆盖 `main` / `audit`；发布守卫按冻结根 Android 只发布 v1.7.0 校验。
- audit 保留 Android WebView 清 Cookie 后注入、白名单逐跳握手、https 网页导航、iOS Secure Cookie，以及 Apple 课程日历精确范围替换。
- 合并冲突只涉及 CI、发布守卫测试和工作记录；按两分支检查范围与审计测试全集解决。
- 1.8.0 首页修复 IPA 仍在 Downloads，旧包 SHA-256 为 `5a5543ea5e0c470cdab2e7f7530bd04b5e18e7903e29e47e4694863a4ae40c0f`。

## 当前痛点

- 合并后的 audit 全量回归、iOS Simulator 检查与 APK/IPA/DMG 构建正在进行。
- 待真机：iPhone Keychain 卸载重装、CAS/WebView 登出、Android 验证码与换账号、Apple 日历范围替换、首页完整原生导航壳。
- 日历实测须使用独立测试日历，保留手工事件与考试，不能操作用户已有日历。
- Windows DPAPI/Crypt32 与 Windows 模型原生测试需在 Windows 主机执行；本机只做 Windows 编译，Mac 上运行失败属于平台不支持。

## 接下来

1. 完成 audit 合并并推送，清理旧名称和两条本地临时分支。
2. 运行合并后的代码级/重构回归检查并记录真实结果。
3. 从 audit 生成 1.8.1 APK、未签名 IPA、Mac DMG，校验版本、签名和压缩包完整性。
