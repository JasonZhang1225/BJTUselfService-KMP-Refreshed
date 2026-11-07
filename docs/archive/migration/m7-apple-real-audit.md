# M7 Apple 真实会话与可访问性首轮验收

日期：2026-07-31

## 验收边界

本轮使用正式 iPhone Simulator 与 macOS distributable。敏感页面只记录页面状态、控件、脱敏计数和错误类型；没有保存或输出姓名、学号、成绩值、余额、课程名、作业正文、Cookie、`sessionid` 或验证码图片。

2026-07-31 首轮时，iOS 新登录仍被学校 CAS 拒绝，因此当时不能把 iOS 业务页写成真实登录通过。该边界后来已由 2026-08-01 的有效 iOS 会话推翻；后续真实结果和仍未完成项以本文续录为准。macOS 的既有正式进程当时保留了一段有效登录会话，本轮先保护该进程完成只读验收，随后正常退出并启动最新构建复验登录页 AX。

## macOS 真实会话结果

| 页面/能力 | 真实结果 | 边界 |
| --- | --- | --- |
| 首页 | 加载邮件、校园卡、校园网、教学周与周议程；`⌘R` 能完成登录态刷新 | 本轮没有产生新的数据变动记录 |
| 成绩 | 真实列表、学期筛选和详情可用；手动同步成功 | 未记录课程名、分数或教师 |
| 课程表 | 当前周、本学期/选课课表和周数选择存在；手动同步成功 | 未保存课表截图 |
| 考试安排 | 真实状态、时间/地点和同步成功 | 未记录考试内容 |
| 作业 | 页面与失败恢复控件可用，但真实请求失败 | 根因见“智慧教学安全通道” |
| 课件 | 页面、教学日历和重试控件可用，但真实请求失败 | 根因与作业相同；未打开保存面板 |
| 其他功能 | 校历与中英文成绩单入口、语言选择存在 | 本轮未下载个人成绩单 |
| 教室 | 真实数据窗口、容量筛选和教室列表加载成功 | 继续保留 macOS 精确明文接口边界，不扩大到 iOS |
| 邮箱 | 正确显示 macOS 系统浏览器边界和可能重新登录提示 | 未向浏览器注入 App Cookie |
| 设置 | 三态主题、版本基线、GitHub、缓存与退出边界存在；切到深色后 SQLDelight 值为 `Dark`，随后通过 UI 恢复为 `System` | 未清缓存、未退出账号 |

登录态原生“前往”菜单的十个入口均启用；从菜单选择“考试安排”能进入真实页面。关闭窗口后进程继续运行，重新激活仍保持登录态、设置页和主题；之后 `⌘Q` 正常退出旧进程。

SQLite 只记录脱敏计数：成绩 26、课程 43、考试 9、作业 0、数据变动信息流 1 个账号范围；`PRAGMA quick_check` 返回 `ok`。这些数字只证明当前本机缓存状态，不代表所有服务器数据完整。

## 智慧教学端点与 macOS 明文授权

真实验收推翻了“旧 API 在 `https://bksycenter.bjtu.edu.cn` 存在等价路径”的假设：

- 原 Android `v1.7.0` 直接访问 `http://123.121.147.7:88/ve/...`，教学日历还使用明文 `:1936`。
- 同路径 HTTPS 域名由 `curl` 与 macOS Ktor CIO 都返回 HTTP 404，证明不是 CIO 单独故障。
- 域名和旧 IP 的 `:88` TLS 探针均握手失败；没有可验证的 HTTPS 等价通道。
- 临时 CIO 网络探针不含账号、Cookie 或响应正文，执行后已从源码删除。

共享作业与课件远端先把初始化文章端点的 HTTPS 404 映射为 `SECURE_CHANNEL_UNAVAILABLE`，避免显示误导性的普通网络错误。用户随后于 2026-07-31 明确授权 macOS 传输该平台登录会话，并接受会话可能被窃听或篡改的风险。

授权后的实现不是全局放宽（2026-08-01 用户随后把同一精确范围授权扩展到 iOS）：

