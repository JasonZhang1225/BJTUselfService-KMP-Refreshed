# M13：物理在线（phyvlab.bjtu.edu.cn）接入目标文档

> 状态：**M13 物理在线初步开发已完成（代码层，2026-08-27），完整验收中**。`M14` 是独立的 Windows 桌面端移植里程碑，本功能不改并入 M14；M13 汇总记录见 `m13-phyvlab-integration-result.md`。范围与验收以本文档为准。
> 上游需求来源：用户提出「接入物理在线平台」，并指出该平台使用通用框架、可借助 MIS/统一身份认证登录。

## 1. 已确认的系统形态

- 入口：`https://phyvlab.bjtu.edu.cn/?redirect=0`。2026-08-26 调研时普通外网 HTTPS 登录页可达；未登录课程页会 303 回到登录页，不能把旧记录中的“外网 443 不通”继续当作当前结论。
- 主系统是 **Moodle 4.0.4+**：页面暴露构建号 `2022041904.07`，使用 Adaptable 主题 `2.1.1.2`、Moodle `menutopic` 课程格式、jQuery 3.6.0 与 YUI 3.17.2。
- Moodle 课程分类为「自然科学 / 教学管理 / 教研管理」。首页公开列出当期和历史课程，例如 `大学物理I_(2026春)`、`大学物理演示实验_(2026春)`、`物理实验I_(2026春)`。
- 访客可浏览部分课程。已实测访客进入「热力学与气体动理论虚拟仿真实验」课程，课程章节包括：进入实验、实验简介、实验流程、实验方法、操作步骤、理论测试、课程资料、自主实验、建议讨论、课程团队、课程思政、课程成绩。
- 普通课程并不等于自动可访问。已实测 `物理实验I_(2026春)` 会落到 Moodle `enrol/index.php?id=74`，页面显示「自助选课 (学生)」，访客必须先登录。任何真实选课都属于外部写操作，不纳入首版自动流程。
- 课程内容使用 Moodle 标准活动与资源：`mod/resource`、`mod/quiz`、`pluginfile.php` MP4/附件、完成标记、成绩入口等。公开课程的理论测试页暴露 5 个标准 Moodle quiz 活动。
- 顶栏提供旧版客户端：Moodle Mobile Android `v3.9.2` 与 Moodle Desktop Windows/Linux `v3.9.2`。这强烈暗示站点曾启用 Moodle Mobile 能力，但标准 REST 服务和 token 获取仍需登录后验证。

### 虚拟实验子系统

公开课程内的「进入实验」不是标准 Moodle 活动，而是自定义目录：

- 入口：`/course/vlab/index.php`。
- 引擎：旧版 **Unity WebGL**，由 `UnityLoader.js` 加载 `WebGl120802.json`，最终渲染到 `<canvas>`。
- 页面把 `url`、`username`、`token` 传入 Unity `Main.GetUserName`；访客值为 `username=guest`、空 token。登录态值尚未采集。
- Unity 与网页的桥接包括：学习结束、查看报告、打开讲义/视频/理论测试/讨论、下载模板、上传数据文件。
- 可见自定义端点包括 `/course/vlab/showreport.php`、`/course/vlab/upload.php`、`/course/vlab/position.dat`；上传是写操作，不纳入首版原生能力。
- 页面依赖 WebGL、文件选择、下载、音频和 `window.open`。现有 Android/iOS `SchoolWebView` 没有完整的多窗口与文件上传承接，因此虚拟实验首版应交给完整浏览器，不把 Unity 仿真搬到原生 UI。

### 账号关联

`/user/asso.php` 是自定义「实验空间」关联页，说明该站还可与 `ilab-x.com` 的实验空间账号关联。页面包含 HTTP 外链。首版不实现该关联，也绝不把 phyvlab Cookie 注入第三方域名；相关链接只能交给系统浏览器。

## 2. 认证链（已验证到登录表单）

物理在线不是智慧教学的 MIS `module 28` 握手，也不是同一厂商的 `JSESSIONID` 平台。它使用 Moodle 内置 OAuth2 认证插件，把北京交通大学 CAS 配成 OAuth2 issuer：

