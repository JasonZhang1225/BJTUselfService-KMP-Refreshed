# M1 KMP + Compose Multiplatform 骨架结果

> 完成时间：2026-07-30  
> 结论：Android、iOS Simulator、macOS 三端均已构建、安装并实际显示同一份 `commonMain` Compose 页面；冻结根 Android 工程未改变。

## 交付结构

独立工程位于 `multiplatform/`，与冻结根 Android 工程完全分离：

```text
multiplatform/
├── shared/       # commonMain/commonTest + Android/iOS/Desktop actual
├── androidApp/   # 独立 applicationId 的 Android 宿主
├── desktopApp/   # Compose Desktop macOS 宿主
└── iosApp/       # SwiftUI + UIViewController Xcode 宿主
```

版本基线：Kotlin 2.4.10、Compose Multiplatform 1.11.1、Material 3 `1.11.0-alpha07`、AGP 9.1.1、Gradle 9.3.1。Material 3 版本按 Compose 1.11.1 官方组件表独立锁定，避免错误地假设所有 Compose 组件共享 `1.11.1`。

## 构建与测试证据

| 目标 | 命令/结果 |
| --- | --- |
| Gradle 工程 | `./gradlew projects`：`shared`、`androidApp`、`desktopApp` 三模块识别成功 |
| 共享测试 | `:shared:desktopTest`：通过；覆盖 599/600/899/900 dp 窗口边界和唯一当前平台标记 |
| Android | `:androidApp:assembleDebug`：成功；APK 31,476,374 bytes，SHA-256 `14bdfe63ae8cb35b007480f9f09e83cdb0b9f16d5796a1cea1d2466d8ebbb067` |
| macOS | `:desktopApp:compileKotlin`、`:desktopApp:createDistributable`、`:desktopApp:packageDmg`：成功；自包含 `.app` 约 134 MB；DMG 73,381,156 bytes，SHA-256 `8119d38cf44d2d3d45cc707c8db51d7416f71c00029165de3c3d7bcf5416dc0c` |
| iOS KMP | `:shared:linkDebugFrameworkIosSimulatorArm64`：成功 |
| iOS Xcode | Xcode 26.6、iPhone 17 Pro iOS 26.5、`CODE_SIGNING_ALLOWED=NO build`：`BUILD SUCCEEDED`；模拟器 `.app` 约 45 MB |
| 冻结 Android | 根工程 `:app:testDebugUnitTest :app:assembleDebug`：`BUILD SUCCESSFUL in 9s` |

`iosSimulatorArm64Test` 的测试二进制已链接，但执行器长时间无输出后被中止，因此不把它写成通过。M1 的共享逻辑测试由 Desktop/JVM target 运行；iOS 可运行性由 framework、Swift 编译/链接、安装和真实首帧共同证明。

## 视觉与交互证据

所有图片只包含 M1 占位页，不含账号、Cookie、验证码或真实业务数据。

- [Android 紧凑布局](m1-evidence/android-kmp.png)
- [Android 按钮展开](m1-evidence/android-kmp-expanded.png)
- [iOS 紧凑布局](m1-evidence/ios-kmp.png)
- [iOS 按钮展开](m1-evidence/ios-kmp-expanded.png)
- [macOS 宽布局](m1-evidence/macos-resize-check.png)
- [macOS 按钮展开](m1-evidence/macos-expanded.png)

Computer Use 读取到的语义树确认：

- iOS 当前平台为 `iOS 26.5 · 紧凑窗口`，按钮从“查看共享边界”变为“收起共享边界”，并新增四条共享边界说明。
- macOS 当前平台为 `macOS 27.0 · 宽窗口`，同一按钮状态和内容发生对应变化。
- Android Studio Running Devices 中真实显示 `Android 17 · 紧凑窗口`；Android 设备截图确认展开状态。

## 视觉验收发现并修复的问题

### Android 共享资源未进入 APK

首次 APK 可以编译和安装，但打开后抛出 `MissingResourceException`，视觉上立即退回已登录的 v1.7.0 原应用。根因是新的 Android-KMP library target 未启用 Android resource 打包。`shared` 增加：

```kotlin
androidResources {
    enable = true
}
```

重建时出现 `copyAndroidMainComposeResourcesToAndroidAssets`，随后 APK 实际启动成功。

### iOS Compose plist 严格校验

首次 Xcode build 成功，但 Simulator 首帧白屏后 SIGABRT。崩溃栈落在 Compose `PlistSanityCheck`；`Info.plist` 补充 `CADisableMinimumFrameDurationOnPhone = true` 后，APP 保持前台并完成交互。

### macOS 打包环境

Android Studio JBR 21 不含 `jpackage`；使用本机已有 Temurin JDK 25 完成打包，并显式把 Java/Kotlin bytecode 对齐到 JVM 21。macOS 的 `jpackage` 不接受首段为 0 的版本号，因此分发包版本使用 `1.0.0`，应用内部 M1 状态仍为 `0.1.0`。

## 冻结边界

M1 前后受保护路径聚合 SHA-256 均为：

```text
a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f
```

详见 `m1-frozen-boundary.md`。构建产物位于忽略目录；没有登录 Apple 开发者账号、创建证书、提交、标签、推送或发布。

## 下一步

直接进入 M2：先在 `commonMain/commonTest` 建立纯 Kotlin 领域模型与成绩计算、筛选、排序、日期逻辑的对照基线，再考虑 Ktor、数据库或登录实现。
