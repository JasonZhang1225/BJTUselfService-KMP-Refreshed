# Agent Remote Windows 本地开发验证（2026-10-21）

> 当前开发位置已变更：主目录本地 `agent-remote@2028776`。下文独立 worktree 指迁移前的历史状态；后续不在其中开发。当前诊断见文末。

## 分支与范围

- 用户仓库：JasonZhang1225/BJTUselfService-KMP-Refreshed。实际 origin 指向用户仓库，与 AGENTS.md 旧远端命名不同；未修改远端。
- 已抓取最新，main 为 `f454d3a`，agent-remote 为 `2028776`，后者包含 main 并多 11 个提交。计划 PR 1–4 已通过 PR #36/#37 集成。
- 工作分支 `feat/agent-windows-local`，位于主仓库 `.artifacts/agent-windows/` 的独立 Git worktree。主工作区已有三份未提交文件保留。
- 本仓库代码未提交、未推送、未建 PR、未合并、未发版；版本仍为 1.8.5-EmergencyFix2 / Build 40。独立 Site 源码仓库按用户发布授权另行提交并部署，见下文。

## 本地实现

- Site 模板 0.2.0：六个 MCP 工具、平台拥有者 ID 校验、普通快照筛选、最小来源即时任务、配对与设备管理页面。
- Windows：精确查询复用 App 当前会话与公共刷新协调器；local 不联网、latest 只请求选定来源。CITEL 获取全文使用独立互斥方法，不改变 UI 当前详情选择。
- 来源投影沿用 CLI 日期与预约规则；远程课表标注来源，物理实验与演示实验可独立筛选。午夜截止按前一天归日。
- 结果重传前重新校验权限；绑定/范围变动丢弃结果。服务端过期租约拒收，关闭范围后旧任务结果与来源元数据不可继续读取。
- 范围更新使用单调版本与 D1 条件更新，快照不能扩权；同一运行中请求共享结果并按各自租约回执；结果超过 256KiB 时返回小型失败，避免本地持久化超大副本。筛选元数据按来源投影。
- 提供自包含的本地 stdio 开发插件与 Skill、无身份模板打包器、Sites manifest 与 Drizzle 迁移布局、Windows 打包 CLI 冒烟脚本。

## 实际验证

本地 JDK 21：`C:/Users/zjg/jdk21/jdk-21.0.8+9`；Android SDK：`C:/Users/zjg/Android/Sdk`；Node 24.12.0。

1. 原始完整基线 `:shared:desktopTest :windowsApp:windowsTest :windowsApp:compileKotlinWindows`：共享 988 项中 5 项失败，均为已知 Unix 脚本/符号链接相关测试。日志 `.artifacts/baseline-windows.log`。
2. 远程定向回归 `:shared:desktopTest --tests 'team.bjtuss.bjtuselfservice.shared.agent.remote.*' :windowsApp:windowsTest :windowsApp:compileKotlinWindows :desktopApp:compileKotlin --no-configuration-cache` 通过。日志 `.artifacts/remote-validation.log`。
3. 最终完整命令：

   ```text
   gradlew.bat -I <主仓库>/.artifacts/android-account-debug-signing.gradle -I <主仓库>/.artifacts/emergencyfix2-checks.gradle :shared:desktopTest :windowsApp:windowsTest :androidApp:assembleDebug :desktopApp:compileKotlin :windowsApp:createDistributable --no-configuration-cache --console=plain
   ```

   最终通过（1m27s）；共享 **992 项、0 失败**（原 988 - 5 个既有排除 + 9 个新增测试），Windows **7 项、0 失败**，包含独立 DPAPI 凭据、缓存密钥、远程令牌与验证码测试。使用既有 init 脚本仅在本机切换 Android debug 签名、排除那五项测试；未改源码签名配置。共享测试没有其他排除。Android APK 与 Windows 可分发目录生成成功。日志 `.artifacts/full-safety-validation.log`。随后增加独立 Site 实测测试及 test-only 序列化依赖，正常 `:windowsApp:windowsTest` 再跑通过（5s，7 通过、1 实测项因未注入凭据跳过），日志 `.artifacts/windows-final.log`。
