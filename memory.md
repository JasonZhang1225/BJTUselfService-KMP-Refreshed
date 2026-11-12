# BJTUselfService 实时工作记忆

> **当前状态（2026-11-12）**
> - **当前分支**：`fix/phyvlab-manual-captcha`，从 `origin/main` `b6e9f358`（PR #100）开独立工作树，跟踪 `origin/fix/phyvlab-manual-captcha`，包含 iOS 验证码短时读取、登录流程和弹层修复，尚待合入。
> - **源码版本**：`2.0.0-Alpha` / Build 45（最新正式 Release：`v1.8.6-KMP` / Build 44）
> - **远端活跃分支**：`main`、`feat/mac-swift`（macOS 原生 SwiftUI 探索）；原 `feat/homework-submit-cli-drop`、`feat/agent-assignment-detail` 已合入并删除。
> - **已完成事项归档**：2.0 开发期已合入 `main` 的全部功能（课程回放、macOS/Windows 硬件合成、作业忽略与筛选拆分、全应用设备时区、离线更新日志、`DESIGN.md` / `AppleAlert` / `AppleMenuPickerCard`、物理实验 IP 备用路径、上传文件拖放、本机 CLI 交作业、统一作业详情与远程增量同步等，PR #68–#99）均已移入 `history_full.md`。

---

## 进行中 / 待处理：物理在线相关检查

### 1. 物理在线登录恢复（2026-11-06 CDP 取证）

- **问题一（网页独立账号登录新增点选验证码）**：
  - 学校物理在线独立登录页新增阿里云图形点选验证，需先 `POST /login/captcha_verify.php` 通过验证后再提交 Moodle 登录表单。
  - 用户已确认手动点选后可以正常登录，但每次新建立会话均需点选；App 现有独立登录协议只提交账号、密码和隐藏字段，无法在无界面下完成图形点选。
- **问题二（统一身份认证 OAuth2 回跳与账户信息拉取失败）**：
  - 统一认证走 `cas.bjtu.edu.cn/o/authorize/`。首轮与第二轮（2026-11-06）Chrome CDP 实测发现两层问题：
    1. **回调参数拆分**：原始回调带授权码时，`state` 内嵌的 `sesskey` 被拆到了外层查询参数；仅在拆出字段与本次原始 `state` 一致时恢复原编码，同一 `MoodleSession` 下可通过原会话校验并将授权码转发为 `oauth2code`。
    2. **服务端账户信息缺失（阻塞项）**：通过回调会话校验后，`/admin/oauth2callback.php` 303 到 `/auth/oauth2/login.php`，后者 303 回登录页，中文提示「缺少账户信息，可能是配置有误」，英文明确显示 `No user information was returned`（对应 Moodle 上游 `loginerror_nouserinfo`，不同于字段不全的 `loginerror_userincomplete`）；三次复测（中文登录页、英文登录页、从“我的课程”触发）一致复现，访问 `/my/courses.php` 同样 303 回登录页。Moodle 服务端到学校身份提供方的 userinfo 请求及后台映射配置在客户端不可见、无法从客户端修复。
