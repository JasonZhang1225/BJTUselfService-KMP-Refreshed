# M1 KMP + Compose Multiplatform 骨架实施计划

> 状态：M1 已于 2026-07-30 完成并通过三端真实运行验收。  
> 目标：在 `multiplatform/` 中建立独立工程，同时运行 Android、iOS Simulator 和 macOS；不修改冻结的根 Android 工程。

冻结边界的当前文件与修改状态已记录在 `docs/migration/m1-frozen-boundary.md`。M1 开始前、三端构建后和交付前必须复核聚合 SHA-256。

## 0. 本机无写入预检（2026-07-30）

已确认可直接使用：

- Android Studio JBR 21.0.10 arm64。
- Android SDK platform 36 和 Build Tools 36.0.0。
- 稳定 `/Applications/Xcode.app` 26.6，`xcode-select` 指向该路径。
- iOS 26.2 Runtime 原已注册；M1 另安装与 Xcode 26.6 SDK匹配的 iOS 26.5 Runtime，并使用 iPhone 17 Pro 验收。
- Apple Silicon arm64；数据卷剩余约 846 GiB，不构成构建空间阻塞。

本机当前**没有缓存**以下 M1 依赖：

- Kotlin Gradle plugin 2.4.10。
- Compose Multiplatform Gradle plugin 1.11.1。
- Android Gradle Plugin 9.1.1。
- Gradle 9.3.1 wrapper distribution。

仓库 `local-maven/` 只有现有 Android Markdown/Markwon 相关资产，与 M1 插件无关。因此：

- 第一次 Gradle 解析必然需要联网下载并写入用户 Gradle cache；执行时按权限流程单独请求网络/缓存写入批准。
- 不把首次构建描述成可离线完成，也不为了离线而降低到根 Android 工程的旧版本组合。
- 新工程创建后先运行 `./gradlew projects` 和各模块 `tasks --all`，再以真实生成的 task 名替换本文的“预期命令”。

## 1. 版本选择

截至 2026-07-29，计划使用全部稳定版本：

| 组件 | 版本 | 依据 |
| --- | --- | --- |
| Kotlin / KMP / Compose Compiler plugin | 2.4.10 | Kotlin 当前 Stable；KMP 官方兼容表 |
| Compose Multiplatform | 1.11.1 | 当前最新稳定版；支持 Android 21、iOS 14、macOS 13 arm64 |
| Android Gradle Plugin | 9.1.1 | 采用 9.1 当前稳定补丁版 |
| Gradle wrapper | 9.3.1 | AGP 9.1 系列要求；同时低于 Kotlin 2.4.10 支持上限 9.5.0 |
| JDK | Android Studio JBR 21.0.10 | AGP 最低 JDK 17；Compose Desktop 打包要求 JDK 17+ |
| Android compileSdk | 36 | 本机已安装；AGP 9.1 兼容范围内 |
| Android minSdk | 28 | 与原 App 保持一致 |
| Android targetSdk | 34（M1） | M1 先避免行为漂移；发布前单独升级与回归 |
| iOS deployment target | 15.0（暂定） | 高于 Compose 最低 iOS 14，覆盖当前目标设备 |
| macOS deployment target | 13.0 arm64 | Compose 1.11.1 官方最低支持 |

官方兼容依据：

