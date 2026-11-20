# macOS 27 SDK 原生启动器

Compose 桌面窗口已有系统红绿灯。旧 JDK 的 jpackage 启动器使用旧 SDK 链接，可能使新版 macOS 继续提供旧版窗口外观。此入口重新编译 OpenJDK 原始启动器，保留 Java/Compose 页面和启动逻辑；本地 macOS 27 上的新红绿灯已经用户确认。

## 本地重建与打包

需要 Apple Silicon Mac、带 macOS 27 或更新 SDK 的 Xcode、Python 3（仅标准库）和完整 JDK 25。在 `multiplatform/` 下运行：

```sh
./gradlew :desktopApp:packageDmg
```

打包会用当前 Xcode 的 SDK 自动重编译启动器。可用 `DEVELOPER_DIR` 选择 Xcode，并用 `DESKTOP_PACKAGE_JAVA_HOME` 指定用于打包的完整 JDK 25。若已经用 `scripts/build-macos-sdk-launcher.py` 生成启动器，可通过 `-PmacosSdkLauncher=<绝对路径>` 复用。SDK 低于 27 会直接报错；默认优先使用标准路径下的 Temurin 25，Gradle 本身仍可使用 Android Studio JBR。

`macos-launcher-sources.json` 将 OpenJDK `jdk-25+36` 固定到提交 `6c48f4ed707bf0b15f9b6098de30db8aae6fa40f`，逐个验证源文件和许可证文件的 SHA-256。首次重建从 OpenJDK 官方 GitHub 下载；再次运行使用通过校验的本地缓存，校验失败直接停止。源文件与许可证保留在输出目录的 `openjdk-src/`，`build-info.json` 记录 SDK、最低系统版本、上游提交和启动器校验和；对外分发时保留对应的源码及上游许可证信息。

本地自动重编的输出在 `desktopApp/build/generated/macos-sdk-launcher/`。主启动器在重新签名前替换。`kmp-package.yml` 在 `xcode-27` 镜像上用 JDK 25 预编译启动器，通过 `-PmacosSdkLauncher` 传给 Gradle，再用 `xcrun vtool -show-build` 校验主程序 SDK 和 `minos 12.0`。安全检查工作流在 Xcode 26 上执行 `:desktopApp:createDistributable -PmacosAppImageSmokeTest`，生成用于检查包内容的 App 镜像。该模式禁止生成 DMG。

## 兼容与验收

- SDK 是编译时的 API/系统行为版本，最低运行系统仍为 macOS 12.0；没有引入只在 27 上可用的新 API，Java 运行时不变。
- macOS 26 是保留的运行目标，但目前未做 26/12 实机验收。各系统使用自身提供的按钮外观，27 特有效果不会移植到 26。
- 不改变版本号、bundle ID、关闭窗口隐藏、Dock 重开既有窗口等行为。启动器仍采用原始 jpackage 配置和参数传递机制；不自绘按钮，不调用私有 AppKit API，也不修改 Mach-O 的 SDK 版本来代替重编译。
- 打包后只读挂载 DMG，用 `xcrun vtool -show-build` 检查主程序的 SDK 和 `minos 12.0`，核对原 bundle ID、正常 `MainKt` 入口、版本/Build 与无测试默认参数，再运行 `codesign --verify --deep --strict`。
- 已验证 SDK 27 包启动、原生绿灯进入全屏、原生菜单关闭、用户目视新版红绿灯；最小化后恢复、完整会话的关闭/Dock 重开和旧系统运行仍需实机验收。本地 ad-hoc 签名不代表 Developer ID 签名或公证。
