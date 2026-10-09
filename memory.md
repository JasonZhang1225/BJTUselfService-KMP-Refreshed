# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-09。
> 当前阶段：CITEL / Duo / MacPWDFix 已合入 main，并推送 `mine/main`。
> 当前分支：`main`，跟踪 `mine/main`。
> 应用版本：`1.8.3-alpha-ci` / Build 25。
> 调试标签：`debug-1.8.3-alpha-ci.2`（只走 Actions 打包，不发 GitHub Release）。
> 正式 Latest 仍是 `v1.8.2-KMP`。未推 `origin`，未做正式发布。

## 已完成

- `codex/Duo` 已快进合入 `main`（含 CITEL、作业聚合、MacPWDFix、Duo/横屏、闲置刷新、顶栏玻璃）。功能尖端 `6663117`。
- 已删本地/远端 `codex/CITEL`、`codex/Duo`，以及本地已合入的 `NewMigration`。
- 备份分支保留：`backup/readme-before-squash-20261007`、`codex/Duo-before-cleanup-20261009`。

## 当前注意事项

- 主线版本仍是调试号 `1.8.3-alpha-ci`，比较器高于 `1.8.2-KMP`，不能发 GitHub Release。
- iOS IPA 未签名，需自签；macOS DMG 为 CI / 本地 ad-hoc。
- iOS CI 需 Xcode 27.1（Duo 系统栏 API：`axisBehavior` 等）。
- 上游 `origin/main` 仍停在更早提交，未推送。

## 接下来

1. 需要正式发布时再定 `1.8.3-KMP` 版本号、说明和四端包。
2. 备份分支确认无用后再删。
