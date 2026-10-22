# BJTUselfService 实时工作记忆

> 最后更新：2026-10-22。
> 本轮云端在 `feat/chatgpt-agent-remote@132b0d5` 完成一次交付 setup 修改及全新私人 Site/插件发布；实现已提交并推送为 `b70cec6`，供新对话从当前开发分支读取；旧 Site/插件已由用户删除。新站点和验证边界见 `docs/agent/setup-audit-2026-10-22.md`，本节最新记录覆盖下方旧实例状态。
> 当前分支：`feat/chatgpt-agent-remote`，2026-10-22 已合入 main `ad2a082`（含下述 main 状态）。main 上 PR #40 图书馆、#41 灵动岛/实时活动/手表、#42 作业搜索与教室当前节次、#46 课程实时动态、#45 验证码模型移除与安装包瘦身、#48 iOS 作业上传修复均已合入（合并提交 `64c24ab`、`6f5c36f`、`8189baf`、`aac1816`、`a58e7c6`、`ad2a082`）；同步时 `AgentCacheUpdate` 的登录失败提示改用 #45 的措辞，桌面 `Main.kt` 去掉已删除的 Core ML 验证码识别。细节见 `history_full.md`。远端另保留 `claude/宣传图制作`、`feat/chatgpt-agent-remote`（AI Agent，开发中）。
> KMP 源码版本：`1.8.6-Alpha` / Build 41（尚未发版）；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。iOS 最低系统 18.0。
> 2026-10-22 仓库清理：冻结原 Android 工程（`app/` 与根 Gradle 工程、`release.yml`/`debug.yml`）与验证码 torch 模型（含 Windows `BJTUCaptcha.pt`、DJL）已移除，存档于标签 `archive/legacy-android-and-captcha-model`（`8189baf`）；Windows 验证码改为手动输入。只保留 `origin` 远端；GitHub 上仍是 `HFDLYS/BJTUselfService` 的 fork，由所有者在 Settings 解除。
> 认证现状（2026-10-20）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码；智慧教学仍卡在学校的旧 CAS 跳转。
> 已完成工作的细节见 `history_full.md` 末尾日志。
> [Agent 分支笔记] 最新同步：本地 `feat/chatgpt-agent-remote`（原名 `agent-remote`）跟踪 `origin/feat/chatgpt-agent-remote`，已快进至 `235ad81`。用户现已授权按 memory 升级私人 Site/插件、实际配对并新建 Chat 验收；本轮按用户要求将 Windows 托盘中文与定位修复、位置测试及工作记忆一并提交并推送至该分支；未合入 main。Site 与插件最新状态见下方首条。
> [Agent 分支笔记] 开发位置：主目录 `C:/Users/zjg/BJTUselfService-KMP-Refreshed` 的本地 `feat/chatgpt-agent-remote`，不再使用开发工作树。原 `origin/agent-remote` 压缩历史已在此前推送，现已被远端新分支取代。快进前本地 memory 完整副本与补丁保存在 `.artifacts/agent-remote-fast-forward-20261022-074553/`，并保留 stash `830fc559561216b6b839a824be624f4e969c0c4f`；本地交接核对笔记已恢复到下方。
> [Agent 分支笔记] 压缩前源码、补丁、原提交 bundle 和旧工作树残留均备份于 `.artifacts/agent-remote-consolidation-20261021/`；原迁移备份和 Git stash 保留。宣传图分支 `claude/project-thread-a8br6t@5b165e4` 本地/远端保留。
> [Agent 分支笔记] 工作树清理：两个旧版 Temp 工作树已由 Git 自带命令删除，Git 仅登记主目录。`.artifacts/agent-windows` 已不是 Git 工作树；其递归残留清理被自动策略拒绝（blocked by policy），未绕过，完整残留已另存 669036652 字节归档。
> [Agent 分支笔记] 实际 `origin` 指向用户仓库 `JasonZhang1225/BJTUselfService-KMP-Refreshed`，无 `mine`，与 AGENTS.md 的旧远端命名不同。压缩基线为已知 `origin/main@f454d3a`；不改写 main 和远端。
> [Agent 分支笔记] Computer Use 已恢复：旧 CODEX_CLI_PATH 指向已不存在的 bin/d0097be4feba73d0/codex.exe，当前程序在 bin/6257d9e8f63c0f2b/codex.exe。已为旧目录建立 Junction，重置后 @oai/sky 可列出并读取安装版窗口；未改写程序。以后移除只删 Junction 本身，不递归删除目标。

