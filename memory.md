# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-09-21（安全复查轮，Liquid 里程碑归档）
> 当前分支：`Liquid`，HEAD `f81da61`（发布标签 `v1.7.8-Liquid` 指向 `a109448`，已推送 `mine/Liquid`）。
> 工作区未提交：`history_full.md`、`memory.md`（本文件整理）、`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`（本轮新增）。
> `history_full.md` 是只读历史归档；本文件只记录当前事实、未决事项和下一步，不重复历史细节。

## 当前目标

- 安全复查遗留项修复：按报告第六节优先级推进（用户明确「后续来修」，尚未开始）。
- Liquid 视觉最终验收：各类 sheet 审美、边缘返回、减少透明度、iOS 26 以下回退壳、iPad 宽屏、首页卡片交互，待用户主观确认。
- iOS 真机签名安装：unsigned IPA 需开发者证书重签名后才能上真机。

## 本阶段已做到

- 2026-09-21 完成只读安全复查（1.7.8-Liquid）：上轮安全修复全部保持、无回归、无高危；新增中危 M1（WebView Cookie 登出不清理）、M2（iOS Keychain 卸载残留）、M3（macOS 无卸载清理）、M4（桌面/Windows 明文缓存）+ 低危 L1-L10，报告在 `docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`。
- Liquid 里程碑与 1.7.8-Liquid 四端发布已归档至 `history_full.md`「M17 Liquid」节；四端产物在 `/Users/zjg/Downloads`。
- GitHub Release `v1.7.8-Liquid` 已正式发布，自动流水线 run `35579588977` 全部成功，发布页为 `https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/releases/tag/v1.7.8-Liquid`；发布正文已补齐，包含四个 CI 产物。

## 当前痛点（≤8 条）

- 安全复查 4 个中危均未修，M1（登出清 WebView Cookie）改动小收益大、最优先。
- iOS 模拟器因签名身份更换替换了 App 容器，无可读取 Keychain 登录态，自动登录视觉复现待用户重新登录。
- iOS 原生标题栏模糊（2026-09-21 未提交，试点作业页）：`NativeChromeBinding.apply` 已改为顶部透明、滚动后切系统默认材质；新增 `session.glassTopBarInsetDp`（Swift 推栏高）+ `LocalTopBarClearance` + `DestinationPage(scrollUnderTopBar)`，作业页列表吃内部顶边距、外层不再 Spacer 占位，失败横幅收进列表首项（仿 legacy 提示先例）。desktopTest 525 项全绿，xcodebuild 通过，同 adhoc 签名重装后登录态保留。截图已证内容伸进栏后（红色提示顶到屏幕顶、右侧圆钮被染红）；但滚起来栏依然清晰。排查：底栏玻璃能折射 Compose 内容（采样兼容），中文标题正常上报（回调链通），遂锁定两处——①`nestedScroll` 复位条件过宽（已收紧为真·顶部 overscroll 才清零）、②`navigationBar.backgroundColor=.clear` 直写可能压住 appearance 材质（已删，栏背景完全交 appearance）。当前装机为诊断版：强制 scrolled=true + 上述两修。已验明：模拟器内二进制 md5 与构建产物一致（9c60e36d），运行进程 pid 29890 即最新包；我亲自读屏，顶部与用户所见一致。顶部栏后是纯色留白，透明/材质肉眼无差是预期的，缺的是"蓝卡进栏一半"那一屏（只能用户滚）。若新包滚起来仍清晰→默认材质在该栏上不渲染，查 Swift 接线/半透明。lldb 实锤：①栏半透明YES、内容全高画到栏后（underlap 好着）；②`configureWithDefaultBackground()` 在 iOS27 对手搓 appearance 无效（fresh 对象 effect/color/shadow 全 nil，tab 栏系统自带的是 SystemChromeMaterial）；③直接 setBackgroundEffect 生效，已把 tab 栏同款 effect 活体注入当前栏（debug 打印可见，visibility=hidden 待解）。现只需用户在作业页滚到蓝卡进栏一半截一张，糊即结案（实现=显式 effect+撤强制+撤日志），仍锐利则继续查栏背景被隐藏的原因。用户 19:47 截图确认糊已出来（活体注入 chrome 材质），但提出重字+太糊像毛玻璃。新包：滚动态材质换 systemThinMaterial、透明色标题属性真正生效（系统标题隐身，只剩手写居中标题）、强制开关与 NSLog 已撤（滚动态模糊重新依赖真实累积值+收紧后的复位条件）。desktopTest 525 全绿，已装机，顶部单标题已自验；用户已确认重字解决、要真玻璃、再让全页铺开。已全量落地（未提交）：`DestinationPage(scrollUnderTopBar)` 14 处开 flag（邮箱列表自绘栏与写信页除外；课表/课件/教室详情/占用详情因固定头挡视口保持 Spacer），新增 `keepsTopBarInset`（暂无调用，留给课表 redesign）；首页/成绩/考试/作业/更多/教室列表/占用列表/作业详情/邮件详情/物理在线及详情/设置/日历/成绩单按作业模板接 clearance。用户报 4 排除页滚起来栏凭空过渡（信号通但栏后无物）：新增逐页开关 `staticTopBar`（只关过渡不碰布局，课表/课件/教室详情/占用详情四处启用，全局与其他页不受影响）。desktopTest 525 全绿，xcodebuild 通过，同签名装机；成绩/首页/更多顶部已截图无回归。待用户逐页滚动态验收。教训：lldb 只能读不能写标量（CGRect/alpha 写变垃圾值；读 hierarchy/appearance 可靠）。
- 桌面端 `AppLog` 分发构建默认开启（低危 L3），与 Android release 不对齐。
- Compose Material3 alpha 依赖 compileSdk 37 豁免开关（`android.experimental.disableCompileSdkChecks`），正式版后应移除。

## 接下来 1～3 个阶段

1. 修 M1：登出/会话失效时清 Android `CookieManager` 与 iOS `WKWebsiteDataStore`，补回归测试。
2. 修 M3/M4：macOS 卸载残留（脚本 + 全量清除补删文件/prefs/Keychain 条目）与桌面/Windows 明文缓存处理。
3. Liquid 视觉验收收口 + iOS 真机重签名安装验证。

## 相关文件

- 安全复查报告：`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`（本轮）、`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-17.md`（上轮）。
- Liquid 计划与发布：`docs/migration/m17-apple-liquid-glass-shell-plan.md`、`docs/releases/v1.7.8-Liquid.md`。

## 维护规则

- 每轮开始先确认 `git branch --show-current`、工作区状态和本文件；事实以当前源码、命令输出和当前截图为准。
- 已完成历史只进 `history_full.md`，实时文件只保留当前接续需要的结论；过期的“已落地/已实机/当前方案”必须删除或改成历史语气。
- 事实与计划分开写，明确验证范围；不记录账号、密码、Cookie、令牌、验证码或其他敏感会话内容。
