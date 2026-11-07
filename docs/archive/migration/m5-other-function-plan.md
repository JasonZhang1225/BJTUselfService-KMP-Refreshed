# M5「其他功能」切片：校历下载 + 成绩单下载

> 历史说明（2026-08-28）：校历下载接口已失效，当前 KMP 入口已改为打开可手动更新的公众号文章；下文保留 M5 时期的下载实现规划与验收记录，不代表当前入口行为。

日期：2026-07-30  
里程碑：M5 第六个业务切片（其他功能）

## 行为基线（冻结 Android 工程，只读参考）

来源：`app/src/main/java/team/bjtuss/bjtuselfservice/repository/OtherFunctionNetworkRepository.kt`
与 `screen/OtherFunctionScreen.kt`。

1. 校历：GET `https://bksy.bjtu.edu.cn/Admin/SemesterTranPage.aspx?noRemark=1`（带桌面浏览器 UA），
   从返回 HTML 的 `<script>` 内容里解析 `url: "..."`，拼成 `https://bksy.bjtu.edu.cn<postfix>` 下载文件。
2. 成绩单：GET `https://aa.bjtu.edu.cn/score/scorecard/stu/5201314/download_pdf/?type=card_en_sign&has_advance_query=`
   （英文）或 `type=card_cn_sign`（中文），依赖 aa.bjtu.edu.cn 会话 Cookie（原 App 从 cookieJar 取 aa 域 Cookie
   拼成 `name=value;` 串作为 Cookie 头；KMP 端直接复用共享 transport 的内存 Cookie 会话，无需手工拼头）。
3. UI：两张功能卡「校历下载」「成绩单下载」，成绩单卡带中英文 Switch，下载中显示进度，成功/失败有反馈。

## KMP 实现

新增包结构（均在 `multiplatform/shared/` 内，中文注释）：

- `domain/otherfunction/OtherFunction.kt`：`ReportCardLanguage`、`OtherFunctionTask`、
  `OtherFunctionTaskState`（Idle/Downloading/Saved/SaveCancelled/Failed）与 `OtherFunctionFailure`
  （NETWORK/PARSE/SESSION_EXPIRED/SAVE_FAILED/SAVE_UNAVAILABLE）。
- `data/otherfunction/OtherFunctionHtmlParser.kt`：Ksoup 解析校历页面，聚合全部 `<script>` 块后用
  正则提取 `url: "..."`（单双引号均可）；缺失脚本/字段/空值返回明确 Failure，不抛异常、不泄露 HTML 正文；
  `reportCardDownloadUrl` 按语言固定构造 aa 下载 URL。
- `data/otherfunction/OtherFunctionRemoteDataSource.kt`：经 `SchoolHttpTransport` 拉取页面与文件。
  域名边界：校历最终文件 URL 必须仍指向 `bksy.bjtu.edu.cn`（相对路径拼 origin，绝对 URL 校验 host），
  成绩单响应 `finalUrl` 必须是 `aa.bjtu.edu.cn`；响应非 PDF（Content-Type 与魔数双重判断）判为
  `SESSION_EXPIRED`（服务器会话失效时返回登录页 HTML）。
- `data/otherfunction/OtherFunctionRepository.kt`：异常到 `OtherFunctionSyncFailure` 的映射，
  `CancellationException` 原样上抛。
- `feature/otherfunction/OtherFunctionScreenModel.kt`：Mutex 串行化两个任务，成功后经
  `HomeworkFileGateway.saveFile` 走系统文件面板；取消 → `SaveCancelled`（不显示红色错误）。
- `feature/otherfunction/OtherFunctionScreen.kt`：两张功能卡 + 中英文 Switch，状态就地反馈
  （下载中进度、成功显示文件名、失败按原因给文案），iPhone 单列，macOS 宽窗口侧栏接入。
- 登录后应用壳（`feature/grade/GradeScreen.kt` 的 `AuthenticatedAppShell`）新增第六个入口「其他功能」，
  紧凑布局进入 `CompactSectionSwitcher` 的 FlowRow，宽窗口进入 `AppSidebar`。

## 关键修复：多 script 块

