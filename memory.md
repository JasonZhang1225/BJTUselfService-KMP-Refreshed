# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-09。
> 当前阶段：Duo 线调试包 `1.8.3-alpha-ci`（Build 25）。
> 当前分支：`codex/Duo`，跟踪 `mine/codex/Duo`。
> 应用版本：`1.8.3-alpha-ci` / Build 25。
> 调试标签：`debug-1.8.3-alpha-ci.2`（只走 Actions 打包，不发 GitHub Release / pre-release）。上一枚 `debug-1.8.3-alpha-ci` 仍指向 Xcode 27.1 修复那次成功包。
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

1. 等 `debug-1.8.3-alpha-ci.2` 的 KMP package 跑完，从 Actions 取四端产物。
2. 需要并进 main 或正式发布时再确认范围。

## 2026-10-09 真机触摸与前台刷新修正

- iOS 原生 UIScrollView 不再与所有 Compose 手势同时识别。纵向拖动接管后取消卡片触摸；横向翻周仍由纵向 pan 拒绝识别，边缘返回保留。显式启用内容触摸延迟与取消；惯性期间第一次落指由滚动器接收以停止滚动，避免传给卡片。沿用 UIKit 的手势阈值和惯性。
- iOS / Mac 短暂回到前台不再触发页面恢复／物理实验刷新；记录后台实际时间起点（包含锁屏／电脑休眠），停留至少 10 分钟后全局并行刷新一次，共享会话去重多个宿主与重复通知。CITEL 初次进入 App 的同步保留。
- 普通 iOS 课表的“添加到日历”与“导出”使用同一左侧摆放规则；Duo 侧栏仍沿用原生侧栏布局。

## 2026-10-09 最终刷新策略（替代上面的 10 分钟版本）

- iOS、Mac、Android：后台停留至少 15 分钟，回到前台时才并行全局刷新一次；保持后台不刷新。包含锁屏／休眠时间，重复前台事件去重。Android 按应用内已启动 Activity 数量判定，不把页面间切换／配置重建当成长期后台。
- Windows：不以窗口聚焦触发刷新；记录最近应用内容点击时间，闲置至少 15 分钟后，下次内容点击触发一次重登，再并行全局刷新。鼠标移动、滚轮、保持闲置、直接关闭窗口不触发；连续点击不重复重登。
- 依用户要求不再本地编译或运行验证。15 分钟策略、Windows 点击计时及显式先重登的测试已补到源码，交由 CI Debug 执行；不能把之前 10 分钟测试通过视为最终版本已通过。
