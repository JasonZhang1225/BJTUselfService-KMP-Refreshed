# M17 Apple 液态玻璃原生导航壳

状态：**实施中（2026-09-18 夜间在 `Liquid` 分支开工）**。步骤 1～3 的壳层代码与 Kotlin 桥接已落地并通过编译与单元测试；步骤 4 的逐 tab 实机视觉验证**尚未完成**（本机 Xcode 27 无 Simulator.app，无法注入触摸，见「当前验证边界」）。

## 目标

iOS 26 起，把导航壳层交给 Apple 原生容器，让 TabBar、导航栏、Toolbar 由系统自动渲染 **Liquid Glass 真实效果**（玻璃材料、浮动 TabBar、流体转场、系统级按压回弹），页面内容继续复用共享 Compose 屏。低于 iOS 26 的设备走现有回退路径，行为不变。

官方依据：[Liquid Glass in a Compose Multiplatform app](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html)。系统对原生导航结构自动应用玻璃效果，无需编写玻璃相关代码；Compose 自绘内容不会自动获得玻璃。

## 与现有工程的关系

- M10 已把 iOS 二/三级页交给 UIKit `UINavigationController`（`iosApp/iosApp/ContentView.swift` 的 `NativeNavigationController`）：根控制器为 Compose，二级页 `pushViewController`，边缘返回手势已接管。M17 在此之上继续收壳，不推翻 M10 结构。
- 构建环境满足 iOS 26 SDK：系统 `xcode-select` 已于 2026-08-11 切换到 Xcode Beta 27.0（Build `27A5228h`）。
- 依赖满足：`composeMultiplatform = 1.12.0-beta03`、`composeMaterial3 = 1.12.0-alpha03`。
- iOS 宿主只有 `iOSApp.swift` + `ContentView.swift`，改动面干净。
- 改动全部在 `multiplatform/`；Android/Windows/macOS 与冻结根 Android 工程零改动。

## 当前壳层与目标的差距

| 层 | 现状 | 目标（iOS 26+） |
| --- | --- | --- |
| 一级 Tab | Compose `CompactBottomBar` 自绘 | 原生 `UITabBarController`，系统玻璃 TabBar |
| 二/三级导航 | UIKit push，但 `setNavigationBarHidden(true)` | 保留 push，显示原生玻璃导航栏 |
| 页面标题/返回 | Compose TopAppBar 自绘 | 原生导航栏 `title` + 系统返回按钮 |
| 边缘返回手势 | `interactivePopGestureRecognizer` 自接管 | 保留现有接管逻辑 |

## 实施步骤

### 1. iOS 26 门禁与双壳

`ContentView.swift` 的装配入口按 `#available(iOS 26.0, *)` 分流：新壳（`LiquidGlassShell`）承载 iOS 26+；旧 `NativeNavigationController` 原样保留为回退壳，不删除、不修改行为。回退壳上已验证的两个修复（Compose 无障碍子树隐藏、手势优先级）需原样带到新壳。

### 2. 一级 Tab 迁入原生 `UITabBarController`

- 新增 Swift `AppTabBarController: UITabBarController`，五个一级入口（首页/课程表/成绩/作业/更多）各持一个子 `UINavigationController`，根控制器为对应 tab 的 Compose `UIViewController`。
- Kotlin 侧 tab 切换从 Compose 底栏回调转发到 `selectedIndex`；Compose 底栏在原生壳模式下隐藏（参照 M10 的 `nativeNavigationEnabled` 加同类开关，如 `LocalNativeTabBar`）。
- Kotlin 的 tab 选中状态与 `selectedIndex` 双向同步，保证 Compose 内部依赖当前 tab 的逻辑（如首页刷新聚合）不感知壳层变化。
- 浮动/最小化 TabBar：**UIKit 侧确有等价 API**（iOS 26+ 的 `UITabBarController.tabBarMinimizeBehavior`，
  取值 `.automatic` / `.never` / `.onScrollDown` / `.onScrollUp`，默认 `.automatic`；已在 iOS 27.0 SDK 头文件确认）。
  新壳显式写 `.automatic` 交给系统决定，**不选 `.onScrollDown`**：滚动发生在 Compose 的 Skia 层里，
  UIKit 观察不到 scroll view，滚动驱动的收起不会生效。是否真的浮动仍需手指滑一次确认。

### 3. 显示原生导航栏

- 去掉新壳中的 `setNavigationBarHidden(true)`；二级页 push 后由系统导航栏提供标题与返回。
- Kotlin 路由补齐供 Swift 读取的 `title`/`subtitle` 元数据（现有 `AppRoute`/`routeId` 白名单已有映射基础），`NativeDestinationViewController` 创建时传入。
- Compose 二级页顶栏在原生壳模式下隐藏（`LocalUseNativeNavigation` 类开关）；返回箭头事件改调既有 `onCloseNativeRoute`（pop）。
- `interactivePopGestureRecognizer` 的接管、边缘手势优先于 Compose 滚动的 delegate 逻辑，随导航栏显示重新验证（UIKit 在有返回按钮时默认启用手势，可能可删掉部分自接管代码，以实机行为为准）。

### 4. Compose 内容区逐 tab 点亮

- 顺序：首页（纯展示）→ 成绩/课程表（列表）→ 作业/更多（含二级 push 最多的路径）。
- 每个 tab 验证：玻璃 TabBar 切换、二级页玻璃导航栏 + 系统转场、边缘返回、标题不重复（原生栏与 Compose 顶栏二选一）、深色模式下宿主底色无闪白。
- 三栏/宽屏（iPad 横向）不受影响：宽屏仍走现有共享 `NavDisplay` 路径，原生壳只接管紧凑端。

### 5. 内容区玻璃增强（可选项，不阻塞里程碑）

个别高价值组件（如邮箱详情工具条、首页同步进度胶囊）如需玻璃材料，在 interop 层叠加 `UIVisualEffectView`，逐点评审后实施；不做批量替换。

## 文件范围

