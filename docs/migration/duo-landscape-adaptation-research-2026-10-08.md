# iPhone 横屏与 iPhone Duo 适配调研

日期：2026-10-08。状态：调研与实施建议，尚未做 iOS 编译、模拟器截图或真机验证。

## 1. 本轮范围与基线

- 已 fetch `origin`，本地 `codex/CITEL` 快进到远端最新 `3fc53da24b84ca6b62bbc792e9da1d3ccb902ca7`。
- 从该提交创建本地 `codex/Duo`，用于后续适配工作。本轮只新增本文，不修改应用实现。
- 当前环境为 Windows；Mac 上的 Xcode、SDK、Duo 模拟器版本尚未检查。
- 用户初步试跑反馈：横屏课表过度压缩，部分 sheet 无法滚动；Duo 底栏未适配侧边布局，内容发生冲突，部分二级页返回入口消失。这些现象作为待复现问题，不能当作本轮已验证结果。

**初步结论：需要同时处理原生导航容器、四边安全区、内容可用高度和导航栈归属。只加一个 Duo 宽度断点，解决不了这些问题。**

## 2. 官方文档和样例提供了什么

### 2.1 SDK 与布局能力

Apple 的 [Prepare your app for iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111461/) 区分了构建 SDK 对屏幕利用和栏布局的影响；完整侧边标准栏行为涉及 iOS 27.1 SDK。建议后续在 Mac 上先记录 Xcode、构建 SDK 与模拟器系统版本，再比较界面。

当前工程部署目标是 iOS 16.0，`SDKROOT = iphoneos`；这不能证明上次安装包使用了哪个 SDK。升级构建 SDK 与提高最低系统版本是两件事，后续新 API 要加可用性判断，保留旧系统路径。

布局应读取当前宿主视图尺寸、trait 和安全区；避免按“是 iPhone 就一定窄屏”判断。Duo 的展开内屏与外屏不是同一种布局环境，内屏也不能靠支持方向列表限制布局。相关官方样例位于上述视频 Code 页的 2:59、5:44、6:52。

### 2.2 两种“侧栏”必须分开

| 概念 | 用途 | 本项目的对应处理 |
| --- | --- | --- |
| 系统侧边栏区域中的竖向 tab / navigation / toolbar 控件 | 保留内容高度，随设备姿态由系统安排 | 原生 UIKit 导航容器负责；不能把独立底栏旋转一下就算完成 |
| 展开内屏的应用导航 sidebar | 显示首页、课程表、成绩等一级入口 | 使用原生 tab sidebar，或明确由 Compose sidebar 接管，避免两套入口同时出现 |

Apple 的 [Raise the bar with iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111462/) 明确指出：标准容器提供的栏参与适配；自行创建的 `UITabBar`、`UINavigationBar`、`UIToolbar` 子组件不参与这种自动迁移。它还展示了竖向栏中的返回、关闭、图标项和自定义视图安排方式。

