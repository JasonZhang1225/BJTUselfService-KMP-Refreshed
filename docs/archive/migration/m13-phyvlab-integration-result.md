# M13：物理在线初步开发记录

> 记录日期：2026-08-27（Asia/Shanghai）
> 状态：**代码层初步开发完成，Mac 作业详情回归与紧凑端详情导航已修复，跨平台与真实账号验收待继续**。本文件是 M13 的收口记录，不把尚未取得运行证据的行为写成正式完成。

## 0. 里程碑归属（重要）

- **M13 = 物理在线（phyvlab.bjtu.edu.cn）接入**。
- **M14 = Windows 桌面端移植**，已在 `v1.7.3-KMP` 期间完成并归档于 [`windows-port-plan.md`](windows-port-plan.md) 与 `history_full.md`。
- 本轮物理在线初步开发整合为一个独立的 **M13**，不改名为 M14，也不覆盖 M14 的 Windows 历史。
- 对照未修改的 `main`/`origin/main`：当前共同指向 **`a342615` / `v1.7.3-KMP-B`**；这是已完成 M14 Windows 基座上的 M13 工作区改动。
- `history_full.md` 的历史顺序也保持不变：先记录 M13 物理在线规划，再记录 M14 Windows 移植；本文件只补充 M13 的当前首版结果。

## 1. 本轮实现范围

### 入口与会话

- “更多”→“学业”中，物理在线为第一项。
- 复用 App 的 CAS/MIS Ktor 会话，按 Moodle OAuth2 authorization-code 流程建立 `MoodleSession`；跳转仅允许 `https://phyvlab.bjtu.edu.cn` 与 `https://cas.bjtu.edu.cn`。
- 物理在线 MoodleSession 单独过期时，先在 App 内强制获取一次 CAS challenge，并用当前内存凭据与现有验证码识别器恢复；不会因为会话失效自动打开系统浏览器，恢复失败才提示退出并重新登录主账号。
- 不复制或持久化密码、Cookie、OAuth code、state、sesskey、draft item 等敏感/短期值。

### 课程、作业与首页日程

- 读取我的课程、课程中的标准 Moodle 作业、完成状态和开放/截止时间；列表与详情按北京时间显示截止状态：已完成为绿色，未完成且未到截止为黄色，已逾期未完成为红色并加粗，无法确认截止时间时保持中性。
- `Thursday` 等英文星期标记会从日期展示中移除，统一为 `yyyy年MM月dd日 HH:mm`；日期解析、月份切换和首页日历归日均固定按北京时间（`Asia/Shanghai`），不随设备时区漂移。
- 课程作业列表增加正/逆序切换，默认按 Moodle 活动 ID 降序（新的在上方，直接反转原本旧的正序），切换后可按旧的在上方查看；截止时间只用于展示，不参与新旧判断。
- 首页日程窗格显示物理在线作业的开放与截止安排，分别保留“物理开始/物理截止”来源标记；物理在线页面自身的月视图仍按实际日期展示。
- 首页归日与 Android 基线一致：普通作业以及物理在线截止若恰好为次日 `00:00`，均把“截止”标记放在前一天；物理在线开放时间不做此平移。
- 物理在线课程、作业、安排和已经读取的详情写入按账号隔离的 `CacheStore` JSON 快照；不保存 Cookie、sesskey、上传草稿上下文或可提交能力。网络/CAS 不可达时保留只读快照，物理在线顶栏显示“未同步 · 缓存”。
- 设置→自动同步中增加“自动同步物理在线”开关；开启后登录完成会自动拉取课程与作业安排，关闭后进入物理在线页仍可手动同步。

### 原生作业详情、成绩和上传