- Swift：`iosApp/iosApp/ContentView.swift`（双壳分流）、`iOSApp.swift`；新增 `AppTabBarController` 相关文件，必要时更新 Xcode project。
- Kotlin iosMain：`MainViewController.kt`（tab 会话装配、路由元数据导出）。
- commonMain：`feature/shell/` 壳层（底栏隐藏开关、tab 状态同步）、路由 title 元数据；不触碰业务 Screen 内容。
- 不修改 `app/`、根 Android Gradle、`desktopApp`。

## 验收门槛

- iOS 26 模拟器/实机：TabBar 与导航栏玻璃为系统真实效果，截图/录屏取证；五个一级 tab 即时切换无 push 动画；二级页系统转场与边缘返回可用、可取消。
- iOS 26 以下：回退壳行为与当前版本一致（M10 验收语义不变），无双标题栏、无导航死路。
- 逐 tab 对照 M0 功能清单做视觉与交互复测；不以「编译通过」宣称完成。
- Android、Windows、macOS 构建与既有回归不受影响；`desktopTest` 通过率不低于当前基线。
- 深色/浅色模式、减少透明度（`UIAccessibility.isReduceTransparencyEnabled`，此时系统自动降级玻璃为不透明）均实测。

## 非目标

- 不整体迁移 SwiftUI；页面内容仍为共享 Compose。
- 不承诺 Compose 自绘组件自动获得玻璃效果。
- 不在本里程碑内重做 macOS 桌面窗口视觉。
- 未经用户授权不提交、不打标签、不发布。

## 2026-09-18 夜间实施记录（工作区未提交，分支 `Liquid`）

### 已落地

- **双壳分流**：`ComposeView.makeUIViewController` 按 `#available(iOS 26.0, *)` **且**
  `userInterfaceIdiom == .phone` 选择 `LiquidGlassShellController`（新）或
  `NativeNavigationController`（M10 回退，行为未改）。用 idiom 而非 size class 判定宽屏，
  是因为 size class 随旋转变化而壳层只能在装配时选一次；iPad/宽窗口因此继续走共享三栏
  `NavDisplay` 路径（步骤 4 的「宽屏不受影响」由这条保证——注意 `forcedRouteId` 分支会强制
  `expanded = false`，若让 iPad tab 根也进新壳会静默丢掉三栏布局）。
  新壳原样带入回退壳的两个修复：Compose 无障碍子树隐藏、边缘 pop 手势优先于 Compose 滚动
  ——两处都抽成 `configureComposeHost(_:)` / `installInteractivePopGesture(on:)` 共用，避免复制漂移。
- **一级 tab 进原生**：`AppTabBarController: UITabBarController` 的 tab 列表来自
  `NativeShellKt.nativeTabItems(session:)`，与 Compose 自绘底栏共用 `bottomNavSections` 同一份来源；
  每个 tab 一个 `TabRootNavigationController`，其 Compose 根控制器推迟到 `viewDidLoad` 才创建，
  冷启动不同时付五份组合。图标改用 SF Symbols（系统负责选中态填充与玻璃着色）。
- **会话流**：登录宿主改用 child VC 常驻承载（不是导航栈切换）——登录宿主管线一旦离开窗口，
  Compose 组合回收时机不可控，会话来源与 `onAuthenticatedSessionChanged` 时序会失稳。
  Kotlin 的 `SideEffect` 每次重组都会重复上报同一会话，Swift 侧按实例身份去重。
- **Kotlin 桥接**（`feature/shell/NativeShell.kt`，public 供 Swift 读取；`AppSection` 等仍 internal）：
  `nativeTabItems(session:)`、`isNativeTabRoute(routeId:)`、`nativeRouteTitle(routeId:)`。
- **壳层开关**（`AuthenticatedAppShell`）：`nativeTabBarEnabled`（底栏换成系统 TabBar，内容只让出
  宿主回报的底部安全区，不再叠加 80.dp）、`onSelectNativeTab`（tab 根页面之间互跳交回宿主切 tab，
  否则出现「内容是成绩、高亮仍是首页」）、`useNativeTitleBar` + `onNativeTitleChanged`
  （二/三级页标题交系统栏，页面仍有动作/同步胶囊时 Compose 顶栏退化为纯工具行，否则整行撤掉只留安全区）。
- **回退壳语义保护**：`useNativeTitleBar` 由宿主如实声明（回退壳传 `false`）。否则 iOS 26 以下会
  既没有系统标题也没有 Compose 标题——夜间自查发现并修正的问题。
- **写信页保持自绘**：`DestinationPage(ownsBackAction = true)`，其返回要先 `cancelCompose()`，
  不能交给系统返回按钮，故该页不显示玻璃导航栏。

- **一级 tab 数量与溢出保护**：紧凑端 `UITabBarController` 拿到 6 项时，iOS 会自己插入系统「更多」溢出项，把应用自己的「更多」入口顶掉（实机截图已复现：物理在线开启时 `nativeTabItems` 返回 6 项）。新壳因此只交 5 项，超出时把 `PHYVLAB` 留在页内「更多」目录；`select(routeId:)` 对不再是 tab 的一级入口改为在当前 tab 上 push，避免点了没反应。
- **会话实例稳定性**：登录过程里 Kotlin 会因 `entryLoggingIn` 翻转与偏好写入产出新的 `AuthenticatedSession` 实例（内部 ScreenModel 是同一批 `remember` 对象）。原生壳按实例装配 tab，实例一变就整条 TabBar 重建、各 tab 返回栈丢失（日志实测 8 秒内重建 3 次）。修法两处：`AuthenticatedSession.entryLoggingIn` 改为可观察 `mutableStateOf` 状态并由工厂 `SideEffect` 同步，不再作为会话 `remember` 的键；Swift 侧 `reloadTabs` 只在 tab 组成或 `profile.studentId` 变化时重建。
- **压入页与 tab 根的区分（夜间代码审查发现并修复的两个真实缺陷）**：玻璃壳里 tab 根和被宿主压入的页面都带 `forcedRouteId`，
  而原壳层用 `showBack` 反推「是不是一级页」。开启物理在线后它被压到 5 个 tab 之外、改由「更多」压入，于是：
  ① 该页 `showBack=false` 会让 Compose 自绘标题与系统玻璃栏标题**同时出现**（双标题），且页内没有返回入口；
  ② `reserveBottomBarSpace` 会为一条并不存在的底栏预留 80.dp + 安全区，页面底部出现一大块空白。
  修法：新增派生标志 `isPushedHostDestination = forcedRouteId != null && !nativeTabBarEnabled`，
  PHYVLAB 的 `showBack` 改用它，底栏预留条件加上 `!isPushedHostDestination`。Android/桌面/回退壳路径取值不变
  （它们的压入页本来就 `showBack=true`，根组合 `forcedRouteId == null`）。
