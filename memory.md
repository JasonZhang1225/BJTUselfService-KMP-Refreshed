# BJTUselfService 实时工作记忆

> 当前分支：`feat/mac-swift`（已变基到 `main` @ `4a9c54ec` / PR #66）。2026-10-25 推进 **macOS 原生 SwiftUI 外观迁移**：新增 `macosApp/`（SwiftUI 宿主工程）+ `shared` 的 `macosArm64` 目标与 `macosMain` 平台实现，基线与本轮首页/弹层改动提交到本分支（未开 PR、未发新版）。JVM 的 `desktopApp`（Windows）未动，Mac 正式发布包仍走原 jpackage 链路。本地仓库只保留 `main` 与本分支。
> Windows 本机既有 MSI 记录（保留接手前未提交修改）：`C:\Users\zjg\Downloads\交大自由行 KMP-1.8.6.msi`（源码 `ab3eac6f` / 1.8.6-Beta2 / Build 43，87678976 字节，SHA256 `9DD504400EAF0968124A1E32C2347F5473C59258CFF8D84AE6575FED96E42645`），ProductCode=`{44161E10-B9DC-4B9F-B257-02DE4C744FE3}`，UpgradeCode 保留；已覆盖上一份 `6f4b07d8` 包，未替换 Program Files 安装版。

> 最后更新：2026-10-25（`feat/mac-swift` 已变基到 `origin/main@4a9c54ec`。首页正文已迁为 SwiftUI ScrollView/卡片，系统工具栏真实滚动模糊已点验；原生登录/弹层/工具栏及首页状态矩阵已检查。代码与 `HomeScreen.kt` 均可自动叠上 PR #66；仅 `memory.md` 需手改。其余业务页与完整原生迁移目标仍在进行）。
> 既往整合记录：`fix/beta2-desktop-agent-classroom`（Beta2 整合、版本兼容修复、iOS ChatGPT 插件页顶栏玻璃修复与 CI 竞态测试修复已于 2026-10-22 按所有者授权推送 main，见日志；App/Site/插件/模板为 1.8.6-Beta2 / Build 43；debug-1.8.6-Beta2 已按用户授权推至最新 origin/main@ab3eac6f）。2026-10-22 按用户授权直接在本地主干提交数据保护修复并推送（禁用自动删库、凭据保护、启动取消后重试；1088 项测试通过，四端相关编译通过；未重新打包），细节见日志。2026-10-22 所有者明确授权将本次全部修改直接推送 main：完整长码 App 配对、公共模板及文档提交 `27efa42b` / `9de3eade` / `08b9286b` 已进入主干；以 `--force-with-lease` 推送，保留原有历史。模板 PR #54 已关闭。私人 Site 已部署成功（源码 `495efffe`、`has_mcp=true`）。本机唯一远端 `origin` = `JasonZhang1225/BJTUselfService-KMP-Refreshed`，GitHub 已脱离 fork。验证与交付细节见日志；本地三端包已生成（源码 `6f4b07d8`，未发版；用户已覆盖安装 IPA，未包含本次数据保护修复）：Release APK 约 5.8 MiB、DMG 约 82.5 MiB、供 SideStore 自签的 Release IPA 约 22.0 MiB，均为 arm64；目录 `~/Downloads/BJTUselfService-1.8.6-Beta-6f4b07d8-20261022/`，含 SHA256SUMS.txt 和构建说明。
> 上次任务分支：`docs/agent-setup-main`（基于 `main@9c7eb55a`；Agent 搭建入口改 main，按用户授权直接推送；验证见日志）。PR #40 图书馆、#41 灵动岛/实时活动/手表、#42 作业搜索与教室当前节次、#46 课程实时动态（Android Live Updates + 双端课前准点出现）均已合入（合并提交 `64c24ab`、`6f5c36f`、`8189baf`、`aac1816`）；#45 验证码模型移除与安装包瘦身（合并提交 `a58e7c6`）、#48 iOS 作业上传修复与上传页统一（合并提交 `ad2a082`）均经所有者实测后合并（2026-10-22），合并后主干安全检查 #179 与打包 #113 见日志；#50 AI Agent 与 ChatGPT 私人远程查询（合并提交 `a18369d`）已合入；#51 v1.8.6 发布说明（`docs/releases/v1.8.6.md`，合并提交 `7513348`）已合入；#52 启动弹窗合并与 1.8.6-Beta 版本号待所有者合并。细节见 `history_full.md`。远端另保留 `claude/宣传图制作`。
> 私人 Site 线上仍为 1.8.6-Beta2（部署需 Codex 的 ChatGPT Sites 连接器，Claude Code 会话无此工具）；模板源码已改 1.8.6-KMP，Beta2 App/模板互不拦截（Site 不校验客户端版本，App 只要求模板数字核心 ≥0.4.0）。
> KMP 源码版本：main 已为 `1.8.6-KMP` / Build 44（2026-10-22 按所有者授权推送，未打标签；正式发版 tag 应为 `v1.8.6-KMP`，发布日期定 2026-10-23，说明 `docs/releases/v1.8.6-KMP.md`）；此前 `1.8.6-Beta2` / Build 43（已推 debug-1.8.6-Beta2 → ab3eac6f，KMP package #39777575554 排队中；下载目录已有 Beta2 / Build43 的三端 Release 包，未安装）；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。iOS 最低系统 18.0。
> 2026-10-22 仓库清理：冻结原 Android 工程（`app/` 与根 Gradle 工程、`release.yml`/`debug.yml`）与验证码 torch 模型（含 Windows `BJTUCaptcha.pt`、DJL）已移除，存档于标签 `archive/legacy-android-and-captcha-model`（`8189baf`）；Windows 验证码改为手动输入。只保留 `origin` = `JasonZhang1225/BJTUselfService-KMP-Refreshed`；所有者已确认在 GitHub 解除 fork 关系，本机远端配置也已对齐。
> 认证现状（2026-10-20）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码；智慧教学仍卡在学校的旧 CAS 跳转。
> 已完成工作的细节见 `history_full.md` 末尾日志。

