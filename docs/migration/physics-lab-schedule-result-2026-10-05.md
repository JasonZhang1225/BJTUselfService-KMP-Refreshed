# 课程表考试、物理实验与同步调整（2026-10-05）

基线：`c82617c`，当前 worktree；应用版本保持 1.8.1 / Build 22。此次修改未提交、未推送、未发布，冻结根 `app/` 未改动。

## 功能与数据链路

- 更多页在物理在线下方增加“物理实验同步”，打开账号密码设置面板。账号安全保存与开关分开操作；启用会立即保存并查询，关闭后不参与同步或课表投影。
- Android 使用独立 Keystore alias 和加密 preferences；iOS/macOS 使用独立 Keychain service/account；Windows 使用独立 DPAPI namespace。以当前应用账号隔离凭据、配置和缓存，更换实验账号会清除旧实验副本。
- 登录后自动同步、首页刷新及课表刷新读取最新已选实验。物理实验拥有独立内存 Cookie 和请求锁，校园网连接失败不会占住教务的同步队列。单轮超时 25 秒，失败保留上次成功结果；正常空表则替换为空数据。
- 仅访问 `http://wlsy.bjtu.edu.cn/` 的登录和 `/Student/Teach/Course/CourseResult.aspx` 查询。登录成功以受保护结果页为准，不自动访问服务端跳转地址，不提供选课、退课能力。Android/iOS 仅为此域名增加明文例外，设置页说明校园网 HTTP。
- 课表按实际日期投影考试、实验。与普通课程重叠时并排保留，各色块按该时段内的实际起止位置绘制。实验用青色，考试用紫色，详情展示准确日期、时间、地点；选课课表按对应学期校历过滤，避免跨学期混入。非教学自然周内的实验也可显示。
- 日历导出新增默认开启的“包含物理实验”。实验以北京时间展开每个实际日期；稳定 UID 使用 `course-physicslab-*`，沿用系统日历的课表范围替换机制，保护手工事件和考试标记。
- 设置页删除原四项自动同步区块和对应 setter。成绩、作业、课表、考试始终自动同步，旧版本保存的关闭值在读取时恢复开启。更多页的物理在线、物理实验总开关仍各自有效。
- 清除离线缓存保留实验同步配置；完整清空时先枚举所有配置账号并清除实验凭据，再清数据库。数据库仅增加查询，不变更 schema。

## 时段和周数依据

2026-10-05 使用用户授权的参考项目 `.env`，在校园网登录后核对 `/Info/TimeInfo.aspx`：

| 时段 | 起止时间 |
| --- | --- |
| 01 | 13:20–15:50 |
| 02 | 16:20–18:50 |
| 03 | 19:10–21:40 |
| 04 | 13:00–16:20 |
| 05 | 17:40–21:00 |
| 06 | 10:10–12:40 |

周数沿用 `/Users/zjg/Downloads/bjtu-phy-lab-ele/physlab_http.py` 的 `is_two_week`：名称含“专题”“软磁”“GPS模拟”“设计”时按连续两个自然周，其余一周。两周的第二次日期为首次日期加 7 天。结果页及实验信息页未发现独立的持续周数字段，因此这是参考项目规则，仍需用户按实际实验安排复核；页面已明确说明该规则。

HTTPS 探测超时，现有参考项目和实测可用端点为校园网 HTTP；没有绕过 TLS 证书校验。

## 验证

从仓库根目录运行（设置本机 Android SDK 路径）：

```sh
ANDROID_HOME=/Users/zjg/Library/Android/sdk \
PHYSICS_LAB_TEST_ENV_PATH=/Users/zjg/Downloads/bjtu-phy-lab-ele/.env \
multiplatform/gradlew -p multiplatform \
  :shared:desktopTest \
  :shared:compileKotlinIosSimulatorArm64 \
  :shared:compileKotlinIosArm64 \
  :androidApp:assembleDebug \
  :windowsApp:compileKotlinWindows \
  :desktopApp:compileKotlin --console=plain
```

