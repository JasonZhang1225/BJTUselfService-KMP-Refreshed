# Windows 本地开发与私人 Site 搭建（模板 0.3.0 / 协议 1 / 快照 schema 2）

维护源：`multiplatform/remote-site-template/`。该目录与 Gradle 工程独立，无第三方 Node 依赖，要求 Node ≥22.5。
MCP 工具、Skill 与本机 CLI 共用的查询参数和返回结构见同目录 `agent-query-contract.md`；工具与 Skill 的唯一维护源是模板的 `src/mcp.js` 与 `plugin/skills/bjtuss-remote/SKILL.md`，不要另存副本。

## Windows 本地开发

在模板目录执行 `npm test`、`npm run build`、`npm run dev`。预览仅监听 `127.0.0.1:4173`，
所有示例均为虚构数据，数据库在内存中，重启重置。它不读取 App 的真实缓存、DPAPI 或学校账号。
预览拥有者鉴权由回环开发服务器处理；此服务器不能用作线上部署入口。

`plugin/` 为本地开发插件。使用目标宿主支持的本地插件导入流程选择该目录后，应能发现 11 个工具。
也可用 JSONL 向 `node plugin/server/stdio.mjs` 输入 `initialize`、`tools/list` 和 `tools/call` 验证。
默认连接本地虚构预览；仅在需要受控远程调试时，在进程环境配置 `BJTU_REMOTE_SITE_URL`、
`BJTU_REMOTE_OWNER_TOKEN`、`BJTU_REMOTE_PLATFORM_ACCESS`。凭据不能放入插件包或命令参数。
stdio 桥拒绝非 HTTPS 外部地址、URL 凭据、重定向和超过 16 KiB 的请求。

Windows 打包：在 `multiplatform/` 用 JDK 21 运行 `gradlew.bat :windowsApp:createDistributable`。
源码不直接替换已安装版。在仓库根目录以 PowerShell 7 执行：

```powershell
./scripts/windows-cli-smoke.ps1 -AppDirectory '<打包目录中的 App 文件夹>'
```

该脚本通过打包启动器的重定向句柄验证 stdout、JSON、退出码与无缓存刷新；用隔离的进程环境
`LOCALAPPDATA`，不安装 Skill、不修改真实账号缓存、不访问学校。
直接从 PowerShell 调 GUI exe 可能不等待完成，验证时应使用 ProcessStartInfo 和重定向句柄。
打包运行时不保证附带 `java.exe`，不要把系统 JDK 当作分发依赖。

## 一句话创建入口

电脑 App 的入口为 **应用 → Agent 和 ChatGPT 插件**，其中“远程查询”的“设置方法”框里只有一个“复制提示词”按钮。
把提示词发送到 ChatGPT Work，要求按模板（README 与查询契约）创建私人 Site、自动生成的插件与一次性配对码；已有旧版 Site 的在原 Site 上升级到 0.3.0。
搭建助手需要先验证私人 Site 的电脑访问并完成安全连接配置，不得只给出配对码就声称链路已通。

拿到地址和配对码后，在同一页填写，选择允许上云的数据、确认同意，再点击“配对并开启”。
配对码过期时在 Site 页面重新生成。普通查询读取 Site 快照；明确要求最新数据时电脑领取任务，网页自动展示执行状态。

同时提供干净模板包。`node scripts/package-template.mjs <源码目录外的绝对输出路径.tar.gz>`
按明确清单生成包，不携带现有 project_id、个人地址、令牌、快照或数据库。

## 正式私人 Site

1. 在独立空目录解包，确认 `.openai/hosting.json` 无 project_id。按 Sites 技能创建一次私人 Site，
   将返回的真实 ID 写入该实例 manifest，之后一直复用。模板维护源不得写入个人 Site 身份。
2. 保留 D1 绑定 `DB`、MCP capability 和 `drizzle/` 迁移；按 Sites 源码助手的流程构建、推送、
   打包和私人发布。新 Site 才建新数据库；升级追加迁移，不覆盖用户绑定。
