# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-09。
> 当前阶段：主线调试包 `1.8.3-beta-ci`（Build 26）。
> 当前分支：`main`，跟踪 `mine/main`。
> 应用版本：`1.8.3-beta-ci` / Build 26。
> 调试标签：`debug-1.8.3-beta-ci`（只走 Actions 打包，不发 GitHub Release）。
> 正式 Latest 仍是 `v1.8.2-KMP`。未推 `origin`，未做正式发布。

## 已完成

- CITEL / Duo / MacPWDFix 已在 `main`。
- 版本改为 `1.8.3-beta-ci`，构建号 26。
- CI `desktopTest` 失败已修：编程作业日程事件按 AC 判定完成，不再把 `submitted=true` 当成完成。

## 当前注意事项

- `debug-*` / alpha / beta 标签只上传 Actions 产物，不发 GitHub Release。
- 比较器里 `1.8.3-beta-ci` 高于 `1.8.3-alpha-ci` 和 `1.8.2-KMP`，不能给这个标签发 GitHub Release。
- iOS IPA 未签名，需自签；macOS DMG 为 CI / 本地 ad-hoc。
- iOS 打包 CI 需 Xcode 27.1。

## 接下来

1. 等 `debug-1.8.3-beta-ci` 的 KMP package 和 security checks 跑完。
2. 需要正式发布时再定 `1.8.3-KMP` 版本号、说明和四端包。
