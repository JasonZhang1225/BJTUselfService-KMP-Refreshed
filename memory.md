# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-03。
> 当前目标：按用户要求整理为 `main` / `audit` 两个分支，并将应用升级到 1.8.1（Build 22）。
> 主线目标为 `main` 1.8.1 / Build 22；个人远端为 `mine`。待将这份主线合入审计分支后再输出三端安装包。

## 本阶段已做到

- 首页课表与校历、校历内部学期与公开日期改为并行请求；保持校历校验和手动选周。
- 公开校历 6 秒超时改为失败结果，不再当成用户取消而中断同步；用户取消仍向上传递。
- iOS 标题栏原生玻璃保持 alpha=1，以直接遮罩控制显示范围；回顶完全隐藏。
- 1.8.0 首页修复已通过 159 项相关测试与各端编译；模拟器 fixture/原生探针验证浅深色、中间滚动和回顶，真机完整导航壳仍待复测。
- 1.8.0 首页修复未签名 IPA：`/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.0-home-fixes-iOS-unsigned.ipa`，SHA-256 `5a5543ea5e0c470cdab2e7f7530bd04b5e18e7903e29e47e4694863a4ae40c0f`；此前不包含待合入安全修改。
- 本轮统一 Android/iOS/macOS/Windows 与应用内版本为 1.8.1，Android/iOS/macOS Build 为 22；CI 将检查 `main` 和 `audit`，发布守卫测试对齐冻结根 Android 的 v1.7.0 条件。

- 全量共享/JVM 回归 561 项通过；旧物理在线月份测试已固定为 2026-09-23，避免系统跨月后失效。Windows Kotlin 编译通过；Windows DPAPI/Crypt32 与 Windows 模型原生测试需在 Windows 环境执行，Mac 上尝试运行的结果不能作为产品回归结论。

## 当前痛点

- 主分支共享回归已通过；与安全分支合并后的回归检查、三端 1.8.1 打包正在进行。
- 仍待真机：iPhone Keychain 卸载重装、CAS/WebView 登出、Android 验证码和 WebView 换账号、Apple 日历范围精确替换及首页标题栏。
- 日历实测须使用独立测试日历，保留用户手工事件和考试安排。
- `AuthenticatedAppShell` 仍较大；本轮只整理、集成和检查，不另行重写无关模块。

## 接下来

1. 提交并将首页修复/版本更新同步到本地与远端 main。
2. 将 main 合入 audit，保留安全与课程日历修复，完成全量回归。
3. 从 audit 输出 APK、未签名 IPA、Mac DMG，完成分支和产物一致性校验。
