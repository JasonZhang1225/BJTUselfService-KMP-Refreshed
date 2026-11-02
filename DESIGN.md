# BJTUselfService 外观与交互设计规范

本文件规定“交大自由行 KMP”各端界面应该长什么样、怎么交互，适用于所有开发者和 AI 编码代理。目的只有一个：**新功能第一次提交就符合现有设计，不靠用户实机挑错、来回返工。**

- 规则用 **必须 / 禁止 / 建议** 三级表述。“必须”“禁止”是硬性要求，不满足的 PR 视为未完成；“建议”可以有理由地偏离，但要在 PR 说明里写出理由。
- 本文描述的是**当前代码已经在用的做法**，每条都给出参考实现。代码与本文冲突时，先以代码为准，并把冲突告诉用户，不要自行“统一”成第三种样子。
- 本文总结自项目历次用户验收（M17 液态玻璃壳、Duo 横屏、图书馆、作业搜索、ChatGPT 插件页等）和本机 Apple Design 技能（WWDC《Designing Fluid Interfaces》等）。写不进本文的新情况，**先问用户，不要自行发挥。**

---

## 1. 总体原则（先读这一节）

1. **让系统画系统该画的东西。** iOS 上顶栏、底栏、返回按钮、栏内按钮、弹层外壳、搜索框、日期选择器都交给 UIKit 原生控件，由系统自动渲染液态玻璃。Compose 只画页面正文。**不在 Compose 里仿造玻璃、仿造导航栏、仿造 sheet 外壳。**
2. **内容在玻璃下面流过。** 玻璃要有东西可以折射才是玻璃：滚动内容必须从屏幕顶边开始画，靠内容内边距让开顶栏，滚动时穿到栏后面。顶栏下方留一条纯色带子是最常见的返工原因。
3. **静止时干净，滚动时出现材质。** 页面在顶部时顶栏透明、只有标题和按钮；内容滑进栏后才逐渐出现玻璃。
4. **一份语义，各端各自的样子。** 共享状态、文案和交互语义；iOS 用原生玻璃，Android 用 Material 3，macOS/Windows 用 Compose 自绘的同一套语义。不要为了“看起来一样”在 Android 上仿 iOS 玻璃，也不要在 iOS 上留 Material 外壳。
5. **克制。** 一个页面顶栏最多一个页面级动作；不加装饰性渐变、阴影、描边；不叠两层半透明；普通出现/消失不弹跳。
6. **立即响应、可以打断。** 按下立即有视觉反馈，不等网络；手势驱动的动画随时可以被抓住、反向。
7. **每个状态都有样子。** 加载、空、错误、部分失败、正常五种状态都要设计，错误就地说明，不只弹 toast。

Apple 的八条设计原则（Purpose / Agency / Responsibility / Familiarity / Flexibility / Simplicity / Craft / Delight）是这些规则背后的理由；遇到本文没写到的情况，用它们判断，然后问用户。

---

## 2. 平台分工

| 端 | 顶栏 | 底栏 / 导航 | 弹层 | 图标 |
| --- | --- | --- | --- | --- |
| iOS 26+（iPhone / iPad 紧凑） | 原生 `UINavigationBar`，系统液态玻璃；滚动后出现 `UIGlassEffect(.regular)` 顶栏材质 | 原生 `UITabBar`（玻璃底栏） | 原生 `UISheetPresentationController`，玻璃背景 | SF Symbols |
| iOS 18–25 | 同一套原生控件，宿主自动回退 `UIBlurEffect(.systemMaterial)` | 同上 | 同上 | SF Symbols |
| iPad 宽窗口 / 横屏宽屏 | 侧栏 + 列表—详情；宽窗口详情不走原生 push，可能由 Compose 自绘顶栏（苹果样式搜索头） | `AppSidebar` | 同 iOS | SF Symbols / 自绘 |
| Android | Compose `CompactAppTopBar`（与页面背景同色，不仿玻璃）；二级页由 `NativeDetailActivity` 承载 | `CompactBottomNavigation` | `AppleSheet` 回退为 Material `ModalBottomSheet`，`AppleSheetOrAlert` 回退为 `AlertDialog` | Compose `Canvas` 自绘 |
| macOS / Windows | Compose 自绘顶栏，支持悬停、右键、键盘 | 宽屏侧栏 | Material 回退 | Compose `Canvas` 自绘 |