## 进行中

- **新用户 setup 一次交付（2026-10-22，最新状态）。** 用户要求不调用界面自动化，搭建中不反复请求用户登录、不由助手取配对码；最终只交付 Site/插件入口，由用户登录 Site 自行生成配对信息。模板 0.5.0 新增 SETUP 与自包含契约、拥有者首次可信身份固定、受保护配对信息 API、App 配对信息导入与安全存储、确定错误停止重试、手机 health 错误分类、版本统一及已验证的部署归档脚本。Site `appgprj_6ad9ad4c356081919b7a7c74e6597689` 已私人发布，canonical 插件 `plugin_asdk_app_sites_bd52708922cc819185277e2eecb62912` 1.0.1 已附 Skill 并读回保留 App/界面；真实配对码与设备均为 0。模板 64 测试、共享端测试、桌面/Windows 编译和 Android compile-only debug 构建通过；Linux 的 Windows DPAPI 原生测试不可用，未生成 Windows MSI、未执行用户登录配对或宿主实际查询。实现已提交并推送 `b70cec6`。从 GitHub 重新稀疏检出到独立目录，匿名读取的 SETUP 与仓库一致；独立副本 64/64 测试、构建、部署归档和清洁模板打包均通过。新增 sync-site.mjs 已用本站真实源码凭据验证，Site 源码 `4855d66` 已成功私人发布（版本 3），同一插件 1.0.1 的 Skill/App 绑定读回仍在。新对话直接读取分支 SETUP，不使用旧安装版复制的提示词。Windows MSI 和本人实际登录/配对仍未验证，不能把云端搭建已就绪表述为完整配对零错误。详见审查报告。

- AI Agent（本分支）：开发中，约七八成；合入 main 后再清理 main 里经紧急修复带进来的半成品 AI 代码。下面先列 main 已合入功能的待验证项，再列 Agent 分支进行中事项。
- 已合入功能的待真机验证（不阻塞）：
  - 灵动岛/实时活动：收起态最终版截图（等实际上课时）；开课时“即将上课”切“正在上课”；两个实时活动并存时的最小态小圆环；Live 菜单读屏自定义操作。
  - iOS 26 预约启动（PR #46）：真机上的启动提醒声音与灵动岛展开效果；重叠/紧挨日程的实际表现；划掉系统启动的那一项后不再出现。
  - Android 实时动态（PR #46）：非 HyperOS 的原生 Android 16 上的样式；长时间不打开 App、系统降低待机分组后闹钟是否仍准点；最近任务未加锁时划掉 App（强停）会清掉闹钟和通知（按规则推断，未实测）。
  - 图书馆：登录流程在 Copilot 修复后改过，需真机重新登录一次；iOS 横屏布局（浮动续借胶囊、顶栏高度）；Android 真机；续借只在早期版本实测过。
  - 作业搜索：iOS 系统搜索按钮展开/取消动画、搜索中进详情再返回；Android 返回键收起搜索；上课时段教室当前节次光晕与空闲/占用标签（浅/深色）。
  - Windows：移除 torch 模型后，遇到验证码时手动输入路径未在 Windows 真机走过；PR #45 原生库裁剪后的 MSI 启动与本地缓存（SQLite）读写、DPAPI 凭据读取未实机验证（Windows 冒烟未在最终提交上运行）。
  - PR #45 登录收紧：需要验证码时的手动输入弹窗、静默自动登录失败后的引导弹窗、Android release（R8）包的签名覆盖安装，均未真机走过。