- 共享/JVM 回归 **576 项通过，0 失败**；新增用例覆盖 ASP.NET 字段、受保护页验证、登录失效、空结果、损坏日期/时段、缓存保留与账号隔离、关闭功能不请求、两周展开、准确重叠、非教学周、导出范围和 UID、旧同步设置迁移及缓存清理。
- Kotlin 实际请求校园网成功，读取 **4 个已选实验**；真实数据、密码、Cookie、ViewState 和原始页面均未写入 fixture 或提交。
- Android debug、iOS 两目标、Windows 与 macOS Kotlin 编译通过。
- iOS Simulator 完整工程构建使用 `xcodebuild`、Debug、iPhone 17 Pro destination，关闭签名；最终结果为 `BUILD SUCCEEDED`。
- `ScheduleEventsRenderTest` 使用实际 Compose 组件与合成数据输出 10 张图：浅深色、1080/390 宽度、1.3 倍字体、更多、实验设置、日历和设置页。图片位于 `multiplatform/shared/build/reports/physicslab-ui/`，已查看图例换行、部分时段色块、重叠布局和新控件。
- 临时桌面应用已运行；CUA 无法绑定该临时 bundle，不能将其视为鼠标、键盘或系统弹层交互验收。临时应用实例与注册已清理。原生触摸/鼠标、真实 Keystore/Keychain 新命名空间和 EventKit 实际导入仍需实机复测。

主要新增实现位于 `feature/physicslab/`、`feature/course/ScheduleEvents.kt`；同步与装配修改位于 `AuthenticatedAppShell`、`AuthenticatedSessionFactory`、`KtorSchoolHttpTransport`，日历修改位于 `AcademicCalendarExport` 与 `CalendarExportSheet`。删除本轮工作区 diff 即可回退，无数据库结构迁移。


## 2026-10-05 追加：深浅色复查与三端安装包

按用户要求分别复查浅色和深色。普通课程色板改为按当前 `MaterialTheme` 背景取色，避免系统主题与页面主题不同时使用错误色板；保持原有颜色值。实际 Compose 组件渲染扩大到 14 张图，检查了宽窄课表、物理实验设置、更多、设置、日历导出及字体放大，文字、描边、开关和重叠色块可见。共享/JVM 576 项回归再次通过；这仍不等同于原生触摸或鼠标交互验收。

本轮执行 `:shared:desktopTest :androidApp:assembleRelease :desktopApp:packageDmg` 成功；iOS `Release`、`generic/platform=iOS`、关闭签名的完整工程构建为 `BUILD SUCCEEDED`。安装包版本均为 1.8.1 / Build 22，文件名标记 `physicslab-20261005`，未覆盖旧安装包。

| 平台 | 文件 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| macOS | `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-physicslab-20261005-macOS-arm64.dmg` | 121023218 | `9666c60fc3befdcc9cf108ae1f80c2bc388f3ce28fa2d293bdbf3f992ed3b531` |
| Android | `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-physicslab-20261005-Android-arm64.apk` | 88832241 | `f2b35454cb78af89a14260fad145c35dc5f6119aecd04f1930f5431f63dc7a90` |
| iOS | `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-physicslab-20261005-iOS-unsigned.ipa` | 41955076 | `130d417d32834b2f600b9e126ea28f462c22e67fea992fbc39b9e3718704c0fb` |

- DMG：`hdiutil verify` 通过；只读挂载后核对应用中文名、Bundle ID、版本与 Build，包内验证码模型/辅助程序、日历和输入源辅助程序齐全；包内应用 `codesign --verify --deep --strict` 通过。镜像已卸载。
- APK：Release、arm64-v8a；`apksigner verify --verbose` 通过（v2 签名）；`aapt dump badging` 核对 Bundle ID、版本 1.8.1 与 versionCode 22。
- IPA：真机 arm64，Payload 应用封装为 ASCII 文件名/可执行文件名，显示名保留；ZIP CRC 完整性检查通过，Info.plist 标明 iPhoneOS、1.8.1 / 22，无 `_CodeSignature`、无 provisioning profile，`codesign -dv` 确认为未签名。需按用户自己的签名流程安装。
- 仍未提交、推送或发布。


## 2026-10-05 追加：实验弹层、图例与导航模糊修复

检查发现本工作树仍 detached 在 `c82617c`，没有包含 `audit` / `mine/audit` 的 `6f1ec08`（定版首页玻璃渐显与短页模糊效果）。前轮打包时未核对该分支差异。本轮保留实验功能与未提交修改，只移植该版本涉及的首页玻璃、原生玻璃渐显、顶部滚动计算及其测试；没有切换工作树或整合 audit 的其他改动。