- **2026-11-09 本分支在做**：搁置学校 OAuth2。首页和作业同步状态新增「输入验证码」，设置页保存时先完成点选。独立会话收到验证码通过结果后才提交一次密码，确认「我的课程」可访问再接入应用会话。失败或取消保留设置页输入，已有缓存仍可读。
- **真实页面取证**：临时调试窗口点选出现「人机验证异常」。AutoConnect 接入用户正常 Chrome 窗口后确认学校已登录，`/my/courses.php` 返回 200 并有退出链接。桌面应用使用系统浏览器打开临时本机点选页，手机使用平台 WebView，页面不持有账号、密码或 Moodle 会话。
- **本地验证**：`./gradlew :shared:desktopTest` 重跑通过（1438 项，跳过 1 项）。`:shared:compileKotlinIosArm64`、`:shared:compileKotlinIosSimulatorArm64`、`:androidApp:assembleDebug`、`:desktopApp:compileKotlin`、`:windowsApp:compileKotlinWindows` 通过。Android Pixel 10 Pro XL 模拟器使用虚构凭据验证学校验证码加载、进入点选、关闭后返回原设置页并保留两个输入框。iOS 签名模拟器构建成功，iPhone 17 Pro 的显示连接及安装卡住，按用户要求改用 Android。macOS 源码窄窗口 smoke 启动后已退出，未目视验收。
- **Android 真实账号验证**：用户完成点选后，Moodle 登录 POST 返回 303。保存后返回原生页面时，旧页面作用域取消了首次刷新，所以作业先显示缓存和「等待同步」。用户手动刷新后，日志确认复用会话并拉取 4 门课程、20 条活动记录。
- **后续修复（代码待提交）**：账号设置与同步状态两处的登录成功回调改为由会话刷新协调器提交物理在线刷新。有效的 Moodle 会话使用按 MIS 与物理在线账号隔离的系统安全存储，重启后先探测课程页再复用；明确返回登录页时清除会话，临时网络失败则保留。显式退出或清除物理在线账号时清理会话。
- **最新验证**：在持有真实会话期间完成共享桌面测试（1442 项，1 项跳过，全部通过）；重跑 Android、桌面、Windows、iOS arm64、iOS simulator 编译及 Android Debug 包构建成功。iOS 签名 simulator 构建成功，Device Hub 显示连接卡住，按用户要求只用 Android 做界面验证。
- **尚待验收**：本轮保留 Android 模拟器现有真实登录，未安装会话持久化版本，因此升级后的冷启动复用尚未实测。
- **本地测试包（2026-11-09，Build 45）**：签名 Release APK 与 ad-hoc 侧载 IPA、Apple Silicon DMG 已复制到 Downloads。APK 签名指纹和正式版 v1.8.6 一致。IPA 的宿主和小组件 App Group 与版本/Build 已校验。DMG 包内 App 签名、macOS SDK 27.0、最低 macOS 12.0 和磁盘映像校验通过。这些是本地下载文件，不是 GitHub Release。

### 2. 物理在线作业详情与老师附件真实页面校验（PR #98/#99 后续）

- PR #98/#99 新增了从物理在线作业说明 HTML 中解析老师附件（`pluginfile.php` 链接与内嵌图片）、草稿/最终提交状态判定（`phyVlabAssignmentDetailHasSubmission`）以及单批详情同步最多恢复 1 次会话的保护。
- 此前受上述登录问题阻塞，老师附件解析仅通过脱敏 fixture 测试验证；待登录恢复后，需用真实学校页面核对 `pluginfile.php` 附件解析、重定向白名单下载与缓存补全。

### 3. 手动验证码在 iOS 上提交后卡住

- **现象**：iPhone 完成点选、验证码变绿后，弹层停在页面自带的「正在提交验证结果…」，`开始验证/重新加载` 均为禁用态，物理在线进不去；同期 iPad 的旧会话一小时后仍可用。
- **判断**：旧版截图将范围缩小到网页产出结果后、原生弹层切换到登录状态前。学校登录页没有「其他设备登录 / 踢掉」类文案，平板会话也没被踢；iOS 用每 350 ms 轮询 `window.phyVlabCaptchaProof`，页面已经跑完 `$send` 却始终没被宿主取走。
- **11-09 尝试**：iOS 改为 `WKScriptMessageHandler`（名称 `phyVlabCaptcha`）由 WebKit 主动投递，去掉轮询与 WebView 引用；`phyVlabCaptchaHtml` 改为按平台选交付方式（`WindowVariable` / `ScriptMessage` / `LoopbackFetch`）；页面交付后 15 秒没有反应就把「重新加载」放回来并提示；iOS 页面加载失败回传 `onUnavailable`；提交验证结果加 60 秒超时，超时后恢复「重试」与可关闭；新增 `PhyVlabCaptchaHtmlTest`（5 项）。
- **11-09 验证**：`:shared:desktopTest` 1447 项 0 失败 1 跳过（`*PhyVlab*` 80 项 0 失败）；`:shared:compileAndroidMain`、`:windowsApp:compileKotlinWindows`、`:shared:compileKotlinIosSimulatorArm64`、`:shared:compileKotlinIosArm64`、`:shared:linkDebugFrameworkIosSimulatorArm64` 均 BUILD SUCCESSFUL；签名 iOS 模拟器 `xcodebuild` Debug `** BUILD SUCCEEDED **`，装进 iPhone 17 Pro 模拟器启动到首页正常（未点验证码）。
- **侧载包（2026-11-09 20:10）**：`~/Downloads/BJTUSelfService-KMP-2.0.0-Alpha-build45-iOS-unsigned.ipa`（23 MB，SHA-256 `81af01a9b14824ec142a90f92b29a4813da4cb18a852622e656ad8f4792a1b17`）。Release `generic/platform=iOS` 未签名构建后按 CI 改名 `Payload/BJTUselfServiceKMP.app`、`scripts/sign-ios-sideload-app.sh` ad-hoc 签名（宿主与小组件 App Group 均 `group.team.bjtuss.bjtuselfservice.kmp`，版本 2.0.0 / Build 45 一致）。包内二进制已确认含新的页面提示与 `phyVlabCaptcha` 通道、不再含旧轮询表达式。同名旧包（15:52）移到 `.artifacts/package-build45-previous/`。
- **11-10 真机反馈**：11-09 侧载版仍先显示「正在提交验证结果…」，随后触发 15 秒未收到提示。截图能确认页面进入交付阶段，旧版究竟在哪一步丢掉结果仍待真机证据。用户此前在 Android 和 macOS 验证过 `a728ffb5` 版本，本轮反馈来自 iOS。
- **本轮改动**：iOS 由 `WKWebView.callAsyncJavaScript` 在主文档里等待单次结果，退出旧脚本消息通道。读取/解析失败与网页进程退出交给原生弹层结束当前尝试，重试新建 WebView。Android 仍使用页面变量，macOS/Windows 仍使用回环 POST。
- **11-10 限资源验证**：Gradle 改用单 worker、禁用并行、JVM 堆上限 1536 MiB。`:shared:desktopTest` 共 1447 项，0 失败、1 跳过（包含验证码 HTML 的通道检查）。iOS 真机与模拟器 Kotlin 目标、macOS、Windows 和 Android 共享源码编译通过。 Android 首轮 APK 构建遇到旧工作树路径的 dex 缓存，移开这一项本地缓存后，单 worker 重跑 `:androidApp:assembleDebug` 通过。脱敏 JavaScript 样例验证了四字段交付、重复回调只接受首个结果、超时后的重开提示。
- **11-10 运行验证边界**：命令行 WebKit 测试进程无响应，移出 CI 测试目录。独立 UIKit 测试应用编译通过。iOS 27.0 模拟器安装超时，iOS 27.1 模拟器报数据迁移失败且安装超时。已结束本轮模拟器尝试。签名宿主构建、iOS 运行时回传与 iPhone 真机点选仍待验收。