- **图标改用 SF Symbols**（house/calendar/list.bullet.rectangle/folder/square.grid.2x2），交给系统做选中态填充与玻璃着色；不再复制 Compose 的 Canvas 图标。

### 逐 tab 复测表（对照 M0 功能清单，验收门槛第 3 条要求）

图例：`tab` = 玻璃 TabBar 根页面；`push` = 由宿主压入、应出现玻璃导航栏；`—` = 该页不涉及此项。
每格填「等价 / 合理平台差异 / 缺失 / 阻塞」，任一 `push` 页出现双标题、双返回或「出不去」都算不通过。

| 入口（routeId） | 壳层形态 | 必看项 | 本轮已取证 |
| --- | --- | --- | --- |
| `HOME` 首页 | tab | 同步胶囊可点、聚合同步进度条悬浮不挤内容、变动卡与邮箱卡跳转、底部让位给玻璃条不留空白 | 首页渲染 + 深色 + 冷启动进壳 ✅；跳转「查看邮箱」✅ |
| `SCHEDULE` 课程表 | tab | 左右滑动切周不被玻璃条吃掉、日/周视图切换、列表着色、加入日历动作仍在顶栏行 | ✅ 页面渲染 + 选中态（周网格色块、前往日期/刷新、色块概览与列表切换两枚动作都在）；滑动切周仍需手指 |
| `GRADES` 成绩 | tab | 选课/筛选/排序、清空选择、窄窗按钮换行 | ✅ 页面渲染 + 选中态（加权平均 90.5 / 共 21 门、筛选与排序钮、成绩色块列表）；交互项仍需手指 |
| `HOMEWORK` 作业 | tab | 列表→`HOMEWORK_DETAIL` push、附件下载入口、返回后列表状态保留 | ✅ 页面渲染 + 选中态（智慧教学说明横幅、3 项作业列表）；详情 push 仍需手指 |
| `MORE` 更多 | tab | 目录页无返回箭头、子项逐个 push、再点已选 tab 回根 | ✅ 页面渲染 + 选中态（专业/校园/信息与下载/设置分组，根页无返回箭头） |
| `MAILBOX` 邮箱 | push | 系统栏标题「邮箱」+ 页内只留「写信/刷新」一行 | ✅ 已取证（含系统返回出栈） |
| `MAILBOX_DETAIL` / `MAILBOX_COMPOSE` | push / **push 但自绘栏** | 详情玻璃栏；写信必须仍是自绘标题+返回（返回要先取消草稿） | ✅ 两者都已取证：详情走玻璃栏；写信页顶部是 Compose 自绘的「‹ 写信」左对齐标题、系统栏未出现（`ownsBackAction` 生效） |
| `EXAMS` `COURSEWARE` `CALENDAR` `REPORT_CARD_DOWNLOAD` `SETTINGS` | push | 玻璃栏标题正确、系统返回、边缘返回可打断 | ✅ 已逐个压入/弹出取证：玻璃栏 + 系统返回按钮，`top=` 栈变化正确，弹回后回到 tab 根；边缘返回仍需手指 |
| `CLASSROOMS` → `CLASSROOM_DETAIL`、`CLASSROOM_OCCUPANCY` → `CLASSROOM_OCCUPANCY_DETAIL` | push / push | 三级栈：动态标题（教学楼名）要随选择更新，返回逐级 | 一级（教室人数估计）已取证；三级动态标题未测 |
| `PHYVLAB` | **push（本轮改动）** | 不再是 tab：从「更多」进入后应有玻璃栏+系统返回、页内不重复标题、底部无多余空白 | ✅ 已取证：玻璃栏居中「物理在线」+ 系统返回，页内只剩「已同步」胶囊行，内容正常、底部无空白——正是本轮 `isPushedHostDestination` 修的那条路径 |
| `PHYVLAB_DETAIL` | push | 玻璃栏「物理作业详情」、返回后列表位置保留 | 未测 |

**二级页取证方法**：临时用 DEBUG 定时器按脚本依次 `openNativeRoute` / `pop` 七个目的地
（SETTINGS、EXAMS、HOMEWORK_DETAIL、MAILBOX_DETAIL、MAILBOX_COMPOSE、CLASSROOMS、PHYVLAB），
每步截图并打印栈顶 routeId；**验证完探针已删净**（`grep` 残留 0）并重新构建、重装、复跑正常。
其中 `HOMEWORK_DETAIL` 是「整行撤掉 Compose 顶栏」分支的样本：截图显示顶部只有玻璃导航栏的居中
「作业详情」+ 系统返回，其下**没有**第二行标题，内容从导航栏下方开始——之前唯一没被覆盖的布局分支就此确认。

**五个一级 tab 已全部实机渲染取证**：用一次性 DEBUG 定时器驱动 `selectedIndex` 轮播（验证后已删除，
`grep` 确认无残留），逐 tab 截图确认内容、标题与选中态都随宿主切换正确；同时用 `tabBar.hitTest(中心点)`
证明玻璃条中心命中的是 **`_UITabButton`**（系统 tab 按钮本体，不是被 Compose 内容盖住）——
即「点 tab 不切页」不是壳层缺陷，纯粹是当晚 Device Hub 窗口拿不到标题、无法注入点击。