- **手机 Chat 旧工具/补授权故障（2026-10-22，最新状态）。** 用户已安装最终 MSI，安装 cfg 时间 08:54:28；只读进程检查未发现遗留源码实例。真实 `get_status` 成功，原电脑设备未撤销、09:07 收到新快照 `snap_lTh9cz3pdwJn`，schema 2、三个作业来源 15/18/56 均可用。用户 HEIC 截图显示旧快照调用 `sources: [homework, exams, citel]`，随后“当前的连接不包含所需权限”；用户反馈登录后称已认证仍不能继续。新 Chat 已存在：`本周DDL`（6ad96254-193c-83ee-80ac-bbb70c3b7b73），read_thread 仅读到用户提问，不能再称用户未建 Chat。本任务通过同一有效 OAuth link 复测旧 `query_snapshot`，实际返回 unknown_tool；get_status 成功，说明配对/上传正常且该 link 可用，但不能证明手机补授权回调正常。canonical 插件仍 1.0.3，Skill/App 绑定已更新而平台工具目录仍旧。Sites 文档说明重发布会重新抓取 tools/list，故只重发相同保存版本 6（源码未改、无 MSI 重编译），部署 appgdep_6ad9647be294819189cae740b4ec67f9 已 succeeded/has_mcp=true。当前 Codex 会话仍仅暴露旧 6 工具，不能据部署成功宣称手机已修复；需手机重新进入插件连接后验证 get_homework。生产 Worker 日志中本轮 get_status 的 /mcp 为 200；未取得手机授权服务回调日志，不断言具体 OAuth 失败原因。下一步应验证新工具目录及手机同一插件连接；无需重新配对电脑，也无需为在手机 GPT 查询而配对手机 App。旧段落“等待新 Chat/最终 MSI 未安装/临时源码仍运行”已被本条覆盖，尚未完成手机 Chat 端到端查询。

- **新 Chat 验收基准（2026-10-22）。** 通过私人 Site 已授权的 owner/snapshot 浏览器页面，只提取数量与处理后的截止日，未输出成绩、学号或凭据：本周 2026-10-19～25 默认全部状态且排除未约实验，应有 9 项（课程平台 7、物理在线 1、CITEL 1）；24:00 的归属日包含 10 月 19、23、25 日。新 Chat 回答需与此基准及实际 MCP 原始返回核对，不能以该只读统计代替 Chat 验收。用户反馈内嵌登录页卡顿；Chrome 专用浏览器通道不存在，Windows Chrome 自动化曾因网址无法可靠识别而停止。插件目录搜索未找到能复用本机 Chrome 登录的合适连接，未安装第三方云浏览器。最新手机 Chat 已出现但卡在补授权，见首条；此前“没有新 Chat”的阻塞条件已变化。未向旧聊天发消息。


- **Windows 托盘中文与定位修复（2026-10-22，本轮提交至 feat/chatgpt-agent-remote）。** 安装版同款精简运行时的字体/GBK 均可用，但独立 AWT 菜单仍复现方框；`WindowsTray.kt` 保留 AWT 图标，右键改用 Swing JPopupMenu/JMenuItem，关闭菜单/退出时清理 owner。用户已实机确认中文正常。随后用户反馈位置不对，核对 OpenJDK Windows TrayIcon 实现发现事件直接传物理像素；本机 Java2D 工作区为 1680×1055、缩放 175%、底部任务栏 48。现改用 MouseInfo 的屏幕坐标，并按当前屏幕工作区夹紧菜单位置；新增 WindowsTrayPositionTest 覆盖右下角、顶部任务栏、负坐标屏幕、缩放后的逻辑工作区。用户在修正版本上明确反馈“现在位置对了”。验证：首次 Unicode 版 `:windowsApp:windowsTest :windowsApp:createDistributable :windowsApp:packageMsi` 1m04s；位置改动后 `:windowsApp:windowsTest :windowsApp:createDistributable --no-configuration-cache --console=plain` 13s 成功，16 项中 15 通过/1 在线项跳过（日志 `.artifacts/windows-tray-position-validation.log`）；最后仅 `:windowsApp:packageMsi` 45s 成功，Kotlin 编译全为 UP-TO-DATE（`.artifacts/windows-tray-final-package.log`）。最终 MSI 位于 `multiplatform/windowsApp/build/compose/binaries/main/msi/交大自由行 KMP-1.8.5.msi`，SHA256 `BF4BC5B76740896FA464FD61CAA76ECFD837D7547D49E64D28FBDE2579452112`，替代之前的 9185…临时包，用户现已安装最终包，cfg 时间 08:54:28；最新只读检查无遗留源码进程，先前临时运行 PID 48152 已非当前状态。代理未取得右下角菜单截图；中文和位置验收来自用户直接实机反馈，尚未验证退出回调及混合 DPI 多屏真机。