## 进行中

- **macOS 原生 SwiftUI 外观（`feat/mac-swift`，2026-10-25，已变基到 `main@4a9c54ec`）**：
  - 架构：新增 `multiplatform/macosApp/`（SwiftUI Xcode 工程，仿 `iosApp` 结构）+ `shared` 增加 `macosArm64()` Kotlin/Native 目标与 `macosMain` 源集。侧栏、工具栏、材质由 SwiftUI `NavigationSplitView` 画，右侧内容区仍是共享 Compose 业务界面（与 iOS「UIKit 承载 Compose」同构）。JVM `desktopApp` 保留给 Windows；Mac 正式发布包暂时仍走旧 jpackage 链路。
  - 关键实现：`shared/src/macosMain/.../MacosComposeHost.kt` 自建 Compose→`NSView` 宿主（CMP 1.12 的 macOS 目标没有 iOS 那样的 `ComposeUIViewController`，只有会自建 `NSWindow` 的 `ComposeWindow`）；`MacosTextInputService.kt` 补文本输入；`MacosAppController.kt` 导出给 Swift 的入口；`shared/.../feature/shell/NativeSidebar.kt` 定义原生侧栏条目与 `NativeSidebarBus`。
  - 共享壳层改动：`App()`/`LoginRoute()`/`AuthenticatedAppShell` 新增 `nativeSidebarEnabled`、`nativeSidebarBus`、`onNativeSectionChanged`、`onNativeScrollProgress`；宿主侧栏驱动时宽屏不再自绘 Compose 侧栏，窗口类固定 `Expanded`。
  - 验证：`./gradlew :shared:compileKotlinMacosArm64` 通过；`xcodebuild -project macosApp/macosApp.xcodeproj -scheme macosApp -configuration Debug -destination 'platform=macOS'` 通过；开发版（bundle id `…kmp.macos.dev`）已在本机跑起，Computer Use 截图确认：原生半透明侧栏（搜索框 + 首页/成绩/课程表/考试安排/作业/课件下载/教室查询/邮箱/图书馆/校历下载/成绩单下载/设置，SF Symbols）、原生工具栏（标题「交大自由行」+ 副标题「首页」+ 刷新按钮）、底部账号行，右侧 Compose 登录页正常渲染。
  - 踩过的坑：① `PlatformContext` 委托对象里同 getter 会自引用导致无限递归；② `SkiaLayer.attachTo` 必须在视图**进窗口之后**调用，否则 `MacOsMetalRedrawer` 空指针崩溃；③ 绘制必须走 `SingleComposeSceneRenderingScope.render(frameRecomposer, canvas, nanoTime)`，直接调 `ComposeScene.draw` 不会驱动重组（只渲染 2 帧、界面停在第一帧）；④ macOS 系统 libsqlite3 不导出 `sqlite3_load_extension`，用 `macosApp/SQLiteExtensionShim.c` 补两个空实现才能链接。
  - 本轮用户反馈：版本说明确认无法点击，右侧与登录仍呈现独立 Compose 页面；标题栏/导航未完成原生桥接。已定位 `MacosComposeHost` 把 `locationInWindow` 直接当内容坐标，漏掉侧栏/标题栏偏移；改为 `view.convertPoint(..., fromView = null)` 后再翻转 Y，并使用 Mouse 事件与点击后 first responder。
  - 本轮改动：新增 `NativeDesktopPresentation`，SwiftUI 原生登录表单与版本说明 sheet；复用 `NativeBarAction` 同步标题、返回、刷新、状态、页面动作/搜索，原生侧栏选择统一替换一级栈；新增 `MacosSheetPresenter`，接入既有 iOS 同类 `NativeSheetPresenter`，每个弹层独占正文宿主，SwiftUI 负责标题/操作与模态窗口，按 id 隔离旧弹层关闭回调；窗口改用单例 `Window`；尝试实际工具栏高度与内容 underlap，不再只切换工具栏背景。新增离线 `--native-ui-smoke` 模式（独立预览类，无学校请求、无账号缓存读写），用于点验倒计时、确认、登录字段与共享 sheet。
  - 本轮验证：第一轮 macOS `compileKotlinMacosArm64` 和 Xcode Debug 构建成功，启动后截图确认重复的页内标题已撤掉，状态与刷新进入原生工具栏。`JAVA_HOME=<Android Studio JBR> ./gradlew :shared:desktopTest :androidApp:assembleDebug :windowsApp:compileKotlinWindows :desktopApp:compileKotlin :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64` 成功，1110 测试 0 失败；本轮最新多端回归再次成功，新增 `NativeDesktopGuideTest` 后为 1111 测试 0 失败，覆盖原生倒计时快照与弹层清理。日志 `.artifacts/mac-native-{compile,xcode,regression}.log`。
  - 已实测：离线预览的版本说明 3→2→1→0 后按钮启用、点击确认关闭；原生学号/密码输入、登录按钮启用、保存开关往返；虚构登录提交打开原生共享 sheet、正文可见、确认可关闭，未向学校发请求。倒计时曾冻结，真实原因是读取 `remaining` 在 `SideEffect` 内未订阅状态；已将快照构建移到 composition 并补上述测试，撤掉无必要性证据的额外帧循环。SwiftUI sheet 正文通过独立 ObservedObject 宿主接收后续更新。截图 `.artifacts/mac-native-{guide-light,login-light,shared-sheet}.png`；日志 `.artifacts/mac-native-preview.log` 只含倒计时，无账号信息。
  - 原生版本说明/登录浅深色均已查看（`--native-ui-dark` 只覆盖预览进程外观，未改系统设置）；截图 `.artifacts/mac-native-guide-dark.png`、`mac-native-login-dark.png`。源码主页面截图确认工具栏标题/同步/刷新为原生、页内重复顶栏已撤去，脱敏裁图 `.artifacts/mac-native-home-top.png`。
  - 本续轮进行中：首页正文从 Compose 转为 `NativeHomeView.swift`（SwiftUI ScrollView + 系统 scroll edge effect、周/日选择、课程/作业/考试条目、下一节、未读邮件/图书馆、变动入口）；`HomeWorkspace`/`HomeAgendaSection` 仍负责日程计算、校历自动跟随保护、回调和详情弹层，通过纯数据 `NativeDesktopHome` 快照提供给 SwiftUI，不重复业务规则。原生首页不再设置手工 toolbar 材质，按 Apple scrollContentBackground/scrollEdgeEffectStyle 使用系统效果。新增 `--native-home-smoke` + normal/loading/empty/error 隔离场景，及 `NativeHomePresentationTest`（手动选周不被异步当前周覆盖、日期选择/回调/加载转空态/释放）。首轮 Xcode 和全平台回归成功（1113 测试 0 失败），已实际打开原生首页。后续又修复无校历时 `resolveHomeAgendaWeekStart` 将周差误当日差（改为 ×7 天），补跨月测试与原生下一周日期断言；最终回归 1114 测试 0 失败，Android assembleDebug、desktop/windows 编译、iOS arm64/simulator arm64 编译与 Xcode Debug 均成功（`.artifacts/mac-native-home-{regression,xcode}.log`）。实际点击下一周显示「第 7 教学周 / 10月26日 — 11月1日」。
  - 首页运行证据：`--native-home-smoke` + 固定时钟/只用虚构课程与内存仓库，实际点验日期选择、今天、课程详情打开/关闭；AX 可读到原生日期按钮/课程按钮/滚动区，操作回调与日志日期/条目数对应。初始安全区域已调整，首页大标题不再进工具栏；原生滚动条从 0→0.67→0.5 时，教学周标题/日期真正滚进系统工具栏并模糊，截图 `.artifacts/mac-native-home-light-{top,scrolled,mid}.png`（全部虚构数据）。课程详情 `.artifacts/mac-native-home-course-sheet.png`；日志 `.artifacts/mac-native-home-runtime.log` 仅记预览日期/条目数。
  - 单次退出需继续追踪：首个离线预览曾发生未处理异常，在 Kotlin 输出堆栈的 CoreSymbolication 路径发生 EXC_BAD_ACCESS（`~/Library/Logs/DiagnosticReports/交大自由行 KMP-2026-10-25-160105.ips`）；无原始异常信息，按该 PID 查询系统日志未取得记录。后续一次 3 分钟、调整安全区域后一次约 5 分钟及日期/详情交互均未再现，不据此认定已彻底修复。CUA 可能在进程结束后自动拉起无参数普通模式，验证前必须核对精确 PID 和 `--native-home-smoke`，禁止把普通账号界面作为离线截图保存。
  - 首页状态矩阵：实际查看浅色/深色正常态（含系统边缘模糊）、loading（双处加载提示、刷新禁用）、empty（明确空日程与双列摘要）、error（橙色就地提示，课程与缓存摘要保留）。截图 `.artifacts/mac-native-home-{dark-top,dark-mid,loading,empty,error}.png`；error/loading 截图另裁正文为 `*-detail.png`。通过系统窗口菜单缩放检查 960 pt 与 1470 pt 窗口宽（截图 `.artifacts/mac-native-home-wide.png`），侧栏 224→320 pt 检查正文收窄后长标题仍完整（`.artifacts/mac-native-home-narrow-wrap.png`；本样例未出现截断，不等于字号放大已验）。宽窗口摘要改为最多两列并平分可用宽度，空图例不再占空行；次级类型文字改用原生 secondary 语义色，颜色条仍来自共享课程调色板。最新视觉修改已再经 Xcode 构建。所有预览仓库/课程均为虚构或内存数据，未连接学校。
  - **未完成**：字号放大及其余业务页窄宽/输入完整验证，其余 Compose 页面点击修正的真实交互与顶栏滚动效果（首页已单独验证原生滚动模糊）；首页以外的右侧业务正文仍为 Compose；其余 Compose 输入框的完整中文输入法与系统编辑菜单尚未实现；Xcode .app/DMG 打包与 CI、AI Agent/扫码/日历/附件等能力对齐尚未完成。必须继续推进原目标，不以外壳或编译成功认定完成。
  - 源码运行：本续轮验证结束已按精确路径结束所有开发版源码进程，安装版仍保留；只使用 `multiplatform/.artifacts/macosapp-dd/Build/Products/Debug/交大自由行 KMP.app` 开发版，安装版 `/Applications/交大自由行 KMP.app` 保持运行且不修改。重启源码版前按精确路径确认并结束其 PID，禁止宽匹配结束安装版。