- **11-12 真机反馈**：SideStore 的 Build 45 截图依次显示验证码通过、正在提交结果，15 秒后显示应用未收到结果。故障发生在原生弹层进入「正在登录物理在线」之前，后续在同类真机现场确认 WebKit 中结果已就绪，见下文。
- **11-12 真机调试准备**：Xcode 对独立 ID `team.bjtuss.bjtuselfservice.kmp.ios.captchadebug` 的 Debug 签名构建成功，`codesign --verify --deep --strict` 通过，确认独立 App Group。iPhone 17 Pro（iOS 27.3）安装报免费描述文件的三个 App 名额已满。当时保留了 SideStore 版。用户要求后续只用真机，模拟器已关闭。
- **11-12 SideStore 诊断**：LLDB 曾成功附加到现有侧载版并分离；Release 默认关闭 AppLog，尝试临时打开未成功，控制台没有采集到验证码交接日志。随后用户移除了旧侧载版以腾出名额。
- **11-12 真机安装**：`xcrun devicectl device install app` 成功安装独立 Debug Build 45，设备应用列表已确认。用独立测试项验证 Keychain 写入、读取与清除，Device Hub 显示 `SECURITY_SMOKE_PASS`。以正常模式重新启动，并接上筛选过的验证码交接日志。

- **11-12 真机复测**：本次手动验证记录到 `captcha proof received`、Moodle 登录 POST 返回 303、同步会话就绪。随后两次用 `devicectl device process launch --console --terminate-existing` 冷启动，均无验证码与密码 POST，课程页 200 并解析 4 门课，确认有效会话可复用。用户指出本次没有复现会话失效，随后于当天晚间抓到复发现场。

- **11-12 真机故障现场**：iOS 27.3 调试版在首页物理在线验证码显示「已完成验证，但应用没有收到结果」。据用户复测，关闭弹层重输和重开 App 都复现。旧控制台文件停在较早的成功同步。LLDB 附加到同一进程，确认 WebView 停止加载、导航代理仍在、网页 `readyState=complete`、结果 Promise 已完成，原生代理仍为 `awaitingResult=true, active=true`。只读取布尔值与状态，未读取或输出验证码结果内容。
- **验证码结果读取修复**：去掉 iOS 端跨用户点选过程等待 Promise 的调用。WebView 加载本地验证码页后，原生每次用有超时的短求值读取已完成的页面结果，成功只交付一次，失败由原生弹层提供重试。Android 沿用原页面变量，桌面沿用回环。

