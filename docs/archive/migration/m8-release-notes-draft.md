# 交大自由行 KMP 0.1.0 发布说明草稿

> 状态：可编辑草稿，不代表已经发布  
> 行为对齐基线：原 Android `v1.7.0@419313d`  
> 目标平台：iPhone、iPad、Apple Silicon Mac

## 一句话说明

这是“交大自由行”的 Kotlin Multiplatform + Compose Multiplatform Apple 端迁移预览版，共享登录、数据、业务规则和大部分界面，同时针对 iPhone、iPad 与 macOS 分别适配导航、窗口、文件和系统交互。

## 本版包含

- 北交大 MIS/CAS 手动验证码登录，账号密码只发送至学校登录服务。
- 首页状态卡、周议程、作业截止提醒和成绩/课表/考试/作业变动信息流。
- 成绩、课程表、考试安排、作业、课件、其他功能、教室、邮箱和设置入口。
- iPhone 单列与底部导航、iPad 自适应布局、macOS 侧栏/原生菜单/快捷刷新/关窗不退出。
- Android Keystore 与 Apple Keychain 凭据边界；普通缓存按账号隔离。
- iOS/iPadOS Dynamic Type、增强对比度、减弱动态效果、降低透明度和基础 VoiceOver 语义。
- iOS/iPadOS 与 macOS 正式应用图标、隐私清单和独立 Bundle ID。

## 平台差异

- Apple 端不复制 Android APK 更新流程；正式发布后应使用 App Store、签名安装包或项目发布页。
- macOS 可使用系统菜单和 `⌘R`；关闭窗口不会退出应用，`⌘Q` 才结束进程。
- iOS/iPadOS 使用系统文件面板；校园网续费三端直接交给默认浏览器，macOS 使用系统文件对话框，其他网页能力按已记录边界处理。
- Android 的 Material You、壁纸/Haze 和 Glance 小组件没有用无效开关或占位界面照搬。小组件按用户决定延后到三端公版主应用完成后；平台通知和个性化背景也需要后续独立实现与验收。

## 安全提示

作业与课件所在的旧智慧教学平台没有可用的 HTTPS 等价通道。iOS/iPadOS 与 macOS 版在用户明确接受风险后，允许把当前登录会话发送到以下两个精确旧地址范围：

- `http://123.121.147.7:88/ve/…`
- `http://123.121.147.7:1936/kk/rp/…`

明文链路可能被同一网络上的第三方窃听或篡改。应用不会为其他地址开放明文请求，`sessionid` 只驻留内存且日志脱敏；iOS ATS 只为固定 IP 开例外，端口/路径仍由共享白名单限制，Android 继续拒绝这两个旧 HTTP 会话端点。

## 当前验证

- 共享 Desktop 测试：224 项，0 failure / 0 error / 0 skipped。
- Android debug/release、iOS Simulator/device arm64 编译通过。
- Xcode 通用 iOS Simulator 宿主构建通过；iPad Pro 11-inch (M5) / iPadOS 26.5 已完成布局、系统无障碍开关和主屏图标 Computer Use 验证。
- macOS Apple Silicon 自包含 `.app` 构建通过；Bundle ID、最低 macOS 12.0、教育类目、图标、Core ML 模型和隐私清单已在最终包中核对，ad-hoc 签名通过严格深度校验。
- 冻结的原 Android 工程摘要保持不变。

## 已知限制

- 尚未完成 Apple Developer Team、真机、Release Archive、Developer ID/App Store 正式签名、公证和 App Store Connect 审核。
- 未签名 iOS Simulator 无法证明 Keychain entitlement 的正式往返行为。
- iOS 当前有效会话已完成首页和九入口核心只读流程；会话失效后的新 CAPTCHA 登录仍受学校服务状态影响，但手动回退路径可用。
- macOS 与 iOS 精确明文作业/课件链路均已有真实会话证据；风险提示和固定端点白名单仍是已知安全限制。
- 真实上传与信息流真实变动样本仍需后续复测；下载和系统文件面板已在 iOS/macOS 验收。进一步无障碍专项已按用户决定移出当前发布门禁。
- 验证码自动识别和登录成功后的四项条件同步已接通；验证码仍需扩大独立留出集。小组件、系统通知、无期限后台同步、壁纸/玻璃态仍不在本预览版完成能力中；其中小组件明确延后到公版主应用完成后。
- macOS 当前构建只含 arm64；是否增加 Intel x64 或 universal binary 在正式发布前决定。

## 安装说明草稿

### iPhone / iPad

当前仅提供开发构建。需要由 Xcode 使用合法 Apple Development Team 签名后安装到真机；不要把未签名 Simulator `.app` 当作可安装 IPA。

### macOS

当前 Apple Silicon `.app` 是本地 ad-hoc 签名开发产物，未公证。正式外部分发前必须使用 Developer ID 签名并完成 Apple 公证；不要引导用户长期关闭 Gatekeeper。

## 发布前编辑清单

- [ ] 确认正式版本号、构建号和发布日期。
- [ ] 填写发布者名称、支持网址和隐私政策网址。
- [ ] 删除不再适用的“已知限制”，其余逐项保留。
- [ ] 补充真机型号、系统版本和签名/公证证据。
- [ ] 加入脱敏的 iPhone、iPad 和 macOS 商店截图。
- [ ] 核对 App Store 隐私标签和 Xcode Privacy Report。
- [ ] 用户审阅并批准最终文案后，才允许提交、打标签、推送或发布。
