# NewMigration 后续改动与安装包

日期：2026-10-05。分支 `NewMigration`。源码已做本地初步提交，未推送、未发布。版本号保持 1.8.1 / Build 22。

## 行为

- iOS 26 及以上 iPhone 的正常导航由 `LiquidGlassShellController`、UIKit `UITabBar` 承载；本轮合成界面只用于无账号验收。
- 设置的底栏项仍可勾选。已选项置顶，右侧手柄支持拖动排序；最多四项自选，首页与应用固定。iOS 27 模拟器把“成绩”拖到“课程表”之前后，列表和底栏立即同步换序。模型测试证明新顺序会保存并重载。
- 设置新增 AIO 社区版仓库入口；README 在 optsimauth 的贡献项说明 AIO 项目与本轮参考来源。
- 课件“选择课程”弹窗移除 72% 的高度限制，使用弹窗可用视口；文件详情采用相同高度/滚动约束。iOS 27 模拟器用 20 门合成课程滚动至最后一门“验收标记”，标题与末尾完整可见。

## 构建和校验

- `./gradlew :androidApp:assembleRelease :desktopApp:packageDmg :shared:desktopTest --max-workers=2 --console=plain`：BUILD SUCCESSFUL；JUnit 611 tests、0 failures/errors/skipped。
- `xcodebuild` Debug iPhone 17 Pro / iOS 27 Simulator：BUILD SUCCEEDED，已实际运行合成测试入口。
- `xcodebuild` Release generic iOS arm64，`CODE_SIGNING_ALLOWED=NO`：BUILD SUCCEEDED。
- DMG `hdiutil verify` 校验和有效；只读挂载包含“交大自由行 KMP.app”，包内签名严格验证通过，Bundle ID 为 `team.bjtuss.bjtuselfservice.kmp.macos`。
- APK `apksigner verify` 的 v2 签名通过，上传证书指纹与既有 KMP 一致；arm64-v8a、包名 `team.bjtuss.bjtuselfservice.kmp`、Build 22。
- IPA ZIP 完整，应用主程序为 arm64，未包含 `_CodeSignature` 或 `embedded.mobileprovision`；Bundle ID 为 `team.bjtuss.bjtuselfservice.kmp.ios`，Build 22。

## 下载文件

| 平台 | 文件 | 字节 | SHA-256 |
| --- | --- | ---: | --- |
| macOS DMG | `/Users/zjg/Downloads/BJTUselfServiceKMP-1.8.1-NewMigration-20261005.dmg` | 121104305 | `b0262e93c9e04e85f56daaa8f3b1e8d6d47e0356274256e95cc1cf7ff4f356d3` |
| Android APK | `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-NewMigration-20261005-arm64-release.apk` | 88930553 | `c5e846f6ce807e6f84f7b0d66e2011eaa70d5265ed85bbffa95882d472b1bcd3` |
| iOS unsigned IPA | `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-NewMigration-20261005-iOS-unsigned.ipa` | 42072047 | `000d2d6fde32a83f581a27533dbf904ecd308663853c8fdf84c232458ee7d1d7` |

三个文件与旧安装包并存。Mac 使用本地 ad-hoc 签名，尚未公证；iOS IPA 没有签名，需通过现有侧载流程自签后安装。KMP 包名与 AIO 社区版不同，可并存。本轮未进行 iOS 真机或 Android 实机安装验收。

构建完整日志位于本机忽略目录 `.artifacts/NewMigration/evidence/`，安装包复制记录和校验值位于 `.artifacts/NewMigration/packaged/delivery.json`。