跨页统一项：五个 tab 切换即时且无 push 动画；各 tab 的返回栈互相独立保留；深色与「减少透明度」下玻璃降级；
退出登录后玻璃 TabBar 收起、回到登录页且不残留二级页。

### 当前验证边界

- 构建与回归全绿：`:shared:linkDebugFrameworkIosSimulatorArm64`、`:shared:compileKotlinIosArm64`、`:androidApp:assembleDebug`、`:desktopApp:compileKotlin`、`xcodebuild` Debug-iphonesimulator（Xcode 27 / iOS 27 SDK）；`:shared:desktopTest` 512 项，仅 `PackagingCiAsciiConfigTest` 2 例既有失败（基线同样 2 例）；新增 `NativeShellBridgeTest` 3 例锁定两端一级入口同源。
- **iPhone 18 Pro（iOS 27）模拟器实机已取证**（`simctl io screenshot`，图未入库）：
  ① 冷启动静默自动登录直接进入玻璃壳，不闪白；
  ② 系统玻璃 TabBar 渲染为浮动圆角玻璃条，5 个一级入口（首页/课程表/成绩/作业/更多）标题与图标正确，选中项系统高亮；
  ③ 首页「查看邮箱」→ 邮箱二级页出现**系统玻璃导航栏**：居中标题「邮箱」+ 系统返回按钮（辅助功能树里是真实的 `BackButton` + `heading 邮箱`），Compose 顶栏退化为只含「写信/刷新」的一行，**无重复标题、无重复返回箭头**；
  ④ 系统返回按钮点击可出栈回首页；
  ⑤ 二级页 push 时 TabBar 正确隐藏（`hidesBottomBarWhenPushed`）；
  ⑥ 深色模式登录页与首页底色正常；**且 `simctl ui appearance dark` 在玻璃壳首页实测**：
     玻璃 TabBar 随系统深色正确变深、内容对比度足够、宿主底色无闪白（浅色↔深色来回切换后回正常）；
  ⑦ 首页顶栏「登录中 → 已同步」随会话状态实时变化（可观察状态修复生效）。
- 「增加对比度」这一项**无法无头设置**：`xcrun simctl ui <udid> increase_contrast on` 在本机 iOS 27 运行时返回
  `Invalid argument`，需人工在 设置→辅助功能 里开，或在减少透明度下看玻璃降级。
- **未验证（剩下的都必须真人）**：
  a) **手指点玻璃 TabBar**：壳层侧已被证明可用（`selectedIndex` 驱动切换后五个 tab 内容/选中态全部正确，
     且 `tabBar.hitTest(中心)` 命中的是系统 `_UITabButton` 而非 Compose 内容），当晚点不动的真因是
     **坐标没落在 `getAppState` 报的截图像素空间里**（按 1280 宽窗口量的 y≈858 在 1056×768 之外），
     与熄屏无关（熄屏只让 `screencapture` 失败）；2026-09-19 已用正确坐标实机点成，见下面第 ③ 条。
     已量好坐标见下面「早上接手清单」第 1 点（**要用 `getAppState` 的截图像素空间，不是 1280 窗口像素**）。
  b) 边缘返回：**结论待重测**——昨夜用 `computer.drag` 合成拖拽时报「不触发系统 pop」，但那批点击的坐标
     落在 `getAppState` 截图空间之外（同一条错坐标也污染了当晚的点击结论，见第 ③ 条），
     所以「拖拽不触发」既可能是合成触摸流本身不驱动 iOS 边缘手势，也可能只是坐标错。
     要带正确坐标空间重测一次；真人手指或真机复测仍是最终判据。
  c) 减少透明度下的玻璃降级（`simctl ui increase_contrast on` 在 iOS 27 运行时返回 `Invalid argument`，无法无头设置）；
  d) iOS 26 以下回退壳实机（本机只有 iOS 27 运行时）；
  e) ~~iPad 登录后的三栏~~ → **2026-09-19 已实测**：iPad Pro 13（iPadOS 27）登录后是左侧 rail（六个一级入口，
     含物理在线与更多）+ 中间目录列表 + 右侧详情的共享宽屏三栏，M17 未回归该路径；
     但整屏**零系统玻璃材质**（rail/列表/滚动条全 Compose 自绘），与用户观察到的「iPad 和 Mac 共享一套外观」一致
     → iPad 要拿到玻璃必须换原生三栏容器。Mac 侧当晚确实做过 `NSSplitViewController` 原生壳并跑通，
     但 22:40 按用户决定取消、代码删净（实测结论见 `history_full.md` 的「M18：macOS 原生外观」一节），
     所以这条仍是未解的同源问题。
  f) 页面内的数据交互：选课/筛选/排序、切周手势、附件下载、三级栈逐级返回、
     `CLASSROOM_DETAIL`/`PHYVLAB_DETAIL` 的动态标题。
- 结论：M17 主体已落地并取得关键玻璃证据，但验收门槛要求「逐 tab 对照 M0 清单复测」，**仍不得按「编译通过」宣称完成**。

### 2026-09-19 上午补充（用户报 3 项，屏幕点亮后补做交互取证）

**① 已修：设置里打开物理在线，底栏毫无反应。** 两处真因叠加：
- `LiquidGlassShellController.handle(session:)` 只按**会话实例身份**去重，而昨夜为了让 tab 栏不重建，
  我把 `AuthenticatedSession` 实例稳定化了（`entryLoggingIn` 改成可观察状态、不再作 `remember` 键）——
  于是偏好变化不再产生新实例，`reloadTabs` 永远不会被调用。**这是我自己昨夜改动引出的回归。**
  现在：实例不变时也会过一遍 `reloadTabs`，由它按「tab 组成 + 账号」自守。