- 物理实验表单补充 `wlsy.bjtu.edu.cn` 账号密码说明；原生弹层移除重复标题，滚动正文使用 UIKit 标题栏避让，底部安全区放进滚动内容，避免正文被外层固定空白截断。其他弹层布局不变。
- 图例改为“实验”，实验和考试移到普通课程类型之后，沿用同样的 0.5dp 描边、字体与间距。
- 原生导航控制器按页面实例保存模糊进度，隐藏页面回调只更新自己的缓存，进入和返回页面立即恢复目标进度；交互式返回取消后重新恢复实际可见页，弹出页面状态清理。隐藏页面标题回调不再修改当前标题。Compose 二级页滚动报告随页面标题重新绑定。
- 渐显使用连续 alpha，首页同时补齐短内容页弹性上滑进度计算。
- Swift 无界面导航状态回归 35 个检查通过，覆盖设置与短页反复往返、隐藏页回调、侧滑返回取消、连续小数进度、非法值与回收。
- 共享/JVM 578 项回归及 Android、macOS、Windows、iOS 两目标编译通过；14 张实际 Compose 合成页面图复查浅深色、宽窄屏与放大字体，实验表单包含 4 个合成实验。
- 原生 UI 自动化明确报告 Mac 锁屏，已请求用户解锁；未将静态渲染或状态测试记为 iOS 原生往返/底部触摸验收。


三端修复安装包已完成，版本仍 1.8.1 / Build 22，旧包保留。iOS 完整 Release 工程构建成功；DMG 只读挂载、辅助文件与包内签名校验通过；APK v2 签名与版本检查通过；unsigned IPA 的真机 arm64 架构、未签名状态、无 provisioning profile 与 ZIP CRC 检查通过。

| 平台 | 文件 | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| macOS | `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-physicslab-ui-fix-20261005-macOS-arm64.dmg` | 121022649 | `566cac7e5c05e610346c146f5333be89c1b552176dfb1cfffcc65da08fcaca53` |
| Android | `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-physicslab-ui-fix-20261005-Android-arm64.apk` | 88832241 | `60b4d4c472433baaceab5e8870a2f904a29a40dc4c7dc6546f582a3c16582ea1` |
| iOS | `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-physicslab-ui-fix-20261005-iOS-unsigned.ipa` | 41977663 | `a1396f3092d18bf689f8294c6deef8870f8d109742b74d91a7bd39e87eae2864` |


## 2026-10-05 追加：教室人数估计短列表弹性上滑

用户实测确认前轮导航往返正常，Android 已测试，剩余反馈为 iOS 教室人数估计顶栏只有玻璃、模糊不足。检查该页面 BuildingList，发现只有逻辑 LazyListState 偏移报告；短列表在 iOS 上弹性上滑时，该值可保持零，内容已经移动至顶栏下但模糊进度没有对应变化。

BuildingList 接入首页已有的 resolveVisualTopScrollOffset 路径：同一 overscroll effect 处理列表事件，只在外层渲染一次；分别测量固定视口与弹性内容位置，合并逻辑偏移后驱动原生玻璃渐显。仅原生顶栏 underlap 生效，其余平台保留原有滚动报告。既有短页测试覆盖逻辑偏移为零的上滑、回弹，以及正常滚动和向下弹性偏移。待实际 iPhone 复核。

共享/JVM 578 项再次全部通过，iOS 真机/模拟器 Kotlin 编译通过。本轮尝试读取原生测试界面时 CUA 通道关闭，未完成 UIKit 交互截图复验。

完整 iOS Release 工程构建成功；新 unsigned IPA 真机 arm64、ZIP CRC、版本 1.8.1 / 22 与未签名状态检查通过。文件 `/Users/zjg/Downloads/BJTUSelfService-KMP-1.8.1-classroom-glass-fix-20261005-iOS-unsigned.ipa`，41982826 字节，SHA-256 `567cfbb08e4af333fc2ef2ccb22df9609d32b32e184fd98224f500cc2b54f246`。旧安装包保留，本轮只新增 iOS 修复包。未提交或推送。

用户确认最新 iOS 教室人数估计模糊修复实测无问题，并授权提交、推送、依次合入 audit/main 与清理分支工作树。

## 2026-10-05 合并回归

功能提交 `67c2d0f` 已推送个人远端 mine，合入 audit 的提交为 `784d7ff`。仅 memory.md 冲突，已整合两侧状态记录；源码自动合并并检查保留 audit 安全、日历范围替换、作业握手与同步启动修复。合并后共享/JVM 596 项（0 失败、0 跳过）、Swift 导航 35 检查全部通过，Android Release、macOS、Windows 和 iOS 两目标 Kotlin 编译通过。按用户授权，后续将 audit 合入 main 后清理临时分支及 fc51 工作树；保留主目录和 Downloads 安装包。