页面代码**不判断 iOS 版本、不判断是否有玻璃**：只要按第 3 节接入统一骨架，平台差异由壳层和宿主处理。

> 项目不引入 Material Icons 等图标库。iOS 原生栏用 SF Symbols；Compose 里的图标沿用 `Canvas` 自绘（参考 `TopBarRefreshIcon`、`TopBarSearchIcon`、`TopBarSyncedIcon`）。

---

## 3. 新增一个功能页面：必做清单

新页面（一级入口或二级页）**必须**全部满足下列各项，才算接入了液态玻璃顶栏。参考实现：图书馆 `feature/library/LibraryScreen.kt`、更新日志 `feature/changelog/ChangelogWorkspace.kt`、ChatGPT 插件页 `feature/settings/AiAssistantSettingsScreen.kt`。

1. **路由有原生标题。** 在 `feature/shell/NativeShell.kt` 的 `nativeRouteTitle()` 给新路由返回非空标题。返回空串意味着“这页自己画顶栏”，宿主会隐藏系统导航栏，只有写信这类“返回前要先确认”的页面才允许这样做。
2. **用 `DestinationPage` 包页面，并打开 `scrollUnderTopBar = true`。** 位置在 `AuthenticatedAppShell.kt` 的 `SectionDestination`。
3. **页面主滚动容器用 `TopScrollLazyColumn` 或 `TopScrollColumn`**（`feature/shell/TopScrollContainers.kt`），不要直接用 `LazyColumn` / `verticalScroll`。只有它们会向壳层上报真实滚动偏移（驱动玻璃浓度），并在 iOS 上把滚动物理交给 UIKit。
4. **顶/底留白加在滚动内容的 `contentPadding` 上，不加在布局上：**

   ```kotlin
   val topClearance = LocalTopBarClearance.current
   val bottomClearance = LocalBottomBarClearance.current
   val listState = rememberLazyListState()
   TopScrollLazyColumn(
       state = listState,
       modifier = modifier.desktopTouchScroll(listState),
       contentPadding = PaddingValues(
           start = 16.dp, end = 16.dp,
           top = 16.dp + topClearance,        // 首项让开顶栏，但视口从屏幕顶开始
           bottom = 16.dp + bottomClearance,  // 末项让开底栏/安全区
       ),
       verticalArrangement = Arrangement.spacedBy(12.dp),
   ) { /* … */ }
   ```

   - **禁止** `Modifier.padding(top = …)`、`Spacer(height = 状态栏高度)`、`statusBarsPadding()` 之类把视口顶边压到栏下面的写法。那样栏后只剩一片纯色，玻璃没有内容可折射——这就是“顶部白条”返工。
   - **禁止**在页面里再加 `navigationBarsPadding()` 或固定底部留白；底部净空只用 `LocalBottomBarClearance`。
5. **页头（账户卡、分段、筛选条）作为列表首项随内容滚动**，不要钉在列表上方的固定头部里。固定头部会挡住内容进入栏后，横屏矮视口还会被挤成一条缝。
6. **顶栏动作只用声明式参数**（`refreshable`、`idleStatusText`、`onStatusClick`、`topBarActionLabel` + `onTopBarActionClick`、`search`），**禁止**传 `topBarAction = { … }` 这种 Composable lambda。宿主画不了 Compose lambda，只要传了它，整条顶栏就会退回 Compose 自绘，iOS 液态玻璃顶栏随之消失。
7. **页面不画自己的标题行和返回箭头。** 原生栏已经显示居中标题和系统玻璃返回按钮；页内再写一遍大标题或返回箭头属于重复。
8. **宽屏单独看一遍。** 宽窗口下详情窗格里的 `TopScrollColumn(enabled = false)` 仍须能用滚轮/触控板滚动。

### 例外：内容滚不进栏后的页面

