# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-09。
> 当前阶段：Duo 线调试包 `1.8.3-alpha-ci`（Build 25）。
> 当前分支：`codex/Duo`，跟踪 `mine/codex/Duo`。
> 应用版本：`1.8.3-alpha-ci` / Build 25。
> 调试标签：`debug-1.8.3-alpha-ci`（只走 Actions 打包，不发 GitHub Release / pre-release）。
> 正式 Latest 仍是 `v1.8.2-KMP`。

## 已完成

- Duo / 横屏适配、并行同步、回顶遮挡、CITEL 编程 AC 判定已在 `codex/Duo`。
- 版本改为 `1.8.3-alpha-ci`，构建号 25；`debug-*` 标签可触发 `kmp-package.yml`，但跳过 GitHub Release，应用内不会当全量更新。
- 未并 `main`，未推 `origin`。

## 当前注意事项

- 本包是 CI 调试产物，从 Actions artifacts 下载，不是 Release 附件。
- iOS IPA 仍未签名，需自签；macOS DMG 为 CI 打包。
- 比较器里 `1.8.3-alpha-ci` 数字段高于 `1.8.2-KMP`，所以不能给这个标签发 GitHub Release。
- 第一次 `debug-1.8.3-alpha-ci` 的 iOS job 在 macos-26 / Xcode 26.6 上失败：`ContentView.swift` 的 iOS 27.1 API（`axisBehavior`、`verticalBarEdge`、`navigationBarMinimization`、`sidebar.preferredPlacement`）编译不过。iOS 任务已改到 `xcode-27` 并强制 iPhoneOS ≥ 27.1。

## 接下来

1. 等 `debug-1.8.3-alpha-ci` 的 KMP package 跑完，从 Actions 取四端产物。
2. 需要并进 main 或正式发布时再确认范围。