- 设置页“版本与项目”重构（分支 `claude/kind-darwin-u2fxah`，2026-10-23，PR 待所有者审核）：系统版本/App 版本/构建号三行（新增 `AppUpdateChecker.CURRENT_BUILD`，`SecurityReleaseConfigTest` 断言与 Android/iOS 宿主/小组件/macOS 构建号一致，改构建号时需同步此常量）；更新源改 Gitee｜自动选择｜GitHub 三段滑块（默认自动，说明随选项变化）；仓库链接分“项目仓库”（GitHub/Gitee，带品牌图标）与“相关仓库”（交大自由行 NEO（原版）、AIO（跨平台））。验证：`./gradlew :shared:desktopTest :windowsApp:compileKotlinWindows` 1110 项 1 失败（`DesktopAgentIntegrationTest.installWritesWorkingScriptAndSkill` 退出码 127，撤下改动后在原代码上同样失败，云端 Linux 环境问题）；`VersionAndProjectCardRenderTest` 离屏渲染浅/深色、1.4 倍字号、检查中状态，截图 `.artifacts/settings-version-card/`。Android/iOS 构建与模拟器实机仅 CI 验证。

- `debug-1.8.6-Beta2` 打包（2026-10-22）：所有者明确要求基于远端最新打标签；目标 `origin/main@ab3eac6f4bf786774ee21e9572dfe4a56da6c43d`，安全检查 run #39774068051 success。远端 annotated tag 已验证 peeled SHA 与目标一致。KMP package run #39777575554 已触发，当前 queued，链接 https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/39777575554 。只打四端包、不创建 Release；尚未验证此次 Actions 最终构建结果。