- **本轮安装后状态复核（2026-10-22）。** 用户已安装首个 0.4.0 配套客户端，安装 cfg 为 2026-10-22 07:55:08，原配对保留。OAuth `get_status` 返回 schemaVersion=2，课表 131 节，课程平台/物理在线/CITEL 均启用、分别 15/18/56 项；推翻此前“尚未安装、旧快照为空”的结论。MCP serverInfo/package.json 仍显示 0.3.0，实际 health 模板为 0.4.0；旧导出目录仍为历史材料。新 Chat 与手机/多设备端到端验收尚未完成。

- **私人 Site/插件 0.4.0 实际升级与验收（2026-10-22，进行中）。** 原项目 `appgprj_6ad87d5c007c8191bd9424d88073aba7` 保留 owner-private 受众与 0001 迁移，追加 0002；独立源 `4bcc65e90767b6d354dbc810ce8fab5ad5fd0109`、部署 `appgdep_6ad94fed45b88191b19b2aef02fc9941` succeeded/has_mcp=true，原 URL 不变。health 模板 0.4.0/协议 1；匿名 health=401、本站服务凭据 health=200，服务凭据不能代替 MCP OAuth（401），没有降低权限。Site 定向检查 28/28，构建一次通过；同一 canonical 插件为 1.0.3 / `pluginrel_6ad9503705308191843b1b3e030c2930`，Skill 原样回读一致，App 绑定/展示/受众保留。用户安装后真实 schema 2 快照 `snap_nVci8A31b8fY` 已收到，原设备 `dev_z5Uu6tUoWFaW3cxe` 保留，三来源真实条目见上一条。原 UAC 取消不再是当前阻塞。内嵌浏览器 tab 2 仍未登录，用户反馈卡顿；尝试系统 Chrome 时 Computer Use 因无法可靠识别网址而终止上一轮，未绕过限制。本聊天工具仍缓存旧 6 工具；尚未用新 Chat 确认 11 工具、本周查询完整性和 24:00 日期，也未完成手机配对与来源切换。

- **升级前 ChatGPT 侧交接核对（2026-10-22，历史核对，最新部署见上一条）。** 已读取 `origin/feat/chatgpt-agent-remote` 的 memory、查询契约、模板 README、MCP/Skill 和版本常量。远端代码已统一 CLI/MCP 查询，模板实际 `TEMPLATE_VERSION=0.4.0`、快照 schema 2、11 个工具，并支持电脑/手机同时配对；作业默认 `status: all`，按 `summary.bySource` 核对三个来源，日期照抄已处理的 `due`/`dueDate`。ChatGPT 侧应依次升级原私人 Site（保留私人可见与既有数据，新增迁移 0002 仅执行一次），重新发布同一个 canonical 插件与模板 Skill，再逐台配对并验收多设备、本周全部 DDL 与 24:00 日期。模板必须取 `feat/chatgpt-agent-remote`，当前 `origin/main` 仍为 `f454d3a`，并无该模板；远端 memory“main 已到 1.8.6”及尾部“升级 0.3.0”与实际分支/版本不符。远端 memory 记载现有 Site 仍为 0.2.0，本次未在线确认部署版本；不能把源码更新视为 Site 已升级。`package.json`/MCP serverInfo 仍写 0.3.0，部署验收以 `/api/v1/health` 的模板版本 0.4.0、协议 1 及实际工具/查询结果为准。


