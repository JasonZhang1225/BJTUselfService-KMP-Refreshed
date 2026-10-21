# BJTUselfService 实时工作记忆

> 最后更新：2026-10-21。
> 云端会话：Agent 查询统一与插件页整改在分支 `claude/chatgpt-plugin-mcp-refactor-6cp10x`（见“进行中”第一条）。
> 开发位置：主目录 `C:/Users/zjg/BJTUselfService-KMP-Refreshed` 的本地 `agent-remote`，按用户要求将原 11 个功能提交与后续实现整理为一个基本实现提交；不再使用开发工作树。用户已要求提交并推送 `agent-remote`；不建 PR、不合并 main。本地相对 `origin/agent-remote` 仍分叉（整理提交 vs 远端 11 提交）。
> 压缩前源码、补丁、原提交 bundle 和旧工作树残留均备份于 `.artifacts/agent-remote-consolidation-20261021/`；原迁移备份和 Git stash 保留。宣传图分支 `claude/project-thread-a8br6t@5b165e4` 本地/远端保留。
> 工作树清理：两个旧版 Temp 工作树已由 Git 自带命令删除，Git 仅登记主目录。`.artifacts/agent-windows` 已不是 Git 工作树；其递归残留清理被自动策略拒绝（blocked by policy），未绕过，完整残留已另存 669036652 字节归档。
> 实际 `origin` 指向用户仓库 `JasonZhang1225/BJTUselfService-KMP-Refreshed`，无 `mine`，与 AGENTS.md 的旧远端命名不同。压缩基线为已知 `origin/main@f454d3a`；不改写 main 和远端。
> 版本仍为 `1.8.5-EmergencyFix2` / Build 40；最新已核验 Release 为 `v1.8.5-EmergencyFix2`。本地 MSI 为 `.artifacts/交大自由行 KMP-1.8.5-agent-plugins.msi`（116428800 字节，SHA256 D5D0E73E3E004448414EF1DDA4701E9FF10FDFA34AFC0837DEA2463178965C36），未安装。只读 ProductCode={8E7A3023-9F2E-43E2-BBE0-3B22932EEF8A}，UpgradeCode 保留；行政提取 /a 返回 1619，未验证包内提取和实际升级。
> Computer Use 已恢复：旧 CODEX_CLI_PATH 指向已不存在的 bin/d0097be4feba73d0/codex.exe，当前程序在 bin/6257d9e8f63c0f2b/codex.exe。已为旧目录建立 Junction，重置后 @oai/sky 可列出并读取安装版窗口；未改写程序。以后移除只删 Junction 本身，不递归删除目标。

## 进行中

- **Agent 查询统一与插件页整改（分支 `claude/chatgpt-plugin-mcp-refactor-6cp10x`，基于 `agent-remote@5bbba0c`，尚未合入 main）。**
  - 统一查询层：本机 CLI、电脑即时查询、Site MCP 工具用同一套参数与返回结构（契约 `docs/agent/agent-query-contract.md`）。电脑端生成“Agent 数据集 schema 2”（`AgentDataset.kt`，日期只在这里换算一次：00:00 截止写作前一天 `24:00` 的现成文字，不再输出次日 ISO 时刻）；查询引擎 Kotlin `AgentQuery.kt` 与 JS `remote-site-template/src/agent-query.js` 同构，用 `fixtures/agent-query/` 同一份样例双端测试（17 例）。
  - 作业一次返回课程平台/物理在线/CITEL 全部来源，`summary.bySource` 给每个来源的数量与启用状态，默认只列未交未截止（旧版整学期全量含说明，推测是 ChatGPT 截断导致物理在线、CITEL 漏项，未实机复现）。日历按天输出 `days`。
  - 远程快照 schema 1→2（`data` 即数据集），模板 0.3.0，MCP 工具 6→11（`get_homework/get_calendar/get_exams/get_grades/get_mail/get_status` + `request_live_query/refresh_all/citel_problem/mail_message` + `get_request_result`）。旧 Site 会以 `schema_mismatch` 拒收新电脑端快照，需按新提示词在原 Site 升级。
  - 邮箱范围从“未读数”改为收件箱摘要（最近 ≤99 封，刷新邮箱时写入加密缓存 `agent.mail.inbox`）+ 即时读取正文（`mail_message`，学校会标记已读）。
  - 插件页重排：远程与本机卡片统一标题/说明/分组字号；“设置方法”框起来，提示词不展开只留“复制提示词”，去掉“查看文档”；提示词改指向 main 上的模板（`REMOTE_TEMPLATE_URL`，合入前联调需临时改分支）。
  - Windows：开机启动改用 JNA 注册表 API（原 reg.exe 读回 GBK 输出、中文安装路径校验必失败）；托盘改为 AWT 托盘并指定中文字体，jlink 加 `jdk.charsets`、`jdk.localedata`（推测方框乱码来自缺 GBK 编码器，未实机验证）。
  - CI 修复：`RemoteAgentCard` 的 `Dispatchers.IO`（commonMain 在 iOS 不可用）；`packageMsi` 的 doLast 引用脚本对象破坏配置缓存。
  - 第二轮（用户反馈）：`homework` 默认改为 `status: all`（问“这周 DDL”带范围全部列出并标状态，问“没交的”才筛）。开机启动改为可被系统管理：Windows 读取 `StartupApproved\Run`（任务管理器禁用后如实显示关闭），按钮可打开“设置 → 应用 → 启动”；macOS 新增 LaunchAgent 登录项（`MacLaunchAgentStartupHost`，卸载清理一并删除）。新增“静默启动”（默认开，Java Preferences），只对带 `--background` 的开机启动生效。开机启动/静默/托盘移到独立卡片 `StartupSection`（Windows 与 macOS 都显示）。macOS 登录项与静默启动、Windows 任务管理器交互均未实机验证。
  - 第三轮：Android/iOS（手机与平板）启用“ChatGPT 插件”。设置 → 功能开关首行新增开关（电脑端名“Agent 和 ChatGPT 插件”，电脑端默认开；手机端“ChatGPT 插件”，默认关），关闭时隐藏应用页入口并暂停上云同步。手机端控制器挂在 `AuthenticatedSession.remoteAgent`（原生二级页共享），不定时、不轮询、无 CLI，只在来源为 APP 的刷新完成后上传快照（`AppRefreshCoordinator.completed`）；密钥用 Android Keystore / Apple Keychain（`AccountSecurityStore.remoteVault`）。配对前读 `/api/v1/health`，模板 <0.4.0 拒绝（旧模板会撤销电脑）；手机需手填“Site 访问凭据”。Site 模板 0.4.0：多设备（迁移 0002 加 `device_kind`，上限 5 台，同名同类型重配只替换自己），查询每个范围取同步最新的设备快照，任务只派给最近在线的电脑。Android/iOS 只经 CI 编译，未在真机/模拟器打开页面；手机端真实配对与上传未验证。
  - 删除 `docs/agent/remote-skill-mcp-export-20261021/` 副本；Skill/MCP 唯一维护源是模板 `src/mcp.js` 与 `plugin/skills/bjtuss-remote/SKILL.md`，本机 Skill 在 `AgentSkill.kt`，两者规则同文。
  - 验证与未验证边界见本次交付说明；Windows 托盘/开机启动、iOS 编译、真实 ChatGPT 问答需实机或 CI 验证。

