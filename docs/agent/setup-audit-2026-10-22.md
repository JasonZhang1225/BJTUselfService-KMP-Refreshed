# 新用户 Site 与插件搭建审查（2026-10-22）

审查基线：`feat/chatgpt-agent-remote@132b0d5`。以下问题清单保留修复前证据；当前实现状态以本节为准。

## 本轮修复与实际发布

- 用户已删除旧的不完整 Site 和插件。本轮创建一份全新 owner-private Site，未请求用户中途登录，也未调用界面自动化。
- 模板升级至 0.5.0；新增自包含 `SETUP.md` 与查询契约，App 提示词仍指向当前开发分支，合入 main 后切回。新提示词要求助手完成创建、配置、发布与 Skill 后统一交付，不领取配对码、不操作用户本机系统存储。
- 新实例发布前配置 `OWNER_PRIVATE_BOOTSTRAP=true`，首次可信用户身份原子固定；机器访问不能冒领身份。本站服务访问材料作为 `SITE_DEVICE_ACCESS` 运行机密，用户登录后自行生成配对信息，经拥有者专属 API 交给本人设备。HTML、源码、聊天不包含其真实值。
- App 支持完整配对信息，验证本站 URL、到期时间、码格式及凭据格式后交给真实配对流程；机密保存在系统安全存储。保留旧八位配对接口兼容已有设备；全新安装应使用新版 App。移除不存在的连接助手及手动分开填写平台凭据的流程。
- 页面明确区分登录/配置错误并停轮询；手机 health 失败不再误报模板过旧。包版本、health、MCP serverInfo 统一为 0.5.0，MCP 发现和 initialize 声明统一。
- 已用 Sites 与 Plugin Creator 实际创建、配置、发布并读回同一 canonical 私人插件的 Skill、App 绑定和界面保留情况。未创建第二个插件，未领取任何真实配对码。生产 D1 已应用迁移，devices 与 pairing_codes 查询均为 0 行。
- 实测发现发布前 `get_site(include_mcp_connection=true)` 会被拒绝，必须在发布前用普通 get_site 配置机密、发布后再取得插件 ID。另新增 `package:site` 脚本固定归档根 manifest 和 dist/server/index.js 布局；这两点已写进 SETUP，后续助手无需再探索。另提供 `scripts/sync-site.mjs`，已用同一 Site 的真实源码凭据通过隐藏 stdin 验证测试、构建、源码同步和归档，可在缺少官方辅助脚本时直接使用，避免临时组织认证命令。

新 Site： https://bjtuss-remote-fresh-20261022.ardent-rice-3928.chatgpt.site

- project_id：`appgprj_6ad9ad4c356081919b7a7c74e6597689`（仅验证记录，不写维护模板）。
- canonical 插件：`plugin_asdk_app_sites_bd52708922cc819185277e2eecb62912`，1.0.1；查询 Skill 已读回，App 绑定与界面配置保持不变。
- 最新 Site 源码 commit：`4855d66d40503c8ef83325364b2918a2e3735f08`，位于 Sites 专属源码仓库；维护仓库的 setup 修改随本轮提交同步至 `feat/chatgpt-agent-remote`，供新对话读取。

验证：模板 `npm test` 64/64；共享端全量 `:shared:desktopTest`、`:desktopApp:compileKotlin`、`:windowsApp:compileKotlinWindows` 通过；后补的 12 个配对/手机回归测试通过；`:androidApp:assembleDebug` 使用云环境已有公开 compile-only 签名 fixture 构建通过，不是正式签名包。`git diff --check` 通过。

边界：Linux 执行 Windows 原生 DPAPI 测试会因缺少 Windows 库失败，故只把 Windows 代码编译计为通过；未在 Windows 安装版或 Apple 真机执行。当前会话未加载新插件工具，不能宣称已完成宿主实际查询。没有生成新版 Windows MSI，旧安装版不能直接解析完整配对信息；用户真实 Site 登录和 App 配对尚未验证。实现提交 `b70cec6` 已推送；已从 GitHub 重新稀疏检出，匿名 raw SETUP 与源码一致。独立目录执行 64/64 模板测试、build、package:site、package-template 均通过；分发包包含 SETUP、查询契约、两个发布准备脚本且不带 project_id。本站源码 `4855d66` 部署成功为版本 3，env revision 1，has_mcp=true；发布后同一插件仍为 1.0.1，Skill/App 绑定读回保持。云端创建/发布已就绪，客户端完整配对仍须单独验收。

## 结论

现有模板具备业务服务和隔离能力，但新用户搭建流程尚未闭环。明确的源码入口错误、缺失的电脑连接导入能力，以及错误分类丢失，会让助手把配置缺口当成需要用户不断登录、重试或升级的问题。仅改一句提示词不足以完成可复用分发。

未读取用户刚才失败对话的完整工具日志，因此下面区分本地已验证的问题与平台待确认的行为；不据此断言某次 OAuth 回调故障的具体原因。