- **修复版真机安装**：同一调试 Bundle ID 的 Xcode 真机签名构建成功，`codesign --verify --deep --strict` 通过，覆盖安装成功，设备上的独立钥匙串自检通过。正常启动后日志已连接，用户完成真实点选后仍卡住。控制台出现 `captcha proof received`，没有后续登录 POST，真机仍停在网页的超时提示。轮询已经取到结果，本次卡在原生登录流程启动之前。
- **修复版构建验证**：`:shared:desktopTest` 共 1446 项，0 失败、1 跳过；`:androidApp:assembleDebug`、`:desktopApp:compileKotlin`、`:windowsApp:compileKotlinWindows`、`:shared:compileKotlinIosSimulatorArm64` 均成功。只编译模拟器目标，未启动模拟器。

- **弹层内提交修复**：UIKit 原生弹层有自己的 Compose 根。验证码结果的接收和随后的登录协程移到实际可见的弹层正文，避免依赖背后页面的 Compose 调度。增加结果是否被接收、提交是否开始与结束的脱敏日志。
- **后续验证**：`:shared:desktopTest` 共 1446 项，0 失败、1 跳过。Android Debug、macOS/Windows Kotlin、iOS 模拟器目标编译和 iOS 真机签名构建均通过。新版已覆盖安装到 iPhone，钥匙串自检通过，正常启动后已接上真机日志。随后用户反馈首页同步弹层的「输入验证码」没有打开验证码弹层，Device Hub 同样复现。入口读取账号的协程已改用主线程调度，避免在 UIKit 弹层覆盖期间等待背后 Compose 页面的调度。随后覆盖安装了入口修复版，签名和钥匙串自检通过。Device Hub 确认首页「输入验证码」可以打开弹层，用户手动点选后日志依次记录收到结果、弹层接收、开始提交、登录 POST 303、会话 Ready，课程页 HTTP 200 并同步 4 门课。

- **登录成功后关弹层**：在真机课程同步成功后仍看到空的「物理在线验证码」弹层。同步入口成功后直接清除验证码弹层状态并刷新，取消路径仍返回同步详情，避免成功时同一轮关闭验证码又打开同步详情。新包签名构建与覆盖安装成功，钥匙串自检通过，冷启动复用现有会话，课程页 200 并同步 4 门课。成功后自动关闭需在下一次真实验证码流程验证。最终版 `:shared:desktopTest` 共 1446 项，0 失败、1 跳过，Android Debug、macOS/Windows Kotlin、iOS 模拟器目标编译及 iOS 真机签名构建通过。

---

## 已知限制与日常维护备忘

- **私人远程 Site 升级**：PR #99 合入后，如需启用按设备增量快照（`PATCH /api/v1/device/snapshot`）与作业附件分块上云，需重新部署 `multiplatform/remote-site-template` 并执行 D1 迁移 `0003_attachments.sql`（旧模板自动回退全量上传、不传附件）。
- **校园网与学校端点特性**：
  - 物理实验、演示实验、物理在线需校园网；物理实验主域名 `http://wlsy.bjtu.edu.cn/` 失败或 6 秒超时会自动回退 `http://59.64.5.228/`（见 `docs/security/physics-lab-ip-backup-2026-11-02.md`）。
  - 智慧教学（课程平台作业/课件）直连 module 28 仍被跳往旧 CAS，当前通过本科服务平台 `jwcZhjx` 自动回退建会话。
  - 教务系统 `aa.bjtu.edu.cn` 偶发拒绝 443 连接时，App 正常进主界面并保留成绩/课表/考试离线缓存。
  - 会话过期且学校恰好要求验证码时，后台静默恢复会停下交给用户，需重登或重开 App 手动输入验证码。
- **测试环境已知差异**：Windows 本机跑全量 `:shared:desktopTest` 时，`DesktopAgentIntegrationTest`（3 项）与 `MacUninstallCleanerTest`（2 项）因 Unix 脚本/符号链接权限与 macOS 路径假设失败，在 macOS 与 CI 上全绿。

---

## 接下来

1. 物理在线登录修复通过验收后，用真实页面核对作业详情与老师附件解析。
2. 重新部署私人远程 Site 模板并执行迁移 `0003_attachments.sql`，推进 2.0.0 发版前验收。