- `PlatformFamily.MacOS` 与 `PlatformFamily.IOS` 注入 `AppleLegacyHttp`，Android 仍使用 HTTPS 策略；
- API 和资源只允许 `http://123.121.147.7:88/ve/...`；教学日历只重建到 `http://123.121.147.7:1936/kk/rp/...`；
- 换协议、换主机、换端口、越出路径前缀、编码路径穿越和 URL 片段均拒绝；
- `sessionid` 仍只驻留数据源实例内存并由请求字符串脱敏；iOS ATS 只为固定 IP 加例外，没有加入全局 ATS 或 Android cleartext 开关；
- Apple 作业页、课件页、宽屏相关页及紧凑布局全局顶部持续显示不可关闭的明文风险提示；因此登录后的首页自动初始化作业时也不会隐藏风险。

沙箱外无凭据连通性复核中，`:88` 与 `:1936` 的固定 HTTP 地址均可达并返回 302；请求没有账号、Cookie、`sessionid` 或响应正文。该证据只证明端口可达，不证明授权后的真实账号同步成功。

## Apple Design 与 AX 修复

真实 AX 树发现登录页“在此设备上安全保存登录信息”原先被拆成无名称按钮和独立文字。现在：

- 登录、作业课程筛选、教室空位筛选和成绩单语言选择均使用一个合并语义、带角色和状态、最小 48dp 的完整控件；
- 成绩自选框增加“选择某课程用于计算”的课程级可访问名称；
- macOS 最新构建把登录控件暴露为具名复选框；
- iOS 最新构建暴露完整名称，切换后 AX 出现 `selected`，恢复后状态消失；
- 共享 UI 没有自定义弹跳、视差或无限动画，系统 sheet/页面动画继续交给平台处理 Reduce Motion。

其他四处控件已通过共享编译与相同语义模式审查；既有语义证据保留；用户随后将进一步运行态 VoiceOver 专项移出当前范围。

## iOS 结果

2026-07-31 正式 iOS 构建成功获取新 CAPTCHA，经用户在动作时确认后提交；学校仍返回统一的“登录未通过”。由于确认等待可能让验证码会话过期，且服务端不区分答案错误与会话过期，当时没有连续提交第二张。

2026-08-01 后续真实登录已成功，首页、成绩、课程表、考试、作业、课件、其他功能、教室、邮箱和设置均已进入正式登录态；作业/课件、文件面板、Native GB18030 与邮箱的详细证据见本文续录。重装移除 Widget 后的宿主 App 仍保留有效会话，不再需要新的 CAPTCHA。

本轮新增的只读证据：

- 首页“刷新”完成后按钮恢复可用，没有错误或卡死；MIS 当前返回新邮件 0、校园卡余额“未知”。“未知”不等于余额接口完整通过。
- 只查询缓存数量，不读取个人内容：成绩 26、课程 43、考试 9、作业 4；`home_change_feed_v1` 有 1 个账号基线、编码长度 38 字节；`PRAGMA quick_check=ok`。
- 刷新前后没有服务器真实变化样本，因此四类新增/修改/删除算法仍不能写成真实变动通过；当前只证明刷新、缓存落地和空变化基线正常。
- “其他功能”先下载中文版成绩单，再切换英文版下载；两次均进入 iOS 系统“文件”保存面板，底部文件名分别正确显示“中文成绩单”和“英文成绩单”，目标位置为“我的 iPhone”。两次均取消保存，没有在模拟器留下成绩单副本；最后恢复为默认中文版。
- Computer Use 自动拖动首页时曾把“前往完美校园”误判为点击；应用只打开自身确认框，随后取消，没有打开外部页面、发起充值或传输数据。自动化滚动仍不作为首页下半部手势通过证据。

### iOS 设置缓存往返

- 设置页长内容由用户真实单指拖动到达底部；缓存操作本身通过 Computer Use 的新鲜 AX 状态逐步确认，没有依赖坐标盲点。
- 清除前账号范围缓存为成绩 26、课程 43、考试 9、作业 4，同步元数据 4；确认清除后四类缓存、成绩筛选和同步元数据均为 0，主题仍为 `Dark`，登录态保留，`PRAGMA quick_check=ok`。
- 逐页手动重新同步后恢复为 26/43/9/4；`home_change_feed_v1` 只重建四类无事件基线，没有把恢复数据误报为新增，数据库再次 `quick_check=ok`。
- 该验证证明磁盘离线副本可清除并恢复，不声称当前进程已经加载的工作集会立即从界面消失；退出账号仍未执行，以保留有效会话等待自然数据变化样本。