- 点击作业在 App 内打开原生详情，不再自动跳转浏览器。
- 详情显示作业要求、开放/截止时间、提交状态/时间、批改状态、成绩、教师评语和已提交文件。
- 详情页按“时间 / 提交与批改 / 作业要求 / 已提交文件”使用色块分组；宽屏时前两块等宽并列，窄屏自动改为单列，时间统一追加中文星期并按实际可用宽度自适应字号；网页备用入口改为有背景色的按钮并移除“备用”括号。
- 物理在线列表在窄屏使用自适应水平边距，安排月份控件在较小宽度下换行，避免卡片与控件贴边或横向溢出；紧凑端点击作业仿作业功能进入原生二级详情页，宽屏继续使用现有 `ModalBottomSheet`。
- 首页聚合同步失败时弹出失败模块清单并立即主动重试；首页主动刷新、失败重试和物理在线页初始化共用同一轮网络同步标记，进入详情再返回不会重复刷新或清掉失败状态。物理在线失败且有缓存时，顶栏显示“同步失败·正显示缓存”，失败横幅显示失败原因、 “仅校园网下同步”、 “当前显示本地缓存”和缓存创建时间，不再展示原始 `network` 诊断。
- 普通 Moodle 文件型作业支持“草稿区上传→最终表单提交”；选文件后必须再次显式确认，提交后重新读取详情。
- Unity/WebGL 虚拟实验、quiz、选课、完成标记、讨论和 Unity 数据上传仍保留网页流程；网页备用入口只有用户主动点击才打开。

### 触摸与平台实现

- 仅 Windows 桌面主要纵向列表、详情页和横向筛选栏增加触摸拖动兼容层；滚轮保持原行为，macOS、Android/iOS 使用平台原生滚动。平台门禁位于 `DesktopTouchScroll` 内部，调用方不能把该层误开到其他平台。
- Android 使用系统文档选择器和 `Intent.ACTION_VIEW`；iOS 使用 `UIDocumentPicker`/WKWebView 相关平台实现；macOS 复用 desktopApp 的 JVM/CIO/文件网关路径。

## 1.2 2026-08-30 回归补丁

- Android、iOS、macOS 不再叠加 `DesktopTouchScroll`；平台门禁继续位于兼容层内部，只有 Windows 桌面目标可以安装拖动映射。为降低横屏首页的整页测量开销，首页外层长内容改用 `LazyColumn`；小米平板真机尚未连接，不能把模拟器结果当作硬件流畅度证据。
- 普通作业同步对远端响应增加数据完整性门禁：明确的无数据状态仍可写入空快照；成功响应缺 `courseList`/`courseNoteList`、异常状态、非 JSON 或批量请求中任一列表无法解析时返回 `MALFORMED_RESPONSE`，仓库沿用旧缓存，避免会话失效被误显示为“暂无作业”。
- 本机 Android debug 数据库只读核对显示仍存在 4 条普通作业缓存；代码中没有删除远端作业的请求。当前连接的 `emulator-5554` 冷启动一次停留在“登录中”，Android 真机和 iOS 登录后页面仍待复测。

## 1.1 `v1.7.3-KMP-B` 基线差异审计

本次按提交内容而不是只看分支名逐文件核对 `v1.7.3-KMP-B^{}` 与 M13 提交 `HEAD`：前者为 `a342615`，后者的直接父提交也是 `a342615`，两者之间只有一个 M13 提交。没有删除旧文件；`v1.7.3-KMP-B` 的 CI 打包修复、Windows ASCII 安装器修复、macOS 中文显示名/DMG 图标修复、课程表教学周修复和作业解析容错均仍在当前树中。