- Swift 侧 `tabItems(for:)` 把 PHYVLAB 硬过滤掉（怕系统塞溢出项顶掉应用自己的「更多」），
  所以即使重配也不会出现物理在线。现在整份交给系统，并把重配改成**按 routeId 增量复用**
  （`controllersByRoute`）：只新建缺的那一栏、丢掉不再属于 tab 的引用、**保留用户当前选中的那一栏**，
  换账号时才整体丢弃旧栈。

**② 实测出 iPhone 系统玻璃 TabBar 的硬上限：只给 5 格（含溢出胶囊）。**
6 个一级入口时 iOS 26 收成「首页/课程表/成绩/作业 + 系统更多(•••)」，物理在线不会单独占一格；
点系统更多进入**系统原生的溢出列表页**（标题「更多」+ 右上「编辑」，两行：物理在线、更多），
「编辑」即 iOS 26 的 tab 栏自定义能力。关闭物理在线时是 5 个应用 tab、无系统胶囊（昨夜已取证）。
→ 「开关打开后底栏多出物理在线」这一期望在 iPhone 紧凑端**无法由系统 TabBar 满足**，
剩余可选（待用户拍）：a) 就用系统溢出（当前实现）；b) 物理在线占一格、应用「更多」目录改挂首页玻璃导航栏右上角
（仅 iOS 适配）；c) 紧凑端回退自绘底栏换回 6 格（放弃系统玻璃）。iPad 规则宽度可放更多入口。

**③ 已取证：手指点玻璃 TabBar 可用。** 显示器点亮后 Computer Use 坐标点击成功：
首页 → 点 (507, 672) → 成绩页即时切过来、玻璃胶囊选中态正确（无 push 动画）；
再点 (599, 672) 打开系统溢出列表。**关键更正：坐标要用 `getAppState` 报的截图像素空间**
（本轮 `Screenshot size: 1056 × 768`），昨夜按 1280 宽窗口量的 y≈858 落在窗外，
这才是当晚 `No visible target window for coordinate action` 的直接原因（显示器熄屏只是让
`screencapture` 失败，与 Computer Use 的点击判据不是同一件事）。

**④ 溢出项「点了没反应」已定位并修**（日志探针取证，探针已删净、`grep -c M17PROBE` = 0）：
临时在 `shouldSelect`/`didSelect` 打 NSLog，点系统溢出行时打出
`shouldSelect target=TabRootNavigationController ... stack=0` → `didSelect=TabRootNavigationController index=4`，
即**选择本身成功了**，但那个 tab 的导航栈是空的——因为本壳把 Compose 根推迟到 `viewDidLoad` 才建，
而从 More 列表进入时 UIKit 不走正常加载路径，界面就停在溢出列表上。修法：`didSelect` 里
`if controller.viewControllers.isEmpty { controller.loadViewIfNeeded() }`。

**⑤ 当前状态与剩下 60 秒的验收（两条新路径都还没实测到）**：
本轮结尾该账号的 `isPhyVlabEnabled` 为 OFF，底栏是 5 个应用 tab、无系统胶囊，所以 ① 的动态重配与 ④ 的溢出修复
都没能当场验到。验收只需：更多 → 设置 → 打开「物理在线」→ **底栏应即时变成「首页/课程表/成绩/作业 + 系统更多」**
（这一步验 ①：同一个会话实例上偏好变化会重配 tab 且保留当前选中栏）；再点系统更多 → 点「物理在线」行 →
**应进入物理在线页并带玻璃导航栏与系统返回**（这一步验 ④）。
仍未取证：边缘返回可打断、减少透明度降级、iOS<26 回退壳、页内数据交互。

**⑥ 方案定稿：Kotlin 侧作为唯一事实源（2026-09-19 00:27）**：本节记录的改动与本文件其余部分出自**同一个会话**
（上下文压缩前那段做完了 `AppRoute.kt` / `MoreWorkspace.kt` / `NativeShell.kt`，压缩后我误读成「另一个会话在并行改」，
08:40 已由用户纠正并在此更正）。它把「5 项上限」做成了**唯一事实源**：
`NATIVE_TAB_BAR_MAX_ITEMS = 5` + `nativeTabSections()` 统一裁列，Swift 不再自己过滤（我已把那条会说谎的注释改掉）；
放不下的入口由「更多」目录承载（`MoreWorkspace(phyVlabEntryInMore = …)` 会把物理在线列进目录），
`shouldOpenNativeSectionRoute` 改成「凡不在 `nativeTabSections()` 里的一级项都由宿主压栈」，
于是被收进目录的物理在线有玻璃导航栏与系统返回。
→ **第 ② 条列的三个候选就此定为「上限 + 目录承载」**，iPhone 底栏不再出现系统溢出胶囊，
所以第 ④ 条修的溢出加载路径属于防御性保留（真机上不再被触发）。
合并后已验：`:shared:desktopTest --tests "*NativeShellBridgeTest*"` + `linkDebugFrameworkIosSimulatorArm64`
+ `xcodebuild` 三项全绿，组合构建已装进 iPhone 18 Pro 模拟器（pid 72324）。
新增测试 `nativeTabOverflowIsPushedByHostSoItKeepsASystemBackEntry` 锁住这套语义
（PHYVLAB 不在原生 tab 上 → 必须由宿主压栈；`useNativeSecondaryRoutes=false` 时仍走 Compose 栈；
MORE/GRADES 不被压栈），实测 4 例全跑、0 失败。
第 ⑤ 条的验收步骤相应改为：打开物理在线 → **看「更多」目录里是否多出「物理在线」行**（底栏保持 5 格是预期），
点它 → 应出现玻璃导航栏 + 系统返回、页内无重复标题、底部无多余空白。
**两条会话并行改同一工作区是本次协作的最大风险**：收口与提交前必须先 `git diff` 对齐双方改动。

### 2026-09-19 早间修复：物理在线在玻璃壳里「拨了没反应」

用户实报 bug：设置里打开物理在线，底栏没有增加选项。实测根因有两条，都不在原计划里：

