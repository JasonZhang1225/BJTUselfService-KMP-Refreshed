# BJTUselfService 实时工作记忆

> 最后更新：2026-10-22。
> 当前分支：`feat/homework-search-classroom-now`（PR #42，从最新 main 拉出，待用户合并；含作业搜索与教室当前节次强调）。
> KMP 源码版本：`1.8.5-EmergencyFix2` / Build 40；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。`history_full.md` 尚无 EmergencyFix2 发版条目。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 已完成工作的细节见 `history_full.md` 末尾日志。
> 认证现状（2026-10-20，PR #29/#30 已纳入 main）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码（设置 → 物理在线账号设置）；智慧教学仍卡在学校的旧 CAS 跳转。

> 本地打包（2026-10-22，566b2b5）：`.artifacts/BJTUSelfService-KMP-homework-search-566b2b5-debug.apk`（debug 签名，本机无 release 口令）与 `.artifacts/BJTUSelfService-KMP-1.8.5-EmergencyFix2-iOS-unsigned.ipa`（Release，按 CI 流程 ad-hoc 签名）。之后的搜索位置改动不在这两个包里。

> 最近只读操作：2026-10-14 使用已安装 bjtuss 完成本机作业缓存导出；产物仅放 `.artifacts/`，未修改源码、未联网刷新，保留原有暂存与未提交改动。

## 进行中

- 作业搜索 + 教室当前节次（`feat/homework-search-classroom-now`，PR #42）：
  - 搜索是独立一层（2026-10-22 按用户反馈重做）：聚合「作业」和未聚合的「课程平台作业」打开搜索后，列表换成结果，结果来自全部作业、不受筛选影响（只沿用排序），同步横幅/筛选 banner/迁移提示都让位；未输入时只显示提示。匹配课程名、标题，聚合页还匹配来源名。离开作业相关页关闭，进详情再返回保留。卡片沿用同一 key，`animateItem` 让匹配项留在原地、其余淡出。
  - iOS 26+ iPhone 原生壳：页面声明 `NativeBarAction.searchPrompt` 后，宿主给本页挂 `UISearchController`（`preferredSearchBarPlacement = .integratedButton`，系统液态玻璃搜索按钮就地展开、取消收回），文字经 `onSearchQueryChange` 回传；只在 Compose 由开变关时收起系统搜索，避免排队的旧动作误关。iPad 等 Compose 自绘栏：苹果样式搜索头（圆角搜索框 + 取消，自上滑入、临界阻尼）。Android / macOS / Windows：顶栏换成 Material 搜索视图头（返回 + 输入框 + 清除，M3 fade through），Android 返回键先收起搜索，Esc 关闭。
  - iOS 搜索按钮位置（2026-10-22，`ContentView.swift`，未提交）：收起时 `searchBarPlacementBarButtonItem` 放在左侧 `leadingItemGroups`，与右侧同步状态/刷新分开；展开期间挪到 `trailingItemGroups`——实测（lldb 导视图层级）UIKit 给前部搜索框固定约 180pt，宽度约束、item.width、换 style 都无效，放尾部才铺满整条栏，右侧胶囊展开时被系统整体隐藏、不占位。展开期间自绘居中标题淡出（`setSearchActive`），push 出的二级页照常显示自己的标题。用户真机确认输入框占满整条栏、标题不再残留；展开/收起动画路径、浅色、宽窗口/Duo 竖栏（仍走系统默认）未看。
  - 教室占用只在「当前学期 + 本周 + 今天」显示当前节次：当前格子向四周散出自身颜色的光晕（`dropShadow`）：浅色用同色系文字色半透明、画在格子上允许轻微染到邻格；深色保留格子色相、拉高饱和度和亮度（外圈本色柔光 + 内圈偏白细亮边，用户在 5 种候选中选定 VividCore 原版；「其他」灰格只提亮不加色），红格也看得清；两种主题都画在格子上方，轻微染到邻格。`CurrentPeriodRenderTest` 覆盖全部占用类型；节次号加粗；卡片右上角标「空闲 / 占用」（配色取当前格子）。课间、课后或在看别的星期/周时不强调。
  - 验证（2026-10-22）：`./gradlew :shared:desktopTest` 945 项中 944 通过，唯一失败仍是容器缺可执行文件的 `DesktopAgentIntegrationTest.installWritesWorkingScriptAndSkill`；`:androidApp:assembleDebug`、`:windowsApp:compileKotlinWindows`、`:desktopApp:compileKotlin` 通过。`CurrentPeriodRenderTest` 离屏渲染浅/深色截图核对了光晕、标签和两种搜索头。iOS Swift（UISearchController）仅 PR CI 编译，未在模拟器/真机验证交互与动画。未改版本号。