展开内屏可以选择 sidebar；[Prepare](https://developer.apple.com/videos/play/tech-talks/111461/) 的 5:44 UIKit 样例使用 `tabBarController.sidebar.preferredPlacement = .sidebar`。这与旧的 iPad sidebar 模式有关联，但不等同于所有 Duo 姿态都强制 sidebar。

对照样例：[Elevate your tab and sidebar experience in iPadOS](https://developer.apple.com/videos/play/wwdc2024/10147/) 的 7:16 展示 `UITabBarController`、`UITab`、`UITabGroup` 与 `.tabSidebar` 的组合。它是理解容器结构的参考，Duo 的实际姿态行为还要按新 SDK 样例确认。

### 2.3 安全区与折叠区域

Duo 的安全区可能不对称，顶部/底部两个数不足以表达内容可用区。[Prepare](https://developer.apple.com/videos/play/tech-talks/111461/) 建议前景内容避让安全区，背景可延伸到边缘。

[Strike a pose with adaptive layouts on iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111463/) 的 7:03、8:07 演示 UIKit reserved regions：division 表示分隔区域，occlusion 表示遮挡区域。它们不是普通的四边 padding；自绘课表若跨越中间折叠区域，还需要单独考虑可读性。

该视频的 11:26 展示 `UIArrangementViewController` 的主/次内容布局。可用于后续“课表 + 课程详情”探索，但第一轮不必先引入双面板；先完成导航、安全区和滚动修复。

## 3. 源码检查：问题与可能原因

以下路径均相对仓库根目录；行号以本轮基线为准，后续修改会变化。

### 3.1 iOS 底栏不会自动迁移到侧边——源码已确认

入口：`multiplatform/iosApp/iosApp/ContentView.swift`。

- `AppTabBarController`（约 937 行）实际继承 `UIViewController`，手动创建并添加 `UITabBar`，不是 `UITabBarController`。
- `viewDidLayoutSubviews`（约 1090 行）始终按底部横向条带计算 frame，栏高至少 80 点。
- 活跃内容视图 frame 设置为整个 `view.bounds`。
- 宿主向共享层回报 `glassTabBarBottomInsetDp` 和 `systemBottomInsetDp`，没有对应的左右栏占位信息。

因此，底栏留在底部有直接代码依据。它与新系统侧边区域叠加后的具体遮挡范围，需要模拟器观察，不能仅凭这一段代码断言。

另外，原生标题/玻璃层也有顶部横栏假设：同文件约 833 行按 `navigationBar.frame.maxY` 写入 `glassTopBarInsetDp`，玻璃覆盖层仍从顶部开始布局；居中标题是手动加到 `navigationBar` 的视图。即使换成系统 tab 容器，这些自定义 chrome 也需要一起检查。

### 3.2 宽窗口逻辑和原生 tab 宿主可能互相矛盾——待验证

入口：

- `multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/LandingContent.kt:22`
- 同目录 `App.kt:186`
- 同目录 `feature/shell/AuthenticatedAppShell.kt`

现有 `WindowClass` 只看宽度：小于 600 为 Compact，600～899 为 Medium，900 起为 Expanded；大字体另有降级。没有高度维度。

共享壳在 Expanded 时停止原生二级 push，打算在 Compose 壳内切换内容；但约 1987 行首先判断 `forcedRouteId != null`，此时强制按 `expanded = false` 渲染目的地，跳过 Compose sidebar。原生 tab 根又通过 forced route 创建页面。

这是一个值得优先记录的分支组合：宽度已判定为 Expanded，实际宿主仍是 forced-route 单页。不能假定展开 Duo 后现有 Compose sidebar 一定会出现，也不能仅调整 900 的断点解决。

### 3.3 二级返回按钮消失——存在高优先级假设

`AuthenticatedAppShell.kt` 的 `DestinationPage` 在 `nativeTitleBarActive` 为真时，不给 Compose 顶栏设置返回回调（约 1230 行），把返回责任交给原生导航。

但 Expanded 时 `useNativeSecondaryRoutes` 为假；如果页面在共享层内部入栈，原生 `UINavigationController` 可能仍只有一个根控制器，没有可显示的系统返回按钮。这与上述 forced-route 分支结合，可能形成“两边都认为对方负责返回”的状态。

后续应记录并对照：`windowClass`、`forcedRouteId`、`nativeTabBarEnabled`、`isPushedHostDestination`、`nativeTitleBarActive`、Compose 栈深、UIKit 栈深。特别检查从“更多”进入设置、邮件列表进入详情、作业进入详情、CITEL 账号设置。

同时检查系统返回按钮是否已移动到侧边、被自绘标题或玻璃层遮挡，以及点按和边缘手势是否有效。当前没有模拟器证据，以上仍是待验证原因。

### 3.4 横屏课表被压缩——源码已确认高度风险

入口：`feature/course/CourseScheduleScreen.kt`。

- `WeekGrid` 约 819 行、`CompactWeekGrid` 约 1496 行均用 7 个 `Row(weight(1f))` 分配剩余高度。
- 日标题高度固定为 42 / 38 dp；周视图 pager 约 1383 行占剩余空间，网格又填满 pager。
- 课程卡还按格子高度计算事件高度。

可用高度减少时，7 行一起收缩，没有最低可读行高。固定底栏、顶部栏和课表控制区进一步消耗高度；只看宽度还可能把手机横屏误当作适合完整宽屏工作区的环境。

建议保留周视图，给时间行最低高度，让网格纵向滚动；顶部日期/周数控制保持可达。初始可试 56～72 dp 的行高，但这是设计起点，不是官方要求，需用长课程名、地点和多事件格子验证。不要通过继续减小字体解决。

竖向滚动 viewport 必须有有限高度，内部网格使用明确内容高度；不能简单在现有 `weight` 布局外套一个无限高度滚动容器。横向翻周与纵向滚动、边缘返回的手势冲突也要检查。

### 3.5 Sheet 不滚动——现有路径不一致，待逐个复现

入口：

- `feature/shell/AppleSheet.kt`
- `multiplatform/shared/src/iosMain/kotlin/team/bjtuss/bjtuselfservice/shared/IosNativeSheetPresenter.kt`
- `multiplatform/shared/src/iosMain/cinterop/UIKitSheetBridge.h`

已确认：

1. `AppleSheet` 是独立 Compose 根。`scrollableBody` 默认 false；它决定填满宿主高度、顶部留白和底部安全区处理，但不会自动给 body 安装滚动。
2. `needsFullHeight` 选择大 detent 和填高，同样不等于正文可滚动。
3. bridge 给有原生标题的正文附加固定 44 点 top safe-area；非 scrollable body 的共享 wrapper 又有 44 dp 顶部 padding。存在重复留白或错误方向占位风险，需要测量实际结果。
4. bridge 默认配置 medium / large detents，并开启 `prefersScrollingExpandsWhenScrolledToEdge`。Compose 的滚动不能直接假设等同于 UIKit `UIScrollView` 的 sheet 协调。
5. 课程详情 sheet（约 318 行）内部已有 `verticalScroll`，但未声明 `scrollableBody = true`；“前往日期”（约 637 行）body 是非滚动 Column；课表/周数选择器也有单独的 full-height 路径。

所以不建议统一加 `verticalScroll` 或统一关闭 sheet 手势。先按课程详情、日期选择、周数选择、筛选、同步详情、日历导出分别检查 body 约束与滚动归属。

参考：[Customizing and resizing sheets in UIKit](https://developer.apple.com/documentation/uikit/customizing-and-resizing-sheets-in-uikit) 提供原生 sheet 样例；[prefersScrollingExpandsWhenScrolledToEdge](https://developer.apple.com/documentation/uikit/uisheetpresentationcontroller/prefersscrollingexpandswhenscrolledtoedge) 说明滚动扩展开关的作用。它不是修复所有正文滚动问题的开关。

## 4. 推荐实施方向

### P0：先做最小原生容器探针

在 Mac 上用同一份 Compose 页面比较现有自建 tab 宿主与真实 `UITabBarController`，每个 tab 包含自己的 `UINavigationController`。先验证系统侧边栏、展开内屏 sidebar、二级返回和四边安全区。

建议优先采用真实 UIKit 容器承载现有 Compose 内容：不用重写业务页面，同时能让系统负责栏的轴向和容器布局。需要迁移 tab 选择、动态入口、每个 tab 的返回栈与生命周期。现有“二级页面盖住底栏”和 tab-bar borrowing 转场依赖旧视图层级，不应原样搬过去；先验证系统行为能否满足需求，再决定保留哪些视觉定制。

一级入口较多时，还要验证溢出、顺序、隐藏/显示功能开关及“更多”的语义；不能为了保留当前所有底栏项而再次绕回独立 `UITabBar`。

### P0：明确导航和安全区的唯一负责人

- 某条路线由 UIKit push 时，UIKit 提供返回；由 Compose 内部入栈时，Compose 提供返回。按实际栈归属判断，不按页面名称或宽度猜测。
- 如果旋转/展开改变承载方式，保留当前页面和可返回历史，避免两个栈重复 push 或历史丢失。
- 前景内容使用宿主当前可用矩形，背景可全出血。四边 inset 分别处理，避免旋转后 left/right 仍被当成 top/bottom。
- 优先使用系统容器和 `safeAreaLayoutGuide`；确需桥接时传递完整几何信息，并明确 UIKit 与 Compose 谁消费哪部分 inset，防止重复 padding。
- 在布局、安全区、trait 变化时刷新几何，不缓存一次屏幕尺寸，不用固定 Duo 像素值。

### P1：内容布局同时看宽度与高度

保持跨平台的内容布局策略，但将“是否适合侧栏”和“课表是否需要滚动”分开计算。以实际内容区为输入，加入低高度模式，避免宽而矮的手机横屏被当成完整桌面布局。

| 环境 | 初步内容策略 |
| --- | --- |
| 普通 iPhone 竖屏 / Duo 外屏 | 单栏；由系统安排导航栏和 tab；周课表不低于可读行高 |
| 普通 iPhone 横屏 | 压缩控制区的占高；周课表纵向滚动，不强制塞进一屏 |
| Duo 展开内屏 | 根据实际内容宽度选择导航 sidebar、周课表或可选详情区 |
| Duo 部分折叠 / 小窗口 | 先保证当前内容、操作、返回可达；关键卡片和按钮避开 division 区域 |

### P1：统一 Sheet 内容约定

逐个规定：原生标题和按钮由谁管理、body viewport 是否有界、滚动容器是谁、底部内容 inset 谁消费、键盘出现时如何保持最后一项和确认动作可达。

替换固定 44 点顶部假设时，使用当前栏/安全区几何。长正文用有界 `LazyColumn` 或 `verticalScroll`；原生日期选择器单独检查最小高度和紧凑表现。只有复现手势争用后，再比较开启/关闭滚动扩展的效果。

### P2：折叠姿态的增强布局

基于 reserved regions 探索“课表 + 详情”、列表/详情分区。不要把七天课表机械拆成固定左右两半；日期连续性、当前选中课程和折叠后的阅读顺序需要先设计。基础导航和滚动通过后再做。

## 5. Mac 上的验证计划

先记下 `xcodebuild -version`、实际 SDK、Simulator runtime、构建提交。使用 Xcode Device Hub / Simulator 的 Duo 姿态控制；不预先写死设备 UUID。

| 场景 | 重点验证 |
| --- | --- |
| iPhone 17 Pro 竖屏 → 左横屏 → 右横屏 → 竖屏 | 课表最后一行可达、课程详情可滚、两侧安全区正确 |
| 小屏 iPhone SE 竖横屏 | 最低可用高度；长标题/筛选动作不会挡住正文 |
| Duo 外屏竖横屏 | 标准栏正确定位，内容避让侧边，返回/关闭始终可用 |
| Duo 展开内屏竖横屏 | 一级导航不重复，sidebar 与正文不重叠，详情能返回 |
| Duo 打开详情后展开、合上、旋转 | 当前路由、栈历史、选中 tab、滚动位置保持合理 |
| Duo 部分折叠与窗口调整 | 关键操作避开折叠区域；最末内容仍可滚到 |
| Sheet 打开后旋转、展开/合上、弹出键盘 | 正文重新布局；标题、关闭、确认和底部输入可达 |
| 深色、增大字号、减少透明度 | 侧边项与正文可读，课表不靠缩小文字兜底 |
| iPad 现有布局、旧系统 iPhone | 原有导航和兼容路径没有回归 |

每组至少走：首页 → 课程表 → 课程详情；更多 → 设置 → 账号设置；作业/聚合作业 → 详情；邮箱 → 详情 → 回复；CITEL → 详情/设置。

Sheet 清单至少包含：课程详情、课表/周数、前往日期、成绩筛选/详情、作业筛选、同步详情、日历导出。用长文本和足够多的项目实际滑到最后，不能只看 sheet 能否打开。

截图应同时保留整个模拟器画面和关键控件区域，记录姿态、页面、字号、SDK/runtime；去除账号和业务敏感信息。正文用 Compose 绘制，现有工程还隐藏了 Compose 无障碍子树，不能把原生 UI 查询结果当作完整页面验证；要人工目视与手势检查。

## 6. 验收标准与当前未确认项

### 2026-10-08 补充：普通 iPhone 横屏灵动岛遮挡

用户进一步报告：正常 iPhone 横屏也会被灵动岛遮住，疑似内容铺得太宽；课程表压缩和“同步状态”sheet 无法上下滑动仍存在。本项只记录，暂不在 Windows 修改 iOS 横屏实现。

优先假设是横屏左右安全区未正确消费，而非简单的内容宽度过大。现有宿主将内容 frame 设置为完整 bounds，桥接主要传顶部与底部 inset；后续在 Mac 上对比左右横屏的 `safeAreaInsets.left/right`、原生栏占位和 Compose 内容边界，确认是不是遗漏或重复消费。不要用硬编码缩窄全页修复；背景可以铺满，正文、课程格子和按钮必须避开灵动岛。

补充复现：普通 iPhone 在横屏时打开“同步状态”，尝试从正文中央上滑、下滑到最后一条，区别正文滚动与拖拽 sheet；旋转前后重复一次。课表检查全部七个时间段、长课程名和地点，并同时检查灵动岛侧的点击区域。

验收：课表保持可读并能到达最后一行；所有长 sheet 能滚到末尾；每条二级路线有可操作的返回入口；侧边栏与内容无冲突；姿态切换不丢当前路由、不重复导航、不产生双份留白。

尚未确认：上次试跑安装包的构建 SDK、哪些 sheet 必现、Duo 各姿态的实际 inset、返回问题是栈归属还是遮挡、当前 Compose beta 对新 SDK 的 inset/interop 表现。

本轮没有改 Swift/Kotlin 实现，没有运行 iOS 构建或模拟器，也没有把建议阈值视为已验证设计。下一步是在 Mac 上复现并做最小原生容器探针，再按 P0 → P1 → P2 修改。
