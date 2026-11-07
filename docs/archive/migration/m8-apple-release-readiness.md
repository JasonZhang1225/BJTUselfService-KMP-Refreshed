# M8 Apple 发布准备审计

> 日期：2026-07-31  
> 功能对齐基线：官方 `v1.7.0@419313d`  
> KMP 自身版本：iOS/Android/shared `0.1.0`；macOS 分发包 `1.0.0`

## 结论

Apple 两端可在没有开发者账号的范围内完成的发布元数据缺口已经补齐并由最终 `.app` 验证。2026-08-01 按用户决定移除课程表 Widget 后，iOS 发布校验恢复为只验证宿主 App，不再要求扩展 Bundle、App Group 或 Widget 二进制。M8 仍保持进行中，因为真机、合法分发签名、Archive、Developer ID、公证和 App Store Connect 审核尚未执行。

`v1.7.0` 是原 Android App 的功能与回归基线，不是新 KMP App 已发布的版本号。迁移构建继续使用 `0.1.0`；Compose Desktop 经 `jpackage` 生成的 macOS 包受版本格式限制，保留 `1.0.0`，不把两者伪装成同一个正式 Release。

## iOS / iPadOS

- Bundle ID：`team.bjtuss.bjtuselfservice.kmp.ios`。
- 支持 iPhone 与 iPad；2026-09-23 安全审查后经用户确认，最低 iOS 版本提高到 16.0。该决定不追溯改变下文 2026-07-31 的构建验证记录；不声明相机、麦克风、照片、位置、联系人或蓝牙权限。
- `Info.plist` 不含 ATS 全局放宽；iOS 只为智慧教学固定 IP 和公开教室域名 `yaya.csoci.com` 加域名级 `NSExceptionAllowsInsecureHTTPLoads`。ATS 不能约束端口/路径，因此共享层分别继续锁定既有智慧教学白名单与 `http://yaya.csoci.com:2333/api/classnum/`；教室请求使用独立、无登录 Cookie 的 transport。
- 新增 `Assets.xcassets/AppIcon.appiconset`，由单张 1024 × 1024、无透明通道主图生成 iPhone/iPad 图标。
- 第一个图标版本在 iPad 主屏 Computer Use 复核中显得过小；v2 放大同一吉祥物并保留安全边距。重新安装后主屏远距离识别度明显改善，角色、八条黄色光芒和应用名称均可辨识。
- 新增根级 `PrivacyInfo.xcprivacy`。代码直接使用 `NSUserDefaults` 保存仅本 App 可见的“记住登录信息”布尔偏好，因此声明 `NSPrivacyAccessedAPICategoryUserDefaults / CA92.1`；不声明未使用的 Required Reason API。
- 当前代码没有广告、分析、跟踪域或向开发者服务器上传数据的链路，因此清单写明不跟踪、未收集数据。正式提交前仍需由发布者按实际后端与 App Store Connect 隐私问卷再次确认，技术清单不能替代商店隐私标签。

最终通用 Simulator 构建：

```text
xcodebuild ... -destination 'generic/platform=iOS Simulator' ... build
** BUILD SUCCEEDED **
```

成品检查确认：

- `.app/PrivacyInfo.xcprivacy` 存在且 `plutil -lint` 通过；
- `CFBundleIconName = AppIcon`；
- `AppIcon60x60@2x.png` 与 `AppIcon76x76@2x~ipad.png` 均由 `actool` 生成；
- `Assets.car`、iPhone/iPad `CFBundleIcons` 和 `UIDeviceFamily = 1,2` 均存在；
- Mach-O 为 arm64 Simulator 产物。

针对具体 iPad M5 的第一次资源裁剪曾卡在 `AssetCatalogSimulatorAgent`，连续八分钟 0% CPU；终止遗留子进程后，改用通用 Simulator 目标构建成功。这是本机 Xcode/Simulator 资源代理问题，不是图标清单或 Swift/Kotlin 编译失败。

## macOS

- Bundle ID：`team.bjtuss.bjtuselfservice.kmp.macos`。
- 应用类别：`public.app-category.education`；Dock 名称为“交大自由行”。
- 最低系统版本最初由错误的 10.13 修正为 11.0；接入同源 Core ML 验证码模型后统一提高到 macOS 12.0，Swift helper 与 `coremlcompiler` 也使用 12.0 deployment target，元数据脚本按最终包校验 12.0。
- 默认 Compose 图标已替换为由同一 v2 主图编译的多尺寸 `.icns`。
- 新增 macOS 隐私清单，声明不跟踪且未收集数据。Compose 的 `appResourcesRootDir` 实际落到 `Contents/app/resources`，不符合 Apple 要求；独立、配置缓存兼容的 `finalizeMacDistributable` 任务会把清单放到 `Contents/Resources` 并更新本地 ad-hoc 资源封印。
- 用户授权的明文登录会话仍严格限于既定 `123.121.147.7:88/ve/` 和 `:1936/kk/rp/`；教室域名例外不携带该会话，也没有形成全局 Apple 明文白名单。

最终 `:desktopApp:createDistributable` 结果：

```text
BUILD SUCCESSFUL
Configuration cache entry stored.
```

成品检查确认：

- `Contents/Resources/BJTUselfServiceKMP.icns` 与 v2 输入逐字节一致；
- `Contents/Resources/PrivacyInfo.xcprivacy` 存在且合法；
- `CFBundleVersion = 1`、类别、最低系统版本和新 Bundle ID 正确；
- arm64 自包含 `.app` 通过 `codesign --verify --deep --strict`；
- 当前签名明确为 ad-hoc，`TeamIdentifier` 未设置，不能描述成正式 Developer ID 或 App Store 签名。

上述可重复检查已固化为：

```bash
cd multiplatform
./scripts/verify-apple-release-metadata.sh
```

脚本直接读取最终两个 `.app`，校验 iOS 图标/隐私清单/Bundle ID/arm64，以及 macOS 图标/隐私清单/Bundle ID/类别/最低系统版本/arm64/严格签名；不以源码配置代替成品证据。

## 仍需发布者/账号完成

1. 确定最终 KMP 正式版本号和构建号；不要直接沿用 Android 功能基线 `v1.7.0`。
2. 登录 Apple Developer 账号，确定正式 Bundle ID、Team、证书、Provisioning Profile 与 Keychain capability。
3. 在真实 iPhone/iPad 上验证 Keychain 往返、文件面板、登录和全功能清单。
4. 生成 Release Archive，检查 Xcode Privacy Report 与 App Store Connect Required Reason API 提示。
5. 为 macOS 配置 Developer ID、Hardened Runtime、正式签名、公证和 DMG；正式签名时应把隐私清单复制安排在签名之前，不能沿用当前打包后的 ad-hoc 收尾顺序。
6. 由发布者核对 App Store 隐私标签、支持网址、隐私政策、年龄分级、截图和发布说明。
7. 用户批准完整草稿后，才允许提交、打标签、推送或发布。

可编辑发布说明草稿见 `docs/migration/m8-release-notes-draft.md`；它明确使用 KMP `0.1.0`，没有把 Android 功能基线 `v1.7.0` 写成新应用已发布版本。

## 官方依据

- Apple Privacy Manifest Files：https://developer.apple.com/documentation/bundleresources/privacy-manifest-files
- Apple Required Reason API：https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api
- Apple App Icon 配置：https://developer.apple.com/documentation/xcode/configuring-your-app-icon
- Apple App Icon HIG：https://developer.apple.com/design/human-interface-guidelines/app-icons
