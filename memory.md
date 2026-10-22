# BJTUselfService 实时工作记忆

> 最后更新：2026-10-21。
> 当前分支：`feat/ios-dynamic-island`（iOS 灵动岛，PR #41 待合并；已合入 main@64c24ab0，含 PR #40 图书馆）。iOS 最低系统已改为 18.0（见 `AGENTS.md` 仓库事实）。PR #35 已合入 main（f454d3a），细节已移入 `history_full.md`。
> KMP 源码版本：`1.8.6-Alpha` / Build 41（来自 main，尚未发版）；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。`history_full.md` 尚无 EmergencyFix2 发版条目。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 已完成工作的细节见 `history_full.md` 末尾日志。
> 认证现状（2026-10-20，PR #29/#30 已纳入 main）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码（设置 → 物理在线账号设置）；智慧教学仍卡在学校的旧 CAS 跳转。

> 最近只读操作：2026-10-14 使用已安装 bjtuss 完成本机作业缓存导出；产物仅放 `.artifacts/`，未修改源码、未联网刷新，保留原有暂存与未提交改动。

## 进行中

- iOS 日程灵动岛 / 实时活动（分支 `feat/ios-dynamic-island`，**PR #41** 待合并；最初在 `dynamicisland` 开发）：首页“正在上/下一节”卡片对应的那一项，课前 10 分钟自动出现到结束。小岛（课程徽章+课名 / 倒计时）、长按展开（状态、课名、地点教师、进度条、接下来）、锁屏实时活动、多活动时收成小圆环（开课前填满、上课后按已过时间填满，与进度条同向）。
  - 文件：`iosApp/CourseScheduleWidget/CourseLiveActivityAttributes.swift`（宿主与扩展共用：ContentState、阶段、staleDate、标识格式）、`CourseLiveActivityWidget.swift`（灵动岛/锁屏 UI，已加进 `CourseWidgetBundle`）、`iosApp/iosApp/CourseLiveActivityCoordinator.swift`（读小组件快照决定启动/更新/结束；单实例换课更新；已显示时课间 ≤30 分钟直接接下一节；锁屏划掉或卡片关闭的那项记入 dismissed 不再自动出现；BGAppRefresh 尽力后台换课/收起）；`CourseWidgetData.swift` 同时编进宿主 target（pbxproj 新增 C1000… 条目）。`iOSApp.swift` 启动时注册后台任务并启动协调器。`Info.plist` 加 `NSSupportsLiveActivities`、`UIBackgroundModes=fetch`、`BGTaskSchedulerPermittedIdentifiers`（`team.bjtuss.bjtuselfservice.kmp.ios.liveactivity`，与代码常量必须一致，否则注册即崩溃）。
  - Kotlin：`feature/home/LiveActivityButton.kt`（`LiveActivityTarget`、`liveActivityOccurrenceId` = `yyyy-MM-dd|开始分钟|结束分钟|课名`、expect 按钮）、Android/桌面空实现、`LiveActivityButton.ios.kt`（UIKitView 包原生 UIButton + UIMenu：课前 10 分钟显示 (HH:mm)=arm / 重新开启或立即开启=start / 关闭=stop；系统关闭实时活动时给“前往设置”）。`HomeNowCard` 新增 `liveTarget` 参数。桥接：Kotlin 发 `BJTULiveActivityCommand` 通知（userInfo 全是字符串），Swift 写 UserDefaults `BJTULiveActivity.supported/enabled/activeId` 并发 `BJTULiveActivityStatusDidChange`。新增测试 `LiveActivityTargetTest`（卡片与快照标识一致）。
  - CI：交接时的两轮（c5a2631 的 KMP package 39545864308、KMP security checks 39545866554）均 success。
  - 2026-10-21 本地 Mac 验证与修改（已推送到 `dynamicisland`）：
    - 构建：签名模拟器 xcodebuild（Xcode 27.1，iPhone 17 Pro iOS 27.0，本机无 Java 21 用 Android Studio JBR 25）BUILD SUCCEEDED，Swift 新文件首编即通过；产物 `BGTaskSchedulerPermittedIdentifiers` 与代码常量一致、宿主 Min iOS 16.0（CI 断言 16.0，未上调）、扩展 17.0、版本 1.8.5-EmergencyFix2/40。日志 `.artifacts/dynamicisland/ios-build-*.log`。
    - 测试：`./gradlew :shared:desktopTest :shared:iosSimulatorArm64Test` 941 + 723 项全部通过（含 `LiveActivityTargetTest`）。
    - 用户反馈后改动：① Live 按钮改为 Compose 画的胶囊（无线电波图标，未开启同“下一节”灰底、开启同“正在上”类别色浅底），放在状态胶囊下方竖排同宽，左侧整列留给课名/地点；原生 UIButton 透明叠在上面只弹菜单（`placedAsOverlay`、`NonCooperative` 触摸，修掉需按住约 0.4 秒才弹菜单且卡片同时按压的问题），读屏双击直接开启/关闭。② 灵动岛收起态按用户要求只留左侧类别图标 + 右侧倒计时（曾试过显示完整课名，长条占满顶部，用户否决）。③ 锁屏卡片字号放大（状态 subheadline、课名 title3、地点 subheadline、时间 footnote、倒计时 30pt），并修复倒计时被挤成两行。
    - 模拟器已看：卡片胶囊浅/深色、菜单弹出与“立即开启/课前 10 分钟显示”、灵动岛紧凑与长按展开（最终“图标 + 倒计时”版本已构建安装，截图未看）、锁屏卡片浅/深色、锁屏划掉后回到 App 不自动重开。
    - 第一轮真机验证后外观微调（云端会话，仅 CI 编译）：① 锁屏/通知中心 `activityBackgroundTint(.clear)` 透出系统液态玻璃；② 收起态左侧改为类别色图标 + 环形进度（与最小态共用 `LiveRing`）；③ 倒计时一律两行（“距开始/距结束” + 时间，收起态也是）；开课前 `Text(timerInterval:)` 带秒，上课后 `LiveMinuteCountdown` 只到分钟——iOS 18+ 用系统 `.timer(countingDownIn:maxFieldCount:2,maxPrecision:.seconds(60))`，iOS 17 退回带秒。注意：苹果论坛报告该分钟精度计时在 iOS 18 早期不刷新；自定义 DiscreteFormatStyle 在实时活动里渲染成占位块（iOS 27 仍有报告），不能用。真机若分钟显示异常（不走、“44” 无小时位等），备选为静态“HH:mm 下课”。
    - 真机反馈（c91f408）：分钟计时会逐分钟刷新（“1 小…”→“59 …”），但中文系统输出带单位全称（“57 分钟”“1 小时 20 分钟”），按 h:mm 定宽被截断；手表智能叠放给的空间大，能完整显示。用户想要“1h20m”，系统计时没有缩写单位选项、自定义格式在实时活动里是占位块，做不到；改为去掉定宽、只设上限（收起 92 / 展开 160 / 锁屏 190pt）并允许缩到 0.7 倍。若仍嫌长，备选：收起态改“16:00 / 下课”静态时刻，或试 en 区域设置看是否输出“1 hr 20 min”。
    - 用户定的倒计时方案顺序：先试英文（`LiveMinuteCountdown` 加 `.environment(\.locale, en_US)`，看系统是否输出更短的“1 hr, 20 min”）→ 不行改显示下课时刻 → 兜底完整显秒。英文版已推送待真机看，云端无法实测实时活动渲染。
    - 英文试验（6fe42f8）真机结果：输出全称“1 hour, 29 minutes”，不缩写，收起态被撑宽（灵动岛围绕摄像头对称，左侧同样变宽）。按用户定的顺序改为上课时显示下课时刻（第一行“下课”、第二行“16:00”，静态），删除 `LiveMinuteCountdown`；开课前仍是带秒倒计时。兜底方案（完整显秒）未启用。
    - 用户最终定：上课时收起态（与锁屏）显示“下课 / 16:00”；长按展开显示“距结束 / 1 小时 29 分钟”（系统分钟计时、中文，18pt，`belowIfTooWide` 防截断）。环形进度上课时按已过时间填充。
    - 再调：锁屏/通知中心也显示“距结束 / N 小时 N 分钟”（24pt），只有收起态显示“下课 / 16:00”。Apple Watch 智能叠放单独视图：`CourseLiveActivityWidget` 加 `.supplementalActivityFamilies([.small, .medium])`，`.small` 渲染 `LiveActivityWatch`（环形进度 + 课名地点 + 距结束/距开始）。首版在 WidgetBundle 里 `if #available … else` 二选一，CI 报 `closure containing control flow statement cannot be used with result builder 'WidgetBundleBuilder'`（run 39564141771）。
    - **最低系统改为 iOS 18.0**（用户决定，2026-10-21）：宿主 `Config.xcconfig` 16.0→18.0、扩展 `Widget.xcconfig` 17.0→18.0、`kmp-security-check.yml` 的最低版本断言同步为 18.0；去掉 iOS 16.2/17/18 的版本分支（协调器、Live 按钮“不支持”分支、分钟计时带秒回退、Widget 二选一）。发版说明需写明最低 iOS 18。
    - 收尾 CI（2026-10-21）：KMP package run 39564988345（6d2f2cf）四端全部成功，iOS IPA artifact 12331100495；KMP security checks run 39566934678（12f077a）成功——上一轮失败是 `SecurityReleaseConfigTest` 把最低版本写死 16.0，已改为断言宿主与扩展都是 18.0。用户表示该功能暂告一段落，不再继续开发；未开 PR，最新包未经真机复看。
    - 真机反馈修正（同日）：收起态“下课/已结束”右对齐会被灵动岛圆角裁掉，改为说明文字居中在时间正上方；手表 `.small` 去掉课名地点、保持系统不透明底，只留环形进度 + “距结束 / 1 小时 28 分钟”（说明 15pt、时间 17pt，不限宽）。
    - 手表无内容（2026-10-21 晚，已提交并推送到 `dynamicisland`）：真机智能叠放和点开后的全屏都只剩类别色圆环，看不到“距结束/时间”。推断原因是手表视图用了 `.secondary`/`.primary`，而视图由 iPhone 按自己的外观渲染，iPhone 浅色时这两种颜色在手表深色底上看不见。改法：`LiveActivityWatch` 固定 `.colorScheme(.dark)`，说明文字和分钟计时改为类别色，去掉 `.fixedSize`，改为最小缩放 0.7。签名模拟器 xcodebuild BUILD SUCCEEDED（`.artifacts/dynamicisland/ios-build-9.log`）。本机 iPhone 17 Pro 与 Watch Series 12 模拟器已配对，活动在 iPhone 端处于 active，但始终没有转发到手表模拟器，手表效果**未验证**，需要真机复看。已在本地按 CI 流程打 Release 侧载包 `.artifacts/dynamicisland/ipa/BJTUSelfService-KMP-1.8.5-EmergencyFix2-iOS-watchfix-local.ipa`（ad-hoc 签名，App Group 与宿主/扩展版本已校验，最低 iOS 18.0），交给用户真机测试。真机测试通过（14:27 显示“距结束 / 1 小时 32 分钟”，15:24 显示“35 分钟”，均为类别色）。随后按用户要求微调：手表左上角圆环上方加小字“交大自由行”（11pt），说明文字加类别前缀（“上课·距结束”“实验·距开始”“考试·已结束”，只改手表）。watchfix2 真机：左列 App 名挤占宽度，说明被截成“上课·距…”、计时被缩小。再改（watchfix3）：① 手表 App 名单独占顶行（10pt），下行圆环 30pt + 右侧倒计时（说明 12pt，最小缩放 0.8，不再截断；计时 17pt 优先布局）。② Live 菜单提前时间改为“课前 10 / 20 分钟显示”二选一，选中的打勾，写入 UserDefaults `BJTULiveActivity.leadMinutes` 作为以后所有课程的默认值（未开启时同时 arm；已开启时发新命令 `lead` 只重新对齐）；协调器按此决定自动出现和下一次唤醒时刻；ContentState 新增可选 `leadMinutes`（旧内容按 10），开课前圆环按它填充。签名模拟器构建、`LiveActivityTargetTest`、Release 侧载包 `ipa/BJTUSelfService-KMP-1.8.5-EmergencyFix2-iOS-watchfix3-local.ipa` 均通过。用户结论（同日）：watchfix3 手表排版真机验证通过；10/20 分钟菜单以代码为准，不再实测；文字放大不需要验证。
  - PR #41 审查修正（2026-10-21 晚，云端会话，Swift 与 iOS Kotlin 仅 PR CI 编译）：① 采纳 Copilot：BGAppRefresh 到期时也调用 `setTaskCompleted(success: false)`，用 `OSAllocatedUnfairLock` 保证只报一次；任务开始先预约下一次，到期不再断掉后台换课。② 采纳 Copilot：Live 按钮把“课前 10/20 分钟显示”作为读屏自定义操作（选中项带“已选中”），系统关闭实时活动时读屏双击改为前往设置，描述为“已在系统设置中关闭”。读屏效果待模拟器/真机确认。
  - 挂起（不阻塞，等合适时机）：灵动岛收起态最终版截图（等实际上课时）；开课时“即将上课”切“正在上课”实测；两个实时活动并存时的最小态小圆环。PR #41 已开，CI 由 PR 触发；打 debug-* 包由用户决定。
  - 已知限制：iOS 只允许前台新建实时活动（无推送服务器），“课前 10/20 分钟自动出现”需要届时 App 在前台或回到前台；后台更新靠 BGAppRefresh，系统不保证准点。重启设备/重装 App 导致活动消失时，协调器会当作“用户划掉”，这一项不再自动出现（可从菜单重新开启）。原生 Live 菜单对读屏不可达，读屏用双击开启/关闭加自定义操作选提前时间代替。
