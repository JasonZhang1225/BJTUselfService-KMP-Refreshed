# M5 课件与课程资源纵向切片计划

> 基线：官方 `v1.7.0@419313d` 隔离源码、真实登录后的脱敏课件页与展开截图
> 修改边界：冻结 Android 根工程只读；实现只进入 `multiplatform/`
> 状态：业务与三端文件网关已实现；用户已授权 iOS/macOS 使用精确旧明文端点，Android 继续拒绝

## 真实行为基线

- 页面标题为“课程资源库”，顶层每张卡代表一门智慧教学平台课程。
- 顶层课程卡可展开；子节点可能是文件夹 `bagList`，也可能是资源文件 `resList`，文件夹允许继续递归。
- 文件显示资源名；原模型还包含扩展名、大小、教师、上传时间、下载次数和 `rpId`。
- 点击单个文件会先 POST `resourceSpace.shtml?method=rpinfoDownloadUrl&rpId=...` 获取实际下载 URL，再读取响应头文件名并下载。
- 文件夹和课程顶层提供递归下载，原版把层级写入“交大自由行下载目录/课程/文件夹”。
- 顶层有独立的教学日历按钮：先进入课程平台页面读取教师 ID，再从 `iframe#pdfIframe` 提取路径并拼出 PDF 地址。
- 原 ViewModel 先显示本地 JSON 缓存，再重新拉取完整课程树并覆盖缓存。

## 远端接口

1. 复用智慧教学平台学期和课程列表。
2. 对每个课程从根节点 `up_id=0` 请求：
   `courseResource.shtml?method=stuQueryUploadResourceForCourseList&courseId=...&cId=...&xkhId=...&xqCode=...&docType=1&up_id=...&searchName=`。
3. 对每个 `bagList` 节点以其 ID 递归请求；`resList` 为叶子文件。
4. 下载前 POST `resourceSpace.shtml?method=rpinfoDownloadUrl&rpId=...`，解析 `flag`、`rpUrl` 和下载类型。
5. 教学日历先请求 `coursePlatform.shtml?method=toCoursePlatform...` 获取 `teacherId`，再读取 PDF iframe。

## 安全边界

v1.7.0 的课程树、文件下载和教学日历都包含固定明文 IP，教学日历最终还使用端口 1936。当前不使用 ATS 全局放行，而是按平台注入封闭策略：

- Android 的课程树、下载票据和资源仍只允许精确 HTTPS 同源；iOS/macOS 仅允许封闭的旧 HTTP 白名单；
- macOS 只允许 `http://123.121.147.7:88/ve/...`，`rpUrl` 不能换 IP、端口、协议或越出 `/ve/`；
- macOS 教学日历只取 iframe 路径末五个安全段并重建为 `http://123.121.147.7:1936/kk/rp/...`，不沿用 iframe 主机；编码路径穿越、换端口和片段均拒绝；
- `rpId`、服务端资源路径和最终 URL 不写日志，不进入截图；缓存只保存重建树和发起受限请求所需的最小字段；
- 递归下载途中任一响应越出 allowlist，整项立即失败，不继续跟随重定向。

真实登录验收前无法证明 HTTPS 映射和资源域名，因此实现文档必须把“源码已写入”和“真实可下载”分开。

## 共享数据与状态

- `CoursewareCourse`：课程 ID、名称、课程号、分组 ID、学期、教师 ID。
- `CoursewareNode`：稳定键、父节点、文件夹/文件类型、名称、`rpId`、扩展名、大小与子节点。
- Repository：缓存优先；只有整棵课程树成功后才替换当前账号快照；单门课程失败不产生半棵新树。
- 状态模型：当前课程、当前路径、展开集合、选中文件、刷新来源、失败状态和下载队列。
- 递归请求检测重复文件夹 ID 与过深层级，避免异常数据造成无限递归；这属于输入防御，不改变正常树结构。

## Apple 信息架构

### iPhone/iPad 紧凑宽度

- 第一层显示课程列表和课程级“下载全部 / 教学日历”。
- 点击课程或文件夹进入下一层，导航栏显示返回与当前路径；不采用无限增加左缩进的长页面。
- 文件卡显示名称、类型和大小；点击后先展示确认/保存流程，不静默下载。
- 递归下载先让用户选定目标位置，再逐文件下载并写入，同时显示总数、完成、失败和取消；不会先把整门课程的 `ByteArray` 全部留在内存中。

