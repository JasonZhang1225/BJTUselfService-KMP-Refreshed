# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-08。
> 当前阶段：本机 `MacPWDFix` 已并入 `codex/CITEL`；Mac 桌面端秘密仍是一条钥匙串。
> 当前分支：`codex/CITEL`，跟踪 `mine/codex/CITEL`。MacPWDFix 合并尚未推送。
> 应用版本仍为 1.8.2-KMP / Build 23。

## 已完成

- 远端拉到 `mine/codex/CITEL`（`0f07b75`），本地检出 `codex/CITEL`。
- 将本机 `MacPWDFix`（`d3f0301`）合并进 CITEL；冲突只在 `DesktopAccountSecurityStore.kt`。
- 登录、缓存密钥、物理实验、CITEL 账号都进同一袋子 `team.bjtuss.bjtuselfservice.kmp.secrets`，键为 `credentials`、`cache-key`、`physicslab:<学号>`、`citel:<学号>`。
- 旧独立条目首次成功读取后迁入袋子并删除；登出只删登录键，CITEL/实验账号和缓存密钥保留。
- `:shared:desktopTest --tests team.bjtuss.bjtuselfservice.shared.security.*` 通过。未推送，未做覆盖安装验收。

## 当前注意事项

- 从分条旧包覆盖到袋子包：密码不会丢。第一次允许钥匙串后会搬家；点不允许则这次读不到，旧条通常还在。
- 第一次覆盖可能按尚未迁移的旧条各弹一次（登录、缓存、实验、CITEL 最多 4 次）；再升级应只弹 1 次。
- 迁成功后再装回不含袋子的旧包：旧版找不到分条，需重新保存密码。
- iOS / Android / Windows 存储未改。
- Mac DMG 仍为本地 ad-hoc 签名。未推送到 `mine/codex/CITEL`。

## 接下来

1. 用户确认是否把合并后的 `codex/CITEL` 推到 `mine`。
2. 覆盖安装验收：已保存登录/实验/CITEL 时，第一次允许搬家，第二次应只输一次钥匙串密码。
3. 需要并进 main 或发布时再确认范围。