- main@ab3eac6f 本地三端包已交付（未发版/未安装，2026-10-22）：`~/Downloads/BJTUselfService-1.8.6-Beta2-43-ab3eac6f-20261022/`，APK 5.8 MiB、IPA 22.0 MiB（另附 .ipa.zip）、DMG 82.6 MiB，全部 Release / arm64，版本仍 1.8.6-Beta2 / Build 43；含 iOS ChatGPT 插件页顶栏玻璃修复与 App 版本解析兼容（Beta2-43 首包不含）。`./gradlew :androidApp:assembleRelease :desktopApp:packageDmg` 与 `xcodebuild … -configuration Release -destination generic/platform=iOS … CODE_SIGNING_ALLOWED=NO build` 成功；APK 签名证书与首包一致、DMG 挂载/codesign/Build43/Swing 依赖、IPA ad-hoc 签名/App Group/宿主与小组件 Build43/bundle ID 不变/zip 完整性均通过，SHA256SUMS 核对通过；源码清单摘要 `3ec6b470…`。日志 `.artifacts/local-package-ab3eac6f/`。未验证 SideStore 重签后的真机覆盖安装、Keychain 与小组件行为。

- Beta2 配对版本误判及安装文案（2026-10-22）：用户 Build43 真机截图把已部署 1.8.6-Beta2 判为低于0.4.0；已用原打包 JAR 实际复现纯数字解析器拒绝 Beta 后缀。App 改为解析 SemVer 数字核心、保留旧多设备门槛；安装页/复制提示词/升级提示引用 App 当前版本，清除可见0.5.0硬编码。公共模板 health 为旧 App 返回数字 templateVersion=1.8.6，完整发布 releaseVersion=1.8.6-Beta2 保留于界面/MCP；兼容源码 `0df877a6ab3b7d3cb7f557b958a50515ab5224a7` 已在先前授权下同步私人 Site 源码仓库并归档，尚未部署；用户随后要求先全部本地提交，因此停止后续部署和重新打包。当前线上仍是上一部署 `b1d64646`。Site69项测试通过，原 Build43 JAR 复现后缀解析失败且数字兼容版本可通过（`.artifacts/beta2-installed-parser-check.log`）；`./gradlew :shared:desktopTest :androidApp:assembleDebug :desktopApp:compileKotlin :windowsApp:compileKotlinWindows :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64` 成功，1091项测试0失败；日志 `.artifacts/beta2-version-compat-gradle.log`。现有下载包尚不含 App 解析/文案修复；服务端兼容层设计面向已安装 Build43，需后续部署才生效；这次没有重新生成安装包。


