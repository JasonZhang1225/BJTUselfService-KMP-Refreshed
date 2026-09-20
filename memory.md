# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-09-20（Liquid 分支，本轮同步面板、课件详情 sheet 与顶栏玻璃渐隐收口）
> 当前分支：`Liquid`，HEAD 为本轮“基本完成”标记提交（未推送远端；具体短哈希以 `git log -1` 为准）。
> `history_full.md` 是只读历史归档；本文件只记录当前事实、未决事项和下一步，不重复历史细节。

## 当前目标

- 收口 Liquid 液态玻璃分支：真实 UIKit sheet、登录页原生导航栏动作、物理在线一级 tab、顶栏 scroll-edge 过渡。
- 纠正 Memory 中把旧方案写成现状的问题；不要把未做真人交互验证的内容写成已验收。

## 当前实现事实

- **iOS sheet**：`feature/shell/AppleSheet.kt` 在 iOS 通过 `LocalNativeSheetPresenter` 把 Compose 内容交给 `IosNativeSheetPresenter`；`UIKitSheetBridge.h/.def` 调用真实 `UISheetPresentationController` 的 page sheet、medium/large detent、系统 grabber 和交互式 dismiss。Compose `ModalBottomSheet` 只保留 Android/桌面回退。
- **sheet 材质**：Compose root 使用官方 `ComposeUIViewController(configure = { opaque = false })`，不再用默认白色 Metal 画布盖住原生材质；UIKit 的 `UIGlassEffectStyleRegular` 同时用于 detent/background effect 和 sheet `UINavigationController` 根视图的 `UIVisualEffectView`，没有自绘颜色、渐变、圆角或伪玻璃。独立 Compose root 会显式继承当前 Material color scheme、typography 和 shapes，避免深色模式退回默认浅色。标题、确认/取消/关闭动作走 UIKit `UINavigationBar`/`UIBarButtonItem`；默认无标题 sheet 也保留原生关闭按钮，只有完美校园这个明确要求无标题且仅保留主操作的入口设置为隐藏关闭按钮。
- **同步面板正文**：`HomeSyncDetailsDialog` 使用 full-height 可滚动透明列表和 UIKit 风格分隔线，不再给每个模块套旧的 Material `surfaceVariant` 卡片；全屏同步清单不会被固定高度截断。课表日期选择也改为 `AppleSheetOrAlert`，iOS 不再直接调用 Material `DatePickerDialog`。
- **弹窗统一**：`AppleSheet`/`AppleSheetOrAlert` 的 iOS 标题与动作统一由 UIKit 原生导航栏提供；首页完美校园弹框无标题、仅有左侧蓝色“打开完美校园”，校园网充值弹框只有“校园网充值”标题和原生右上角关闭 X。成绩详情、课程详情、考试详情、课件下载、筛选/选择/日历等 sheet 均补齐原生标题并移除重复的正文标题。公共 API 支持可空标题与 `showDismissButton`；非 iOS 的 Material sheet fallback 也渲染同一套标题和操作语义。
- **弹窗细节**：考试详情 sheet 直接使用 full-height native detent，避免“添加到日历”按钮和最后一行被半屏底部裁掉。完美校园 sheet 保持无标题，但同时提供左侧蓝色“打开完美校园”和右侧原生关闭 X；正文改为全宽、左对齐的动态正文排版，减少窄文本块和无意义留白。
- **一级页标题栏统一**：Liquid iOS 壳的所有一级 tab 根页（首页、课程表、成绩、作业、物理在线、更多）与二级 push 页都使用稳定的 UIKit 紧凑标题；标题字号统一为动态 21pt 半粗体。`UINavigationItem.title` 继续承担 UIKit 的显隐/语义，但可见标题由导航栏坐标系内唯一的 UIKit 居中标签承载，避免 UIKit 为避让异步右侧动作而把标题挤到左边；标题与动作回调在同一主线程事务中应用。
- **顶栏动作几何**：同步、刷新和页面级动作使用统一 32×32 的 UIKit SF Symbol `UIBarButtonItem`；动作布局以结构键缓存，滚动只更新材质和回调，不重建玻璃控件。忙碌态无论是否有点击回调都占一个真正显示 spinner 的原生槽位，完成态与忙碌态不会因动作宽度变化而移动标题；日历动作放在左侧，右侧只保留状态和刷新，文字动作保留完整无障碍标签。
- **首页刷新反馈**：首页刷新动作现在先打开同步状态 sheet 再开始并行刷新；同步中的 spinner 仍可打开同一面板。同步面板在 iOS 只保留系统右上角 X；只有存在失败项目时才提供右侧“重试”，不再同时生成左侧“关闭”和右侧 X。
- **作业提示滚动**：紧凑作业页的明文传输提示现在作为 `HomeworkScrollableContent` 的首个 `LazyColumn` item，与筛选摘要和作业卡片共用滚动体；宽屏仍保留顶部提示。提示不会再固定覆盖滚动中的作业卡片。
- **弹窗覆盖面**：首页卡片/数据变动、成绩变动、作业上传、邮箱发送确认、物理在线提交/上传、设置清理/更新提示都走 `AppleSheetOrAlert`；非 iOS 保持 Material `AlertDialog` 或统一的 Material sheet fallback。
- **登录/同步动作**：`AuthenticatedAppShell` 在 native title bar 接管时把 `entryLoggingIn` 也发布为 `NativeBarAction`；Swift `NativeChromeBinding` 使用 UIKit `UIBarButtonItem` 与 SF Symbol，不再使用 Compose 刷新胶囊。忙碌态是原生 `UIActivityIndicatorView`，外包一个无背景、32×32 的 UIKit 按钮，仅用于点击打开同步面板；完成/失败状态用系统语义图标表达，文字仍保留在无障碍标签中。
- **同步状态**：原生标题栏刷新进行中使用 UIKit `UIActivityIndicatorView`；native title-bar 页面不再绘制 Compose `LinearProgressIndicator`，也不会再同时显示忙碌刷新与旧的“已同步”。
- **同步状态入口**：忙碌态 spinner 的导航栏按钮用标准 target/action 并保留 intrinsic hit area；Device Hub 运行态已验证在同步过程中点击它可以打开“同步中”面板，面板会显示进行中/已完成/等待同步状态。
- **密码保存**：登录勾选状态现在会立即同步到安全存储设置；取消勾选立即清除旧凭据。iOS 正式路径仍是 `AppleKeychainCredentialVault`，没有增加明文或 `NSUserDefaults` 密码回退。未签名模拟器访问 Keychain 会返回 `-34018`，这是签名边界，不是载荷编码失败。
- **密码保存迁移**：`AccountPreferences` 现在能区分“明确关闭”与“旧版本没有记忆标记”。如果旧版本已把凭据写入 Keychain 但没有写标记，启动时会恢复并补写标记；只有明确关闭才清除安全存储。新增迁移回归测试覆盖该路径。
- **烟测隔离**：此前 DEBUG `--security-smoke` 错用了生产 Keychain service/account 并在结束时清除，可能造成模拟器已保存凭据被测试擦掉；现在生产 store factory 支持隔离的 service/account/preferences key，烟测只清理 synthetic fixture。
- **登录弹层**：验证码恢复和静默自动登录失败提示也已收口到 `AppleSheetOrAlert`；iOS 不再从 `LoginScreen` 直接渲染 Material `AlertDialog`。保存失败会在登录后明确提示，不再静默让用户下次才发现密码没有保存。
- **物理在线层级**：`NativeShell.kt` 不再裁五项、不再暴露 `nativeFloatingEntry`；开启开关时 `bottomNavSections(true)` 包含 `PHYVLAB`，More 只保留总开关，不再列出物理在线入口。`AppRoute.shouldOpenNativeSectionRoute` 因此不会把它 push 成二级页。
- **iOS tab 宿主**：`ContentView.swift` 用 UIKit `UITabBar` + 每项独立 `TabRootNavigationController` 的自定义容器，绕过 `UITabBarController` 自动插入 More 的五项限制；没有自绘玻璃补位。
- **tab 重配**：物理在线开关增删 tab 时，UIKit 在关闭动画的事务中同时更新 item 集合和选中项，避免先短暂选中已删除的 PHYVLAB 再跳回首项。
- **顶栏边缘过渡**：Compose 只把“内容实际滚到导航栏下方”的布尔状态交给 UIKit；Swift 通过 `UINavigationBarAppearance` 在顶部透明边缘外观与滚动后的系统默认背景之间过渡，不再叠加 Compose 自绘渐变，也不再改变标题层级或导航栏高度。滚动量仍夹在 `[0, 52dp]`，只累计子内容实际消费的滚动距离；向下回滚列表中部不会提前清零，只有子内容在顶部报告 downward overscroll 时才复位；不可滚动页面的手势不会改变状态。
- **顶栏滚动材质**：iOS 26/27 使用公开 UIKit 导航栏 API 保留系统 Liquid Glass 材质（公开 `UINavigationBarAppearance.backgroundEffect` 只接受 `UIBlurEffect`，不强塞 `UIGlassEffect`）；旧系统回退到 `UIBlurEffect(.systemMaterial)`。`NativeChromeBinding` 始终保持 `prefersLargeTitles = false` 与 `.never`，避免 Compose/UIKit 两套滚动模型产生大标题残留空地；标题和图标前景色显式使用系统 `label/secondaryLabel`，不继承错误的玻璃白色。
- **顶栏渐隐玻璃**：原生导航栏下方新增 UIKit `UIGlassEffect(.clear)`/旧系统 blur 的渐变 mask 视图，覆盖标题栏下沿到正文约 76pt；它位于 Compose 内容之上、导航栏之下，不是 Compose 自绘渐变，内容会自然地被玻璃模糊并向下淡出。
- **课件详情 sheet**：课件文件详情改为原生大 detent 全高 sheet，正文滚动容器填满可用高度并保留底部安全区；底层“课件下载”导航标题不再与 sheet 标题叠在一起，下载按钮不会被底部截断。