1. Moodle 登录页给出 `/auth/oauth2/login.php?id=1&wantsurl=...&sesskey=...`。
2. Moodle 重定向到 `https://cas.bjtu.edu.cn/o/authorize/`，参数为 OAuth2 Authorization Code 流程。
3. CAS 回调地址是 `https://phyvlab.bjtu.edu.cn/admin/oauth2callback.php`。
4. 请求的身份 scope 为 `uid name yxsh yxmc email ptype`。
5. 未登录 CAS 时进入现有 Django 登录表单：`loginname`、`password`、`csrfmiddlewaretoken`、`captcha_0`、`captcha_1`；验证码图片仍是 `cas.bjtu.edu.cn/image/{id}/`。

这与 App 已实现的 MIS 登录使用同一个 CAS 主机、同一组表单字段和验证码机制。因此最有希望的复用路径不是再次填密码，而是：

1. 用户先按现有流程登录 App，Ktor 会话已持有 CAS/MIS Cookie。
2. 用同一个 `SchoolHttpTransport` 请求 phyvlab 登录页并解析 OAuth2 链接。
3. 请求 OAuth2 链接；若 CAS Cookie 有效，应直接颁发 authorization code 并回到 Moodle callback。
4. Moodle 在同一 Cookie jar 内建立自己的会话，再把仅对 `phyvlab.bjtu.edu.cn` 生效的 Cookie 桥接给受信任网页容器。
5. 若 MoodleSession 单独过期，M13 首版会在 App 内强制获取一次 CAS challenge，复用当前内存凭据恢复 CAS；失败后只提示退出并重新登录主账号，不自动把用户推到系统浏览器。

全链路均为 HTTPS，当前没有新增 Android cleartext 或 Apple ATS 例外的理由。真实账号下是否完全免二次验证码、最终 Cookie 名、跳数和会话续期策略仍待 Ktor 真机/桌面实测。

## 3. 产品范围

### 第一纵向切片：安全登录与门户（优先）

- 共享层实现 phyvlab OAuth2 握手状态机，严格只允许 `phyvlab.bjtu.edu.cn` 与 `cas.bjtu.edu.cn` 的 HTTPS/443 跳转。
- 解析登录页中的动态 OAuth2 URL，不硬编码 `sesskey`、client id、state 或 authorization code。
- 成功后检查最终主机、登录态标记和 phyvlab 会话 Cookie；失败时区分 CAS 会话过期、需要验证码、站点格式变化与网络错误。
- Android/iOS 的原生课程、作业和详情页复用共享 Ktor/CAS 会话；需要打开普通 Moodle 页面时，`SchoolWebView` 只注入收窄后的 phyvlab Cookie。macOS/Windows 的网页备用入口仍交给系统浏览器，不能假定 App Cookie 能注入系统浏览器。
- Unity WebGL、测验提交、选课、完成标记和讨论继续交给网页流程；普通 Moodle 作业的附件提交已接入原生 Moodle 草稿区上传 + 提交表单，但必须由用户显式确认，且本轮没有向真实账号执行提交。

### 第二纵向切片：课程、作业详情与附件提交

优先验证 Moodle 标准 Mobile/Web Service，而不是先写脆弱 HTML 解析：

- `core_webservice_get_site_info`：用户、站点与可用函数。
- `core_enrol_get_users_courses` / 时间线课程：我的课程。
- `core_course_get_contents`：章节、活动、资源和完成状态。
- `gradereport_overview_get_course_grades`：课程总成绩（若站点权限开放）。

若站点没有向第三方客户端开放 token，再退回到登录态 HTML 解析：`/my/courses.php`、`/course/view.php`、作业详情页与 `pluginfile.php`。当前首版已采用登录态 HTML 解析，原生展示「我的课程 / 作业 / 到期安排 / 完成状态 / 提交状态 / 批改成绩 / 教师评语 / 已提交文件」。附件提交遵循 Moodle 页面生成的短期 `sesskey`、草稿 `itemid`、上下文与 filemanager 字段：先调用 `repository_ajax.php` 上传草稿，再提交编辑表单；这些短期值只留在内存，不进入 UI、缓存或日志。