| 情况 | 用法 | 现有例子 |
| --- | --- | --- |
| 固定头挡在列表上方，内容永远到不了栏后 | `staticTopBar = true`（栏保持顶部样子，不做透明→玻璃过渡） | 课表、课件、教室详情、占用详情 |
| 不可纵向滚动的全览表格 | `keepsTopBarInset = true`（整块停在栏下 + 8.dp 呼吸） | 课程表色块概览 |
| 需要把标题栏压矮 | `denseTopBar = true` | 教室查询详情 |

用例外之前先确认：能不能把固定头改成列表首项？大多数情况可以，应优先改成首项。

---

## 4. 顶栏

### 4.1 标题

- iOS：宿主居中显示一个原生标签，21pt 半粗、随动态字体缩放；`prefersLargeTitles = false`，一级页和二级页都用紧凑居中标题。**不要**改回大标题，也不要用 `UINavigationItem.title` 的系统标签显示标题（它会被右侧按钮组挤得左右漂移）。
- Compose 回退顶栏：`headlineSmall` + `FontWeight.Bold`，单行省略；一级页左缘 20.dp，二级页跟在返回箭头后；内容行固定 52.dp（`denseTopBar` 为 40.dp），保证有无状态按钮时标题都在同一高度。
- 标题用具体名词（“图书馆”“作业详情”），不用“详情”“页面”这类泛称。动态标题（选中的教学楼、邮件主题）由壳层回报覆盖静态标题。

### 4.2 玻璃材质

- 页面在顶部时顶栏**透明**，只有标题和按钮；内容滚进栏后，玻璃随滚动距离在 52.dp 内从 0 渐变到 1。
- 浓度直接跟随滚动位置，不另加动画。这样不会滞后于手指、不会过冲，也天然满足“减少动态效果”。
- iOS 顶栏玻璃是宿主的 `NativeNavigationBarGlassView`，色调向页面背景色靠（`appBackgroundUIColor` 40%），不在深色内容上显得像一条亮带。页面不需要也**禁止**自己再画一层渐变、模糊或分隔线。
- 内容不满一屏时上拉回弹，内容也会滑进栏后；`TopScroll*` 容器已把回弹位移算进去，这是不能绕开统一容器的另一个原因。

### 4.3 哪些按钮放顶栏

放进顶栏的只有**导航栏级**信息和动作：

| 类型 | 声明方式 | iOS 位置与样式 | 说明 |
| --- | --- | --- | --- |
| 返回 | 壳层自动 | 左侧，系统玻璃返回按钮 | 页面不画返回箭头 |
| 刷新 | `refreshable = true` | 右侧最靠边，圆形玻璃按钮，SF Symbol `arrow.clockwise` | 项目**不使用下拉刷新**，可刷新页一律用它 |
| 同步状态 | `idleStatusText` + 可选 `onStatusClick` | 刷新按钮左侧，圆形玻璃按钮；已同步 `checkmark.circle`，失败 `exclamationmark.triangle`，其他 `info.circle` | 只有点开能看到详情时才可点；不可点时降为灰色 |
| 忙碌（登录中/同步中） | 壳层根据 `isRefreshing` / 登录状态自动替换 | 与刷新按钮**同一个 32×32 槽位**的原生转圈 | 不另起一行进度条、不改标题位置 |
| 页面级动作（至多一个） | `topBarActionLabel` + `onTopBarActionClick` | “今”“添加到日历”“导出”在左侧，其他动作的位置由宿主决定（可能并入右侧组）；圆形玻璃按钮 | 一个页面只能有一个 |
| 搜索 | `search = PageSearchState` | iOS 26+ 系统 `UISearchController` 搜索按钮，收起时在左侧，展开铺满整条栏 | 输入、展开收起动画全由系统负责 |

**不进顶栏**的：筛选、排序、视图切换、批量操作、“提交”“上传”这类业务动作。它们放在内容区，例如列表首项的筛选 banner、`FilterChip`，或浮动胶囊（第 6 节）。

### 4.4 顶栏按钮的样式规则

