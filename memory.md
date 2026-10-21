# BJTUselfService 实时工作记忆

> 最后更新：2026-10-21。
> 当前分支：`dynamicisland`（iOS 灵动岛，基于 main@f454d3a，已推送、未开 PR，用户转本地 Mac 继续）。PR #35 已合入 main（f454d3a），细节已移入 `history_full.md`。
> KMP 源码版本：`1.8.5-EmergencyFix2` / Build 40；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。`history_full.md` 尚无 EmergencyFix2 发版条目。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 已完成工作的细节见 `history_full.md` 末尾日志。
> 认证现状（2026-10-20，PR #29/#30 已纳入 main）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码（设置 → 物理在线账号设置）；智慧教学仍卡在学校的旧 CAS 跳转。

> 最近只读操作：2026-10-14 使用已安装 bjtuss 完成本机作业缓存导出；产物仅放 `.artifacts/`，未修改源码、未联网刷新，保留原有暂存与未提交改动。

## 进行中

- iOS 日程灵动岛 / 实时活动（分支 `dynamicisland`，已推送、**未开 PR**；2026-10-21 云端会话交接给本地 Mac 继续）：首页“正在上/下一节”卡片对应的那一项，课前 10 分钟自动出现到结束。小岛（课程徽章+课名 / 倒计时）、长按展开（状态、课名、地点教师、进度条、接下来）、锁屏实时活动、多活动时收成小圆环（开课前填满、上课后按已过时间填满，与进度条同向）。
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
  - 挂起（不阻塞，等合适时机）：灵动岛收起态最终版截图（等实际上课时）；开课时“即将上课”切“正在上课”实测；两个实时活动并存时的最小态小圆环。分支已整理为两个提交（功能 + 文档）；最新提交未跑 CI（推分支不自动触发，需手动 dispatch）。开 PR、打 debug-* 包由用户决定。
  - 已知限制：iOS 只允许前台新建实时活动（无推送服务器），“课前 10/20 分钟自动出现”需要届时 App 在前台或回到前台；后台更新靠 BGAppRefresh，系统不保证准点；iOS < 16.2 不显示按钮。重启设备/重装 App 导致活动消失时，协调器会当作“用户划掉”，这一项不再自动出现（可从菜单重新开启）。Live 菜单本身对读屏不可达，读屏双击只做开启/关闭。

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