### 明确不包含

- 不原生重做 Unity/WebGL 实验。
- 不自动选课、提交 quiz、标记完成、发讨论或上传 Unity 实验数据；普通 Moodle 作业附件提交仅在用户选文件并再次确认后执行。
- 不实现 ilab-x 账号关联。
- 不绕过 CAPTCHA，不持久化 OAuth code、token 或 Moodle Cookie 到普通缓存。

## 4. 与现有 KMP 架构的映射

建议新增独立 `phyvlab` 纵向切片，不塞进智慧教学 `homework/courseware`：

- `data/phyvlab/PhyVlabRemoteDataSource.kt`：登录页/OAuth2 握手、REST 或 HTML 拉取。
- `data/phyvlab/PhyVlabRepository.kt`：课程/作业/日历/详情读取、会话过期语义与提交结果编排。
- `domain/phyvlab/PhyVlabModels.kt`：课程、活动、到期安排、提交状态、成绩、反馈与附件摘要模型。
- `feature/phyvlab/PhyVlabScreenModel.kt`、`PhyVlabScreen.kt`：加载/空/错误/正常与网页入口。
- `commonTest/.../phyvlab/`：脱敏登录页、OAuth 回调落地、课程 JSON/HTML fixture。

当前实现补充：Moodle 课程概览卡片由脚本异步渲染，Ktor 取到的 `/my/courses.php` 可能只有侧栏课程链接；解析器对此提供服务端链接兜底。日历月视图在部分月份同样依赖脚本，因此原生安排优先从每门课程的“到期日”生成，并与可取到的 Moodle 日历事件按作业链接去重。作业详情页若只提供“编辑提交”链接，会先只读 GET 编辑页准备 filemanager 上下文；上传前后都检查站点域名、HTTP 状态和登录页特征。Windows 桌面端所有主要纵向滚动容器以及横向筛选 chip 都增加了触摸拖动兼容层，Android/iOS 保留平台原生滚动。

需要同步修改的现有边界：

- `SchoolWebDomainPolicy` 增加 `phyvlab.bjtu.edu.cn`，但 Cookie 校验继续要求 Cookie 域与页面域完全一致。
- 不复用 `followSmartHandshakeRedirects` 的 HTTP 降级逻辑；只复用其「逐跳解析、主机白名单、跳数上限、查询参数不入日志」设计原则。
- 现有 `SchoolHttpRequest.toString()` 已脱敏 `password/captcha/csrf/loginname`，新逻辑还需确保 OAuth `code/state/sesskey/token` 不进入日志。
- 当前 `SchoolWebView` 对 Unity 的弹窗、文件选择、全屏与下载不足；Moodle 普通页可内嵌，`/course/vlab/` 应明确分流系统浏览器。

## 5. 平台策略

| 平台 | Moodle 普通页 | Unity 虚拟实验 |
|---|---|---|
| Android | 应用内 WebView，可注入收窄后的 phyvlab Cookie | 系统浏览器，避免 WebGL/弹窗/上传能力缺口 |
| iOS | WKWebView，可注入收窄后的 phyvlab Cookie | Safari；真机验证 WebGL、音频、下载与文件选择 |
| macOS | 当前实现为系统浏览器 | 系统浏览器 |
| Windows | 当前桌面实现同样走系统浏览器 | 系统浏览器 |

所有平台都只允许 HTTPS。`ilab-x.com`、百度 CDN、Moodle 下载站等第三方地址不接收学校 Cookie。

## 6. 验收