3. 拥有者登录 Site 后访问 `/api/v1/identity` 取得 Site 内 userId，作为部署机密 `OWNER_USER_ID`。
   或在已核实只有拥有者可访问的 Site 中显式设置 `OWNER_PRIVATE_BOOTSTRAP=true`，由第一次可信
   用户身份请求固定拥有者；服务访问令牌本身不能引导身份。受控机器接口使用独立 `OWNER_TOKEN`。
   平台身份头只在可信 Sites 边界有效，不把自托管公网服务器上的可伪造请求头视为身份。
   拥有者未配置时 API/MCP 拒绝访问。网页只载入壳，不包含任何令牌。
4. 用当前 Sites 支持的 service access 材料验证 Windows 访问。`OAI-Sites-Authorization` 只交给
   该 Site，且平台服务访问不等于拥有者身份；设备 API 还要求独立设备令牌。
   平台门槛未实测前，只传虚构数据。不能用公开 Site 绕过此步骤。
5. 发布后复用 Sites 自动创建的 canonical 私人插件，确认安装/连接并调用工具。
   连接助手先把本站访问材料安全配置到目标安装版的系统存储；在 Site 页面生成一次性配对码，
   Windows App 里只填写站点地址和配对码，不提供手工填写平台访问材料的输入框。
   设备令牌与平台凭据留在 Windows DPAPI，学校密码仍在本机安全存储。
6. 真实数据上云前由用户在电脑端确认范围。课表、考试、作业为默认范围，成绩和邮箱另行选择。
   普通查询使用云端快照；用户明确要求最新时才创建最小来源任务，结果可能排队、部分失败或要求手动登录。

默认后台周期为 1800 秒刷新上传、5 秒轮询；只有开启远程同步才执行上传和轮询。开机启动默认关闭，需用户自己选择：
Windows 写当前用户 `Run` 启动项，出现在任务管理器“启动应用”与“设置 → 应用 → 启动”，在那里禁用后 App 内如实显示关闭；
macOS 写 `~/Library/LaunchAgents` 登录项，出现在“系统设置 → 通用 → 登录项与扩展”。“静默启动”（默认开）只影响开机自动启动：
Windows 只留托盘、macOS 不开窗口（点程序坞图标打开）；手动打开总是显示窗口。
本页下方的“最小化到托盘”与上云授权独立保存，默认开启：最小化或点关闭会隐藏窗口并保留托盘，
可从托盘重新打开；托盘菜单的“退出”结束 App 和远程服务。关闭该开关后点窗口关闭按钮会退出 App。
系统不支持托盘时不会隐藏成无法重新打开的后台进程。
一台活跃电脑绑定一个学校账号；切换学校账号需重新确认绑定。

### Codex 启动的调试实例与安装版存储隔离

2026-10-21 已在本机复现：从 Codex 启动的诊断/调试进程与从开始菜单启动的安装版，
即使显示相同 `%LOCALAPPDATA%` 和 Java Preferences 路径，也可能进入不同的文件、注册表视图。
本次 Codex 内的缓存最终解析到 `Packages/OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/BJTUselfServiceKMP`，
而安装版实际控制器里没有对应配对配置和平台材料。

不能把独立诊断程序的“已配对”或 HTTP 200 当作安装版的证据。连接材料必须写入目标应用
实际使用的安全存储，再用目标应用的真实配对、Site 心跳及快照确认。不要直接复制整份数据库、
修改用户账号、关闭加密，或把访问材料写进明文文件和命令参数来绕过隔离。

## 停用、回退与未验证边界

电脑端关闭远程同步可停调度，本机 App/CLI 保持可用。Site 可以暂停查询、撤销设备、清除云端数据；
三者是独立动作。清数据但不关电脑同步，会在下次上传重新出现。电脑离线时本地改范围不会瞬间通知云端。

仓库开发代码在主目录 `agent-remote` 整理为基本实现提交，尚未推送/合并；新 MSI 已本地生成，未替换安装版或正式发版。用户授权的维护者私人 Site 与同一插件已发布；
Windows Ktor 对该实例的机器访问和虚构数据协议往返通过，具体身份和证据见同目录验证报告。
真实安装版配对、学校账号刷新与网页自动完成状态已验证。canonical 插件 1.0.2 的 Skill 沿用工具返回的已处理日期时间，禁止重复移日；
用户 ChatGPT 测试仍反馈物理在线 DDL 漏项，未定位根因。iPhone 完整正确性、第二真实用户实例、连续半小时运行、
锁屏/重启、实际托盘与开机启动、新 MSI 升级和 Apple 原生编译仍需分别验证。
