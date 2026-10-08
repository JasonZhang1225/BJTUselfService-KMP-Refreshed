# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-08。
> 当前阶段：CITEL 线补登录转圈、作业聚合默认开、引导弹窗、独立账号清除与保存后自动启用。
> 当前分支：`codex/CITEL`，跟踪 `mine/codex/CITEL`，本地领先（含 MacPWDFix 合并与本轮未提交改动）。
> 应用版本仍为 1.8.2-KMP / Build 23。

## 已完成

- MacPWDFix 已并入本地 `codex/CITEL`；CITEL 账号也进同一钥匙串袋子。
- iOS 登录过程右上角在 `busy` 时就转圈（静默登录 `canRefresh=false` 也会画 spinner）。
- 新安装作业聚合默认打开；CITEL、作业聚合各一次引导，未看过 Redesign 的也强制看 3 秒。
- 物理实验 / CITEL 账号页保存按钮下增加红色「清除配置信息」；保存同步成功返回后自动打开对应功能开关。
- 作业筛选变长后 iOS 底部白条：筛选 sheet 改为可滚内容自己吃 Home Indicator，Android 全高 sheet 同样处理。
- 相关 desktop 测试与 iOS simulator Kotlin 编译已通过。未推送。

## 当前注意事项

- 引导只在首页宿主、登录完成之后弹出，一次一个。
- 已保存过 `aggregate_assignments=false` 的账号不会被改回默认开。
- 保存成功后自动打开的是设置里的功能开关；聚合开着时不会再单独露出 CITEL/作业底栏项。
- iOS 模拟器需重新编译安装后才能看到本轮界面。未做覆盖安装钥匙串验收。

## 接下来

1. 模拟器重装后看：登录转圈、三条引导、保存后开关、清除配置、作业筛选底部不再露白条。
2. 用户确认是否把 `codex/CITEL` 推到 `mine`。
3. 需要并进 main 或发布时再确认范围。