| 差异类别 | 受影响代码 | 结论 |
| --- | --- | --- |
| Windows 桌面触摸/滚动 | 课程表、课件、考试、成绩、作业、教室、设置、首页和登录页 | 只是给既有 `verticalScroll`/`LazyColumn`/横向筛选栏增加 `desktopTouchScroll`；平台门禁强制限制为 Windows，未替换业务数据源或旧页面逻辑 |
| 物理在线接入 | `App`、登录会话、首页日程、设置、导航及新增 `phyvlab` 包 | M13 必需接线；不属于 B 的旧功能回退 |
| 认证/公共传输 | `SchoolLoginProtocol`、`KtorSchoolHttpTransport`、`SchoolHttpTransport` | 为 Moodle CAS 恢复、无重定向握手和“表单字段+multipart 文件”上传增加能力；保留原普通登录调用入口 |
| 平台桥接 | Android 网络权限、`ACTION_VIEW` 外链桥接、物理在线域名白名单 | 为 M13 的网页备用入口和网络访问所需；没有改动 B 的 Windows/macOS 原生安装逻辑 |
| 版本/打包元数据 | Android/iOS/desktop/Windows 版本与描述、更新检测当前版本 | 版本统一为 `1.7.4-KMP`；B 的品牌和 CI 规则未被覆盖。本次另补齐 macOS build `15` 与开发运行时 Dock 中文名 |

因此，除物理在线、桌面滚动适配和上述为其服务的公共层扩展外，没有发现属于 `1.7.3-KMP-B` 的独立功能漏进 `windows-dev`；当前问题是 M13 叠加时的运行时回归，而不是 B 基座未嫁接。

## 2. 验证证据

| 验证项 | 当前证据 |
| --- | --- |
| 物理在线 HTML/日期/会话/上传协议专项测试 | 通过（脱敏 fixture/队列传输，含英文星期清理与作业正/逆序） |
| App 内 CAS 恢复专项测试 | 通过（强制 CAS challenge、验证码认证、敏感字段脱敏） |
| 最新 M13 定向桌面回归（phyvlab + SchoolLoginProtocol） | 通过；包含首页午夜归日、缓存编解码、离线缓存保留及同步重试/详情返回状态测试 |
| `:windowsApp:compileKotlinWindows` | 通过 |
| `:desktopApp:compileKotlin` | 通过 |
| Windows EXE/MSI 打包 | 通过；桌面副本与源包 SHA-256 一致 |
| `:shared:desktopTest` 全量 | 通过（含物理在线缓存/首页安排回归） |
| `:shared:iosSimulatorArm64Test` | 通过；共享层 iOS Simulator Arm64 单元测试与 Debug framework 链接通过 |
| Android 编译/模拟器 | 已在 `C:\Users\zjg\android-sdk-codex` 安装 Android SDK Platform 36、Build Tools 36.0.0、Android Emulator 37.1.11 与 `adb 37.0.1`；`:shared:compileAndroidMain`、`:androidApp:compileDebugKotlin` 和 `:androidApp:assembleDebug` 均通过。`codex-m13-api36` AVD 已启动（`sys.boot_completed=1`），带网络权限的新 APK 已安装并进入 Compose 登录页，`MainActivity` 保持 resumed 且无应用崩溃；尚未用真实账号进入物理在线课程页 |
| macOS 实机 | 在当前 Mac 上执行 `./gradlew :desktopApp:packageDmg` 成功；生成的 arm64 `.app` 通过 `codesign --verify --deep --strict`，DMG 内应用名、Dock 名称均为“交大自由行 KMP”，版本 build 为 `15`，源图标已进入 `.app` 与 DMG。真实登录态下进入“更多→学业→物理在线”，实时读取 3 门课程、32 个活动；课程切换、作业正/逆序、月份切换和同步状态均可操作。真实作业详情现在可显示提交状态、提交时间、批改状态、成绩、作业要求和已提交文件；其可选编辑提交页返回 `404` 时不再阻断主详情。未执行上传或提交 |
| iOS 模拟器 | Xcode 27.0 Debug `iPhone 17 Pro / iOS 26.5` 构建成功，`simctl install` 与 `simctl launch` 成功，应用显示 1.7.4-KMP 登录首屏；本轮公共层改动后 `:shared:iosSimulatorArm64Test` 仍通过，未用模拟器登录，不能把登录后 M13 通过写成已验证 |
| iOS unsigned IPA | 当前工作区 Release/generic iOS 构建和 IPA 封装成功，产物为 `/Users/zjg/Downloads/BJTUSelfService-KMP-1.7.4-KMP-iOS-unsigned.ipa`；版本 `1.7.4-KMP`、Build `15`，包内无 `_CodeSignature`，不能直接替代签名真机包 |
| iPhoneOS 设备架构 | Xcode 27.0 Release `generic/platform=iOS` 构建成功，产出 arm64 unsigned `.app`，Bundle ID/版本字段正确；签名校验明确为未签名 |
| iOS 实体机安装 | 当前已配对的 iPhone 15 Pro Max 在 `devicectl` 中为 `unavailable`，Apple Configurator 无连接设备，Xcode 不列为可用 destination；本机签名构建另因没有匹配 Bundle ID 的 provisioning profile 失败。实体机安装、启动和登录后 M13 验收尚未完成 |