- 图书馆 iOS 顶/底栏与 PR #40 评审修复（`claude/amazing-mccarthy-sfljuw`，2026-10-21）：
  - 顶栏：图书馆页由 `staticTopBar` 改为 `scrollUnderTopBar`，账户卡与 tab 作为列表首项随内容滚动，内容滚进 iOS 原生液态玻璃顶栏（与考试/作业页一致），横屏不再有固定头部挤压和顶部实心空带。
  - 续借栏：整条 Material 底栏 + 底部透明净空（横屏露出黑条）改为浮在列表上方的胶囊（最宽 560dp、避开水平安全区，底边 = 真实底部安全区 + 10dp），列表末尾按胶囊实测高度让位；当前借阅为空时不显示。
  - 评审：`firstReferer` 改为精确校验 https + `opac.bjtu.edu.cn` + 默认端口（新增 `libraryFinishUrl` 及 2 个测试），`followAllowed` 起始地址也过白名单；`LibraryLoginProtocol`/`LibraryModel` 以学号为 remember key，换账号不沿用旧借阅与 loanId。
  - 云环境（Mine）：Maven Central 对容器出口 429；用户已在 Mine 的 Setup script 里写入 Gradle 镜像 init 脚本（Google 的 Maven Central 镜像）、Android SDK（`/opt/android-sdk`，platform 36、build-tools 36.0.0）与仅云容器用的一次性调试签名（随机口令，非共享 upload key，APK 不能覆盖安装正式包）；`ANDROID_HOME` 写在环境变量。
  - 已验证（云容器 Linux，JDK 21）：`:shared:compileKotlinDesktop`、`:windowsApp:compileKotlinWindows`、`:androidApp:assembleDebug` BUILD SUCCESSFUL；`:shared:desktopTest` 978 项仅 `DesktopAgentIntegrationTest` 1 项失败（macOS 专属，Linux 返回 127），图书馆 39 项全过。iOS 交给 PR CI。
  - 未验证：iOS 模拟器/真机实际观感（本环境为 Linux，无 Xcode）；横屏顶栏高度若仍明显高于其他页，需用 `--layout-metrics` 采宿主上报的 `top=`。
