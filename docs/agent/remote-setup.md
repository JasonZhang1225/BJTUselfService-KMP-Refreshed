# Windows 本地开发与私人 Site 搭建（模板 1.8.6-Beta2 / 协议 1 / 快照 schema 2）

维护源：[main 分支模板目录](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/tree/main/multiplatform/remote-site-template)，安装指导见其中的 [SETUP.md](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/blob/main/multiplatform/remote-site-template/SETUP.md)。该目录与 Gradle 工程独立，无第三方 Node 依赖，要求 Node ≥22.5。
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

## 一句话创建入口与私人部署

完整步骤的唯一维护源是模板内 `SETUP.md`，它与 `agent-query-contract.md` 都随干净模板包分发。

电脑 App → Agent 和 ChatGPT 插件 → 复制提示词，发给具备 Sites 与 Plugin Creator 的助手。助手完成私人 Site 创建、身份初始化配置、设备访问机密、发布，以及同一 canonical 插件的查询 Skill；最终统一交付 Site 和插件入口。搭建中不要求用户提前登录、取得身份 ID、提供系统存储权限或领取配对码。

用户打开 Site 并登录，点击“生成配对信息”，在支持新流程的 App 中只粘贴完整配对长码（已含地址，无需另填）、选择上云范围并确认。配对信息包含本站机器访问授权，只通过拥有者专属 API 返回，十分钟有效；一次性码只保存摘要，机器访问材料最终保存在设备系统安全存储，不发到聊天、日志或源码。过期在 Site 自行生成，无需回到对话。

## 手机与平板（Android / iOS）

仅在手机 ChatGPT 查询已配对电脑上传的数据时，无需配对手机 App。手机 App 自己需要上传缓存时，登录同一 Site 另生成一份配对信息；模板需为 1.8.6-Beta2 或更高，并使用支持完整配对信息的新客户端。

手机不定时上传、不心跳、不轮询任务，只在 App 刷新后上传；即时任务由电脑执行。health 认证/网络失败应报告实际错误，只有确实读到旧版才提示升级。

## 开发与回归

`node scripts/package-template.mjs <源码目录外的绝对输出路径.tar.gz>` 生成无个人部署身份的包。创建实例保留 DB、MCP、Drizzle 迁移；新实例确认 owner-private 后，用 Sites 工具设置 `OWNER_PRIVATE_BOOTSTRAP=true` 与 secret `SITE_DEVICE_ACCESS`，后者来自本站 get_site 的服务访问令牌。配置后部署，用户首次可信登录固定身份。升级保留已有身份与数据。

不要以公开 Site 绕过平台访问，不让搭建助手直接写用户安装版存储，不用诊断进程成功代替实际 App 配对证据。对权限和部署配置错误停止自动重试，给出可操作原因。

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

仓库 Agent 实现已通过 PR #50 合入 main；新 MSI 已本地生成，未替换安装版或正式发版。用户授权的维护者私人 Site 与同一插件已发布；
Windows Ktor 对该实例的机器访问和虚构数据协议往返通过，具体身份和证据见同目录验证报告。
真实安装版配对、学校账号刷新与网页自动完成状态已验证。canonical 插件 1.0.2 的 Skill 沿用工具返回的已处理日期时间，禁止重复移日；
用户 ChatGPT 测试仍反馈物理在线 DDL 漏项，未定位根因。iPhone 完整正确性、第二真实用户实例、连续半小时运行、
锁屏/重启、实际托盘与开机启动、新 MSI 升级和 Apple 原生编译仍需分别验证。