### 首页外部动作调整

- 用户指定 iOS 与 Android“完美校园”均使用 `https://wxaurl.cn/RLEw5IMZRKl` 微信小程序跳转链接并交给默认浏览器；macOS 显示同一链接的二维码并提示手机微信扫描。平台路由测试分别锁定移动端浏览器动作和桌面二维码动作。
- 最新 iPhone 17 Pro Simulator 登录态已确认本地弹层显示“将打开完美校园微信小程序链接”和“打开微信小程序”；随后取消，没有打开外部页面或发起充值。
- 用户进一步要求校园网续费三端直接在默认浏览器打开，不经过分享或剪贴板。旧 `UIActivityViewController`、`ACTION_SEND`、macOS 剪贴板及未通过的 JNA picker 尝试已从正式源码删除。iPhone 17 Pro Simulator 由用户真实单指拖动到卡片，确认后 Safari 打开学校 `weixin.bjtu.edu.cn`，返回 App 后登录态保留。
- 完美校园确认后 Safari 进入 `wxaurl.cn` 的“完美校园·小程序”落地页并显示“前往微信打开”；用户确认该落地页正确且安装微信时会继续唤起小程序。Simulator 没有微信，因此本轮证据止于正确落地页，不冒充微信内小程序已打开。

## iPadOS 动态字体、对比度与窗口复核

在 iPad Pro 11-inch (M5) / iPadOS 26.5 Simulator 上使用正式 Xcode 产物完成了竖屏、横屏、可调整窗口和无障碍设置复核：

- 默认字号下，横屏使用完整双栏并标记为“宽窗口”；恢复到竖屏/系统窗口后使用单栏“中等窗口”，登录字段、保存凭据和验证码按钮的 AX 名称保持完整。
- 首轮把系统内容大小调到 `accessibility-extra-extra-extra-large` 后，Compose 字体完全没有变化，由真实截图确认这是缺口，不写成通过。
- 现已由 iOS actual 读取 `preferredContentSizeCategory`，监听 `UIContentSizeCategoryDidChangeNotification`，并把 12 档内容大小映射到 Compose `fontScale`；Android 与 macOS 继续使用原有 Compose 密度，不重复缩放。
- 最大无障碍字号在运行中即时放大；宽窗口自动降为可滚动单栏，避免双栏把登录卡下半部挤出可达区域。把系统字号恢复为默认后，应用无需重启即回到横屏双栏。
- iOS actual 同时监听增强对比度变化，以更深/更亮的 Material 颜色提高文字与轮廓对比；测试结束后已把 Simulator 的字号恢复为 `large`、增强对比度恢复为关闭。
- “减弱动态效果”由 iOS actual 实时监听；启用时 Material 3 空间动画改为 `snap`，不再让 sheet/dialog 产生位移，状态与完成反馈仍保留 120–180ms 的短效果过渡。
- “降低透明度”由独立通知实时监听；共享 UI 中所有显式半透明卡片、标签和次级文字统一升为不透明，不影响普通模式原有层级。
- Computer Use 在系统“设置”中确认两项开关分别从 0 切到 1，同一 KMP 进程前后台往返后界面与 AX 保持完整；随后两项均恢复为 0，并关闭测试用设置窗口。
- Computer Use 能确认实时重排、首屏层级和 AX 树；其拖动动作没有可靠注入 Simulator 触控；用户随后将最大字号手势专项移出当前范围。

共享纯函数新增字体映射单调性、未知类别回退和无障碍字号窗口降级测试。Apple Design 的“内容优先、动态字体、宽度与字号共同决定布局”原则因此落实到代码，而不是只写在验收清单。

## 自动化与构建

