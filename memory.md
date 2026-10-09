# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-08。
> 当前阶段：CITEL 线 UI 已提交并推到 `mine/codex/CITEL`。
> 当前分支：`codex/CITEL`，跟踪 `mine/codex/CITEL`。
> 应用版本仍为 1.8.2-KMP / Build 23。
> `MacPWDFix` 已并入 CITEL，本地与 `mine` 上的该分支已删除。

## 已完成

- 作业聚合独立成卡并加描述，与三个功能开关分开。
- 首页增加正在上课/进行中卡：地点、老师、完整时段分行。
- 数据变动：只显示真变化字段；iOS 半屏原生 sheet，标记已读在左上角；点卡片进详情，不跳一级页。
- 摘要「已筛选」加粗。
- 本轮已推到 `mine/codex/CITEL`。未并 `main`。
- 本地安装包已放到 Downloads（未覆盖旧包）：`BJTUSelfService-KMP-1.8.2-CITEL-20261008-iOS-unsigned.ipa`、`BJTUSelfService-KMP-1.8.2-CITEL-20261008-macOS-arm64.dmg`。IPA 未签名需自签；DMG 为 ad-hoc。

## 当前注意事项

- 正在上课卡用系统时钟每 30 秒刷新；今天课上完则不占位置。
- 变动记录 codec 升到 v2；旧 v1 缓存仍能读。点「标记已读」后同类差异不会再弹。
- iOS 模拟器需重新编译安装后才能看到本轮界面。

## 接下来

1. 用本轮 CITEL 安装包在 iOS（自签）和 Mac 上看聚合开关、正在上课卡、变动弹窗。
2. 需要并进 main 或发布时再确认范围。

## 2026-10-09 同步策略澄清

- CITEL 进入 App 与其他模块并行同步一次；模型按会话 generation 0 去重，不因窗口聚焦或再点作业页重复同步。
- CITEL／课程平台仅在提交或读取确认登录失效后恢复；网络超时不作为重新登录依据。
- Ktor 3.5.1 Cookie 存储已有内部锁。请求改为按主机并发 2 条、CAS 1 条；CITEL 只读页 2 路，失效恢复合并，写入保留既有锁。
- 本地 Debug 可用 `--sync-timing` 记录脱敏耗时；最终记录与对比在 `.artifacts/desktop-debug-final-parallel-timing.log`、`.artifacts/parallel-sync-comparison.md`。