- **Agent 查询统一与插件页整改（分支 `claude/chatgpt-plugin-mcp-refactor-6cp10x`，基于 `agent-remote@5bbba0c`，尚未合入 main）。**
  - 统一查询层：本机 CLI、电脑即时查询、Site MCP 工具用同一套参数与返回结构（契约 `docs/agent/agent-query-contract.md`）。电脑端生成“Agent 数据集 schema 2”（`AgentDataset.kt`，日期只在这里换算一次：00:00 截止写作前一天 `24:00` 的现成文字，不再输出次日 ISO 时刻）；查询引擎 Kotlin `AgentQuery.kt` 与 JS `remote-site-template/src/agent-query.js` 同构，用 `fixtures/agent-query/` 同一份样例双端测试（17 例）。
  - 作业一次返回课程平台/物理在线/CITEL 全部来源，`summary.bySource` 给每个来源的数量与启用状态，默认只列未交未截止（旧版整学期全量含说明，推测是 ChatGPT 截断导致物理在线、CITEL 漏项，未实机复现）。日历按天输出 `days`。
  - 远程快照 schema 1→2（`data` 即数据集），模板 0.3.0，MCP 工具 6→11（`get_homework/get_calendar/get_exams/get_grades/get_mail/get_status` + `request_live_query/refresh_all/citel_problem/mail_message` + `get_request_result`）。旧 Site 会以 `schema_mismatch` 拒收新电脑端快照，需按新提示词在原 Site 升级。
  - 邮箱范围从“未读数”改为收件箱摘要（最近 ≤99 封，刷新邮箱时写入加密缓存 `agent.mail.inbox`）+ 即时读取正文（`mail_message`，学校会标记已读）。
  - 插件页重排：远程与本机卡片统一标题/说明/分组字号；“设置方法”框起来，提示词不展开只留“复制提示词”，去掉“查看文档”；提示词改指向 main 上的模板（`REMOTE_TEMPLATE_URL`，合入前联调需临时改分支）。
  - Windows：开机启动改用 JNA 注册表 API（原 reg.exe 读回 GBK 输出、中文安装路径校验必失败）；托盘改为 AWT 托盘并指定中文字体，jlink 加 `jdk.charsets`、`jdk.localedata`（推测方框乱码来自缺 GBK 编码器，未实机验证）。
  - CI 修复：`RemoteAgentCard` 的 `Dispatchers.IO`（commonMain 在 iOS 不可用）；`packageMsi` 的 doLast 引用脚本对象破坏配置缓存。
  - 第二轮（用户反馈）：`homework` 默认改为 `status: all`（问“这周 DDL”带范围全部列出并标状态，问“没交的”才筛）。开机启动改为可被系统管理：Windows 读取 `StartupApproved\Run`（任务管理器禁用后如实显示关闭），按钮可打开“设置 → 应用 → 启动”；macOS 新增 LaunchAgent 登录项（`MacLaunchAgentStartupHost`，卸载清理一并删除）。新增“静默启动”（默认开，Java Preferences），只对带 `--background` 的开机启动生效。开机启动/静默/托盘移到独立卡片 `StartupSection`（Windows 与 macOS 都显示）。macOS 登录项与静默启动、Windows 任务管理器交互均未实机验证。
  - 第三轮：Android/iOS（手机与平板）启用“ChatGPT 插件”。设置 → 功能开关首行新增开关（电脑端名“Agent 和 ChatGPT 插件”，手机端“ChatGPT 插件”；两端都默认开，便于发现，不配对不上传），关闭时隐藏应用页入口并暂停上云同步。手机端控制器挂在 `AuthenticatedSession.remoteAgent`（原生二级页共享），不定时、不轮询、无 CLI，只在来源为 APP 的刷新完成后上传快照（`AppRefreshCoordinator.completed`）；密钥用 Android Keystore / Apple Keychain（`AccountSecurityStore.remoteVault`）。配对前读 `/api/v1/health`，模板 <0.4.0 拒绝（旧模板会撤销电脑）；手机需手填“Site 访问凭据”。Site 模板 0.4.0：多设备（迁移 0002 加 `device_kind`，上限 5 台，同名同类型重配只替换自己），查询每个范围取同步最新的设备快照，任务只派给最近在线的电脑。Android/iOS 只经 CI 编译，未在真机/模拟器打开页面；手机端真实配对与上传未验证。
  - 删除 `docs/agent/remote-skill-mcp-export-20261021/` 副本；Skill/MCP 唯一维护源是模板 `src/mcp.js` 与 `plugin/skills/bjtuss-remote/SKILL.md`，本机 Skill 在 `AgentSkill.kt`，两者规则同文。
  - 验证与未验证边界见本次交付说明；Windows 托盘/开机启动、iOS 编译、真实 ChatGPT 问答需实机或 CI 验证。