1. **`UITabBarController` 只放得下 5 个第一方入口**，而开启物理在线后一级入口有 6 个。当晚把 6 项整份交给系统后，
   iOS 自己插入系统「更多」溢出页，**把应用自己的「更多」目录一起收进溢出列表**——底栏看起来「什么都没增加」，
   而 `MoreWorkspace` 里根本没有物理在线那一行（它原本假设物理在线一定在底栏），于是玻璃壳里既没有 tab 也没有目录行。
2. **Compose 状态变化不会自动到 UIKit**：`AppTabBarController.reloadTabs` 只在会话实例变化时被调用，
   拨开关不产生新会话 → 原生底栏永不重读。

修法（三处，全部实机取证）：

- `NativeShell.kt`：新增 `NATIVE_TAB_BAR_MAX_ITEMS = 5` 与 `nativeTabSections()`，超上限时固定把物理在线让给
  「更多」目录（保住高频的「更多」在 1 步内，而不是把它塞进系统溢出页）；`isNativeTabRoute` 同步用这份被裁剪的集合。
- `MoreWorkspace`：`phyVlabEntryInMore` 为真时在「学业」组里给一行物理在线入口，并把开关副标题从
  「并在底栏显示入口」改成「入口显示在本页列表」（原文案在 iOS 上是假的）。
- `AppRoute.kt`：`shouldOpenNativeSectionRoute` 改为「不是原生 tab 的一级项也要宿主压栈」。**这条最关键**：
  只加目录行不够，物理在线会落进 tab 根的 Compose 栈，而玻璃壳里它的 `showBack=false`，进去就没有返回口。
- `AuthenticatedSession.onNativeTabItemsChanged` + 壳层 `LaunchedEffect` + Swift 装配时挂回调：入口集合变化显式回推 UIKit。

02:26 实机链路全绿（iPhone 18 Pro / iOS 27，Computer Use 真实点击）：底栏恒为 5 项且「更多」是应用自己的
grid 图标 → 「更多」页开关副标题正确、下方出现「物理在线（仅能在校园网下访问）」一行 → 点进后出现**玻璃导航栏**
（AX 暴露 `back button ID: BackButton` + `heading 物理在线`）、底栏隐藏、无重复标题、数据真实（我的课程 2 门 +
Chapter15-16 课程作业 + 「已同步」胶囊）→ 点系统返回回到「更多」目录、底栏回来。
`desktopTest --tests "*NativeShellBridge*"` 通过（`PHYVLAB` 已改判为非原生 tab）。

### 08:32 页内交互补测（Computer Use 真实点击，iPhone 18 Pro / iOS 27）

邮箱三级栈是 M17 最吃栈语义的一条路径，实测全对：

- 「更多」→「邮箱」：玻璃导航栏出现，底栏隐藏，Compose 只保留「写信 / 刷新」一行，无重复标题。
- 列表 → 点第一封：`heading 邮件详情` + `back button ID: BackButton`，且**返回按钮文案是上一页标题「邮箱」**
  （iOS 原生语义，比自绘的通用 ‹ 更明确）。
- 点系统返回：回到 `heading 邮箱` 列表，栈未越级。
- 列表 → 「写信」：宿主 AX 树里**没有任何导航栏元素**，页面仍是自绘「‹ 写信」+ 收件人/抄送/主题/正文 +
  「邮件会先在服务器创建草稿」提示 —— 与文档里写的写信页例外一致（返回要先取消草稿，不能交给系统栏）。

因此本清单剩余项收敛为：边缘返回是否可中途反悔、减少透明度降级、iOS 26 以下回退壳、
以及邮箱之外的页内数据交互（选课/筛选/排序、切周、附件下载、教室占用查询）。

### 早上接手清单（按顺序）

> **02:30 已完成项**：清单 1（点亮显示器后坐标点击与 AX elementIndex 都可用）、清单 2（点玻璃 TabBar 切页，
> 实测 5 项选中态与内容正确）、清单 3 的「系统返回可用」与「更多→物理在线」新路径，均已用 Computer Use 真实点击取证。
> 剩下要人眼的：边缘返回是否可中途反悔、减少透明度降级、iOS<26 回退壳、iPad 登录后三栏、页内数据交互。

1. 显示器点亮后（`pmset -g log | grep "Display is turned" | tail -2` 可自查），在 Device Hub 里让
   iPhone 18 Pro 的设备窗口**保持前台可见**（显示卡死时可用 `kill -9 <DeviceHub pid>`
   再 `open -b com.apple.dt.Devices` 恢复渲染，普通 `kill`（SIGTERM）无效；设备窗口被关掉后无法用菜单重开）。
   当晚「点不动」的真因**不是熄屏也不是窗口注册**，而是坐标空间：见下面第 3 点的坐标口径。
   玻璃 TabBar 点击坐标要用 `getAppState` 报的**截图像素空间**（本轮 1056×768）：
   首页 (409, 672)、课程表 (456, 672)、成绩 (507, 672)、作业 (553, 672)、系统更多 (599, 672)。
   窗口尺寸一变这些数字就作废，每次先 `getAppState` 读 `Screenshot size` 再定位。
2. 点玻璃 TabBar 的「成绩/课程表/作业/更多」确认即时切换、无 push 动画、各 tab 返回栈独立保留。
3. 更多→设置/考试/邮箱：确认玻璃导航栏标题正确、系统返回与边缘返回可用且可中途反悔；重点看邮箱列表只有「写信/刷新」一行；写信页应仍是自绘标题与返回（预期差异，不是回归）。
   **另需单测一条本轮新增路径**：更多→物理在线（它已不是 tab，改由「更多」压入）——应出现玻璃导航栏 + 系统返回、
   页内不再自绘标题、页面底部无多余空白。
4. 设置里打开「减少透明度」与深色模式，确认玻璃降级为不透明、无闪白（深色已在本轮实测通过；
   减少透明度无法无头设置：`simctl ui increase_contrast on` 在 iOS 27 运行时返回 `Invalid argument`）。