4. `scripts/windows-cli-smoke.ps1 -AppDirectory <本次Windows打包目录>` 通过：打包 GUI 启动器经重定向句柄输出 help、合法 JSON、正确退出码；隔离 LOCALAPPDATA 下无缓存刷新与未知来源在联网前失败，不遗留 IPC 端点。没有安装 Skill、没有修改真实缓存或凭据。
5. 另一次对实际缓存的只读 `agent status --json`：合法 JSON、退出码 2，原因是多个缓存账号且无法确定当前登录账号。只汇总退出码，未展示个人数据；不把此项说成真实缓存读取或真实登录成功。
6. Site `npm test`：**26 项通过**，覆盖十次普通查询不入队、精确任务幂等、未授权查询、午夜归日、过期租约、权限撤销、范围版本、拥有者固定和独立实例隔离。`npm run build` 成功。
7. 独立 Node stdio 子进程：initialize、tools/list（6 工具）、get_status 均有合法响应，无日志混入 stdout；插件 manifest JSON 验证通过。不是目标宿主安装验证。
8. 本地预览 HTTP 200；cua 浏览器清单仅有 MCP Apps，没有可用普通浏览器。尝试 IAB/Chrome 均报告不可用。open_in_codex 返回 queued；未取得浏览器视觉/交互证据。
9. Sites 官方源码准备与归档流程成功；Windows bundled npm 路径解析失败后，构建使用项目无依赖命令 `node scripts/build.mjs`，未安装依赖或修改系统。托管部署与 D1 实测见下文。
10. `node scripts/package-template.mjs` 重建并检查 `.artifacts/bjtuss-remote-template-0.2.0.tar.gz`；明确清单、不带 project_id/个人站点地址/运行数据库/凭据。配对修正后 SHA-256：`87618AC1EF9DAE95409C0153DFA8BBA79A926C729FCC9E1D8E596D8B30BAE2C2`。
11. `git diff --check` 通过。原 `migrations/0001_init.sql` 保持不变；Drizzle 镜像用于新 Site 的 schema-only 初始化，移除了原兼容迁移中的元数据 INSERT。

## 私人发布与 Windows 连通

- 用户已明确授权私人发布，保留 owner-only 受众。Site：<https://bjtuss-private-remote.ardent-rice-3928.chatgpt.site>，项目 `appgprj_6ad87d5c007c8191bd9424d88073aba7`。
- 独立 Site 源提交 `b2502b0cc6776abe8338ff5dc3c470b57815166e`；部署 `appgdep_6ad87ea7a5608191bd10d14ea30df38a` 返回 succeeded、has_mcp=true。
- 同一 canonical 插件更新为 1.0.1，保留 App 连接、个人私有受众与展示资料；插件 ID `plugin_asdk_app_sites_9c8cc6038e1c8191885c65089da2a0ef`，发布 `pluginrel_6ad8800473088191ae0c4f8e46a5d893`，安装工具返回 Already installed。没有创建第二份插件。
- Windows Node 实测匿名请求 401、仅平台服务访问进入 health 为 200、平台访问加应用 owner Bearer 的 owner/status 为 200、缺少应用凭据为 401。
- `:windowsApp:windowsTest --tests 'team.bjtuss.bjtuselfservice.windows.WindowsRemoteSiteLiveTest' --no-configuration-cache --console=plain`，仅进程环境注入凭据，**通过（19s）**。真实 Ktor/CIO 到私人 Site：配对虚构设备、更新范围、上传 fixture、领取任务、续租、提交结果、重复回执幂等、owner 查询结果及设备撤销；证明真实 D1 迁移与存储往返。测试最终撤销自身设备，没有替换用户已有设备。
- `/mcp` 的平台 OAuth 与设备机器访问独立；应用 owner Bearer 请求 MCP 被平台拒绝 401，测试明确验证该边界，没有降低认证。它不能证明插件宿主 OAuth 查询成功。
- 用户明确授权后创建验收聊天 `01a15dbd-33cc-7e81-968c-cd6471998562`，只尝试 get_status 和 query_snapshot。该聊天实际工具清单没有二者，搜索返回空；未能调用，不把已安装或机器 API 通过当作聊天工具验收成功。没有触发学校刷新。

## 本机真实配对问题修正（同日）