- Beta2 本地三端包已交付（未发版/未安装，2026-10-22）：`~/Downloads/BJTUselfService-1.8.6-Beta2-43-20261022/`，含 APK 5.8 MiB、IPA 22.0 MiB、DMG 82.6 MiB（全部 Release / arm64）、SHA256SUMS.txt 和构建说明.txt。基于 `2cb49216` 加未提交 Beta2 改动；构建前后849个源文件哈希一致，源码清单摘要 `60e159ec9482abe247f435031adc5304720250f41cb99c73650d2c5b937e0972`。`./gradlew :androidApp:assembleRelease :desktopApp:packageDmg`、`xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination generic/platform=iOS -derivedDataPath ../.artifacts/local-package-beta2/ios-derived CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= build` 均成功。Android apksigner/aapt 通过，证书与旧包相同；DMG 校验/只读挂载、包 codesign/arm64/Build43、Swing dispatcher 与 Beta2 class 常量验证通过，验证卷已卸载；IPA 依仓库 sign-ios-sideload-app.sh 嵌套 ad-hoc 签名、App Group、宿主/小组件版本、原有 bundle ID、arm64 和 zip 完整性均通过；三份 SHA-256 全部核对通过。日志/校验脚本在 `.artifacts/local-package-beta2/`。未验证 SideStore 重签后的真机覆盖安装、Keychain/小组件行为；没有安装/替换应用、提交/推送或推 tag。