5. iPad Pro 13 英寸（已 booted，本分支构建已装）：登录页宽屏两栏已实测正常；登录后三栏需在上面同一个
   可见窗口里输凭据（iPad 画面在 Device Hub 里缩放比例与 iPhone 不同，坐标要重新量）。
   **已试过并排除的捷径**：为了绕开输凭据，用 `simctl clone` 造了一台临时 iPad（再 `shutdown`+`delete` 清理，
   未动用户自己的设备），把 iPhone 模拟器目录下的 `keychain-2-debug.db`/`-shm`/`-wal` 拷进克隆机后开机启动，
   结果仍然是登录页——凭据走 `IosKeychainCredentialVault`（钥匙串按设备/分区加密），
   **跨模拟器设备拷钥匙串不迁移登录态，iPad 登录后三栏只能真人登录一次**。
   **02:21 已由用户自行登录后补测完成**：iPad Pro 13 横屏三栏（左栏 首页/课程表/成绩/作业/物理在线/更多 +
   中间目录 + 右侧详情）无回归，且宽屏左栏不受 5 项上限、物理在线直接是一级入口；
   但同时确认 **iPad 整屏零玻璃材质**（左栏与列表全为 Compose 自绘，M17 的 `userInterfaceIdiom == .phone`
   门禁让它继续走 M10 回退壳）——这与 Mac 是同一套外观，iPad 的玻璃化必须和「原生宽屏容器」这件事一起做
   （Mac 那条 A 路线当晚跑通后已按用户决定取消，实测结论见 `history_full.md` 的「M18：macOS 原生外观」一节）。
6. 已知待观察点：a) 导航栏显隐由 `willShow` + 标题迟到回调两处决定，注意 push 动画中途玻璃条是否跳动；
   b) **重点看走「整行撤掉 Compose 顶栏」分支的页面**（`MAILBOX_DETAIL`、`HOMEWORK_DETAIL`、
      `CLASSROOM_DETAIL`、`PHYVLAB_DETAIL` 这类既无页面动作也无同步胶囊的）：它们顶部只剩一条
      `windowInsetsTopHeight(WindowInsets.statusBars)` 占位，若 iOS 把玻璃导航栏算进 `safeAreaInsets.top`
      则刚好让位，否则会顶进玻璃条下面——本轮唯一没被截图覆盖到的布局分支（邮箱列表页有「写信/刷新」行，
      走的是另一条分支，已验证正常）。
   c) **二级页返回栈语义变化**：Compose 壳里「更多」的子页永远被压成 `[更多, 目标]`，返回必回更多目录；
      玻璃壳改为标准 iOS 压栈，未弹回就继续点会累积成 `[更多, 设置, 考试…]`。这是有意的平台化差异，
      需要用户确认接受（邮箱的 列表→详情→写信 层级正是靠它才自然）。
   d) 玻璃 TabBar 的浮动/最小化：UIKit 的 `tabBarMinimizeBehavior` 已显式设为 `.automatic`；
      因为滚动在 Compose 的 Skia 层内，`.onScrollDown` 不会生效，需手指滑动确认系统最终表现；
   e) 物理在线开启时不再占一个 tab，改由「更多」目录承载（本次设计决定，需用户确认接受）。
7. 验收通过后再谈提交：本次改动全部未 commit，涉及
  `multiplatform/iosApp/iosApp/ContentView.swift`、`shared/src/iosMain/.../MainViewController.kt`、
   `shared/src/commonMain/.../{App.kt,LoginScreen.kt,AuthenticatedSession.kt,feature/shell/{AuthenticatedAppShell.kt,AuthenticatedSessionFactory.kt,NativeShell.kt(新)}}`、
   `shared/src/commonTest/.../NativeShellBridgeTest.kt(新)`、本文件。

## 2026-09-19 下午：用户 17:00 四条反馈全部落地（iPhone 18 Pro 模拟器出图）

用户看了真机（iPhone 17 Pro，已装 Debug 包）与模拟器截图后提了四条。四条都改完并各自有图，
`./gradlew :shared:desktopTest` 514 例只剩 `PackagingCiAsciiConfigTest` 那 2 例 1.7.6 遗留失败。

**① 底栏没有通透感 → 内容改为穿过玻璃条**
根因不是材质：`ContentView.swift` 从未设过 `UITabBarAppearance`，系统给的就是玻璃。真正的问题是
`AuthenticatedAppShell.kt` 把底部安全区当成**布局内边距**（`Column.padding(bottom = compactBottomBarOverlayPadding)`），
列表视口停在玻璃条上沿，玻璃背后只剩一片纯色 → 没有东西可折射。
改法：新增 `feature/shell/BottomBarClearance.kt` 的 `LocalBottomBarClearance`，玻璃壳下把这份净空
从布局挪到**滚动内容尾部**（`contentPadding` / 滚动内 padding），首页、成绩、课表、作业、更多五处各自接上。
自绘底栏（Android 与 iOS 26 以下）保持老语义：`glassScrollUnderBar = nativeTabBarEnabled && reserveBottomBarSpace`
为假时仍走布局内边距。
取证：`/tmp/m19_v4.png`、`/tmp/m19_style3.png`——「数据变动」卡的文字已经压在玻璃条下面，玻璃能折射出卡片边缘。
**注意**：CMP 的 klib 里查不到 `ignoreSafeAreaLayoutInsets` 这类开关，但实测证明不需要——
Compose 的根视图本来就铺满、安全区只是被我们自己当 padding 用了。