当前版本：`1.7.4-KMP`（Android/iOS build 15；macOS build 15；Windows/desktop 数值版本 1.7.4；保留 Windows UpgradeCode 支持覆盖安装）。本次重新打包后源包与桌面副本逐字节一致：

- `/Users/zjg/Downloads/BJTUselfServiceKMP-1.7.4.dmg`（115,885,364 bytes；SHA-256 `0f2328f13f0d3b7c41e5362082150fce9ec4efb3ecffecd1ffa21b1e9e0b96af`）
- `/Users/zjg/Downloads/BJTUSelfService-KMP-1.7.4-KMP-iOS-unsigned.ipa`（41,403,730 bytes；SHA-256 `9532a82795b9c858f503e9bc70a9e888e1a9bbff1ab365a6f12d4a0d29c0684e`；本次 M15 邮箱只读前端、分页和头像修正已包含）

- `C:\Users\zjg\OneDrive\Desktop\交大自由行 KMP-1.7.4-KMP-DEV.msi`（113,213,258 bytes；SHA-256 `7AA795E592E5110909469173827ABFF371048E678FFF04C179A3FCFB9B909599`）
- `C:\Users\zjg\OneDrive\Desktop\交大自由行 KMP-1.7.4-KMP-DEV.exe`（113,874,944 bytes；SHA-256 `8640F3A40D814197EA733257C6C88C2C7F565A76EAFF10AB5782CD181553D9EC`）
- `C:\Users\zjg\OneDrive\Desktop\交大自由行 KMP-1.7.4-KMP-DEV-debug.apk`（368,489,589 bytes；SHA-256 `96A6EB987B8304A90E7AE7C481625FF38CEF83AB8562E11CBD9690A8D85C0A5B`）

本机 `C:\Program Files\交大自由行 KMP\交大自由行 KMP.exe` 的文件时间早于本轮桌面包；此前观察到的 `Thursday/Saturday` 和旧作业顺序来自该旧安装，不能作为新包日期清理/排序功能的验收证据。请先安装上面的桌面开发包再复测。

## 3. 未完成事项与堵点

