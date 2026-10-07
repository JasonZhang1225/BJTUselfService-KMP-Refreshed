# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-08。
> 当前阶段：Mac 升级钥匙串多次弹窗，桌面端秘密已合并成一条。
> 当前分支：MacPWDFix，从 main `100d795` 拉出；已提交并推送到 `mine`。未合并 main，未发布。
> 应用版本仍为 1.8.2-KMP / Build 23。

## 已完成

- 查清次数来自独立钥匙串条目：缓存密钥、登录凭据、物理实验账号；ad-hoc 重签后每条 ACL 各弹一次。
- Mac JVM 生产路径改为单一条目 `team.bjtuss.bjtuselfservice.kmp.secrets`，袋内键为 `credentials`、`cache-key`、`physicslab:<学号>`。
- 旧三条首次成功读取后迁入新条目并删除；登出只删登录键，全量清理仍按原顺序，缓存密钥保留。
- 测试用 `MacOsKeychainCredentialVault` 仍走独立条目，不碰用户生产袋子。
- 未放宽 ACL；升级后完全不弹留给以后的 Developer ID。
- `:shared:desktopTest --tests team.bjtuss.bjtuselfservice.shared.security.*` 通过（含 MacOsSecretBag 6 项真实钥匙串）；`:desktopApp:compileKotlin` 通过。未做覆盖安装验收。

## 当前注意事项

- 装上本修复后的第一次覆盖，读取尚未迁移的旧条目仍可能弹 1–3 次；再升级应只弹 1 次。
- 另一条线再加账号密码时，应写入同一袋子的新键，不要再 `MacOsKeychainItem` 新开服务名。
- iOS / Android / Windows 存储未改。
- 回退到不含袋子的旧包：若已迁移，旧版找不到分条，需重新保存密码，缓存会按现有加密迁移逻辑重建。
- NewMigration 的侧栏图标、iOS 闪屏和物理实验顶栏玻璃仍待用户本机确认；本分支未做那些验收。
- Mac DMG 仍为本地 ad-hoc 签名。

## 接下来

1. 用户用两个 ad-hoc 包覆盖安装（已保存登录和实验账号）：第一次允许搬旧条，第二次应只输一次钥匙串密码。
2. 另一条线的新账号密码接到 `MacOsSecretKeys` 新键。
3. 需要合并进 main 或发布时再确认范围。