- **iOS 一律是系统玻璃按钮。** 由宿主用 `UIBarButtonItem` 生成，系统在 iOS 26+ 自动给出圆形液态玻璃；相邻按钮自动合并为一个玻璃胶囊组。按钮载体固定 32×32，SF Symbol 18pt semibold。
  - **禁止**在宿主里对栏内按钮设置 `sharesBackground = false`：所有按钮都退出共享背景时，UIKit 根本不创建栏背景，整条栏变成全透明（已实机验证）。
  - **禁止**在玻璃按钮里再套胶囊、描边、底色，或用 `UIButton.Configuration.glass()` 自己做玻璃——会变成“玻璃里套玻璃”，文字按钮还会被压成扁胶囊。
  - 文字型动作（如“今”）也用同一个方形载体，系统给它画成圆形玻璃；需要单独成圈时放进独立的 `UIBarButtonItemGroup`。
- **图标由文案决定。** 宿主 `ContentView.swift` 的 `symbolName(for:kind:)` 按 `topBarActionLabel` 关键字选 SF Symbol（“写信”→`square.and.pencil`，含“日历”→`calendar.badge.plus`，含“导出”→`square.and.arrow.up`，其他回落到 `ellipsis.circle`）。新增一种页面动作时，**必须**同时在这里加映射，并确认位置（左/右）符合 4.3；不要让新动作显示成 `ellipsis.circle`。
- **Compose 回退顶栏**（Android、macOS、Windows、iPad 自绘栏）用同一语义的圆形按钮：`Surface(shape = CircleShape, Modifier.size(32.dp))`，底色 `surfaceVariant.accessibleAlpha(0.68f)`（禁用 `0.30f`），图标 16.dp，颜色 `onSurfaceVariant`（禁用再降到 42%）。直接复用 `TopBarRefreshCircle`、`TopBarStatusCircle`、`TopBarSearchCircle`、`TopBarBusyStatusCapsule`，不要另写一套。
- 每个顶栏按钮**必须**有无障碍标签（“刷新”“已同步，点按查看同步详情”“回到今天”）。

---

## 5. 弹层（sheet / 确认框）

- **必须**用 `AppleSheet`（内容型弹层）或 `AppleSheetOrAlert`（确认、提示、短表单），位置 `feature/shell/AppleSheet.kt`。**禁止**在业务页直接用 `ModalBottomSheet`、`AlertDialog`、`Dialog` 或自绘弹层。少数旧页面仍直接调用 `AlertDialog`，那是历史遗留，不要照抄，后续统一整改。
- iOS 上弹层是真正的 `UISheetPresentationController`：圆角外壳、grabber、detent、玻璃背景、下滑关闭全由系统提供。Compose 只提供正文；**禁止**在正文里再画标题行、关闭按钮、确认按钮或外层圆角卡片（“框里套框”）。
- **标题与按钮放在弹层的原生导航栏里：** 标题居中；确认动作（`confirmLabel`）在左侧，用 Done 样式；“取消”或文字关闭在右侧最靠边；没有 `dismissLabel` 时右侧是系统圆形关闭按钮（X）；“今天”紧挨在“取消”左边。只有确实有事可做时才给确认按钮（例如同步面板有失败项才显示“重试”），不要在系统 X 旁边再放一个“关闭”。
- **高度：**
  - 正文较短、按内容定高的（详情、二维码、确认、同步状态、添加到日历）：`fitContentHeight = true`。
  - 筛选器、周数选择、选课列表、带提交/上传的长表单：`needsFullHeight = true`。
  - 两种模式下**禁止**在 content 里再套 `verticalScroll`，滚动和底部安全区由 sheet 负责。
- 弹层是独立的 Compose 根：主题已由 `AppleSheet` 继承，**不要**在正文里重新包 `MaterialTheme` 或写死颜色。
- 发起网络请求的弹层：用页面（宿主）级 scope 启动请求，再关闭弹层。弹层内 `rememberCoroutineScope()` 启动的协程会随弹层销毁被取消，曾导致“一直同步中”。

---

## 6. 按钮与控件

按位置和用途选控件，不要按“哪个好看”选：