### macOS/宽窗口

- 应用侧栏进入“课件”；工作区左列为课程，主列使用可折叠 Outline，右侧常驻资源详情和下载操作。
- 支持鼠标双击展开、方向键、Return、Space/Command-S 保存等后续键盘行为；不把手机卡片简单拉宽。
- 递归下载使用系统目录选择器，保持课程/文件夹层级；同名覆盖必须由系统确认或明确冲突策略处理。

## 下载与目录语义

单文件复用 M6 的 `HomeworkFileGateway` 保存结果语义，但目录下载需要扩展平台网关：

- Android：选择目录树并通过 DocumentFile/SAF 创建子目录。
- iOS/iPadOS：选择可写目录的安全作用域 URL，或在系统允许范围内导出完整文件夹；不能静默退化成散落的多个文件。
- macOS：选择目标目录后按树创建子目录。

如果某个平台的系统 API不能可靠保留目录层级，必须先在本地生成明确命名的归档并让用户导出，且在 UI 中说明；不能只下载第一层或只下载叶子之一。

## 当前源码结果

- `domain/courseware` 已定义课程、文件夹/资源节点、稳定键、紧凑路径和宽窗口可见树规则。
- `data/courseware` 已实现严格 JSON/HTML 解析、版本化嵌套缓存和账号范围 Repository。2026-08-01 起首次同步只请求每门课程的顶层节点，文件夹第一次展开时才加载直接子项；移除每请求固定 80 ms 人工等待。2026-08-02 真实无缓存 iOS 首层仍需约 6–10 秒，确认剩余瓶颈是各课程顶层串行请求，随后改为最多 3 个有界并发；7 门课程测试的峰值严格为 3。缓存 v2 记录文件夹是否已加载，并兼容把 v1 完整树缓存迁移为已加载状态。
- 单文件下载先申请 ticket，再拒绝非 HTTPS、非当前已知 `bksycenter.bjtu.edu.cn` 主机、空响应和越界最终 URL；真实验收若出现其他 HTTPS 资源主机，必须逐个核验后加入，不预先放行整个 `*.bjtu.edu.cn`。
- 教学日历按 v1.7.0 顺序进入课程平台、读取教师 ID 和 `iframe#pdfIframe`；Android 只读取允许的学校 HTTPS 地址，iOS/macOS 按上述固定 `:1936/kk/rp/` 规则重建。
- iPhone/紧凑界面使用课程选择、文件夹逐级进入、文件详情 sheet 和系统保存反馈；macOS/宽窗口使用课程列、可折叠资源树和常驻详情。
- 课程与文件夹导出使用会话式目录网关：导出前先递归补齐目标子树中尚未加载的文件夹；任一目录请求失败则不打开系统目录，避免把残缺树导出成“完整成功”。补齐后 Android 文档树、iOS security-scoped/协调写入和 macOS 目标目录都由用户选定并创建本次新根目录，随后每下载一个叶子文件就立即写入；相对文件夹层级不会被压平，也不会把全课程内容同时驻留内存。
- 资源正文与教学日历 PDF 请求不会携带智慧平台自定义 `sessionid`；Cookie 仍由 Ktor 按主机和路径管理。刷新、单文件、目录和日历网络操作使用同一共享互斥锁，避免并发覆盖下载进度和失败状态。
- Android 与 macOS 文件网关会拒绝同时打开多个系统面板；Android SAF、iOS 协调写入和 macOS IO 会话都在每个文件前后检查取消，取消、下载失败或写入失败时在 `NonCancellable` 清理段尝试删除本次刚创建的根目录，避免把半份目录留成成功结果。
- 目录选择、网络下载和实际写入共用同一个可取消 Job；Android 将目录打开 continuation 取消绑定到 SAF 根目录创建 Job，iOS 把 security-scoped 访问权持有到会话提交/回滚，macOS 在会话结束前保持系统面板互斥。系统选择器自己的取消返回 `Cancelled`，不会显示为失败。
- 共享文件名清洗在 UTF-16 长度边界避免截断 emoji 的高代理项；相关断言已并入名称安全测试，并由后续 Desktop 全量与三端门禁覆盖。
- 三端文件 API 共用 `safeExportFileName/safeExportPathSegment`：剥离路径、拒绝 `.`/`..`、替换控制及双向文本控制字符、限制长度并尽量保留短扩展名，避免远端名称逃出用户选择的导出根目录。共享分配器还按大小写不敏感规则同时登记文件和文件夹；清洗后同名时稳定追加 ` (2)`，避免 Android 自动改名而 Apple 端失败或不同逻辑目录被合并。
- 课件导航已作为第五个登录后业务入口接入应用壳，共新增 27 项 `commonTest` 源码，覆盖 JSON、HTML、缓存、重复课程/节点、递归网络、安全 URL、会话头隔离、Repository、网络互斥、状态模型、先选目录再下载、逐文件写入、失败/取消回滚、目录层级、名称冲突、路径穿越、不可见字符和脱敏契约。