1. **浏览器管理员策略限制**：Edge 扩展和 Codex 内置浏览器仍会因管理员策略校验不可用而拒绝读取 `phyvlab.bjtu.edu.cn` DOM。2026-08-27 已改用本机 Chrome DevTools：用户自行登录后可以只读课程页与作业详情，并完成与解析器的结构对照；上传控件只在可编辑作业的 `action=editsubmission` 页出现。现场发现的 `作业状态`、filemanager 初始化 JSON 和反馈容器差异已在解析器中修复，并有专项测试；原始拒绝、安全边界和复核结论见 [`phyvlab-browser-policy-limit.md`](phyvlab-browser-policy-limit.md)。
2. **真实上传尚未执行**：当前只验证脱敏 Moodle 草稿上传/最终表单协议，尚未用真实账号和真实文件提交；真实提交必须由用户明确确认。
3. **Apple 端仍有真实验收堵点**：macOS 的原生作业详情已修复，但当前真实作业没有可用的 filemanager 编辑页，因此只能保留网页备用入口，真实上传仍需可编辑作业和用户确认。iOS 模拟器已构建、安装并启动到登录页；iPhoneOS arm64 unsigned 构建通过，但实体 iPhone 当前不可用，且本机没有匹配 Bundle ID 的 provisioning profile，不能进行实体机安装/登录后验收。不能把模拟器启动或 unsigned 构建当作真机证据。
4. **Windows 真实触摸屏验收缺失**：触摸拖动通过桌面指针路径验证，UU 远程 Windows 触摸模式仍需用户实机确认。
5. **Moodle REST/Mobile token 未确认**：站点虽提供旧版移动客户端入口，但登录后 Web Service/token 权限尚未取证；首版采用登录态 HTML 解析，不保存猜测性 token。
6. **既有测试环境差异**：全量桌面测试的唯一失败是 Windows 不具备 macOS Keychain 类，不是物理在线专项失败。
7. **Windows 模拟器网络权限**：启动 Android AVD 后，Windows 曾弹出 `adb.exe` 公共/专用网络访问授权；首次出现时未代用户点击系统安全对话框，也未修改防火墙规则。用户随后已手动点击允许；后续若再次出现，只能在明确观察到同一个 `adb.exe` 授权对话框时使用这一次授权，不延伸到其他系统安全提示。

### Android 本机验证复现

本机已将命令行 SDK 放在 `C:\Users\zjg\android-sdk-codex`（不属于仓库），并安装/接受了编译所需许可。可用以下环境变量复现共享层检查：

```powershell
$env:JAVA_HOME = 'C:\Users\zjg\jdk21\jdk-21.0.8+9'
$env:ANDROID_HOME = 'C:\Users\zjg\android-sdk-codex'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_AVD_HOME = 'C:\Users\zjg\android-avd-codex'
.\gradlew.bat :shared:compileAndroidMain
```

`:shared:compileAndroidMain`、`:androidApp:compileDebugKotlin`、`:androidApp:assembleDebug` 均已通过。首次联网构建期间曾遇到 JDK 的 `Remote host terminated the handshake`，重试后依赖已补齐；debug APK 为 `C:\Users\zjg\OneDrive\Desktop\交大自由行 KMP-1.7.4-KMP-DEV-debug.apk`（368,489,589 bytes；SHA-256 `96A6EB987B8304A90E7AE7C481625FF38CEF83AB8562E11CBD9690A8D85C0A5B`）。`codex-m13-api36` AVD 已冷启动到 `emulator-5554`，`adb install -r -g` 返回 `Success`，`uiautomator` 可读取 Compose 登录页；重新生成的 APK Manifest 已用 `aapt dump permissions` 确认声明 `android.permission.INTERNET` 与 `android.permission.ACCESS_NETWORK_STATE`；未填写账号、未访问或提交物理在线数据。

## 4. M13 后续验收顺序

1. 在 Android、iOS、macOS 复测 CAS 会话恢复和原生详情；Mac 详情主链路已通过，继续观察不同 Moodle 作业类型。
2. 连接并解锁实体 iPhone，准备与 Bundle ID 匹配的合法签名配置后，安装并运行 iOS 开发版；不要把 unsigned 包当作真机包。
3. 在真实 Windows 触摸模式验证整个应用的纵向列表和横向筛选栏拖动。
4. 选择普通 Moodle 文件型作业，先核对成绩/评语/附件，再由用户明确确认一次小文件上传；不要用 Unity 实验上传代替普通作业验收。
5. 如需继续浏览器取证，使用管理员策略允许的控制链路；不得复制 Cookie、密码或令牌，也不得绕过浏览器安全策略。