| 位置 / 用途 | 控件 | 形状 |
| --- | --- | --- |
| 顶栏 | 见第 4 节，系统玻璃圆形按钮 / Compose 圆形 32.dp | 圆形 |
| 弹层标题栏 | `AppleSheet` 的 `confirmLabel` / `dismissLabel` / `todayLabel` | 系统决定 |
| 内容区主操作（每屏至多一个） | `Button`（实心，`primary`） | Material 默认全圆角 |
| 内容区次操作 | `FilledTonalButton` 或 `OutlinedButton` | 同上 |
| 低强调、内联链接式操作 | `TextButton` | 同上 |
| 筛选、类别、多选标签 | `FilterChip`；类别色只来自 `CourseCategoryPalette` | 胶囊 |
| 二到三选一 | 分段控件（参考设置页更新源“Gitee｜自动选择｜GitHub”滑块、成绩单中/英分段） | 胶囊 |
| 开关型设置 | `Switch` | — |
| 选中项需要持续操作（批量续借、批量导出） | **浮动胶囊操作条**：浮在列表上方，内容在它后面继续滚动 | `RoundedCornerShape(percent = 50)` |

浮动胶囊操作条参考 `LibraryRenewBar`：`surfaceContainerHigh` 底色、0.5.dp `outlineVariant` 发丝描边、`shadowElevation = 8.dp`、离底 10.dp 加真实底部安全区，列表末尾按胶囊实测高度让位；没有可操作项时整条隐藏。**禁止**用铺满底部的 Material `BottomAppBar` 或实心底栏，它会在底部安全区留下一条黑/白带。

通用要求：

- 点击区域不小于 44pt / 48.dp。视觉上 32.dp 的圆形按钮，周围要留出足够的可点区域。
- 按下立即有反馈（Material ripple / 系统高亮），不要等请求返回才变化。耗时动作在按钮内显示进行中（转圈 + “正在续借…”）并禁用重复点击。
- 破坏性、不可撤销的动作（删除、清除离线数据、提交作业）先用 `AppleSheetOrAlert` 确认，按钮文字写明后果。可撤销的动作（标记完成、忽略）不弹确认，给撤销条（参考 `AssignmentIgnoreUndoBar`）。

---

## 7. 底栏与侧栏

- 紧凑端一级入口在底栏：iOS 是原生 `UITabBar`（SF Symbols，`AppTabBarController.symbolNames`），Android/桌面是 `CompactBottomNavigation`。两者的入口来源都是 `bottomNavSections()`，新增一级入口只改这一处，再在 iOS 的 `symbolNames` 补图标。
- 二级页（push 进来的页面）隐藏底栏；页面只消费 `LocalBottomBarClearance`，不自己处理。
- 宽屏用 `AppSidebar`，图标与底栏一致。

---

## 8. 版面、排版与形状

- **字体**：只用系统字体和 `MaterialTheme.typography`，不引入自定义字体。层级靠字号 + 字重 + 行高一起建立，强调优先加字重。**禁止**用固定高度截断文字，大字号下内容必须能换行或伸长。
- **页面左右边距**：紧凑端 16.dp，宽屏 24.dp。
- **间距**：从 4 / 6 / 8 / 10 / 12 / 14 / 16 dp 中选。列表项之间 10–12.dp，卡片内行与行之间 6–8.dp。不要发明 7、9、13 这类新值。
- **圆角**：卡片 14–16.dp，卡片内的小块/行 10–12.dp，胶囊和标签用 `RoundedCornerShape(percent = 50)` 或 999.dp，按钮用 Material 默认。新代码只从这几档里选；现存的 17/18/22.dp 等是历史值，不要扩散，后续统一整改。
- **卡片**：`Card` / `ElevatedCard`，底色 `surface` 或 `surfaceContainer*`。卡片里不要再套一层带底色的圆角 `Surface`。
- **分隔**：用间距和卡片分组，不用 1px 分隔线；顶栏下方**禁止**画分隔线（玻璃材质本身就是边界）。

---

## 9. 颜色

- 只用 `MaterialTheme.colorScheme`。品牌色在各端 `ThemePlatform.*.kt`（iOS/macOS 固定品牌蓝，Android 可选 Material You 动态取色）。**禁止**在页面里写 `Color(0xFF…)` 表达通用语义。
- 课程类别色（必修/限选/任选/体育/未知/实验/考试）**只**来自 `feature/course/CourseCategoryPalette.kt`，课表、成绩、首页、小组件共用。成绩页 `GradeTypeColors.kt` 目前还有一份重复色值、限选色也与调色板不一致，属遗留；页面里散落的语义色同样待收拢。新代码不要再复制色值。
- 有利变化标绿（截止或折扣时间延后、成绩变好），使用首页变动弹层已有的那组绿色；新增“利好”语义时复用 `HomeChangeField.isFavorableChange()` 的判断与配色，不另起颜色。错误用 `colorScheme.error`。
- 浅色、深色都要看。半透明底色一律经过 `accessibleAlpha()`，“减少透明度”打开时自动变成实色。
- 文字在玻璃或半透明表面上时，用高对比的 `onSurface`，不要用浅灰小字。