- Desktop 全量测试：224 项，0 failure，0 error，0 skipped。
- Android Debug/Release、iOS Simulator/device arm64 编译、macOS distributable：通过。
- Xcode iPad Pro 11-inch (M5) / iOS 26.5 Simulator 最终 host build：通过；此前 iPhone 17 Pro / iOS 26.5 host build 也已通过。
- macOS distributable：主程序 arm64，严格深度签名通过。
- 冻结 Android 摘要保持 `a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f`。

现有 ICU 最低 Simulator 版本和 Compose iOS `UIKitView` 弃用警告仍存在；本轮没有把警告写成失败，也没有忽略后续升级风险。

## 下一步

1. 继续等待可控的真实数据变化样本，验证成绩、课程表、考试、作业四类新增/修改/删除信息流；不为了测试而篡改学校数据。
2. 用户确认当前没有学校新布置、可用于上传验收的作业；真实上传延期到后续有作业时，下载和 iOS 系统保存面板已通过。
3. 当前账号缓存清除/恢复往返已通过；退出账号留到当前有效会话不再需要时。获得合法 Apple Development 签名身份后补 iOS Keychain 保存/重启/清除往返；当前无签名 Simulator 不能替代该证据。
4. 用户于 2026-08-01 决定暂时跳过进一步 VoiceOver、键盘与最大字号手势专项，它们不再作为当前迁移完成门禁；小组件也已删除并延后到公版完成后。
5. 本轮未提交、打标签、推送、创建 PR 或 Release。


## 2026-07-31 续：macOS 登录重试与包重建

- 用户明确允许 CAPTCHA 提交后，旧 macOS 进程上多次“刷新→填写→立即提交”仍返回统一“登录未通过”。
- 为提高验证码可读性，登录页验证码显示改为更大尺寸与 `ContentScale.Fit`；`createDistributable` 重建成功，ad-hoc arm64。
- 新包 Bundle ID `team.bjtuss.bjtuselfservice.kmp.macos` 可启动，但 Computer Use 附着失败（list_apps 可见、get_app_state Invalid app/timeout），因此放大后的验证码与新会话作业/课件尚未验收。
- 该段历史中的 Widget 验证性实现已于 2026-08-01 按用户决定完整删除；不再属于当前产物或门禁。

## 2026-08-01 续：macOS 作业/课件明文链路端到端打通

通过临时诊断日志逐跳追踪真实握手，定位并修复了 macOS 明文通道的三层真实缺陷，
作业与课件两条链路在真实账号会话下端到端验收通过：

- **多跳 OAuth 握手**：登录态下 MIS module 28 直接以裸 HTTP 302 指向明文
  `http://123.121.147.7:88/oauth/api/user/thirdLogin`，而非旧代码假设的 HTML
  `<form id="redirect">`。Ktor 的 HttpRedirect 默认拒绝 HTTPS→HTTP 降级跟随，
  因此握手在最外层 302 处停住。新增共享 `followSmartHandshakeRedirects`，逐跳
  校验后手动跟随——明文跳限精确 `apiOrigin`，HTTPS 跳限 cas/mis 学校主机，
  降级绝不扩散到白名单之外。真实链路 `module 28 → thirdLogin → CAS authorize
  → oauth/token/callBack → ve/s.shtml` 全程 200。
- **Cookie 下发的 sessionid**：握手最后一跳 `ve/s.shtml` 通过 `Set-Cookie:
  JSESSIONID` 下发会话，article 接口只返回文章列表（无 `sessionId` 字段）。
  原“从 article JSON 解析 sessionId”的假设与真实服务器不符。现优先从
  transport Cookie 存储读 `JSESSIONID` 填入自定义 `sessionid` 头，article JSON
  解析仅作回退；transport 同时自动携带该 Cookie。
- **空数据容错**：服务器对“该课程没有作业/课件”返回 `STATUS:"2"` +
  `message:"没有数据"`（作业 `courseNoteList`、课件 `resList`/`bagList` 为空）。
  原 Android 用 moshi 默认值容忍任意 STATUS 直接得到空列表；KMP 严格解析把
  `"2"` 当失败，会在第一门无内容课程处中断整批同步。现对 `STATUS:"2"` 识别为
  合法空列表（仍拒绝其他非 0 状态码），行为对齐原 Android。