- **Agent Remote 基本实现（`agent-remote`）。** 上一轮实现与证据见 `docs/agent/windows-local-verification-2026-10-21.md`；Site https://bjtuss-private-remote.ardent-rice-3928.chatgpt.site 仍是旧模板 0.2.0，需要按新提示词升级后才能与新电脑端配合。未验证：新 MSI 升级安装、连续半小时/睡眠恢复、第二真实用户部署、Apple 原生构建；远程常驻仅 Windows。

## 要让 ChatGPT 做的事（它不太聪明：一次只发一条，照抄，做完逐项核对）

模板维护源是 `multiplatform/remote-site-template/`（当前 0.4.0：快照 schema 2、11 个工具、多设备）。合入 main 前，链接里的 `tree/main` 要换成实际分支名。
私人 Site 现状：https://bjtuss-private-remote.ardent-rice-3928.chatgpt.site 已部署模板 0.4.0，canonical 插件已更新到 1.0.3；第 1、2 步部署与 Skill 回读完成。用户已安装新版，原配对与真实 schema 2 上传已验证；托盘中文及位置已由用户实机确认，最终 MSI 已安装；手机 Chat 查询仍待验收。

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

## 接下来

- Agent：Windows 开机启动仍待实机验证；私人 Site 已升级到模板 0.4.0，继续处理 ChatGPT 旧工具目录与补授权故障，再用手机 ChatGPT 复测“本周 DDL”（核对物理在线、CITEL 与 24:00 截止）；合入前合并 main。

1. 在真机已有版本（SideStore 保留扩展）上验证小组件共享容器与各尺寸显示。
2. 华为 HBN-AL10（HarmonyOS 4.2）装含 PR #32/#33 的新包：确认课表小组件尺寸（logcat `CourseWidget` 标签）与教务不可达时不再闪退。
3. 真机过一遍“进行中”列出的待验证项。
4. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。

## 更新前保留的本地笔记（历史快照）
1. 等 CI 四端产物和 GitHub Release。
2. 需要热修时再开 `1.8.3-KMP-A` 或下一版本。

## 2026-10-11 工作区：演示实验只读接入

- 已加入独立账号配置、已预约查询、普通缓存清理保留配置、完整清理安全存储、首页/课表同步与按实际日期显示，以及“包含演示实验”的日历导出开关。课表配色沿用物理实验，演示实验详情保持独立类型。
- 用户明确SelfService只需查询/课表/导出；持续监控、Qwen OCR、自动预约均留在 `C:\Users\zjg\bjtu-phy-lab-ele` 的独立TUI，后台继续每轮后等30秒。
- 最终793项共享/JVM回归通过，Android共享库及应用Kotlin编译、Windows编译通过；实际Kotlin登录并重复查询校园网预约结果成功（0条）。17张Compose合成图复核浅深色及放大字体。
- iOS/macOS原生编译和系统日历实机写入待Mac验证。未提交、未推送、未打包发布，版本保持1.8.3-KMP / 29。记录见 `docs/migration/physics-demo-result-2026-10-11.md`。