- Beta2（2026-10-22）：课间/晚饭与首节前高亮当天下一节，文案区分“下一节”；Mac 补远程控制器、系统安全存储、桌面轮询/上传、退出释放及 Dock 后台说明，补 Swing Main dispatcher 依赖（原生报错与缺依赖一致）；Site 独立设备卡展示最近联系/在线、各自快照、范围与撤销，手机标记按需联系而非持续在线；App/Site/MCP/插件/模板版本统一 1.8.6-Beta2，构建号43；验证已结束：`./gradlew :shared:desktopTest :androidApp:assembleDebug :desktopApp:createDistributable :windowsApp:compileKotlinWindows :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64`（Android Studio JBR + Temurin25）成功，202 套件1089 测试0失败；Site 68测试通过，`git diff --check` 通过。Mac 独立虚构数据 `--remote-ui-smoke` 已实际启动，确认“主线程调度器正常”和完整长码配对入口（`.artifacts/beta2-mac-remote.png`），源码实例已结束；未连接真实 Site 或学校账号。多设备页面已用虚构 Windows在线、Mac离线、手机按需上传在 IAB 检查，截图 `.artifacts/beta2-site-device-status.png`。私人 Site 已部署 Beta2（见 history_full.md）；App/公共模板代码本次仅本地提交，未推送或打标签。iOS 真机、Windows 原生运行、macOS 真实配对及后台长时间运行未验证；三端本地安装包已生成，见上条。


- 本地安装包验证记录（未发版）：`main@6f4b07d8` / `1.8.6-Beta` / Build 42。`JAVA_HOME=<Android Studio JBR> DESKTOP_PACKAGE_JAVA_HOME=<Temurin 25> ./gradlew :androidApp:assembleRelease :desktopApp:packageDmg` 成功；`xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination generic/platform=iOS -derivedDataPath ../.artifacts/local-package-6f4b07d8/ios-derived CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= build` 成功。APK 经 apksigner、aapt 验证；DMG 经 hdiutil verify、只读挂载后的 codesign/版本/架构检查，已卸载验证卷；IPA 经仓库 sign-ios-sideload-app.sh 依次 ad-hoc 签名并验证 App Group、宿主/小组件版本与 codesign，unzip -tq 通过。产物已交付下载目录，日志 `.artifacts/local-package-6f4b07d8/`；未安装或替换 App，未验证真实安装/配对，不代表发布签名成功。

