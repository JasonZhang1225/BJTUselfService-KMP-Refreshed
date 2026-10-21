# BJTUselfService 实时工作记忆

> 最后更新：2026-10-21。
> 开发位置：主目录 `C:/Users/zjg/BJTUselfService-KMP-Refreshed` 的本地 `agent-remote`，按用户要求将原 11 个功能提交与后续实现整理为一个基本实现提交；不再使用开发工作树。用户已要求提交并推送 `agent-remote`；不建 PR、不合并 main。本地相对 `origin/agent-remote` 仍分叉（整理提交 vs 远端 11 提交）。
> 压缩前源码、补丁、原提交 bundle 和旧工作树残留均备份于 `.artifacts/agent-remote-consolidation-20261021/`；原迁移备份和 Git stash 保留。宣传图分支 `claude/project-thread-a8br6t@5b165e4` 本地/远端保留。
> 工作树清理：两个旧版 Temp 工作树已由 Git 自带命令删除，Git 仅登记主目录。`.artifacts/agent-windows` 已不是 Git 工作树；其递归残留清理被自动策略拒绝（blocked by policy），未绕过，完整残留已另存 669036652 字节归档。
> 实际 `origin` 指向用户仓库 `JasonZhang1225/BJTUselfService-KMP-Refreshed`，无 `mine`，与 AGENTS.md 的旧远端命名不同。压缩基线为已知 `origin/main@f454d3a`；不改写 main 和远端。
> 版本仍为 `1.8.5-EmergencyFix2` / Build 40；最新已核验 Release 为 `v1.8.5-EmergencyFix2`。本地 MSI 为 `.artifacts/交大自由行 KMP-1.8.5-agent-plugins.msi`（116428800 字节，SHA256 D5D0E73E3E004448414EF1DDA4701E9FF10FDFA34AFC0837DEA2463178965C36），未安装。只读 ProductCode={8E7A3023-9F2E-43E2-BBE0-3B22932EEF8A}，UpgradeCode 保留；行政提取 /a 返回 1619，未验证包内提取和实际升级。
> Computer Use 已恢复：旧 CODEX_CLI_PATH 指向已不存在的 bin/d0097be4feba73d0/codex.exe，当前程序在 bin/6257d9e8f63c0f2b/codex.exe。已为旧目录建立 Junction，重置后 @oai/sky 可列出并读取安装版窗口；未改写程序。以后移除只删 Junction 本身，不递归删除目标。

## 进行中

- **远程 Skill/MCP 导出。** 当前写法与用法快照已移入并跟踪 `docs/agent/remote-skill-mcp-export-20261021/`（52 文件）：远程插件 1.0.2 四个原文件、六工具完整 Schema、初始化 instructions、最近发布 Site 源、Windows 查询/CLI 参考源码、用法和虚构 JSONL 示例。`SHA256SUMS.json` 和 provenance 记录来源；MCP src 与最近发布 checkout f02ebf0 逐文件哈希一致，JSON/清单检查通过，无真实凭据缓存。当前插件服务 transport 失败，使用本会话更新后已回读验证的 1.0.2 原文，导出明确注明未再次确认远端变化；没有改逻辑或重新发布。

- **CLI/MCP 一致性只读核对。** 两端复用 agentHomework/agentCalendar 及 homeworkJson/calendarJson 等业务输出，但查询接口、默认数据位置、外层结构和能力没有完全统一：CLI 支持日期范围/周次/未交/多课程/预约与 App 筛选，MCP 仅 sources/course/date/taskId，云端日程窗口为昨天起 21 天；CLI data.items 与 MCP snapshot.data.assignments.homework.items 层级不同，即时结果另套任务回执。远程 Skill 1.0.2 已要求不重复移日，本地 AgentSkill 和 MCP initialize.instructions 仍有旧“午夜归前一天”文案，存在解释歧义，本轮未改。虚构 Node 样例确认 dueAgendaDate=2026-10-25 与 midnightDeadline=true 在 25 日查询保留、26 日为 0 项；只证明该过滤例，不证明真实物理在线漏项原因。