- [x] 外网可达性、框架/版本、访客课程、课程目录、标准活动与 Unity 子系统已调研。
- [x] 未登录 OAuth2 链验证到 CAS 表单，确认 issuer、callback、scope 与表单字段。
- [x] 现有 App 已登录 CAS 会话下完成 Ktor phyvlab SSO；同一 Cookie jar 可免二次验证码建立 Moodle 会话，日志只保留脱敏主机/路径、状态码与字节数。
- [x] 登录后盘点并接入「我的课程、作业、完成状态、到期安排」；当前首版补充了单项作业的提交/批改/成绩/评语/附件详情，通知聚合仍未纳入。
- [x] 物理在线日期统一为 `yyyy年MM月dd日 HH:mm`，去掉 `Thursday` 等英文星期；日期解析、月份切换和首页日历归日均固定按北京时间（`Asia/Shanghai`），并合并到首页日程。
- [x] Windows 原生详情页显示提交状态、提交时间、批改状态、成绩、评语和已提交文件；点击作业不再自动跳浏览器。
- [x] 普通 Moodle 作业的原生附件提交协议已用脱敏假传输覆盖：编辑页准备、草稿上传、最终表单提交、会话参数脱敏均有测试；真实提交尚未执行。
- [ ] 验证 Moodle Mobile/Web Service 是否开放，以及能否安全取得仅用于本 App 的 token。
- [ ] Android/iOS 真机或模拟器、macOS 运行时验证课程/作业详情、文件选择器、Cookie 同步、域名白名单、会话过期与外链分流。
- [ ] Unity 入口在 Android/iOS/macOS/Windows 的系统浏览器实际运行，WebGL、音频、弹窗、下载/上传边界明确。
- [ ] 三端/四端原生页覆盖加载、空、失败、登录过期和正常状态（当前 Windows 构建版已验证，其他平台受本机环境限制）。
- [ ] 日志、截图、fixture 不含账号、Cookie、OAuth code/token、真实成绩或验证码会话。

## 7. 当前风险与待验证点

- **CAS Cookie 复用**：浏览器未登录时会出现验证码；App 的 Ktor 会话理论上可免登，但必须用真实会话证明。
- **Moodle token**：旧版 Moodle 客户端下载说明 Mobile 能力很可能存在，但还不能据此认定 REST token 对第三方 App 开放；当前仍使用登录态 HTML。
- **原生提交协议**：不同作业插件/主题可能使用不同 filemanager 名称、上下文或最终表单动作；当前解析器有多组兜底，但必须用真实可编辑作业做一次用户确认后的端到端验证。
- **Android 本机门禁**：已补装独立 Android SDK Platform 36/Build Tools 36、emulator 与 `adb`；`:shared:compileAndroidMain`、`:androidApp:compileDebugKotlin`、`:androidApp:assembleDebug` 均通过并生成 debug APK。`codex-m13-api36` AVD 已冷启动并安装 APK 到 Compose 登录页，但未用真实账号进入物理在线页。
- **Apple 共享层门禁**：`:shared:compileKotlinIosSimulatorArm64` 已在 Windows 交叉编译通过，Skiko iOS 依赖已缓存；`:shared:compileKotlinIosArm64` 在 Windows 上启动后未在有限窗口内完成；这不替代 macOS/Xcode 下的 App、Simulator、签名和真机验收。
- **作业顺序**：物理在线课程作业列表默认新的在上方，并提供正/逆序切换；按 Moodle 活动 ID 反转原本正序，不用可能被调整的截止时间推断新旧。
- **账号/选课关系**：登录后可能仍需用户手动自助选课；首版不能自动改变选课状态。
- **Unity 兼容性**：旧 `UnityLoader` 构建可能对现代 iOS WebKit、移动端内存、横屏和音频手势有限制，必须真机验证。
- **安全维护**：站点基线为 Moodle 4.0.4+，版本较旧；App 不应扩大 Cookie 域、不在第三方页面内嵌学校会话，也不尝试绕开站点权限。

## 8. 下一步

1. 在真实课程变化后补充解析 fixture，并评估 Moodle Mobile/Web Service 是否开放；未确认前不保存 token。
2. 将当前 Windows 验证扩展到 Android/iOS/macOS 的详情页、系统文件选择器和会话续期；在用户明确确认后验证一次真实作业附件提交。
3. 保持 Unity、自动选课、quiz、完成标记和讨论的网页边界；再评估通知等只读扩展。
