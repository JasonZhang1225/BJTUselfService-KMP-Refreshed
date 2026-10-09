# Duo 与普通 iPhone 第二轮适配

**分支状态：Duo 和横屏基本适配完成（2026-10-09）。** 导航、安全区、横屏课表分栏、sheet、图例及统一单列表的原生收起已处理。首页、设置和作业列表／详情已有实际手势反馈；其他页面的验收范围与限制见下文。

对应用户实测反馈的九项问题。基线为 codex/Duo 的 1df7e7a；上轮构建通过并不代表这些产品体验已经通过。

## 导航设计依据

Apple 的 [Prepare your app for iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111461/) 明确将 sidebar 描述为可选导航；标准 tab 默认会根据形态改变轴向。此应用的主入口数量少，已有设置页负责配置入口，因此本轮恢复系统默认 tab，不强制左侧 sidebar，也不展示无可编辑内容的“编辑”按钮。

[Raise the bar with iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111462/) 说明外屏和展开横屏会使用竖向动作栏，展开竖屏恢复横向栏。相关动作需要保持分组、提供图标及文字，文字专用按钮或复杂自定义 view 可能留在横向栏。回到今天使用 22 点 semibold 的“今”字按钮，独立为一个原生圆形玻璃动作组；刷新／进度、状态仍共用另一组；自定义 spinner 声明支持竖向布局。