- **Agent Remote 基本实现已收口，等待合入与剩余验收。** 包含公共刷新服务、App/CLI 同一执行者、Windows DPAPI 配对/常驻/托盘/开机启动、私人 Site 持久快照与任务、6 个 MCP 工具、本地开发插件与查询 Skill、应用页“Agent 和 ChatGPT 插件”入口及搭建向导。最小化到托盘独立保存，默认开启，系统无托盘时不隐藏。现有 App/Site 日期处理逻辑没有因 DDL 纠错改动。
- **验证证据。** JDK 21，`-I .artifacts/emergencyfix2-checks.gradle :shared:desktopTest :shared:compileAndroidMain :desktopApp:compileKotlin :windowsApp:compileKotlinWindows` 成功，996 项共享测试通过（沿用 Windows 既有 5 项排除）；Windows 既往 9 项通过/1 在线项跳过。Node `npm test` 36/36、`node scripts/build.mjs` 通过。浅深色和 1.5 倍文字合成 UI 已检查；`:windowsApp:packageMsi` 成功（45 秒）。本轮整理 Git 不重复构建。更多证据见 `docs/agent/windows-local-verification-2026-10-21.md` 与 `.artifacts/`。
- **真实链路。** Site https://bjtuss-private-remote.ardent-rice-3928.chatgpt.site 仍 owner-private；当前网站源 f02ebf048cd4774836a5f4e1af3182bac38a3ef4、部署 appgdep_6ad8bec4564c819181112b3ff776d5e9 succeeded。真实安装版配对、心跳、快照同步通过，浏览器一次刷新 21:20:44→21:21:01，17 秒自动显示完成并更新来源时间。网页已去掉周期说明并同步新入口。
- **插件日期规则。** canonical 插件 ID `plugin_asdk_app_sites_9c8cc6038e1c8191885c65089da2a0ef`，1.0.2 / pluginrel_6ad8c1212c848191baf033854407cbb5。只改技能日期解释及版本，回读验证 App 绑定和受众保持：以拉取结果的已处理日期时间为准，归属日 25 日 + 24:00 就按 25 日显示，不能再前移/后移。原始次日 00:00 不覆盖已处理日期。用户“本周DDL整理”测试反馈的物理在线漏项尚未定位，不擅自改过滤逻辑。引用对话正文无法加载，只读到用户纠错与思考概要；未另发聊天验收消息。
- **安装版存储隔离。** Codex 启动的诊断进程读到隔离 AppData/Preferences，不能代表开始菜单安装版。此前独立诊断的“凭据存在/HTTP 200”不构成真实表单成功证据。已通过本机命名管道把精确 Site 访问材料交给目标安装版，由系统安全存储保存，真实配对同步成功；不复制数据库、不记录明文凭据。
- **未验证边界。** 新 MSI 升级安装、真实 Windows 托盘关闭/最小化/恢复与开机重启、连续半小时/睡眠恢复、iPhone ChatGPT 完整查询正确性、第二真实用户部署、Apple 原生构建；当前功能仅 Windows 提供远程常驻，macOS 保留本机 Agent。既有聊天工具缺失记录不再代表所有新聊天；用户已能在 ChatGPT 测试插件，但不能据此宣称无漏项。

## 当前痛点