- 用户打开客户端并确认已登录，截图中的平台凭据为空；私人平台门槛的 401 被原客户端误报为设备撤销。非应用 JSON 的 401/403 现归为平台认证失败，首次配对的 401 也不再声称已有设备被撤销。
- 用户授权本机配对后，以仅标准输入传递的本站访问材料调用产品 `WindowsRemoteSecretStore`，保存到 DPAPI 并读回核验；没有打印密钥、写明文凭据文件或改学校账号。预配置材料绑定精确 Site URL，表单留空只沿用同站材料，另一站点不复用；解除绑定时一并清理。
- Site 增加“复制配对码”，复制被拒时提示手动复制。独立 Site 源提交 `81a43fbd294e323259fae362195b835ab243164a`，部署 `appgdep_6ad8862bf4808191ba57c3b12d989376` succeeded，受众未变。Windows HTTP 读取页面 200，并确认已包含复制功能；浏览器工具未提供当前 IAB，因此未冒称实际点击/视觉验收通过。
- 新增 `RemotePairingAccessTest` 验证同站空表单保留、异站清除、显式替换与首次配对提示；共享完整 **993 项、0 失败**，仍只排除原 5 项。日志 `.artifacts/pairing-fix-full.log`。
- 全量构建首次在 `createDistributable` 因源码客户端占用文件失败；按精确路径结束该源码实例后单独重建成功（8s），日志 `.artifacts/pairing-fix-package.log`，未操作安装版。Windows 测试最终 **8 通过、1 在线项因无凭据跳过**（6s），包括新独立 DPAPI 站点绑定测试，日志 `.artifacts/pairing-windows-final.log`。打包 CLI 隔离冒烟再次通过。
- 配对修正后单独 `:androidApp:assembleDebug :desktopApp:compileKotlin --no-configuration-cache --console=plain` 补跑通过（1m26s），日志 `.artifacts/pairing-other-targets.log`；同一 Android debug 签名 init 与虚构环境值，无正式签名改动。
- 修正版源码客户端已重开；用户正在用网页新配对码操作。最近只读 owner/status 显示无活跃设备、无快照，尚未取得真实电脑配对成功证据。不要在此状态下运行会重新配对/撤销的虚构在线测试。

### 真实绑定与缺失主线程调度器修正

- 用户曾报告进入“更多/应用”页退出；开启独立 stdout/stderr 捕获并重新运行后，用户确认未再次崩溃且配对成功。没有该次退出的堆栈，不能直接宣称其原因已确定或修复。
- 只读 Site 状态确认真实设备 `dev_vlsIFo6TQYssavTa` 在线，范围 schedule/exams/assignments/grades，邮箱未授权；此阶段尚无快照。后续不再用虚构设备替换真实绑定。
- `.artifacts/windows-ui-stderr.log` 抓到实际异常：`Module with the Main dispatcher is missing`，调用链 `AppRefreshCoordinator.startBatch → AppSessionRemoteExecutor.refreshAll → RemoteAgentRunner.runUploadCycle`。设备轮询仍在线，但上传前刷新失败。
- `windowsMain` 补充 `kotlinx-coroutines-swing` 生产运行依赖；只新增版本目录 alias，未升级版本目录的通用版本。Gradle BOM 将 Swing 和 core 同步解析为 **1.11.0**，证据 `.artifacts/main-dispatcher-dependency.log`。它提供 Swing 的 Main 调度器，参见 [Kotlin 官方 API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-swing/)。
- 新增 `WindowsMainDispatcherTest`：在真实 `Dispatchers.Main.immediate` 上检查 AWT EventQueue 线程。修复前单例明确失败（`.artifacts/main-dispatcher-before.log`），修复后 Windows 全部 **9 通过、1 在线项跳过**（1m19s，`.artifacts/main-dispatcher-after.log`）。未用 setMain 替身掩盖运行依赖。
- `:windowsApp:createDistributable --no-configuration-cache --console=plain` 通过（13s）；分发目录与启动 cfg 均包含 Swing 1.11.0 JAR，CLI 隔离冒烟再通过。按精确源码路径重开后原设备 ID 与心跳保留，`.artifacts/windows-ui-fixed-stderr.log` 未再出现上述异常；尚未到下一自动上传周期，请用户点一次“立即同步”完成首轮验证。
- 随后用户明确反馈“立即同步”成功，独立 owner/status 实测收到快照 `receivedAt=1792577432`；courses/exams/homework/grades 各来源均 success，physicsLab/physicsDemo/citel/phyvlab skipped，无邮箱授权。仅查看状态与时间，没有输出个人记录或另行触发刷新；新日志仍只有启动警告。至此真实电脑→私人 Site 配对、心跳、实际数据同步通过；canonical 聊天插件查询仍待宿主工具接入。

### 设置页合并与网页「连接电脑」（同日，Codex 额度切断）