**② 物理在线要底栏入口 → 玻璃悬浮圆按钮**
iPhone 紧凑端 `UITabBarController` 只给 5 格，第 6 项会让系统插自己的溢出页并顶掉应用的「更多」（昨夜实测），
所以「加一格」在 iPhone 上做不到。按用户建议改成悬浮圆按钮：Kotlin 侧 `nativeFloatingEntryFor(phyVlabEnabled)`
算出「被上限挤掉的那一项」，Swift 侧用 `UIGlassEffect()`（iOS 26+，属性名是 `isInteractive` 不是 `interactive`）
做一颗 46pt 圆钮，贴在玻璃条右上缘之外，点击走 `select(routeId:)` → 宿主压栈，保留系统返回。
两个坑：(a) 开关拨动时底栏组成不变（仍是 5 格），`reloadTabs` 的 guard 会提前返回，所以
`syncFloatingEntry` 必须放在 guard **之前**；(b) 按钮的显隐不能靠猜 `tabBar.frame`——push 时
`AppTabBarController.viewDidLayoutSubviews` 不一定再跑，会和 push 动画抢写入，结果按钮赖在二级页上。
改成 `TabRootNavigationController.onBarVisibilityChanged`，在 `willShow` 里如实上报「当前是不是根页」。
取证：`/tmp/m19_phy3.png`（圆钮进页 + 二级页上圆钮已消失）。测试 `phyVlabMovesToAFloatingEntryWhenTheBarIsFull`。

**③ 二级页刷新胶囊独占一行 → 收进系统导航栏右侧**
新增 `NativeBarAction(status, canRefresh, busy, label, onClick)`，沿 `onNativeTitleChanged` 同一条路
（`MainViewController` → `App.kt` 的 `AuthenticatedDestinationApp` → `DestinationPage`）多接一个
`onNativeActionChanged`；`onClick` 作为 ObjC block 导出，Swift 的 `UIBarButtonItem(primaryAction:)` 直接回调进本页闭包，
不经过全局状态，pop 之后不会串页。宿主渲染成 `rightBarButtonItems = [刷新, 状态文字]`
（UIKit 把 index 0 放最右，所以主操作靠右），`busy` 时换成 `UIActivityIndicatorView`。
门槛：`nativeTitleBarActive && !entryLoggingIn && topBarAction == null`——页面自己有 Compose 动作按钮时整行仍留在页内。
取证：`/tmp/m19_phy3.png` 导航栏右侧一条「已同步 ⟳」玻璃胶囊，页内那一行没了，内容直接从「我的课程」开始。
回归面：`useNativeTitleBar=true` 只有 iOS 玻璃壳会传（Android 与回退壳都是 false），所以 Android 的刷新入口不受影响。

**④ 首页方块元素改苹果风格（只改 iOS）**
新建 `feature/home/HomeCard.kt`：iOS 用 `Surface(shape = shapes.large, color = surface)`，不投影不描边；
其它平台继续 `ElevatedCard`，Android 外观不动。选 `surface` 是因为这套主题 `background=#F4F5F9`（页面灰）、
`surface=#F9F9FC`（卡片白），正好是苹果分组列表的分层关系；第一版误用 `surfaceContainerLow`，
和页面同色，卡片直接糊没了（`/tmp/m19_style2.png` 可见）。深色模式同样成立（`/tmp/m19_dark.png`）。
首页 5 处卡片（周日程、新邮件、校园卡、校园网、数据变动）全部换用 `HomeCard`。

**另外两处修正**
- 分支：昨夜到今天下午工作区实际在 `main` 上（17:04:20 有一次 `Liquid → main` 的 checkout），M17 的脏改动是被 git
  带着跨过 checkout 的，内容没丢但分支指针错了。已把 `Liquid` 快进到 `df92d34`（它原本只是 `main` 的父提交，零丢失）
  再切过去。**以后每轮开工先 `git branch --show-current` 复核，不要相信上一轮的记号。**
- 测试漂移：昨夜把 PHYVLAB 改成「由宿主压栈」时只更新了 `NativeShellBridgeTest`，漏了
  `GradeScreenNavigationTest.mailboxAndClassroomRootsPushNativelyFromMore` 里的同一断言，本轮全量跑才抓到。
  改语义时要把断言同一谓词的测试全找出来，不能只改自己新加的那个。

### 18:10 复核：哪些人工项本机根本做不了（别再来回试）

- **iOS 26 以下回退壳**：`xcrun simctl list runtimes` 只有 `iOS 27.0` 一个 runtime。要验回退壳必须先下载
  iOS 18.x 模拟器 runtime（数 GB、要接受许可），不属于「顺手能做」的范围 → 留给人工决定要不要装。
- **减少透明度降级**：`simctl spawn <udid> defaults write com.apple.Accessibility ReduceTransparencyEnabled -bool YES`
  能把键写进去（`defaults read` 确认 `= 1`），但**系统玻璃 TabBar 仍然透光、Compose 侧也没观察到变化**——
  模拟器的这条开关要走设置 App 真正拨一次才会生效。测完记得写回 `-bool NO`，别把模拟器留在怪状态。
- **列表滚到底时末组能否让开玻璃条**：Computer Use 的 `scroll` 与 `drag` 都驱动不了 Compose 的 Skia 滚动
  （和 `computer.drag` 触发不了系统边缘返回是同一个限制）。坐标点击 tab 是可以的（实测点 (608, 668) 成功切到「更多」），
  所以「切页看布局」能自证，「滚动相关」只能靠人手指。
- **边缘右滑可打断**：同上，手势注不进去。

本轮已自证：首页与「更多」页内容穿过玻璃条（`/tmp/m19_style3.png`、`/tmp/m19_more.png`）、悬浮圆钮进页与在二级页隐藏
（`/tmp/m19_phy3.png`）、二级页导航栏「已同步 ⟳」（同图）、深浅两套配色（`/tmp/m19_dark.png`）。
**邮箱列表页是 ③ 那道门槛的反向证据**（`/tmp/m19_mail2.png`）：它有页面级动作「写信」，`topBarAction != null`
→ 顶栏行原样保留在页内、系统导航栏右侧不重复出刷新按钮。代价是邮箱这一页仍然占着一行——要把这行也省掉，
得让 `写信` 一起进导航栏（`topBarAction` 是任意 Composable，不能直接渲染，需要为它单独开一条文案+闭包的桥接），
留给用户决定是否值得。
另外补了一道门槛：状态胶囊**可点击**时（部分同步失败要点开明细看 `syncFailureItems`）也必须留在页内，
导航栏里的状态是纯文本 UILabel，搬过去就把这个唯一入口弄丢了。