- 已合入功能的待真机验证（不阻塞）：
  - 灵动岛/实时活动：收起态最终版截图（等实际上课时）；开课时“即将上课”切“正在上课”；两个实时活动并存时的最小态小圆环；Live 菜单读屏自定义操作。
  - iOS 26 预约启动（PR #46）：真机上的启动提醒声音与灵动岛展开效果；重叠/紧挨日程的实际表现；划掉系统启动的那一项后不再出现。
  - Android 实时动态（PR #46）：非 HyperOS 的原生 Android 16 上的样式；长时间不打开 App、系统降低待机分组后闹钟是否仍准点；最近任务未加锁时划掉 App（强停）会清掉闹钟和通知（按规则推断，未实测）。
  - 图书馆：登录流程在 Copilot 修复后改过，需真机重新登录一次；iOS 横屏布局（浮动续借胶囊、顶栏高度）；Android 真机；续借只在早期版本实测过。
  - 作业搜索：iOS 系统搜索按钮展开/取消动画、搜索中进详情再返回；Android 返回键收起搜索；上课时段教室当前节次光晕与空闲/占用标签（浅/深色）。
  - Windows：移除 torch 模型后，遇到验证码时手动输入路径未在 Windows 真机走过；PR #45 原生库裁剪后的 MSI 启动与本地缓存（SQLite）读写、DPAPI 凭据读取未实机验证（Windows 冒烟未在最终提交上运行）。
  - 作业上传（PR #48）：CITEL 上传与编程题单文件提交未真机实测；Android/桌面上传由对话框改为底部 sheet，未实机查看；sheet 内保存面板（日历导出）与快速查看的真机表现未点验。
  - 完整长码配对（main@08b9286b）：真实手机与线上 Site 的配对、手机/电脑同时上传、原生容器交互、此次签名包的 Keychain 往返、Windows 实机未验证；已安装 App 需更新后才有单输入框。
  - AI Agent / ChatGPT 插件（PR #50）：手机 ChatGPT 端到端查询（卡在插件补授权，需在手机上重新连接后验证新工具目录）；电脑与手机同时配对的实机验收；Windows 托盘在混合 DPI 多屏下的位置与退出回调。
  - 1.8.6 版本说明弹窗（PR #52）：新装/升级各走一遍，确认后不再出现；Android 版本说明后接通知权限说明的顺序。
  - PR #45 登录收紧：需要验证码时的手动输入弹窗、静默自动登录失败后的引导弹窗、Android release（R8）包的签名覆盖安装，均未真机走过。

## 当前痛点

- iOS 覆盖安装后丢独立密码、设置重置及首页字号/布局异常：用户决定暂按偶发处理，原因未定（SideStore 仅为猜测），停止继续追查。已在主干禁用自动删库并保护凭据；日程等待重启后恢复。若复发再取真机证据；Beta2 / Build43 下载包已包含保护修复；旧 Build42 包仍未替换。