- 用户要求把网页「连接 Windows」改成「连接电脑」，并同步改网页 UI。客户端新增 `AiAssistantSettingsScreen`：设置页只保留「AI 助手与远程查询」入口；宽窗左设置/右状态，窄窗上下排列。右侧「本机 AI 助手」与「私人 Site 与电脑配对」分开，文案写明聊天插件是否可用需在聊天中单独验证。
- 独立 Site 源 `46b9989377971ae1dc8397b06762a6f2dfe3c85b`，归档 `.artifacts/agent-site-computer-ui.tar.gz`（含「连接电脑」「AI 助手与远程查询」）。部署 `appgdep_6ad8926e2d548191b06237f5424eaeaa` 查询 status=succeeded、has_mcp=true、url 仍为原私人地址。本机未带凭据拉取返回 401，不能当作已打开页面的视觉验收。
- Codex 在 18:24 因 usage limit 中断（`last_agent_message` 为空），Computer Use 仍报找不到 app-server 路径。后台 Gradle 在 18:25 结束：`:shared:desktopTest :windowsApp:windowsTest :androidApp:assembleDebug :desktopApp:compileKotlin :windowsApp:windowsJar` BUILD SUCCESSFUL（2m41s，`.artifacts/assistant-page-validation.log`）。未提交、未推送、未替换安装版。

## 待验证

- 当前阻塞：新验收聊天仍未暴露 canonical 插件工具，需要现有插件在宿主侧加载后完成真实工具调用。未重复创建聊天/插件以刷新。
- D1 跨重新部署持久化、iPhone ChatGPT、第二真实用户独立实例、连续半小时运行、睡眠/重启、真实校园网刷新均未验证；Node 独立实例测试不能替代第二真人验收。
- Windows 真实缓存查询需先在 App 确认当前账号；本次不替换/安装应用，不修改用户账号与系统启动项。
- Apple 原生构建与模拟器不可在本机 Windows 执行；`:desktopApp:compileKotlin` 是 JVM 编译，不证明 macOS UI、Keychain 或 iOS 编译通过。
- 学校凭据未读取、未上传；所有远程联调均用虚构数据。

## 回退与下一步

本次所有实现位于独立 worktree，没有覆盖 main。可在其中审阅差异；继续时从 `feat/agent-windows-local` 工作，不重新建一个重复分支。
继续使用现有 Site、插件与验收聊天处理工具加载；真实电脑配对及后台生命周期单独实测，不通过公开 Site 绕过门槛。
未合入前只更新 memory.md；不向 history_full.md 写“已完成合入”的日志。

## 20:15 后只读诊断与主目录迁移

- 主目录原为 `main@b083953`；全部开发改动在 `feat/agent-windows-local` 工作树，未提交。现已创建并切换主目录本地 `agent-remote`，跟踪 `origin/agent-remote@2028776`。55 份已修改/新增文件逐文件 SHA-256 一致后移除旧工作树登记；未提交、推送、创建 PR 或改写 main。
- 原有 `.gitignore`、memory、history 的修改有文件备份和 stash；迁移时 history 合并保留双方条目，memory 采用较新的本地诊断并更新。源码和本地诊断归档：`.artifacts/agent-remote-migration-20261021/old-worktree-source-and-diagnostics.tar.gz`，不包含可重建的 build/.gradle/node_modules。
- `git worktree remove --force` 已移除登记，但删除旧目录因 Windows 长路径失败；后续 PowerShell 残留目录清理被自动安全策略拒绝。目录残留不是 Git 工作树，不再用于开发；另两份 Temp 下的历史工作树与本次无关，未动。
- 使用安装版 JAR 的 `WindowsRemoteSecretStore` 只读加载 DPAPI：设备令牌和平台凭据均存在、可解密且绑定本站。已有凭据请求 `/api/v1/health` 返回 HTTP 200。通过 JDBC `mode=ro` 读取加密 metadata，remote config 存在、enabled/paired=true。未输出令牌、账号、快照正文；未写配置、重新配对或触发学校刷新。
- Site `get_site` 为 active、版本 3，仍是 owner-private，MCP 连接仍绑定同一 canonical 插件。只读 D1 devices 核验：无活跃设备；本地绑定设备于北京时间 18:58:22 撤销，最后心跳 18:35:42。当前绑定无效，但没有证据把它归因于 MSI 清空设置。用户报告的重新配对平台认证失败尚未复现，不能用 health 200 声称表单恢复。
- 账户插件源文件核验版本 1.0.1、PRIVATE/USER，包含远程 Skill 和原 App 绑定；本聊天仍无 get_status/query_snapshot 工具。插件搜索返回空并不等于插件已被删除；直接按 Site 返回的 ID 读取源文件成功。尚不能宣称手机 ChatGPT 可用。
- 本轮验证：主目录 `multiplatform/remote-site-template` 执行 `node --test`，27/27 通过（含 helpers 文件，业务测试 26 项）；`git diff --check` 用于迁移差异检查。没有改产品行为，不重复打包或执行虚构配对在线测试。本机 debug/安装版重启持久性、实际配对操作和手机即时查询仍待验收。
