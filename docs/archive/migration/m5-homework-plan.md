# M5 作业纵向切片实施计划

> 基线：官方 `v1.7.0@419313d` 隔离源码与 Android 脱敏视觉记录
> 修改范围：仅 `multiplatform/` 与迁移文档；冻结 Android 根工程保持只读
> 状态：实现、测试与三端编译已通过；用户已授权 iOS/macOS 使用精确旧明文端点，Android 继续拒绝

## v1.7.0 行为基线

- 同步平时作业、课程设计和实验报告三种任务；课程内身份使用 `(courseName, upId)`，不能退回按标题匹配。
- 支持课程多选、隐藏过期任务，以及截止时间“原顺序 / 由近到远 / 由远到近”三态排序。
- 汇总显示当前列表数量，并统计未来 0–48 个完整小时内尚未提交的任务。
- 卡片显示课程、任务类型、标题、开放/截止时间、提交状态、提交人数和评分。
- 详情显示 HTML 要求和教师附件；原版还有教师附件下载、学生已交附件下载与作业上传。
- 列表来自智慧教学平台，不是 AA 教务页面；登录 MIS 后还需通过 module 28 建立独立平台会话。

## 当前共享实现

1. `StrictJson.kt`：自包含标准 JSON 解析，不依赖新增 iOS 产物，也不在错误中保存响应正文。
2. `HomeworkJsonParser.kt`：解析学期、课程、三类任务、详情与附件元数据，保留 `(courseName, upId)` 稳定身份。
3. `HomeworkRemoteDataSource.kt`：惰性建立智慧教学平台会话，完整拉取三种任务，并对已评分项尽力补全分数。
4. `HomeworkRepository.kt`：缓存优先、按账号替换；任一远端步骤失败时保留旧快照，详情失败与列表失败分别反馈。
5. `HomeworkScreenModel.kt`：管理多课程筛选、过期过滤、三态排序、48 小时统计、详情请求和刷新来源。
6. 登录后应用壳增加“作业”入口。

## 网络安全边界

原 Android 通过固定明文 IP `123.121.147.7:88` 访问智慧教学平台。当前按平台拆分边界：

- Android 只构造 `https://bksycenter.bjtu.edu.cn` 下的固定路径并拒绝 HTTP 降级；iOS/macOS 使用封闭旧 HTTP 白名单；
- macOS 在用户明确接受窃听/篡改风险后，只允许 `http://123.121.147.7:88/ve/...`；不允许其他 IP、端口、协议或 `/ve/` 外路径；
- module 28 仍先通过 HTTPS MIS 建立跳转，每个中间和最终 URL 都按当前平台端点重新校验；
- `sessionid` 只保存在内存，请求字符串化时按敏感头脱敏；
- 不给 iOS 添加全局 `NSAllowsArbitraryLoads`，也没有给 Android 打开 cleartext。

Apple 宽窗口相关页、作业页和紧凑布局全局顶部持续显示不可关闭的明文风险提示；Android 仍显示安全通道不可用。授权没有扩大到 MIS、AA、邮箱、任意学校子域或任意 HTTP 资源。

## Apple 布局

- iPhone：单列任务卡；课程多选放在底部 sheet；详情以底部 sheet 展示，不要求横向滚动。
- macOS：侧栏进入作业；主区为列表—常驻详情；刷新保留当前内容。
- 筛选文字改为清晰的“显示全部日期 / 已隐藏过期”，不沿用原版含义不直观的文案。
- 仅使用系统 sheet、进度条和选中状态反馈，不为普通刷新添加弹跳。

## 平台能力边界

本切片已迁移教师附件的名称和大小语义，但不把“看见附件”冒充“文件流程完成”：

- 当前详情把 HTML 安全转换为可读纯文本；WKWebView/桌面网页容器及 Cookie 同步属于 M6。
- 系统文件选择器、附件保存/预览、学生已交附件下载和作业上传属于 M6。
- 在上述能力完成前，UI 明确提示尚未接入，不静默写文件，也不提供会失败的伪按钮。

## 待执行验证

- 作业核心现有 16 项测试源码：JSON 解析 4、已交附件 HTML 解析 2、远端 3、Repository 3、状态模型 4；文件网关另有 2 项契约测试，并补 multipart 请求脱敏测试。
- ~~运行全量 Desktop 测试、Android debug/release、iOS 两架构、macOS distributable 和 Xcode Simulator build。~~ **2026-07-30 已执行**：Desktop 123 项测试零失败，`:androidApp:assembleDebug`、`:shared:linkDebugFrameworkIosSimulatorArm64`、`:desktopApp:createDistributable` 与 Xcode Simulator build 全部成功。修复了两处真实编译问题：kotlinx-datetime 0.8.0 移除 `kotlinx.datetime.Clock` 后改用 `kotlin.time.Clock`（`HomeworkScreenModel.kt`），以及两处测试 `assertEquals(emptyList(), ...)` 泛型推断失败。
- 登录 iOS/macOS，验证 HTTPS 会话、真实三类任务、筛选/排序、详情和附件元数据。
- 完成 M6 后，再按原 Android 同一任务验证下载、上传、取消、失败恢复与系统文件面板。

**2026-07-31 真实更新**：iOS 新 CAPTCHA 登录仍被 CAS 统一拒绝；macOS 既有有效会话允许进入作业页，但当时安全策略拒绝旧 HTTP，因而初始化失败。无凭据探针确认假设的 HTTPS API 返回 404，而旧 `:88` HTTP 端点可达并返回 302。用户随后明确授权 macOS 明文登录会话；现已通过封闭端点策略接入并完成 224 项共享测试、三端 Gradle 与 Xcode 构建。Computer Use 后续已成功向 macOS Compose 登录框填写内存中的凭据并填入当前 CAPTCHA 答案，登录按钮已启用；因没有收到本次动作时提交确认而停在按钮前，故授权后真实账号同步尚未执行，不能写成真实作业已拉取。详见 `m7-apple-real-audit.md`。