## 已确认的问题

### 1. P1：复制提示词指向不存在的 main 模板

本轮状态：已按用户要求把 App 提示词 URL 改为 `tree/feat/chatgpt-agent-remote/multiplatform/remote-site-template`，合入 main 后再切回。改动未提交、未打包进入安装版。模板当前尚未合入 main 属正常开发状态，问题是分发提示词提前指向 main。

- 位置：`multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/agent/remote/RemoteSetupGuide.kt:4`。
- 提示词要求读取 `tree/main/multiplatform/remote-site-template`；更新本地 `origin/main` 后，`git cat-file -e origin/main:multiplatform/remote-site-template/README.md` 仍报路径不存在。实际模板在开发分支。
- 新用户没有历史上下文来纠正入口，只能搜索、询问、重试，或者让助手另写实现。
- 修复：分发入口必须指向实际存在、可匿名读取的发布版本，采用带版本与校验信息的模板产物；未发布前明确使用开发分支。不能让每个用户自己猜分支。“最新版或更高”也不应替代确定的版本选择。

### 2. P1：电脑端要求预配置凭据，却没有可供新用户使用的导入入口

- 位置：`RemoteAgentCard.kt:98`、`:254`，`RemoteAgentController.kt:181`，`docs/agent/remote-setup.md:63`。
- 电脑表单仅显示 Site 地址和配对码，平台凭据字段只给 mobile。文档声称“连接助手”负责提前写入目标安装版系统存储。
- 检索生产代码，平台凭据写入发生在 `pair()` 接收到凭据时；未发现所述助手对应的生产导入命令、深链接或安装版 IPC 入口。底层 DPAPI/Keychain 存储能力不等于分发给用户的连接能力。
- 全新安装在空凭据下进入私人 Site 会被平台拒绝；浏览器登录和重新生成配对码不能给 App 配置服务访问材料。云端执行环境也不能直接写用户电脑的安全存储。
- 修复：先实现明确的安装版连接入口和安全交接协议，再让提示词承诺自动配置。凭据由目标安装版接收并保存，不能依赖助手临时运行诊断进程修改注册表；仓库已记录该进程可能与安装版存储隔离。连接流程要能恢复、显示本站授权状态，并复用已有授权。

### 3. P1：已登录不等于已初始化拥有者，提示词没有确定的初始化流程

- 位置：模板 `src/index.js:48`、`README.md:122`、`.env.example`。
- 干净部署既未配置 `OWNER_USER_ID`，也未开启 `OWNER_PRIVATE_BOOTSTRAP`。即使请求已含可信用户身份，拥有者 API 与 MCP 工具调用仍拒绝。
- 本地复现：相同虚构用户请求 identity 为 200、health 为 200、tools/list 返回 11 个工具，但 owner/status 与 tools/call 返回 503 `owner_auth_not_configured`。
- 错误文字只提示 `OWNER_TOKEN`，没有说明可使用的 Site 用户身份配置路径，容易诱导助手增加另一套令牌而不解决浏览器用户授权。
- 修复：在确认 owner-private 后，选择并文档化唯一的默认初始化流程，例如显式设置首次可信身份固定，再用拥有者身份完成首次请求；保持对其他身份和机器请求的拒绝。状态页应分别显示未登录、未初始化、身份不匹配。不要要求用户重复登录来修复部署配置。

### 4. P1：页面把配置错误伪装成暂时断网并无限重试

- 位置：模板 `src/refresh-monitor.js:67`，`src/page.js` 的 `api()`。
- 轮询 catch 丢弃实际错误，统一展示“连接暂时中断，正在自动重试”；401、403、owner 配置 503 均如此。非 JSON 响应又统一引导确认登录。
- 本地注入 owner 配置 503，页面实际得到通用中断提示并安排 4 秒后重试；之后最大间隔 30 秒，仍没有终止或恢复指引。
- 修复：保留结构化错误码，区分平台授权、应用身份、缺少部署配置、暂时服务错误和网络失败。确定的配置/权限错误进入需要处理状态并停自动重试；恢复后续跑原步骤。网络重试需要请求超时与可见状态。

### 5. P2：手机把平台认证或网络失败误报成需要升级模板

- 位置：`RemoteAgentController.kt:190`。
- `api.health()` 的任何 Failure 经 `as? RemoteCall.Ok` 变成空版本，随后都显示“不支持电脑和手机同时连接，请升级”。
- 用户可能已经在 0.4.0，却被引导重部署，实际缺少服务访问材料的问题保留。
- 修复：先处理 health 的失败类型，只有真实读取到低版本才提示升级；未知版本单独说明，不能判定为旧版。

### 6. P2：提示词没有区分插件安装、Site 登录和设备上传配对