## 已完成的代码验证

- `./gradlew :shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64`：通过。
- `./gradlew :shared:desktopTest`：通过，515 tests；`./gradlew :shared:iosSimulatorArm64Test`：通过，488 tests。
- Xcode iOS 27 Simulator arm64 构建：通过；本机合法 Apple Development 签名包的安全烟测显示 `SECURITY_SMOKE_PASS`，覆盖隔离 Apple Keychain store 与 `AccountSecurityCoordinator` 的保存/恢复/清除路径。烟测使用独立 service/account/preferences key，不会清除生产登录凭据；未签名包的同一烟测明确显示 `SECURITY_SMOKE_FAIL_CLEAR_-34018`。
- `./gradlew :shared:desktopTest :shared:iosSimulatorArm64Test`：通过（本轮 53s；桌面/ iOS 测试任务均成功）。
- 强制重生成 iOS arm64/Simulator cinterop 后，`:shared:compileKotlinIosArm64`、`:shared:desktopTest`、`:shared:iosSimulatorArm64Test` 均通过；Xcode iOS 27 Simulator 构建也通过。
- Device Hub 的 iPhone 18 Pro / iOS 27 已登录会话中已复核：首页同步时右上角为原生 spinner；底栏显示首页、课程表、成绩、作业、物理在线、更多六项；More 仅保留物理在线开关；成绩详情、课程详情和全屏筛选均为真实 UIKit sheet，使用统一的系统 Regular glass、原生抓手、原生关闭按钮，底层页面被柔化且正文不再被默认白色 Compose 画布覆盖。
- 最新包冷启动后等待自动登录完成，仍进入已登录首页且没有自动登录失败弹窗；关闭物理在线时底栏直接保留“更多”选中项，重新开启后六项 tab 恢复。
- 最新一次强制重生成 UIKit cinterop 后，iOS 27 模拟器包已重新安装并复核 native close/header、full-height Regular glass、同步中 spinner 点击入口；无标题 sheet 的原生关闭按钮已实际出现。
- 本轮最新包已在 iOS 27 Device Hub 复核：首页完美校园 sheet 无标题且只有“打开完美校园”；校园网充值 sheet 有“校园网充值”原生标题、二维码正文和单个右上角关闭 X；成绩详情与课程详情均显示统一的原生居中标题。所有已检查 sheet 的底层页面都会被系统材质柔化，正文不再与底层内容直接重叠。
- 本轮最新包又在 iOS 27 Device Hub 复核：考试详情以完整高度显示到“添加到日历”按钮；完美校园弹框显示“打开完美校园”与原生关闭 X，正文左对齐；首页标题与右侧动作处于同一条紧凑原生导航栏中，不再上下错位。
- 本轮最终标题栏包已在 iOS 27 Device Hub/模拟器复核：首页顶栏下沿出现玻璃渐隐；课程表在“添加到日历＋同步状态＋刷新”同时存在时标题仍保持屏幕正中，日历图标独立在左侧；首页刷新反馈链、同步 spinner 槽位和动作缓存已落地。课件详情 sheet 已改全高，完整测试集与 Xcode 模拟器构建通过。物理在线上滑/下滑不再改变标题或高度；不可滚动内容不会被手势误触发。作业明文提示仍随列表滚动离开，不再固定遮挡。
- 本轮没有在 Computer Use 中输入、输出或复制真实账号、密码、验证码；使用的是用户已打开的登录会话。最新包冷启动等待自动登录完成后仍停留在已登录界面，密码自动保存链路已有运行态证据；真实凭据内容本身未被读取或输出。
- Device Hub 当前可通过 Computer Use 直接键入普通测试文本，但系统键盘捕获/模拟器剪贴板无法安全注入本地凭据；已关闭键盘捕获并清空测试剪贴板。未读取、输出或复制 `MisSecret.md` 内容。