- **请求超时**：课件递归拉取资源树时个别慢子文件夹请求触发 Ktor 默认
  `HttpRequestTimeoutException`。transport 现显式配置 request/connect 15s、
  socket/request 30s，递归资源树在真实服务器上完整拉取成功。

真实验收结果（正式包，登录态，User-Agent 与 CAS 语义对齐）：

- 作业：同步出人工智能基础及应用课程 4 项实验报告（实验1–4：温度数据分析、
  经典机器学习、深度学习图像分类、课程助教数字人），全部“已提交”，含截止时间、
  提交人数（如 62/62）与“未公布成绩”评分；作业详情正文完整渲染。
- 课件：同步出 7 门课程资源树——微积分(B)Ⅱ 4 项、概率论与数理统计(B) 8 项、
  毛概 10 项、写作与沟通 0 项、民族器乐欣赏 0 项（空数据容错）、国际组织与
  发展英语 1 项、高等数学方法Ⅰ 1 项；教学日历按钮随会话建立而启用。

临时诊断日志已全部移除，保留正式修复。Desktop 全量测试、Android debug、
iOS framework、macOS distributable 完整门禁通过。

## 2026-08-01 续：M6 文件能力真实验收与已提交附件 GBK 修复

在 macOS 登录会话有效期间完成文件能力的真实端到端验收：

- **教师附件下载**：作业详情（人工智能基础及应用·实验4）显示教师附件
  任务书（20 KB）与实验报告（19 KB）。点击任务书"下载"弹出 macOS NSSavePanel
  系统保存面板，文件名正确显示，选择 Save 后落盘 Documents，20832 字节，
  文件头 `PK..` 为真实 Microsoft Word 2007+（DOCX/ZIP）文档。
- **课件单文件 ticket 下载**：课件资源树选微积分(B)Ⅱ 的 d11_24 RAR 资源，
  详情显示大小/类型/上传教师/上传时间/下载次数。点击"下载并选择保存位置"经
  `rpinfoDownloadUrl` ticket → 下载 → NSSavePanel，文件名正确为 `d11_24.rar`，
  落盘 5599196 字节（5.34 MB），文件头 `Rar!` 为真实 RAR v5 归档。
- **已提交附件中文文件名乱码首轮修复（macOS）**：作业"我已提交的附件"中的中文文件名曾
  显示为西里尔/拉丁扩展乱码（如 `Сȫ`）。真实反推证实该老接口以
  GBK/GB18030 返回中文文件名（解码还原为"小组全部成员一起协作的照片.png"等
  合理名称），而 `bodyText()` 固定按 UTF-8 解码。首轮 `bodyTextGbk()` 经
  Ktor charset 注册表按 GB18030 解码、不支持时回退 UTF-8，并在已提交附件解析处
  启用；macOS 4 个文件名完整还原。后续 iOS 实机发现 Kotlin/Native 不支持该
  charset，已改为平台 expect/actual，见本文件后续 iOS 验收记录。

Desktop 全量测试、Android debug、iOS framework、macOS distributable 完整门禁
通过。`createDistributable` 在应用运行时偶发签名替换失败属已知环境限制，退出
应用后单独构建通过，不写成缺陷。

## 2026-08-01 续：iOS 明文策略边界实机验收与拒绝文案修正

在 iPhone 17 Pro（iOS 26.5 Simulator）上以真实登录会话实测作业/课件页：

- **事实**：iOS 注入 `SmartPlatformEndpoint.VerifiedHttps`。登录态下 MIS
  module 28 同样返回裸 302 指向明文 `http://123.121.147.7:88/oauth/api/user/
  thirdLogin`；`followSmartHandshakeRedirects` 判定该目标不在 HTTPS 白名单
  （仅精确 apiOrigin 与 cas/mis 主机）而停止跟随，**明文请求从未发出**。
- **发现并修正的缺陷**：握手停在未放行 3xx 时，作业/课件两处
  `ensureInitialized` 原先统一映射为 `NETWORK`，UI 误报"无法连接智慧教学
  平台，请检查网络后重试"。现区分"安全拒绝"与"网络故障"：停在 3xx 先抛
  `SECURE_CHANNEL_UNAVAILABLE`。实机复验——作业页显示"学校平台未提供可验证
  的 HTTPS 通道，已拒绝降级到明文连接。"，课件页显示"学校平台没有提供可验证
  的 HTTPS 通道。"，应用不崩溃、可正常返回其他页面。