---

## 10. 动效

- 默认临界阻尼、无过冲：`spring(dampingRatio = Spring.DampingRatioNoBouncy, …)`，响应约 0.3–0.4 秒。普通出现、消失、展开**不弹跳**；只有带动量的拖拽释放才允许轻微弹性。
- 进入和退出走同一条路径；弹层、菜单从触发它的位置出现。
- 玻璃浓度、拖动这类跟手的效果直接跟随输入，不加时长动画。
- “减少动态效果”（`LocalReduceMotion`）：位移、缩放、弹簧改为 120–180ms 的淡入淡出（壳层已提供 `ReducedMotionScheme`）。新写的位移动画必须检查这个开关。
- 列表切换排序、切换筛选后回到顶部；异步结果不能覆盖用户刚刚手动做的选择（例如手动选的周）。

---

## 11. 状态与反馈

- **同步状态在顶栏**（4.3），不要在内容区另画“同步中…”横条。部分失败时顶栏显示“部分同步失败”，点开看明细；已经成功的数据和缓存**保留显示**，不要清空。
- **加载**：首次无缓存时在内容区居中显示进度；有缓存时先显示缓存，顶栏转圈。
- **空状态**：一句说明 + 必要时一个操作（例如“去设置开启”），居中，不放大插图。
- **错误**：就地说明原因和下一步（“需要校园网”“账号未登录，去设置”），可重试的给“重试”。不要只用 toast 或只写错误码。
- **校园网限定数据源**（物理实验、演示实验、物理在线）失败时，明确写“需要校园网”。
- 涉及授权的旧 HTTP 页面保留不可关闭的风险提示（见 `AGENTS.md` 安全红线），提示作为列表首项随内容滚动。

---

## 12. 无障碍

- 支持动态字体 / 文字放大到 1.5 倍以上不截断、不重叠。
- 所有只有图标的按钮都有 `contentDescription` / `accessibilityLabel`。
- “减少透明度”：经由 `accessibleAlpha()` 变实色；“减少动态效果”：见第 10 节；“增强对比度”：`platformColorScheme(increasedContrast = …)` 已处理，新颜色不要绕开它。
- macOS / Windows：可键盘操作（Tab 焦点、Esc 关闭搜索和弹层）、悬停反馈、滚轮和触控板滚动、触屏拖动（`desktopTouchScroll`）。

---

## 13. 禁止清单（每一条都对应过一次返工）

| 禁止 | 原因 |
| --- | --- |
| 新页面不开 `scrollUnderTopBar`，或用 `padding(top)` / `Spacer` 让开顶栏 | 顶栏下出现纯色白条，玻璃没有内容可折射 |
| 用 `topBarAction = { Composable }` 往顶栏放按钮 | 宿主画不了，整条顶栏退回 Compose 自绘，iOS 失去液态玻璃 |
| 页面主列表直接用 `LazyColumn` / `verticalScroll` | 玻璃浓度不跟滚动、回弹时栏后没有玻璃、iOS 滚动物理不一致 |
| 在 Compose 里画顶栏渐变、模糊、分隔线，或仿玻璃按钮 | 与系统玻璃叠成两层，深色下发灰发亮 |
| 栏内按钮 `sharesBackground = false`，或玻璃按钮里再套胶囊 | 栏背景整个消失 / 玻璃套玻璃 |
| 下拉刷新（Material PTR、`UIRefreshControl`） | 与系统过滚抢手势、误触，已整体移除；刷新用顶栏圆形按钮 |
| 业务页直接用 `ModalBottomSheet` / `AlertDialog` | iOS 上背景与文字分层错位、全高顶状态栏、无法下滑关闭 |
| sheet 正文里画标题、关闭按钮、外层圆角卡片 | 与系统 sheet 外壳重复，变成框中框 |
| sheet 正文再套 `verticalScroll` | 高度测量错误，sheet 越长越高 |
| 铺满底部的实心操作栏 | 底部安全区出现黑/白带；改用浮动胶囊 |
| 固定在列表上方的页头 | 内容进不了栏后；横屏视口被挤窄 |
| 页面里自己画大标题或返回箭头（原生栏已接管时） | 标题和返回重复出现 |
| 新的硬编码颜色、课程色、圆角、间距值 | 各页风格漂移；类别色必须来自 `CourseCategoryPalette` |
| 弹层内的 `rememberCoroutineScope()` 发起请求后立即关闭弹层 | 协程被取消，状态卡在“同步中” |

