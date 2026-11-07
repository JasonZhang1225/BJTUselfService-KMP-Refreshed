# M6 作业文件能力实施计划

> 基线：`v1.7.0@419313d` 的教师附件下载、学生已交附件下载与作业上传  
> 状态：共享网络协议、三端文件网关、共享 UI 与三端编译门禁已完成；真实文件面板和上传/下载验收待新登录会话

## 三条真实流程

### 教师附件

1. 作业详情返回 `picList`，显示文件名和大小。
2. 点击后请求 `dataSynAction.shtml?method=downLoadPic&id=...&noteId=...`。
3. 收到二进制后由系统保存面板选择位置；取消不算失败，也不得留下半文件。

### 学生已交附件

1. 仅在作业已提交时请求 `courseWorkInfo.shtml?method=piGaiDiv...`。
2. 从 `div.homeworkContent` 的回调参数解析服务端路径、文件名和 ID。
3. 请求 `downloadZyFj.shtml` 获取二进制，再交给系统保存面板。

### 作业上传

1. 系统文件选择器允许逐项选取文件，并显示名称、类型和大小。
2. 每个文件通过 multipart 上传到 `rpUpload.shtml`，严格解析四个服务端回执字段。
3. 把回执组成 `fileList`，再向 `sendStuHomeWorks` 提交说明、课程、任务类型和作业 ID。
4. 服务端确认成功后刷新作业快照；中途任一文件失败时不发送最终提交表单。

## 已写入的共享层

- `SchoolHttpRequest` 新增结构化 multipart 文件，不允许与普通表单混发。
- 文件名、文件正文、作业说明、`fileList`、`sessionid` 和 URL 查询参数在请求字符串中脱敏。
- Ktor transport 使用 `MultiPartFormDataContent`，不新增第三方上传库。
- `HomeworkRemoteDataSource` 已包含教师附件下载、已交附件发现/下载和两阶段上传。
- Android 端点继续限定在验证过的学校 HTTPS；iOS/macOS 按用户授权仅允许 `SmartPlatformEndpoint.AppleLegacyHttp` 的固定 `123.121.147.7:88/ve/` 范围，每次最终 URL 仍重新校验并常驻显示明文风险。iOS ATS 例外只覆盖固定 IP，端口与路径由共享白名单约束。
- 新增已交附件 HTML 解析、无扩展名上传回执、二进制下载和 multipart 请求测试源码。

## 已写入的平台文件网关

共享 UI 只负责表达“选择 / 保存 / 取消 / 失败 / 成功”，文件系统操作已经按平台分别写入：

- iOS/iPadOS：`UIDocumentPickerViewController` 导入与导出；作业单文件的安全作用域 URL 只在读取期间持有，临时导出文件在完成、取消或协程取消后删除。课件目录另用 `UTTypeFolder` 打开写入会话，security-scoped URL 持有到提交/回滚，并以 `NSFileCoordinator` 逐文件协调写入。
- macOS：AWT `FileDialog` 映射系统保存/打开面板；读写在后台执行，单文件先写同目录临时文件再原子替换，取消不会把目标文件留在半写状态；网关互斥多个系统面板。
- Android KMP：Storage Access Framework 的打开、创建文档与目录树契约，不申请宽泛存储权限；Activity 销毁时结束悬挂操作。课件先选择文档树并创建会话根目录，再逐文件写入；打开 Job 和写入协程都响应调用方取消，回滚只删除本次新建根目录。

平台网关统一返回 `Selected / Saved / Cancelled / Failed`，取消不显示红色错误。共享详情现可分别下载教师附件和已交附件；上传界面要求先选择并检查文件列表，再点击提交。二进制只在操作期间驻留内存；服务端私有路径不进入数据库、截图或日志。

## Apple 交互

- iPhone：详情 sheet 内分别列出“老师提供的附件”和“我已提交的附件”；下载后弹出系统保存面板。上传使用独立 sheet，文件选择和提交是两个明确步骤。
- macOS：常驻详情中显示附件区和上传区；保存/打开面板不被自定义弹层遮挡；上传期间保留当前列表和详情。
- 成功只在服务器确认并完成本地刷新后显示；网络失败、保存失败和用户取消使用不同反馈。
- 不自动覆盖同名文件，不在后台静默保存，不用循环进度伪装无法测量的上传进度。

## 验证门禁

1. ~~共享解析、Repository、状态和 transport 测试。~~ **2026-07-30 已通过**：Desktop 123 项测试零失败（含 multipart 脱敏、文件网关契约与会话式目录导出测试）。
2. Android/iOS 两架构/macOS 编译与 release 形态检查。
3. 使用合成字节验证选择取消、保存取消、空文件、中文名、无扩展名和多文件。
4. 真实登录后分别验证教师附件、已交附件和上传；上传只使用用户明确选定且确属当前作业的文件。2026-08-01 用户确认现在没有新布置的可提交作业，因此上传验收延期到后续有作业时，不制造任务或上传无关文件。
5. Computer Use 检查 iPhone 与 macOS 的系统面板、焦点、取消、覆盖确认、成功与错误反馈；截图必须脱敏。

**2026-08-01 当前门禁状态**：Android debug/release、iOS framework 与 Simulator 测试、macOS distributable 和 arm64 Xcode 宿主均通过。macOS 教师附件/课件真实下载和 iOS 作业/课件/中英文成绩单系统保存面板均已验收；iOS 中文附件名也已用 Native GB18030 修复并复验。仍未执行的是作业真实上传，因为它需要用户明确选定无敏感测试文件；不能用下载面板或合成测试冒充上传通过。

## 网页容器与 Cookie 同步（2026-07-30 晚新增）

剩余功能（校历、成绩单、邮箱、教室、教学日历）的共同前置——应用内网页容器——已完成基础设施：

- 共享协议：`WebPageRequest`/`WebCookie`/`ExternalLinkPolicy` 与 `SchoolWebDomainPolicy`。只允许 `mis/cas/aa/bksycenter/dean.bjtu.edu.cn` 五个学校域名；Cookie 域名必须与页面域名匹配（跨学校域名也拒绝）；强制 HTTPS；`toString` 对 Cookie 值与 URL 查询参数脱敏。
- expect/actual：`SchoolWebView` 在 iOS 用 WKWebView + `NSHTTPCookie` 同步会话 Cookie，非学校域名取消导航并分流系统浏览器；Android 用 WebView + CookieManager，同样按域名分流；macOS 不引入 JCEF，按既定方案显示引导卡并用 `Desktop.browse` 打开系统浏览器，按钮标签带具体目标域名。
- 验证：新增 7 项域名校验测试（白名单、跨域 Cookie、跨学校 Cookie、大小写/端口、HTTPS 强制、脱敏），全量 Desktop 130 项测试零失败，Android/iOS/macOS/Xcode 构建全部成功。
- 具体入口现已接入应用壳：校历、中英文成绩单和邮箱等会按各自平台边界进入下载、网页容器或系统浏览器。iOS WKWebView 邮箱已由用户肉眼确认可用；作业、课件和中英文成绩单系统保存面板已用真实会话通过。macOS 邮箱仍按既定边界使用系统浏览器且不注入 App Cookie。