- **测试锁定**：新增 `stopsHandshakeAtPlainHttpRedirectAndReportsSecure
  ChannelUnavailable`，断言 HTTPS 策略下 302→明文只发 1 个请求且抛
  SECURE_CHANNEL_UNAVAILABLE；Desktop 全量测试、Android debug、iOS
  framework、macOS distributable 完整门禁通过。
- **历史边界结论**：该次验收证明 iOS 拒绝路径有效；用户随后明确授权 iOS 使用
  与 macOS 相同的精确 origin。当前 Apple 端仅固定 IP ATS 例外与端口/路径白名单，
  Android 仍拒绝，无任何全局 ATS/cleartext 放宽。

## 2026-08-01 续：iOS 明文作业/课件、文件面板与 Native GB18030 验收

用户明确授权 iOS 与 macOS 使用同一智慧教学旧 HTTP 精确范围，并暂不考虑 App
Store 上架。实现与实机证据如下：

- 传输策略集中为 `smartPlatformEndpointFor`：iOS/macOS 注入
  `SmartPlatformEndpoint.AppleLegacyHttp`，Android 保持 `VerifiedHttps`；新增
  平台策略测试，避免数据链路与风险提示分叉。
- iOS `Info.plist` 只为固定 IP `123.121.147.7` 添加
  `NSExceptionAllowsInsecureHTTPLoads`；共享层仍强制 API `:88/ve/`、教学日历
  `:1936/kk/rp/` 和安全路径校验。未启用 `NSAllowsArbitraryLoads`。
- iPhone 17 Pro / iOS 26.5 Simulator 真实登录成功；首页、作业页、课件页均常驻
  显示不可关闭的明文窃听/篡改警告。
- 作业真实同步 4 项实验报告；实验 4 详情正文完整，提交状态、评分、开放/截止时间
  正确。课件真实同步 7 门资源树；微积分(B)Ⅱ 4 项，d11_24 RAR 详情显示 5.34 MB、
  教师刘明惠、上传时间与下载次数。点击下载后 iOS 系统“文件”保存面板打开，目标
  “我的 iPhone”，文件名 `d11_24` 正确，未执行最终保存。
- 实机发现已提交附件仍显示 GB18030 乱码：Ktor common charset 在 Kotlin/Native
  iOS 不支持 GB18030，静默回退 UTF-8。现把 `decodeLegacyGb18030OrNull` 改为
  expect/actual：Android/Desktop 使用 JDK `Charset("GB18030")`，iOS 使用
  CoreFoundation `kCFStringEncodingGB_18030_2000` 转 `NSStringEncoding`，再由
  Foundation `NSString(data:encoding:)` 解码。
- 修复版实机复验 4 个中文附件名全部正确：`小组全部成员一起协作的照片.png`、
  `讲解脚本.md`、`天气之子 机器学习.mp4`、`天气之子（耿博帆组）实验4 实验报告.docx`。
  点击第一项下载后，iOS 系统保存面板底部显示正确中文文件名；取消面板，未落盘。
- 为让 commonTest 真正在 iOS 跑通，教室数据源的 legacy 可用性改为生产默认值可注入；
  生产 iOS 仍拒绝该第三方教室 HTTP，模拟 transport 测试则可明确开启。最终
  Desktop 238 项、iOS Simulator 223 项均 0 failure / 0 error / 0 skipped；Android
  debug、iOS framework、macOS distributable 与 Xcode App 构建全部通过。

## 2026-08-01 范围调整：暂缓无障碍专项

用户确认邮箱页面肉眼显示与实际操作均正常，并明确要求暂时跳过无障碍专项。
因此后续不再继续 Accessibility Inspector、运行态 VoiceOver、网页内部 AX 或最大
无障碍字号手势验收，也不把这些项目作为当前迁移/发布完成门禁。已实现的基础语义、
动态字体适配及 iOS WKWebView `accessibilityEnabled` 保留，不回退正常功能。