- iOS 课程详情关闭后卡片无响应（PR #35，`fix/ios-sheet-dismissal`）：用户确认首次详情通过右上角关闭按钮收起；6 秒录屏中课程卡片仍有按压反馈，课表/周数弹层可正常开关，未证明主线程整体卡死。原生弹层宿主被所有 `AppleSheet` 共用，内容、关闭回调和动画状态存在串用风险。
  - 修复：每个 `AppleSheet` 组合通过 `createPresentation()` 持有独立 iOS 呈现对象；旧组合的释放/关闭动画不能清空后一个弹层。原生 dismissal delegate 绑定具体控制器，忽略过期回调，并在发起 present 前绑定 delegate。
  - 验证：真实 `AppleSheet` + 可延迟关闭完成的测试宿主，3 项离屏集成测试通过（重叠弹层独立释放、关闭后快速重开、重组保留呈现对象并更新回调）；`./gradlew :shared:desktopTest --no-configuration-cache` 完整共享测试 935 项全部通过（含新增 3 项）。Linux 无 Xcode/iOS 模拟器，原生编译与 iOS 测试交给 PR CI，未做 iPhone 实机复测，未修改版本号或发版。
- PR #34 已由用户合入 main（eff3629），完成细节移入 `history_full.md`。本次将 main 合入 #35，只解决 `memory.md` 冲突，保留两项修复的源码原样。
- Package 记录：PR #34 的 `935d5ba` → run `39522324933`；PR #35 原独立提交 `5430eb8` → run `39522327270`。两次打包均已成功，使用各自原提交；当前 #35 合入 main 后的提交包含两项修复，未额外触发打包；未改版本或创建 Release。

## 当前痛点

- 智慧教学（课程平台作业/课件，MIS module 28）仍被学校跳到已下线的 `cas.bjtu.edu.cn/o/authorize`，浏览器同样 404，属学校侧问题；待学校改到 SSO 后再适配 `SmartPlatformEndpoint` 握手白名单。
- 同步卡顿：Beta3 已到可接受范围。剩余长帧为同步详情 sheet 弹出（约 50ms）、进入作业页（约 49ms）和原生滚动带来的导航栏布局；`checkSession` 改后台后未复采；壳层随任一状态流整体重组（约 0.4 秒/次同步），彻底解决需拆分壳层。
- 小组件：PR #8 的 ad-hoc 签名后，侧载包的小组件共享容器是否可读尚未在真机确认；周视图、中号两栏、脚注只经 CI 编译，未在模拟器浅/深色下查看，小屏 iPhone 周视图可能偏挤；“正在上/还剩 N 分钟”未在上课时段实机观察。
- 首页物理在线/CITEL 日程项进入二级详情只在代码层实现，模拟器只验证了演示实验。
- “保存二维码并扫一扫”未真机验证（Android MediaStore 保存/去重、扫码后进入小程序/缴费页）；iOS 每次保存会多一张；Android 二维码弹窗未在平板上看。
- HyperOS 小窗↔全屏来回切换未实测（adb 无法切换窗口模式）。
- 课件 15 分钟大文件超时未用 >30 秒真实下载验证；iOS/Android“未开放下载”标记只验证编译。
- 在 Chrome 登录智慧教学会顶掉 App 会话，App 自动恢复登录失败一次（需手动刷新），未排查。
- macOS 附件默认应用打开、iOS 保存面板未点验；`HomeworkFileGateway` 已被邮箱复用，可改名为通用文件网关。

## 已知限制（非缺陷，排查时先看这里）

- 教务系统 `aa.bjtu.edu.cn` 会临时拒绝 443 连接（2026-10-20 晚间约 1.5 小时，MIS 同时可达）；PR #33 起 App 照常进入主界面，仅成绩/课表/考试同步失败并保留缓存。复现可在 `KtorSchoolHttpTransport.executeOn` 临时对 aa 抛异常，测完撤销。
- 校历公众号文章不能由第三方 App 直接在微信内打开（invalid source），走系统浏览器。
- 小米“跟随应用内设置”覆盖安装清不掉：装新包后卸载重装，或在“使用高刷新率的应用”里打开交大自由行 KMP。
- 本地 Gradle 偶发被 Maven Central 429 限流，此时测试与 iOS 编译交给 PR CI。

## 迁移期遗留（长期，无排期）

- iOS Keychain 合法签名往返、正式签名发布（无 Apple Developer Team）。
- 物理在线真实上传、Windows 触摸屏；邮箱真实发送/删除/移动/附件上传；真实作业上传、退出账号往返（等学校侧条件）。
- 验证码发布级正确率需至少 300 张独立留出集。

## 接下来

- 后续开发统一从最新 `main` 建分支，PR 以 `main` 为目标。

1. 真机看作业搜索：iOS 导航栏系统搜索按钮的展开/取消、搜索中进详情再返回；Android 返回键收起搜索；上课时段教室当前节次光晕与空闲/占用标签，浅色/深色都看一眼。
2. 在真机已有版本（SideStore 保留扩展）上验证小组件共享容器与各尺寸显示。
3. 华为 HBN-AL10（HarmonyOS 4.2）装含 PR #32/#33 的新包：确认课表小组件尺寸（logcat `CourseWidget` 标签）与教务不可达时不再闪退。
4. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。