## 尚未证明的事项

- 仍需要用户主观确认：各类 sheet 在不同内容长度下的最终审美、边缘返回、减少透明度、iOS 26 以下回退壳、iPad 宽屏、首页卡片内容交互，以及顶栏整体过渡是否达到 iOS 27 参考观感。成绩页上滑/回弹和同步面板同步中入口已完成运行时验证。
- 本轮已创建本地基本完成标记提交，尚未推送或发布；后续继续修改前仍需保留该提交作为回退点。

## 相关文件

- 计划与历史决策：`docs/migration/m17-apple-liquid-glass-shell-plan.md`、`history_full.md`。
- 原生 sheet：`multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/feature/shell/AppleSheet.kt`、`multiplatform/shared/src/iosMain/kotlin/team/bjtuss/bjtuselfservice/shared/IosNativeSheetPresenter.kt`。
- 原生壳：`multiplatform/iosApp/iosApp/ContentView.swift`、`multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/feature/shell/NativeShell.kt`。

## 维护规则

- 每轮开始先确认 `git branch --show-current`、工作区状态和本文件；事实以当前源码、命令输出和当前截图为准。
- 已完成历史只进 `history_full.md`，实时文件只保留当前接续需要的结论；过期的“已落地/已实机/当前方案”必须删除或改成历史语气。
- 事实与计划分开写，明确验证范围；不记录账号、密码、Cookie、令牌、验证码或其他敏感会话内容。