真实页面（2026-07-30 curl 验证，HTTP 200）含 6 个 `<script>` 块，`url:` 字段在第 4 个块中，
前 3 个为外链/空脚本。首版实现只取 `selectFirst("script")` 会在真实页面上失败，
已改为聚合全部 script 块内容再匹配，并补充回归测试。原 Android App 用 `doc.select("script").html()`
同样是聚合语义，本实现与之一致。

## 域名白名单说明

`SchoolWebDomainPolicy.allowedHosts` 维持 mis/cas/aa/bksycenter/dean 五域不变：
本切片不通过应用内 WebView 打开 bksy 页面，校历下载走共享 transport 直连并在远端数据源内做
host 校验，因此无需把 `bksy.bjtu.edu.cn` 加进 WebView Cookie 白名单（避免扩大 Cookie 同步面）。

## 测试与验收

新增 commonTest 22 项（全量 Desktop 152 项，零失败，基线 130 项）：

- `OtherFunctionHtmlParserTest`（10 项）：双引号/单引号/多 script 块/缺 script/缺 url 字段/
  引号不闭合/空值/相对路径保持/中英文成绩单 URL 构造。
- `OtherFunctionRemoteDataSourceTest`（6 项）：校历两段式下载与 URL 断言、外部域名 postfix 拒绝、
  解析失败、中文成绩单会话下载、HTML 响应判会话失效、重定向出 aa 域判会话失效。
- `OtherFunctionScreenModelTest`（6 项）：成功保存显示文件名、保存取消非错误、保存面板不可用、
  网络失败、会话失效、语言切换传递。

门禁命令与结果（2026-07-30）：

- `./gradlew :shared:desktopTest` — BUILD SUCCESSFUL，152 项零失败零跳过。
- `./gradlew :androidApp:assembleDebug :shared:linkDebugFrameworkIosSimulatorArm64 :desktopApp:createDistributable`
  — BUILD SUCCESSFUL。

真实网络验证：

- 校历：`curl` GET `https://bksy.bjtu.edu.cn/Admin/SemesterTranPage.aspx?noRemark=1`（桌面 Chrome UA）
  返回 200，页面 script 中含 `url: "/New/Semester/2024-2025校历.pdf"`，与解析器行为一致。
  校历切片**不需要登录**，即可用真实网络完整验收（页面 → 解析 → PDF 下载 → 系统保存面板）。
- 成绩单：**依赖 aa.bjtu.edu.cn 会话**（经 MIS→CAS→AA 登录联动建立）。2026-08-01 已用有效 iOS 会话完成中英文真实下载：两次均进入系统“文件”保存面板，文件名分别为“中文成绩单”“英文成绩单”，随后取消保存；协议层同时覆盖正常/会话失效/域名逃逸。

## 校历真实网络端到端验收（2026-07-30，通过）

用临时 desktop 测试（验收后已删除）直连真实教务处，完成「GET 页面 → 聚合 script 解析 → 域名边界校验 → 下载文件」全链路：页面 200（约 700 KB），最终文件 `application/pdf` 共 **9,744,588 字节**。

验收中发现并已修复一个真实 bug：校历 PDF 路径含中文（`2024-2025校历.pdf`），服务器对未编码的非 ASCII 路径返回 **404**（探测确认原始路径 404、百分号编码后 200）。数据源此前直接拼接原始中文路径，异常被吞为 NETWORK。修复：`toAllowedCalendarUrl` 对未含 `%` 的路径用 `encodeURLPath()` 编码（已含 `%` 不重复编码）；`fileName` 改从原始 postfix 取，避免展示成百分号编码串。已更新对应测试断言（请求 URL 编码、文件名保持中文）。全量 152 项测试零失败。

## 边界与遗留

- 2026-07-30 首轮没有做真实 MIS 登录；2026-08-01 已由后续有效 iOS 会话补齐成绩单下载证据。
- 文件名与 URL 查询参数不进入日志与 `toString`；成绩单文件名固定为「中文成绩单.pdf」「英文成绩单.pdf」，
  校历文件名取 URL 末段。
- 原 App 的邮件订阅卡片在 v1.7.0 源码中已被注释掉，本切片不迁移该功能。
- iOS 系统文件面板与真实登录后的中英文成绩单端到端验收已通过；两次均取消最终保存，未留下个人文件副本。macOS 成绩单保存面板仍未单独执行。