以上描述实现范围；最新编译、运行和真实服务器证据见文末 2026-07-31 更新，不能用后续门禁反推未执行的文件下载流程已经成功。

## 平台 API 元数据审查

在无法执行 Gradle/Xcode 门禁期间，本机只读元数据提供了以下较窄证据：

- Material 3 `1.11.0-alpha07` 的 Desktop 字节码包含 `LinearProgressIndicator(Function0<Float>, ...)`，目录进度条的 lambda 形态匹配。
- AndroidX Activity `1.13.0` 的 Kotlin metadata 与 nullable 注解表明 `OpenDocumentTree` 的输入/输出是 `Uri?`，因此 `launch(null)` 符合契约；Android 36 `DocumentsContract.createDocument/deleteDocument` 签名也匹配。
- Kotlin/Native `2.4.10` 的 iOS Simulator 平台 metadata 包含当前使用的文档选择器构造器、delegate、`UTTypeFolder`、security-scoped URL、`NSFileCoordinator`、递归建目录和 `NSData.writeToURL` 签名。
- Apple 官方“Providing access to directories”说明 iOS 13 起可由文档选择器返回目录的 security-scoped URL，并允许递归访问和新增内容；因此 iOS 目录网关改为明确选择文件夹，再在后台通过 `NSFileCoordinator` 写入。根目录已存在时不覆盖；写入失败或协程取消时尝试删除本次新建的半成品根目录。

这些证据只能降低 API 记忆错误风险，不能证明 Compose/Kotlin 编译、文件提供器兼容性、权限、冲突处理或真实导出成功。

## 验证门禁

1. JSON：空列表、`""` 代替数组、混合文件夹/文件、中文名、无扩展名和坏节点。
2. 树：顶层先返回、文件夹按需加载、v1/v2 缓存迁移、重复 ID 拒绝、加载失败保持旧缓存；完整导出会补齐多层子树。
3. 下载：申请失败、非 HTTPS、非 allowlist、无文件名、空响应、取消和目录冲突。
4. UI：iPhone 逐级导航、macOS Outline/详情、加载/缓存/空/错误和下载进度。
5. 真实验证：登录 iOS/macOS 后对照两张 Android 脱敏基线，至少验证一个文件夹展开、一个单文件保存、一门课程递归下载和一个教学日历；不会使用或上传用户未明确选择的文件。

静态门禁确认旧 IP 只存在于 `SmartPlatformEndpoint.AppleLegacyHttp` 封闭配置、iOS 固定 IP ATS 例外和测试中；没有 trust-all、全局 ATS/Android cleartext 或请求日志。最新 Gradle/Xcode 结果见下节；macOS 真实课件树与下载已验收，iOS 正在复验。

## 2026-07-31 真实验证更新

macOS 有效登录会话已进入真实课件页，但课程树初始化失败。无凭据 `curl` 与 Ktor CIO 均确认 `https://bksycenter.bjtu.edu.cn/ve/back/coursePlatform/message.shtml` 返回 404；域名和旧 IP 的 88 端口均不能建立可用 TLS。原 Android 的明文 `123.121.147.7:88` 不是可以直接替换主机名的 HTTPS 服务。

初始实现把该 HTTPS 404 映射为 `SECURE_CHANNEL_UNAVAILABLE`。用户先授权 macOS、后于 2026-08-01 授权 iOS 接入精确 `:88/ve/` 与 `:1936/kk/rp/` 策略；Apple 版在课件页和紧凑全局顶部常驻不可关闭提示，Android 仍不发送会话到旧 IP。iOS ATS 只为固定 IP 开例外，端口/路径仍由共享白名单限制。无凭据联网复核确认两个旧 HTTP 端口均可达并返回 302；macOS 真实课件树与下载已完成，iOS 正在复验。完整证据见 `m7-apple-real-audit.md`。