- 智慧教学旧直连入口（首页 29 / module 28）仍落到旧 CAS 空白页面；已发布的 Fix 2 加入首页 11 的本科平台回退。该链仍依赖学校本科平台可用性，上传/提交与真实附件下载本轮未执行。
- 同步卡顿：Beta3 已到可接受范围。剩余长帧为同步详情 sheet 弹出（约 50ms）、进入作业页（约 49ms）和原生滚动带来的导航栏布局；`checkSession` 改后台后未复采；壳层随任一状态流整体重组（约 0.4 秒/次同步），彻底解决需拆分壳层。
- 小组件：PR #8 的 ad-hoc 签名后，侧载包的小组件共享容器是否可读尚未在真机确认；周视图、中号两栏、脚注只经 CI 编译，未在模拟器浅/深色下查看，小屏 iPhone 周视图可能偏挤；“正在上/还剩 N 分钟”未在上课时段实机观察。
- 首页物理在线/CITEL 日程项进入二级详情只在代码层实现，模拟器只验证了演示实验。
- “保存二维码并扫一扫”未真机验证（Android MediaStore 保存/去重、扫码后进入小程序/缴费页）；iOS 每次保存会多一张；Android 二维码弹窗未在平板上看。
- HyperOS 小窗↔全屏来回切换未实测（adb 无法切换窗口模式）。
- 课件 15 分钟大文件超时未用 >30 秒真实下载验证；iOS/Android“未开放下载”标记只验证编译。
- 在 Chrome 登录智慧教学会顶掉 App 会话，App 自动恢复登录失败一次（需手动刷新），未排查。
- macOS 附件默认应用打开、iOS 保存面板未点验；`HomeworkFileGateway` 已被邮箱复用，可改名为通用文件网关。

## 已知限制（非缺陷，排查时先看这里）

- 教务系统 `aa.bjtu.edu.cn` 会临时拒绝 443 连接（2026-10-20 晚间约 1.5 小时，MIS 同时可达）；PR #33 起 App 照常进入主界面，仅成绩/课表/考试同步失败并保留缓存。复现可在 `KtorSchoolHttpTransport.executeOn` 临时对 aa 抛异常，测完撤销。
- 校历公众号文章不能由第三方 App 直接在微信内打开（invalid source），走系统浏览器。
- 小米“跟随应用内设置”覆盖安装清不掉：装新包后卸载重装，或在“使用高刷新率的应用”里打开交大自由行 KMP。
- 本地 Gradle 偶发被 Maven Central 429 限流，此时测试与 iOS 编译交给 PR CI。

## 迁移期遗留（长期，无排期）

- iOS Keychain 合法签名往返、正式签名发布（无 Apple Developer Team）。
- 物理在线真实上传、Windows 触摸屏；邮箱真实发送/删除/移动/附件上传；真实作业上传、退出账号往返（等学校侧条件）。
- 验证码发布级正确率需至少 300 张独立留出集。

## 接下来

- Agent Remote：先定位用户反馈的物理在线 DDL 漏项，严格沿用已处理的日期时间；再验收新 MSI 升级、托盘与开机启动、手机完整查询、连续周期与睡眠恢复、第二用户独立部署。继续在主目录 `agent-remote` 开发。

1. 在真机已有版本（SideStore 保留扩展）上验证小组件共享容器与各尺寸显示。
2. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。

## 更新前保留的本地笔记（历史快照）
1. 等 CI 四端产物和 GitHub Release。
2. 需要热修时再开 `1.8.3-KMP-A` 或下一版本。

## 2026-10-11 工作区：演示实验只读接入

- 已加入独立账号配置、已预约查询、普通缓存清理保留配置、完整清理安全存储、首页/课表同步与按实际日期显示，以及“包含演示实验”的日历导出开关。课表配色沿用物理实验，演示实验详情保持独立类型。
- 用户明确SelfService只需查询/课表/导出；持续监控、Qwen OCR、自动预约均留在 `C:\Users\zjg\bjtu-phy-lab-ele` 的独立TUI，后台继续每轮后等30秒。
- 最终793项共享/JVM回归通过，Android共享库及应用Kotlin编译、Windows编译通过；实际Kotlin登录并重复查询校园网预约结果成功（0条）。17张Compose合成图复核浅深色及放大字体。
- iOS/macOS原生编译和系统日历实机写入待Mac验证。未提交、未推送、未打包发布，版本保持1.8.3-KMP / 29。记录见 `docs/migration/physics-demo-result-2026-10-11.md`。