- **Agent Remote 基本实现（`agent-remote`）。** 上一轮实现与证据见 `docs/agent/windows-local-verification-2026-10-21.md`；Site https://bjtuss-private-remote.ardent-rice-3928.chatgpt.site 仍是旧模板 0.2.0，需要按新提示词升级后才能与新电脑端配合。未验证：新 MSI 升级安装、连续半小时/睡眠恢复、第二真实用户部署、Apple 原生构建；远程常驻仅 Windows。
- **main 已前进到 1.8.6-KMP（图书馆借阅等），本分支未合并 main。**

## 要让 ChatGPT 做的事（它不太聪明：一次只发一条，照抄，做完逐项核对）

模板维护源是 `multiplatform/remote-site-template/`（当前 0.4.0：快照 schema 2、11 个工具、多设备）。合入 main 前，链接里的 `tree/main` 要换成实际分支名。
私人 Site 现状：https://bjtuss-private-remote.ardent-rice-3928.chatgpt.site 仍是 0.2.0，新版电脑端/手机端都连不上，必须先升级。

1. **升级 Site（在原 Site 上，不要新建）**——发给 ChatGPT（Work 模式）：
   > 用 Sites 插件更新我已有的私人 Site（地址：<Site 地址>），不要新建第二个 Site。代码完全以这个目录为准：<仓库>/tree/<分支>/multiplatform/remote-site-template 。把 src/、migrations/、drizzle/、plugin/ 整个替换成模板里的版本，不要自己改写任何查询、日期或权限逻辑。数据库按顺序执行还没执行过的迁移（0002_multi_device.sql），不要删库重建。保持 Site 私人可见。
   - 核对：打开 `<Site>/api/v1/health`，`templateVersion` 必须是 `0.4.0`（或更高）、`protocolVersion` 是 `1`。不是就让它重做，不要接受“已更新”的口头说法。
2. **重新发布插件（MCP 与 Skill）**——发给 ChatGPT：
   > 用这个 Site 自动生成的插件重新发布，不要另建插件。工具列表必须正好是这 11 个：get_status、get_homework、get_calendar、get_exams、get_grades、get_mail、request_live_query、request_refresh_all、request_citel_problem、request_mail_message、get_request_result。Skill 原样使用模板 plugin/skills/bjtuss-remote/SKILL.md，一个字都不要改。发布后在新对话里调用一次 get_status 和 get_homework（range=this_week）给我看原始返回。
   - 核对：返回里有 `schemaVersion: 2`、`summary.bySource` 三个来源；截止时间形如 `2026-10-25（周日）24:00`。旧的 `query_snapshot` 工具不应再出现。
3. **给每台设备生成配对码**——发给 ChatGPT：
   > 在 Site 页面为“电脑”和“手机”各生成一个一次性配对码（10 分钟有效）。同一个 Site 要同时连接两台设备，不要撤销已有设备。
   - 电脑：Windows App → 应用 → Agent 和 ChatGPT 插件，填地址与配对码。电脑的平台访问凭据仍由连接助手写入系统安全存储（不手填）。
   - 手机：先在 设置 → 功能开关 打开“ChatGPT 插件”，再进 应用 → ChatGPT 插件 填写；还需要一份“Site 访问凭据”，发给 ChatGPT：
     > 为我的私人 Site 生成一个给手机 App 用的服务访问凭据（service access token），只给这一个 Site 用，告诉我完整的值，我会填进手机 App 的安全存储。
4. **验收多设备**——在 ChatGPT 里问“这周的 DDL”，核对：物理在线、CITEL 都列出；24:00 截止日期正确；`remote.dataFrom` 显示数据来自刚刷新过的那台设备。手机刚刷新过时数据应来自手机。
5. 以后模板再升级：重复第 1、2 步；电脑端/手机端若提示“模板版本过旧”或 `schema_mismatch`，就是 Site 没升级成功。

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

- Agent：Windows 实机验证托盘中文与开机启动；按新提示词把私人 Site 升级到模板 0.3.0 后，用手机 ChatGPT 复测“本周 DDL”（核对物理在线、CITEL 与 24:00 截止）；合入前合并 main。

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