- 位置：`RemoteSetupGuide.kt:15`、`:18`。
- 要求一次确认 Chat、Work、Codex 全部能调用，并为每台设备生成配对码；但一个会话通常无法实际验收所有宿主。刚发布的插件也可能尚未载入当前会话。
- 手机只使用 ChatGPT 查询已有电脑快照时，无需配对手机上的交大自由行 App。只有手机 App 也上传缓存时才需要新增设备配对。现有提示词没有讲清，容易增加不必要的授权与操作。
- 当前 Sites 官方 MCP 文档明确：发布后复用自动生成的插件；读取 `get_site(include_mcp_connection: true)` 取得插件 ID，按需展示安装/连接 UI；已安装且当前会话没有工具时说明会话工具加载状态。不需要 `codex mcp add` 或 `codex mcp login`。
- 修复：按用户目标验收当前宿主，复用已安装连接；只对确实缺失的权限提示一次操作。平台插件连接失败不应触发电脑重新配对。提示词写明这些恢复规则，不能承诺助手在单个对话里控制所有宿主。

### 7. P2：模板包不是完整的搭建说明来源，canonical Skill 附加步骤仍靠助手补齐

- 位置：模板 `scripts/package-template.mjs`、`README.md:117`、`RemoteSetupGuide.kt:10`、`:15`。
- 模板包内不含仓库级 `docs/agent/remote-setup.md` 和 `agent-query-contract.md`，README 却要求继续读取它们。只拿到分发包的助手缺少完整操作流程。
- build 脚本只打包服务代码、manifest 与迁移；本地插件目录存在 Skill 并不证明 Sites 自动生成的 canonical 插件已包含该 Skill。提示词要求“附上”但没有确定的读取、保留 App 绑定、更新与验收步骤。
- 修复：模板内提供自包含 setup 文档与查询契约，打包清单包含它们；明确 canonical 插件的 ID 获取、已有 App 绑定保留、Skill 更新方式和验收条件。禁止把开发 stdio 插件另建成第二个云端入口。

### 8. P2：版本与协议声明互相冲突，容易造成无效升级判断

- health 与 README 为模板 0.4.0；package.json、本地 plugin.json、默认压缩包名和 MCP serverInfo 仍为 0.3.0；`docs/agent/remote-setup.md:33` 还要求升级到 0.3.0。
- `src/mcp.js:99` 宣告支持 MCP 2026-07-28，initialize 却固定返回 2025-03-26。平台实际使用哪条发现路径尚未验证，不能把它直接断言为本次登录失败原因。
- 修复：模板版本设单一来源，统一包名、health、文档和服务器元数据；MCP 使用与声明相符的协商行为。发布前自动检查，不能让用户根据互相冲突的文字反复升级。

## 面向复用的修复顺序

1. 先修分发入口、自包含 setup 文档、版本声明，保证同一提示词可以稳定取得同一模板。
2. 完成私人 Site 的拥有者初始化及错误分类，形成可恢复的创建流程：取得源码 → 创建并保存 Site ID → 配置身份 → 构建与发布 → 发现 canonical 插件 → 安装/连接。每一步保存结果，恢复时不重建 Site 或轮换凭据。
3. 实现安装版设备连接入口；生成配对码安排在设备准备好之后，避免十分钟有效期耗在构建、登录或插件安装上。设备授权与插件安装分别报告状态。
4. 最后重写 App 复制提示词：明确执行顺序、所需能力、幂等恢复和确定错误的停止条件，不要求用户担任调试人员。工具缺失时只报告具体阻塞，不搜索其他账号、不自建 OAuth、不反复重装。

用户必要操作只保留平台确实要求的授权/安装、必要的 Site 首次登录，以及在 App 里确认上传范围。服务端初始化、源码下载、版本核验、构建、发布和插件元数据配置由助手完成。这里的可复用是复用模板与创建流程，每位用户仍有独立 Site、存储、插件身份和设备授权。

## 开发侧验收，避免消耗用户耐心

应在开发环境预先覆盖：干净账号上下文/模板可下载、缺少身份配置、已有 Site 恢复、重复创建响应不明、当前会话未加载工具、凭据缺失/失效、真实旧版本与 health 失败的区分、第二独立实例。用户不应被要求通过反复登录或重新配对来定位这些状态。

交付要分开说明“Site 已部署”“插件已连接”“设备已上传”；不能用 health 200 或 tools/list 成功代替真实授权工具调用，也不能把用户尚未选择上传数据视为创建失败。

本轮已执行：

- 模板目录 `npm test`：59/59 通过。
- 模板目录 `npm run build`：通过。
- 内存 SQLite + 虚构身份直接复现 identity/health/discovery 成功而实际工具 503，以及页面将配置 503 转成持续重试。
- 更新并读取 origin/main，确认复制提示词所指模板路径不存在。

上述检查没有请求用户反复登录，没有创建新 Site、安装/重装插件、轮换凭据或更改现有部署。未在第二个真实用户账号或用户 Windows 安装版上执行端到端流程；测试通过不代表新用户 setup 已经可用。