---

## 14. 提交前自查（PR 描述里逐项回答）

涉及界面的 PR，在描述里复制下面的清单并逐项勾选；做不到的写明原因。

- [ ] 新页面：`nativeRouteTitle` 有标题；`DestinationPage(scrollUnderTopBar = true)`；主滚动容器是 `TopScrollLazyColumn` / `TopScrollColumn`；顶/底留白来自 `LocalTopBarClearance` / `LocalBottomBarClearance` 并加在 `contentPadding` 上。
- [ ] 顶栏只用声明式动作；新增页面动作已在 `ContentView.swift` 的 `symbolName(for:kind:)` 配好 SF Symbol 和左右位置。
- [ ] 弹层全部走 `AppleSheet` / `AppleSheetOrAlert`，并选对了 `fitContentHeight` / `needsFullHeight`。
- [ ] 没有新增硬编码颜色、图标库、下拉刷新、实心底栏、Compose 仿玻璃。
- [ ] iOS 模拟器实际打开过页面：顶部时栏透明；内容上滑后出现玻璃；内容不满一屏上拉回弹时也有玻璃；栏内按钮是系统圆形玻璃。
- [ ] 浅色 / 深色、加载 / 空 / 错误 / 正常、文字放大都看过；Android 与 macOS 至少各看一次窄窗口和宽窗口。
- [ ] 截图已避开学号、姓名、成绩、邮件等个人信息，未脱敏原图只放在 `.artifacts/`。

编译通过不等于设计验收通过。涉及外观的改动，没有模拟器或真机实际观察的，在结论里写明“未目视验证”。

---

## 15. 参考实现索引

| 主题 | 文件 |
| --- | --- |
| 页面骨架、顶栏声明、玻璃浓度计算 | `multiplatform/shared/src/commonMain/.../feature/shell/AuthenticatedAppShell.kt`（`DestinationPage`） |
| 原生栏数据桥、路由标题 | `.../feature/shell/NativeShell.kt`（`NativeBarAction`、`nativeRouteTitle`） |
| 统一滚动容器 | `.../feature/shell/TopScrollContainers.kt` |
| 顶/底净空 | `.../feature/shell/TopBarClearance.kt`、`BottomBarClearance.kt` |
| Compose 回退顶栏与圆形按钮 | `.../feature/shell/CompactAppTopBar.kt` |
| 弹层 | `.../feature/shell/AppleSheet.kt`、`shared/src/iosMain/cinterop/UIKitSheetBridge.h` |
| 页内搜索 | `.../feature/shell/PageSearch.kt` |
| iOS 宿主：栏内按钮、SF Symbol 映射、顶栏玻璃、原生滚动 | `multiplatform/iosApp/iosApp/ContentView.swift` |
| 无障碍开关与减弱动效 | `.../shared/AccessibilityPresentation.kt` |
| 课程类别色 | `.../feature/course/CourseCategoryPalette.kt` |
| 接入示例：浮动胶囊、页头随列表滚动 | `.../feature/library/LibraryScreen.kt` |
| 接入示例：最简玻璃顶栏页 | `.../feature/changelog/ChangelogWorkspace.kt` |
| 历史决策与实机证据 | `history_full.md`（检索“M17 Liquid”“顶栏玻璃”“下拉刷新”），`docs/migration/m17-apple-liquid-glass-shell-plan.md`（其中 2026-09-19 条目是实验记录，以 2026-09-20 更正为准） |