[UINavigationItem.navigator](https://developer.apple.com/documentation/uikit/uinavigationitem/itemstyle/navigator) 对应传统导航的小标题居中，browser 对应左对齐。本轮最初将 browser 无差别用于普通 iPhone，这是范围过大的改动，最终已纠正：读取 SDK 的 UITraitCollection.verticalBarEdge，只有系统采用竖向栏时使用左对齐；普通 iPhone 和 Duo 展开竖屏采用居中标题。横向栏用已有的动态 21 点原生 UILabel 保持居中，避免三个动作将系统标题挤左；Duo 竖向栏使用动态 20 点原生系统标题。横向课表的导出放在左侧独立玻璃按钮，状态与刷新在右侧同组，标题与按钮留有间距；竖向栏仍将三个动作放在同一侧组。

Apple 的 Duo 视频说明竖向系统栏默认没有 scroll-edge 效果；HIG 也明确只有滚动内容经过悬浮控件时才使用该效果。iOS 27 提供 `UINavigationItem.navigationBarMinimization` 和 `.onScrollDown`，并允许应用选择安全区是否随栏变化。当前 Compose 滚动内容不是 UIKit 的 `UIScrollView`。两次错误实现已经撤回：先用 `setNavigationBarHidden` 模拟收起，又把 Compose 套进另一个 `UIScrollView` 并灌入按列表索引估算的偏移。前者移除返回／动作并改变安全区，后者让两套滚动和布局控制同时工作；这都不能当作原生 minimization 的正确接入。

23:11 的直接承载回退先停止了闪动；下文保留当时的限制与记录。最新实现已在首页和设置页接入一个真正负责纵向手势的 `UIScrollView`，并关闭对应 Compose 容器的纵向手势与回弹。UIKit 消费真实手势，Compose 仅按实际消费距离更新内容；固定渲染视口不因收起改变尺寸，不再按列表索引估算偏移。嵌套内容与 sheet 不继承主滚动控制器，横屏课表两侧继续独立滚动。

依据 [UIKit 原生 minimization 示例](https://developer.apple.com/videos/play/wwdc2026/278/) 与 [setContentScrollView(_:for:)](https://developer.apple.com/documentation/uikit/uiviewcontroller/setcontentscrollview(_:for:))，向 UIKit 登记实际滚动容器，竖向栏配置 `.onScrollDown`，安全区调整设为 `.disabled` 以稳定正文几何；其他形态保持 `.automatic`。标题收起、恢复和滚动边缘材质均由系统实现，没有自定义阈值、标题动画或模糊动画。配置仅在变化时写入，避免每次布局重置系统动画。“今”回顶时同步实际滚动位置。

## 修正与验收范围

| 反馈 | 本轮处理 | 验收依据 |
| --- | --- | --- |
| 1 全形态底部白条 | 渲染表面延伸到底部，底部避让只进入滚动末尾或固定网格内容，避免额外 UIKit 空白带 | Duo 外屏竖／横、内屏竖／横和 Pro 竖／横已查看；最终 Pro 与 Duo 竖屏截图已保存 |
| 2 小屏首页动作混乱 | 相关动作放同一 trailing group；回到今天用“今”字按钮，spinner 支持竖向轴 | Duo 27.1 展开横屏中“今／状态／spinner”在右侧动作栏；翻到非本周后出现，点按后回到本周并隐藏 |
| 3 小屏标题过小 | 动态 20／21 点 semibold；竖向栏左对齐、横向栏居中 | 最终 Pro 首页／课表及 Duo 展开竖屏应用／课表居中；Duo 展开横屏仍左对齐；共用导航控制器覆盖一级页和 push 页 |
| 4 常规课表不完整、按钮换行和日期弹窗 | 常规高度七行适配；单行简短标签；日期弹窗大 detent、自有滚动，内嵌日历按自然高度测量 | Duo 日期 31 日可见、可选且确认跳转成功；Pro 短横屏弹窗实际滚到 31 日、选择并确认跳转成功 |
| 5 Duo 小屏文字 | 模式标签 13 sp、单行；保留点击展开课程、每日列表语义 | Duo 外屏竖屏按钮单行、七节完整；Pro 最终横屏按钮单行 |
| 6 横屏课表布局（用户补充） | 左侧为课表／周次、日期、模式、星期和图例，右侧为周课表或课程列表；两侧独立滚动，右侧尽量展示七节 | Pro 横屏周表七节完整；左栏可独立滚到最后提示，右栏课程列表可独立滚到第七节且不被底栏遮挡；Duo 外屏横屏与展开横屏均七节完整，展开横屏翻周同步左侧周次 |
| 7 侧栏编辑与竖屏导航 | 恢复系统默认 tab，无强制 sidebar 的编辑入口 | Duo 内屏竖屏底部 tab、横屏竖向 tab 已查看，无侧栏编辑入口 |
| 8 充值弹窗偏心（用户截图澄清） | sheet 按自身 UIKit 安全区约束；完美校园说明文字居中对齐 | 校园网二维码在 Duo 展开横屏中居中；用户 18:27 截图明确指向完美校园确认弹窗文字，调整后各行在弹窗内居中 |
| 9 页面高度跳变 | 全高渲染表面和固定标题样式；底栏净空不再改变 Compose 控制器 frame | Duo 展开竖屏“应用→课表”录屏：早先返回课表的 17 个细分帧下边界均为 521 像素；最终标题／顶栏修正后重新记录，34 个课表采样帧下边界仍全部为 521 像素（402 像素宽的采样帧） |

## 隔离和限制

使用独立应用 team.bjtuss.bjtuselfservice.kmp.layoutprobe 的离线资料；不覆盖、登录、退出或清理原版应用。记录只承认实际验收的形态。用户追加要求横屏使用“其他内容在左、课表在右”的布局，本轮纳入实现与验收，不再仅记录为后续方案。

## 本轮验证记录

- iOS arm64 模拟器 Debug 构建通过；最终标题、材质和图例日志为 .artifacts/duo-scroll-clearance-build.log。
- NativeShellBridgeTest、DestinationBottomClearanceTest、CampusCardDestinationTest：15 项，失败 0，错误 0。这些策略在最终视觉微调中未改变，未重复运行。新增复用 NativeNavigationGlassStateTests：35 项检查通过，涵盖离屏回报、返回恢复与取消返回。布局和材质由实际交互与截图验收。
- 截图、25 秒切换录像与逐帧几何记录均在 .artifacts/duo-round2/，不提交生成媒体。
- 验证包在模拟器上显示为“交大自由行布局验证”。这是生成包的显示名称，生产 Info.plist 未改；应用标识仍为独立的 layoutprobe。
- 用户追加截图已澄清：完美校园问题是打开链接确认弹窗的说明文字，非外部二维码页面。确认弹窗及说明文字居中已验证，未点击外部打开链接，不涉及登录或支付。

## 解锁后补验

Mac 解锁后继续完成原先受阻的交互检查，未重复运行已通过的构建与策略测试。新增证据：

- iphone-landscape-panels-scroll.png：两侧分别滚到末尾；左侧图例与提示、右侧第七节课程在底栏上方完整可见。
- iphone-landscape-date-scrolled.png：短横屏原生日期 sheet 已滚到月末，31 日选中；点击“前往这一天”后弹窗关闭，主页面星期六选中。
- duo-open-landscape-panels-final.png 与 duo-closed-landscape-panels-final.png：两种横屏左操作区、右七节课表。实际右侧横滑后左侧从第 1 周更新到第 2 周。
- duo-open-landscape-home-busy.png 与 duo-closed-portrait-home-busy.png：持续加载 fixture 下旧版日历、状态与 spinner 同组；此后当前版本将回到今天改为“今”字按钮，见本轮 Duo 27.1 最终验证。
- 最终模拟器保留 Duo 展开竖屏首页和 Pro 首页的普通验证包，已退出持续 busy fixture。原版应用没有被启动或覆盖。

## 截图澄清后的最终修正与验收

- campus-card-text-centered.png：完美校园确认弹窗说明文字各行居中，已使用本地 sheet 边界。
- duo-legend-grouped-final.png：横屏左栏第一行为必修／限选／任选／体育／未同步，第二行为实验／考试，不再只剩考试单独落下一行。竖屏保留自然的一行图例；窄窗口和放大字体仍允许各组内部换行以避免裁切。
- iphone-schedule-centered-final.png：普通 Pro 课表标题居中；导出独立在左，右侧状态／刷新不碰标题；七节完整。
- iphone-home-centered-glass-final.png：普通 Pro 首页标题居中，实际滚动后顶栏后内容模糊，标题与动作保持清晰。
- duo-open-portrait-centered-final.png：Duo 展开竖屏标题居中、底部 tab、课表七节完整。
- duo-home-top-after-rotation.png 与 duo-home-glass-after-rotation.png：最终包在真实列表顶部旋转到竖向栏、再回到横向栏，首行仍在顶栏下；继续实际滚动后玻璃正常显示。原生材质恢复时始终登记当前页，不因竖向栏临时隐藏材质而丢失滚动状态。
- TopScrollLazyColumn 在顶栏净空变化且列表原本位于顶部时请求首项重新布局，避免 LazyColumn 保持旧的物理位置而把首项放到顶栏后；已滚动列表继续保留当前位置。
- final-portrait-tabs.mp4 与 final-transition-geometry.txt：最终包“课表→应用→课表”录屏，4 fps 采样中识别到的 34 个课表帧下边界全部为 521 像素。验证限于此实际路径，不扩称所有页面转场。

九项反馈和追加的弹窗文字、图例、普通 iPhone 标题与玻璃问题已处理并验证。测试包使用离线资料，真实账户登录状态保持不变。

## 22:20 用户反馈后的修正

- “今”改为独立原生动作组，字号 22 点 semibold；状态与刷新保持另一长条组。
- 删除滚动过程中 `setNavigationBarHidden` 的调用，不再把整条导航栏（包括返回和侧边动作）隐藏。采用原生 scroll observation 与 minimization。
- CITEL 的应用前台 generation 监听已移除；启动恢复缓存，进入 CITEL 功能时同步。作业详情和提交已有的只读请求仍在确认会话失效后重新登录，不因窗口聚焦重新同步。
- 验证使用离线 layoutprobe，不使用或改变原版登录状态。

### 最终构建与视觉证据（22:48）

- iOS 模拟器构建通过：`.artifacts/duo-scroll-native-observation-build.log`。
- Mac 共享代码编译通过：`.artifacts/duo-citel-desktop-compile.log`。未用真实账号做登录／提交网络验证。
- 最终包一轮检查图：`.artifacts/duo-final-review/visual-review.png`；包含翻周后的独立“今”、首页上滑后的侧边动作、设置上滑后的原生返回按钮。
- 最终上滑／反向滑动录像：`.artifacts/duo-final-review/final-check.mp4`。检查中侧边动作和返回按钮保留，未复现旧版整栏隐藏后的正文跳变。
- 必须明确限制：声明原生 minimization 不等于视觉验证通过。当前模拟器合成拖动没有验证出标题自动收起，截图中标题仍显示。已移除错误的整栏隐藏方案，不再通过隐藏返回和侧边动作模拟标题收起。原生收起效果仍需继续确认。

## 23:11 闪动修复

- 删除 `NativeChromeScrollView`、`observeComposeScroll` 和整个偏移注入调用链，恢复正文直接由 UIKit 安全区约束。
- 没有新增标题平移、淡出、阈值隐藏或手动导航动画；采用系统默认 `.automatic`。
- 保留独立 22 点“今”按钮与 CITEL 前台监听移除。
- 已重新查看旧录像所有实际编码帧，不通过补帧推测动画。旧的静态检查图不足以支撑“没有闪动”的结论。
- 构建通过：`.artifacts/duo-flicker-fix-build.log`。当前版的快速拖动录像全部 176 个编码帧已展开；标题和返回按钮区域像素差异小于 0.32／255（含视频编码噪声），没有位置／可见性变化。快速拖动的列表末尾回弹与导航阈值效果分开观察，不能把回弹中的大片空白算作导航验收成功。

- 针对旧阈值附近的小幅上滑／回滑补录 `.artifacts/duo-flicker-fixed/threshold.mp4`，实际只输出 4 个编码帧；记录里没有连续的中间手势帧，因此不把它包装成完整动画验证。4 帧中标题、返回按钮均固定，正文上滑后返回。
- 连续帧交付图为 `.artifacts/duo-flicker-fixed/frame-review.png`，来自快速拖动录像第 132–143 帧，全部是相邻编码帧（39.088–39.270 秒），没有跳帧或补帧。标题／返回固定，正文连续恢复。完整 176 帧网格在 `gesture-frames/all-frames.png`，保留列表末尾回弹现象供核查。
- 当前修复解决的是撤回不可靠的双滚动与阈值重排，不表示实现了 Compose 页面的 UIKit 自动收起。仍保留这个限制，不用新手写动画遮盖。

## 最新原生收起验收（替代此前未完成结论）

- 用户在 Duo 离线设置页实际上滑、回滑后确认：“标题顺滑收起和恢复”。同轮录像还记录了首页真实手势的收起与恢复，返回按钮、侧边动作与 tab 保留。
- 录像 `.artifacts/duo-native-single/actual-gesture.mp4` 共 327 个实际编码帧，按原始时间戳展开，不补帧。交付图 `.artifacts/duo-native-single/visual-review.png` 包含设置前／中／后状态及收起过程连续帧。模拟器合成拖动缺少中间 pan 事件，不能代替这次实际手势验收。
- `TopScrollContainersTest` 两项通过，验证普通列表和虚拟列表按实际距离消费、两端限位，以及原生模式不创建 Compose 回弹；日志 `.artifacts/duo-native-single/scroll-contract-tests.log`。
- 最终 iOS 模拟器构建日志 `.artifacts/duo-native-single/final-build.log`。自动滚动调试入口已移除，最终包仅以离线参数启动。
- 范围限于首页和设置页的主滚动容器；课表分栏和其他页面不宣称已迁移。真实账号应用与登录状态未改动。CITEL 聚焦同步修正继续保留。

## 首页之外的接入与设置转场修正（10 月 9 日）

用户补充：作业等一级／二级页没有原生收起，从应用进入设置会闪动。

- 移除首页／设置标题白名单，统一按页面已有的 `scrollUnderTopBar`、原生标题接管与非静态布局条件接入主滚动控制器。覆盖作业聚合列表、作业详情、成绩列表、考试列表／详情、物理在线列表／详情、CITEL 列表／详情与账号设置等已采用 TopScroll 容器的页面。没有主滚动容器的空页面不模拟动画；课表分栏、固定头页面、写信和 sheet 保留原滚动归属。
- 从创建宿主视图时即安装固定尺寸 UIScrollView 与 Compose 画布。首帧异步回报只开启／关闭滚动消费者，不再重挂 Metal 视图，不修改顶部约束。所有系统栏宿主统一从内部消费实测顶栏净空，不再因消费者出现把宿主顶部从安全区换到屏幕顶。
- 安全区调整在 navigation item 创建时即设为 disabled，避免首次接入消费者时安全区策略再变化。加载／空状态撤下消费者时清理旧滚动范围与回弹位移，不改变视口。
- 离线验证源新增 18 条作业与 24 段详情，仍使用独立 layoutprobe，无网络、真实缓存或提交行为。原版应用没有被覆盖或登录。
- 原生滚动消费本身未改；沿用此前两项已通过的消费距离／两端限位／关闭 Compose 回弹检查，不重复跑同一测试。

- 本次最终 iOS 模拟器构建通过：`.artifacts/duo-native-single/all-pages-build.log`；`git diff --check` 通过。
- 一轮离线视觉检查实际打开并返回设置、作业列表和作业详情；列表有 18 条离线作业，详情有长正文，侧边动作／返回存在。
- 用户对作业列表与详情的实际上滑／回滑确认：“是 好像没问题”。据此确认这两页的标题收起／恢复；不扩称其他页面都已实际手势验收。
- 用户要求“不用补全录像了”后立即停止录像，不继续补录或逐帧处理。设置闪动已针对首帧重挂与净空切换修正，但不以静态截图宣称已完成全部动画帧验收。

## Duo 回顶内容遮挡修正（10 月 9 日）

- 不能仅用宿主 `safeAreaInsets.top` 判断 Duo 顶部标题占用空间。按导航栏在正文宿主中的实际起点与 UIKit 完整 fitting 高度计算净空，并与宿主安全区取较大值；窗口安全区可能已含导航栏，不能再次累加。
- 完整栏高度按窗口尺寸、侧栏形态与文字大小缓存，避免原生收起动画每一帧改变正文 padding。只有当前可见标签页发布共享顶栏净空，防止后台标签页覆盖旋转后的数值。
- UIKit 回到零点时，若 Compose 因尺寸变化保留了旧列表锚点，仅在无手势、无惯性滚动的空闲状态校正到第一项。正常滑动和惯性仍由原生 UIScrollView 驱动。
- LazyColumn 与 ScrollState 两种消费者的回顶测试通过；iOS 模拟器构建通过。验证使用独立 layoutprobe 应用，不覆盖正式包、不使用真实账号重新登录。
- 最终视觉检查：外屏横屏作业顶部的同步失败卡、作业摘要和第一条作业完整可见；内屏竖屏首页的 MIS 错误提示与下一节课程完整显示在导航栏下方。展开后再折回外屏切换标签页，顶部净空正确恢复。截图合并为 `.artifacts/duo-top-origin-fix/visual-review.png`，未补录视频，也不以静态截图代替动画逐帧验收。