- 图书馆借阅功能（PR [#40](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/pull/40)，待合并；#38 已关闭）：新增 `data/library/`（模型、endpoints、SSO OAuth2 登录协议、remote、repository）、`feature/library/`（LibraryModel、LibraryScreen）、`AppSection.LIBRARY` + 应用页磁贴 + 底栏自定义候选 + 首页到期提醒卡片；续借支持单本/全选批量并带确认弹窗，不做离线缓存。
  - 协议证据（2026-10-21 真实抓包）：SSO 会话有效时授权页直接回 `opac.bjtu.edu.cn/OAuth2/bjtu/index?code=...`；回调页 HTML 内一次性 `data/time/enc`；`/OAuth2/bjtu/login` 返回 `{"status":true}` 并写 opac Cookie（`p_auth_token` 等）；`/find/*` 只靠 Cookie 鉴权（不带 SPA 动态 JWT 头也能读取，已实测）。
  - 已验证（本机 Windows，JDK 21，仓库 Gradle 9.3.1）：`:androidApp:assembleDebug`、`:windowsApp:compileKotlinWindows` BUILD SUCCESSFUL；`:shared:desktopTest` 975 项中 5 项失败均为 macOS 专属旧测试（`DesktopAgentIntegrationTest` 3、`MacUninstallCleanerTest` 2，Windows 上本来就会失败），图书馆 36 项（协议 6、数据层 16、模型 13、真实链路 1）全部通过。IDE 自动改的 Gradle 9.8.0 wrapper / daemon JVM / tooling.parallel / `.idea` 已全部还原，wrapper 保持 9.3.1 并保留 SHA256 校验。
  - 本机构建准备：生成了仅测试用 keystore `~/.android/bjtu-kmp-upload.keystore`（alias `bjtu-kmp-local`，口令属本机环境信息，未记录在仓库），`multiplatform/local.properties` 写入 sdk.dir；对应本地签名口令写在用户级 `~/.gradle/gradle.properties`（均被 gitignore/仓库外，不提交）。AGP 已自动安装 SDK Platform 36。
  - 顺手修复 Android Studio 同步失败：`desktopApp/build.gradle.kts` 的 `resolveDesktopPackageJavaHome()` 在无 `JAVA_HOME` 的 IDE 守护进程里直接 error，导致整仓 sync 失败；已加 `System.getProperty("java.home")` 兜底（打包仍要求完整 JDK）。另注意 `multiplatform/gradle/wrapper/gradle-wrapper.properties` 被 Android Studio 改成 Gradle 9.8.0（未提交，需用户决定保留或还原 9.3.1）。
  - 待办：Android 真机验证（用户用 Android Studio 打包）；续借为写操作尚未真实执行。
  - 真实链路联调（`LibraryLiveTest`，用户凭据，2026-10-21）：登录 + 用户信息 + 当前借阅 1 条 + 历史 8 条读取成功（`cardStatus=1`）。关键修复：`/OAuth2/bjtu/login` 返回 `status:true` 后还必须跟随 `find/sso/login/bjtu/0?data/time/enc`，这一步才写入 API 鉴权所需的 `SESSION` Cookie；只做到 login 会得到 401。
  - 真机续借验证（2026-10-21）：续借成功但界面一度显示"失败原因 · 2026-11-20 17:50:25"。根因：续借响应 `data.result` 是 loanId→值，`fail==0` 时值是**新应还日期**、`fail>0` 时才是失败原因；已按 fail 区分，成功时展示"新的应还日期"，并把新日期带进成功文案；补了 2 个回归测试（数据层 16 项、模型 13 项全过），APK 已重打。
  - UI 反馈修复（2026-10-21）：整张借阅卡可点选（复选框/续借按钮仍独立响应）；借出/应还改为 FlowRow，放不下时整段换行避免"应还日期"被拆两行；已重编译 APK 与 Windows 源码运行。
  - Windows 标题栏修复（2026-10-21）：`windowsApp/Main.kt` 原无条件设置 `DWMWA_USE_IMMERSIVE_DARK_MODE=1`，浅色系统下标题栏纯黑；改为按注册表 `AppsUseLightTheme` 设置 0/1，并在窗口生命周期内每 2s 在 IO 线程轮询（实测单次 JNA 读注册表 ≈40µs，CPU 占用 ≈0.002%），运行中切换 Windows 应用深浅色也能跟随（未用 WndProc 子类化，避免 JNA 回调风险）。
  - 应用目录修复（2026-10-21）：`applicationSections` 原来是"候选 − 底栏"，把已加到底栏的入口从「应用」页移除；改为返回全部功能入口（底栏只是快捷方式），更新 `NewMigrationNavigationTest` 断言，APK 与 Windows 源码实例已重打。
- iOS 课程详情关闭后卡片无响应（PR #35）：已合入 main（`f454d3a`），修复细节见 `history_full.md` 待补日志。

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

1. 在真机已有版本（SideStore 保留扩展）上验证小组件共享容器与各尺寸显示。
2. 华为 HBN-AL10（HarmonyOS 4.2）装含 PR #32/#33 的新包：确认课表小组件尺寸（logcat `CourseWidget` 标签）与教务不可达时不再闪退。
3. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。