- [Compose Multiplatform compatibility and versions](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html)
- [Kotlin Multiplatform compatibility guide](https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-compatibility-guide.html)
- [Google Android-KMP library plugin](https://developer.android.com/kotlin/multiplatform/plugin)
- [AGP 9.1 release notes](https://developer.android.com/build/releases/agp-9-1-0-release-notes)

不采用当前 metadata 中的 Compose 1.12 beta、Kotlin 2.4.20 Beta 或 AGP 9.4 alpha。

### Xcode 兼容性门槛

- Kotlin 2.4.10 官方表列出的 Xcode 版本为 26.4。
- 本机命令行稳定版为 `/Applications/Xcode.app` 26.6，超出文档已列范围。
- 本机另有 `/Applications/Xcode-beta.app` 27.0 beta 3，不用于 M1。
- M1 先用稳定 Xcode 26.6 做最小 framework + simulator build 实测。
- 如果失败原因明确是 Xcode 版本不受支持，不通过隐藏警告或切换 Kotlin Beta 规避；先报告证据，再由用户决定是否安装/选择 Xcode 26.4。

## 2. 模块结构

采用独立应用壳 + KMP 共享库：

```text
multiplatform/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── shared/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/
│       ├── commonTest/
│       ├── androidMain/
│       ├── iosMain/
│       └── desktopMain/
├── androidApp/
│   ├── build.gradle.kts
│   └── src/main/
├── desktopApp/
│   ├── build.gradle.kts
│   └── src/main/kotlin/
└── iosApp/
    ├── iosApp.xcodeproj/
    └── iosApp/
```

理由：

- `shared` 使用 `org.jetbrains.kotlin.multiplatform`、`com.android.kotlin.multiplatform.library`、`org.jetbrains.compose` 和与 Kotlin 同版本的 Compose Compiler plugin。
- Google 的新 Android-KMP plugin 只用于 library；Android application 必须放在独立 `androidApp`，使用 `com.android.application`。
- `desktopApp` 是独立 JVM/Compose Desktop application，依赖 `shared` 的 desktop/JVM 产物，避免在 KMP library 上叠加 Java Application plugin。
- `iosApp` 是最薄的 Xcode 宿主，嵌入 `shared` 静态 framework，并显示共享 Compose `UIViewController`。
- 该结构与 JetBrains 已归档的官方 Compose template 的模块边界一致，但构建插件升级为 2026 年当前官方 Android-KMP方案；不直接复制旧模板的 `com.android.library + androidTarget()`。

## 3. M1 文件清单

### 根构建文件

- `multiplatform/settings.gradle.kts`
  - 只 include `:shared`、`:androidApp`、`:desktopApp`。
  - 仓库使用 `google()`、`mavenCentral()`、`gradlePluginPortal()`。
  - 不 include 到仓库根 `settings.gradle.kts`。
- `multiplatform/build.gradle.kts`
  - 集中声明 plugins，默认 `apply false`。
- `multiplatform/gradle/libs.versions.toml`
  - 锁定上述稳定版本和最小 M1 依赖。
- `multiplatform/gradle.properties`
  - JVM、AndroidX、Kotlin style 和必要 KMP参数；不复制根工程的临时镜像设置。
- `multiplatform/gradle/wrapper/*`、`gradlew*`
  - 独立 Gradle 9.3.1 wrapper。
- `multiplatform/.gitignore`
  - 忽略独立 build、IDE、Xcode DerivedData 等产物。

### shared

- `shared/build.gradle.kts`
  - Targets：Android KMP library、`jvm("desktop")`、`iosArm64()`、`iosSimulatorArm64()`。
  - M1 不增加 Intel iOS simulator 或 macOS x64。
  - iOS framework 暂名 `BJTUShared`，静态链接。
- `shared/src/commonMain/kotlin/.../App.kt`
  - 共享 Material 3 页面：项目名、平台名、窗口类别和三个目标状态卡。
- `shared/src/commonMain/kotlin/.../Platform.kt`
  - `expect` 平台名称；只演示真正的平台差异。
- `shared/src/commonTest/kotlin/.../AppSmokeTest.kt`
  - 验证平台无关的展示模型和窗口分类。
- `shared/src/androidMain/.../Platform.android.kt`
- `shared/src/iosMain/.../Platform.ios.kt`
- `shared/src/iosMain/.../MainViewController.kt`
- `shared/src/desktopMain/.../Platform.desktop.kt`
- `shared/src/androidMain/AndroidManifest.xml`

### androidApp

- `androidApp/build.gradle.kts`
  - `com.android.application`，依赖 `project(":shared")`。
  - applicationId 暂用 `team.bjtuss.bjtuselfservice.kmp`，避免覆盖官方 1.7.0。
- `androidApp/src/main/AndroidManifest.xml`
- `androidApp/src/main/kotlin/.../MainActivity.kt`
- `androidApp/src/main/res/values/strings.xml`

显示名暂用“交大自由行 KMP”，在功能对齐和签名方案确认前不占用正式包身份。

### desktopApp

- `desktopApp/build.gradle.kts`
  - Kotlin JVM + Compose Desktop，依赖 `shared`。
  - 配置 macOS arm64 DMG，package name 暂为 `BJTUselfServiceKMP`。
- `desktopApp/src/main/kotlin/.../main.kt`
  - 建立主窗口，调用共享 `App()`。
  - M1 记录关闭窗口和退出进程的实际行为，不擅自加入后台驻留。

### iosApp

- `iosApp/iosApp.xcodeproj/project.pbxproj`
- `iosApp/iosApp/Info.plist`
- `iosApp/iosApp/iOSApp.swift`
- `iosApp/iosApp/ContentView.swift`
- `iosApp/Configuration/Config.xcconfig`

Bundle ID 暂用 `team.bjtuss.bjtuselfservice.kmp.ios`。M1 使用 `CODE_SIGNING_ALLOWED=NO` 构建模拟器，不登录开发者账号、不创建证书。

## 4. 初始 UI 与 Apple Design 约束

M1 只建立可验证骨架，不提前复制全部 Android 页面：

- 三端显示同一个共享内容结构，证明 Compose UI 真实共享。
- 页面清楚显示当前平台和 source set，不把 Android build 误当 iOS/macOS 成功。
- Android/iPhone 使用紧凑单列；macOS 窗口宽度足够时卡片横向排列。
- 使用系统字体、语义色、浅/深色适配和基本语义标签。
- 按下立即反馈；M1 不加入装饰性弹跳、玻璃态或长动画。
- 支持减少动态效果的结构预留；实际平台读取在后续 UI 基础切片完成。

## 5. 实施顺序

1. 创建独立 wrapper、settings、version catalog 和空模块。
2. 联网解析插件和依赖；运行 `projects`、`:shared:tasks --all`、`:androidApp:tasks --all`、`:desktopApp:tasks --all`，保存真实任务名。
3. 先编译 `shared` metadata/commonTest，排除版本和插件问题。
4. 编译并运行 Android KMP 壳，确保官方 1.7.0 可同时保留。
5. 编译并运行 desktopApp，检查窄/宽窗口和关闭行为。
6. 生成 `BJTUShared` iOS Simulator framework。
7. 创建 Xcode host，以 `CODE_SIGNING_ALLOWED=NO` 构建并运行 iPhone 17 Pro Simulator。
8. 用 Computer Use 分别观察三端共享页面并记录截图。
9. 重新运行根 Android 基线构建，证明 `multiplatform/` 没有破坏旧工程。
10. 复核 `docs/migration/m1-frozen-boundary.md` 的聚合摘要与 6 条既有修改状态完全不变。
11. 更新 `goal.md` 和 `memory.md`，再进入 M2 共享领域层。

## 6. 验证命令

具体 task 名必须在 Gradle 首次列出后复核。先执行：

```bash
cd multiplatform
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew projects
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :shared:tasks --all \
  :androidApp:tasks --all \
  :desktopApp:tasks --all
```

随后预期至少包括以下命令；如果插件实际生成的 task 名不同，以 `tasks --all` 证据修正文档，不猜测：

```bash
cd multiplatform
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :shared:allTests
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :androidApp:assembleDebug
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :desktopApp:packageDmg
xcodebuild -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -sdk iphonesimulator \
  -configuration Debug \
  CODE_SIGNING_ALLOWED=NO build
```

运行验证：

- Android：Pixel 10 Pro XL 模拟器。
- iOS：iPhone 17 Pro，iOS 26.5 Simulator。
- macOS：Apple Silicon 本机，至少检查窄/宽两种窗口。
- 视觉：浅色/深色、内容无截断、平台名正确、交互按下有反馈。

## 7. M1 验收标准

- 根 Android 工程没有因 M1 被修改。
- `multiplatform/` 能独立解析依赖和执行 Gradle任务。
- 首次下载的插件、wrapper 和库来自计划声明的官方仓库；没有把临时镜像或 `local-maven/` 私有资产混入新工程。
- 同一份共享 Compose `App()` 在 Android、iOS Simulator、macOS 实际显示。
- `commonTest` 通过，Android debug APK生成，macOS `.app`/DMG生成，iOS simulator build 成功。
- 三端运行证据经过 Computer Use 视觉确认，不只依据编译日志。
- Android 官方 1.7.0 与 KMP 调试包可在模拟器共存。
- Xcode 26.6 的支持边界有实测结论；失败不能被描述为 iOS 已完成。
- 没有账号、密码、Cookie、验证码、签名材料或 DerivedData 进入 Git。

## 8. 回退方式

M1 的所有新代码仅位于 `multiplatform/`。若骨架版本组合失败：

- 保留失败命令和错误证据。
- 只调整 `multiplatform/` 内的版本和配置。
- 不修改根 Android 工程来迁就 KMP。
- 不删除或重置用户已有改动；需要移除整个实验目录时先获得用户确认。

## 9. 实施结论

完整结果、命令、运行期问题与 Computer Use 截图见 `docs/migration/m1-result.md`。验收标准全部满足；唯一未作为通过证据的项目是 `iosSimulatorArm64Test` 执行器曾长时间无输出，已中止，iOS 目标改由 framework 链接、Xcode build 和真实 Simulator 运行证明。共享纯 Kotlin 测试由 `desktopTest` 执行并通过。