- 智慧教学（课程平台作业/课件）：直连 module 28 仍被学校跳到已下线 CAS，但 1.8.5-EmergencyFix2 起回退经本科服务平台 `jwcZhjx` 建立会话，作业/课件可正常获取（见日志 2026-10-20）；学校改到 SSO 后可去掉回退。
- 同步卡顿：Beta3 已到可接受范围。剩余长帧为同步详情 sheet 弹出（约 50ms）、进入作业页（约 49ms）和原生滚动带来的导航栏布局；`checkSession` 改后台后未复采；壳层随任一状态流整体重组（约 0.4 秒/次同步），彻底解决需拆分壳层。
- 小组件：PR #8 的 ad-hoc 签名后，侧载包的小组件共享容器是否可读尚未在真机确认；周视图、中号两栏、脚注只经 CI 编译，未在模拟器浅/深色下查看，小屏 iPhone 周视图可能偏挤；“正在上/还剩 N 分钟”未在上课时段实机观察。
- 首页物理在线/CITEL 日程项进入二级详情只在代码层实现，模拟器只验证了演示实验。
- “保存二维码并扫一扫”未真机验证（Android MediaStore 保存/去重、扫码后进入小程序/缴费页）；iOS 每次保存会多一张；Android 二维码弹窗未在平板上看。
- HyperOS 小窗↔全屏来回切换未实测（adb 无法切换窗口模式）。
- 课件 15 分钟大文件超时未用 >30 秒真实下载验证；iOS/Android“未开放下载”标记只验证编译。
- 在 Chrome 登录智慧教学会顶掉 App 会话，App 自动恢复登录失败一次（需手动刷新），未排查。
- macOS 附件默认应用打开、iOS 保存面板未点验；`HomeworkFileGateway` 已被邮箱复用，可改名为通用文件网关。

## 已知限制（非缺陷，排查时先看这里）

- macOS 原生端（`feat/mac-swift`）已用 SwiftUI 原生表单解决登录字段；其余 Compose 文本框的完整输入法/系统编辑仍未对齐：Compose Multiplatform 1.12 的 macOS 目标里 `MacosTextInputService` 只存值不回传编辑命令（上游 TODO CMP-9858），macOS 的 `InteropView` 也只是 `typealias InteropView = Any`。这是上游缺口，不是本仓库的 bug；要在 macOS 原生端登录需自写 `NSTextInputClient` 或改用宿主侧原生输入框。

- 教务系统 `aa.bjtu.edu.cn` 会临时拒绝 443 连接（2026-10-20 晚间约 1.5 小时，MIS 同时可达）；PR #33 起 App 照常进入主界面，仅成绩/课表/考试同步失败并保留缓存。复现可在 `KtorSchoolHttpTransport.executeOn` 临时对 aa 抛异常，测完撤销。
- 校历公众号文章不能由第三方 App 直接在微信内打开（invalid source），走系统浏览器。
- 小米“跟随应用内设置”覆盖安装清不掉：装新包后卸载重装，或在“使用高刷新率的应用”里打开交大自由行 KMP。
- 本地 Gradle 偶发被 Maven Central 429 限流，此时测试与 iOS 编译交给 PR CI。
- 使用中会话过期且学校恰好要求验证码时，后台会话恢复会失败，提示“点右上角刷新”也无效，需退出重登或重开 App 才会看到验证码（各端已无识别模型）。用户 2026-10-22 决定暂不在主界面加验证码弹窗。
- 本地 Linux 容器跑 `:shared:desktopTest` 时 `DesktopAgentIntegrationTest.installWritesWorkingScriptAndSkill` 因缺命令（退出码 127）失败，main 上同样如此，与改动无关。

## 迁移期遗留（长期，无排期）

- iOS Keychain 合法签名往返、正式签名发布（无 Apple Developer Team）。
- 物理在线真实上传、Windows 触摸屏；邮箱真实发送/删除/移动/附件上传；真实作业上传、退出账号往返（等学校侧条件）。

## 接下来

- 后续开发统一从最新 `main` 建分支，PR 以 `main` 为目标。

1. 在真机已有版本（SideStore 保留扩展）上验证小组件共享容器与各尺寸显示。
2. 华为 HBN-AL10（HarmonyOS 4.2）装含 PR #32/#33 的新包：确认课表小组件尺寸（logcat `CourseWidget` 标签）与教务不可达时不再闪退。
3. 真机过一遍“进行中”列出的待验证项。
4. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。
5. Android 首次打开的通知权限说明（分支 `feat/android-notification-intro`）：说明框已在启动队列里排在 1.8.6 版本说明之后，只经编译验证，未在模拟器/真机走过。多余启动弹窗已于 PR #52 合并为一个 1.8.6 版本说明。
6. 清理 main 里经 1.8.5 紧急修复带进来的半成品 AI 代码（PR #50 合入前的计划，未开始）。
