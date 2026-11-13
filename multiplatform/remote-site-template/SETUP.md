# 私人 Site 和插件：一次交付流程（模板 2.0.0-KMP）

本文给搭建助手执行，支持**新建私人 Site**与**从旧版本增量更新已有 Site**。每个用户复用同一模板，但有自己的 Site、DB、canonical 插件和设备授权。搭建与升级中不要求用户提前登录 Site、取身份 ID、取配对码或给助手本机存储权限。用户交互集中在最后交付 Site、插件安装/连接入口及操作说明。

## 1. 准备和注册

- 读取当前 Sites skill 的发布流程、身份与机密、Site MCP 文档，以及 Plugin Creator 的 Sites MCP 和 App-backed plugin skill 更新文档。以当前工具返回值为准。
- 模板源码入口为 `https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed` 的 `main` 分支、`multiplatform/remote-site-template/` 目录。可用 Git 稀疏检出只读取该目录；复制到仓库之外的独立 Site 目录，不能连带 `.git` 或在维护仓库内直接执行 Site 同步。安装文档与查询约定均读取该 main 分支目录内的版本。
- 在独立空目录或已有 Site 的独立工作目录使用本模板。它是无第三方 Node 依赖的 Worker ESM，Node >=22.5；无需安装整个 KMP 工程、Vinext 或其他框架。不要改写 src 查询实现。
- 检查 `.openai/hosting.json`：保留 `d1: "DB"`、`capabilities: ["mcp"]`、无 static。只有确认确实是全新站点（不存在匹配的已有可用 Site，且工作副本尚无 `project_id`）时才 `create_site` 一次，并立即将返回的 ID 写入该实例 manifest。维护模板不写用户 ID。
- **先查已有 Sites，宽松匹配并默认走增量更新（不要轻易新建）**：执行任何建站操作前，即使提示词里用户没有贴旧 Site 链接或 `project_id`，也必须先列出用户当前账号下的 Sites。满足以下**任意一条**即按下方「已有 Site 的增量更新」直接升级原 Site，完整保留原 Site、DB、插件与所有设备数据，**禁止再创建第二份 Site 或第二份插件**：
  1. 用户明确指定了已有 Site / URL / `project_id`；
  2. 账号下存在**名字相同或相近**的私人 Site（如名称或标题含“交大自由行”/`BJTUselfService`/`bjtuss`/“私人查询”等）；
  3. 账号下存在**模板结构相似**的私人 Site（如 `hosting.json` 含 `d1: "DB"` 与 `capabilities: ["mcp"]`、文件布局含类似 `src/`/`migrations/`/`drizzle/`、D1 含 `template_meta`/`devices`/`snapshots`/`tasks` 等核心表、或提供 `/api/v1/health` 接口）。旧版站点在文件清单、工具数（如 `11` 个与 `13` 个）、版本号格式或插件状态上与 `2.0.0-KMP` 有差异属升级前的正常现象，**名字相同或模板相似就算同一套站点**，直接增量更新，不得以此为由新建站点。
  - 若账号下只有这一份匹配 Site，直接增量更新，不要再额外打断用户确认或重新 `create_site`；若恰好存在多份同名/相似候选 Site 且难以区分，优先选择最近活跃或已有绑定数据的那一份，实在无法判断时问用户选哪一份，不新建第三份。
  - **兼容已发布客户端旧提示词的冲突说明**：若用户粘贴的客户端提示词里仍写有“没有提供旧 Site 时新建一份 / 用户未指定旧 Site 则新建”等旧文案，**不得据此强行新建第二份站点**；该措辞本意是“没有旧 Site 时才新建”。只要按本节检查出已有名称或模板相似的 Site，一律优先在原 Site 上增量更新。
  - 仅当确认当前 Sites 列表中**没有任何名字或模板相似的已有 Site**，或用户在本轮对话中明确要求另建一个全新 Site 时，才新建一次；创建响应不明时先核实原操作，不能重复创建。

### 已有 Site 的增量更新（升级指导）

当用户指定了已有 Site，或按上述宽松条件在 Sites 列表中匹配到名字相同/相近、模板结构相似的已有 Site（如从 `0.x` / `1.8.6-Beta` / `1.8.6-KMP` 等任意旧模板升级至 `2.0.0-KMP`）时，严格执行以下增量步骤，**禁止新建重复站点、销毁重建数据库或更换实例身份**：

1. **打开已有源码并增量覆盖模板文件**：按 Sites 源码流程拉取/打开用户原有 Site 源码，**完整保留** `.openai/hosting.json` 中的原有 `project_id`、`d1: "DB"` 与 `capabilities: ["mcp"]`（以及本地已有的运行时环境变量/机密，绝不覆盖）；用 `2.0.0-KMP` 模板覆盖以下全部模板文件与目录：`src/`、`fixtures/`、`migrations/`、`drizzle/`、`plugin/`、`scripts/`、`test/`、`package.json`、`README.md`、`SETUP.md`、`agent-query-contract.md`、`.gitignore`、`.env.example`（其中 `fixtures/` 是测试与契约校验必需的共享样例，必须一并同步，否则新增测试会因缺少样例失败）。
2. **保留原有拥有者与有效机密**：保留 DB 中已固定的 `OWNER_USER_ID`（如已设置不得替换或覆写），并保留已配置的 `OWNER_PRIVATE_BOOTSTRAP` 与 `SITE_DEVICE_ACCESS`；仅当缺少其中一项时按第 2 节补齐。不要无故轮换已有 `SITE_DEVICE_ACCESS`，以免已配对设备立即断连。
3. **只按顺序补跑未执行的 DB 增量迁移**：先在原 D1 数据库检查当前迁移版本（`SELECT value FROM template_meta WHERE key = 'schema_migration'`）与现有表结构，再严格按序号**仅执行尚未落库的迁移文件**（`migrations/` 与 `drizzle/` 内容对应；构建产物保留 `dist/.openai/drizzle/`）：
   - `0001_init.sql`：基础表（`template_meta`、`pairing_codes`、`devices`、`snapshots`、`tasks`）
   - `0002_multi_device.sql`：多设备支持（`devices.device_kind` 列）
   - `0003_attachments.sql`：作业附件与分块表（`attachments`、`attachment_chunks`、`attachments_lookup` 索引）
   - 例：当前为 `0002`（`1.8.6-KMP`）升级至 `2.0.0-KMP`，**只需补跑 `0003_attachments.sql`**；当前为 `0001` 则依次补跑 `0002_multi_device.sql` 和 `0003_attachments.sql`。确认 `template_meta.schema_migration` 已更新为 `'0003'`。切勿删除、重建或清空已有表，原有 `devices`、`snapshots`、`tasks` 与设备令牌必须完整保留。
4. **验证、部署原 Site 并同步升级同一个 canonical 插件**：按第 3 节运行 `npm test`、`npm run build` 并部署回原 Site；验证 `GET /api/v1/health` 返回 `releaseVersion: "2.0.0-KMP"`（`templateVersion: "2.0.0"`）。随后按第 4 节打开同一个 canonical `plugin_id`，保留原 App 绑定，增量更新 `plugin/skills/bjtuss-remote/SKILL.md` 与插件版本并核对 `/mcp` 的 13 个工具，不新建第二个插件。
5. **无需重新配对已绑设备**：增量升级不会失效既有设备令牌与快照；告知用户原有已配对的电脑和手机可继续直接同步，只有新加设备时才需到 Site 重新生成配对信息。

## 2. 发布前配置，两项都由助手完成

确认 Sites 当前返回的访问范围只有拥有者本人。保留平台私人访问控制，不通过公开站点解决机器访问。

用 Sites 环境变量工具设置：

此时读取 `get_site` 不带 `include_mcp_connection`；MCP 尚未发布时请求连接信息会被拒绝，不能因此要求用户登录。发布成功后才读取 MCP 连接信息。

| 键 | 内容 |
| --- | --- |
| `OWNER_PRIVATE_BOOTSTRAP` | 字符串 `true`；首次来自可信 Sites 边界的用户身份原子固定在 DB，其他用户不能冒领，机器服务令牌不能初始化身份 |
| `SITE_DEVICE_ACCESS` | 读取同一 Site 的 get_site 返回的 siwc_bypass_bearer_token，原值设为 secret；只交给同一 Site，不记录到源码、HTML、manifest、聊天或本地明文文件 |

不为新实例添加 OWNER_TOKEN；不要求用户浏览器登录后手动取得 OWNER_USER_ID。升级若已有 OWNER_USER_ID 则保留，不能替换已有拥有者。

若工具未返回服务访问材料，先查当前 Sites 支持的取得方式；确定不可用时报告“设备连接授权配置受平台能力阻塞”。不要向用户反复索要登录，不以猜测值发布成可用状态，也不轮换凭据来测试。

## 3. 构建与发布

- `npm test`、`npm run build`。构建产物为 `dist/server/index.js` 和 `dist/.openai/hosting.json`，保留所有相对导入模块与 `dist/.openai/drizzle/` 迁移。源 manifest 与构建产物的 project_id 必须相同。
- 优先使用当前 Sites source helper；辅助脚本不在当前环境时，不安装或搜索其他登录工具，直接使用模板自带的脚本：

  ```text
  node scripts/sync-site.mjs <源码目录外的绝对部署归档路径.tar.gz>
  ```

  以 `exec_command(tty: true, yield_time_ms: 1000)` 启动，出现 `Ready for Site credential JSON on stdin (input is hidden).` 后，用 `write_stdin` 传入一行 JSON：`{"credential": <create_site 或 create_source_repository_write_credential 返回的完整凭据对象>}`，末尾加换行。不要把凭据替换为聊天文字、文件或命令参数。脚本自己完成测试、构建、源码 commit/push 和打包，输出最终 project_id、commit_sha、archive，原样传给私人发布工具。它只支持当前已验证的 work_proxy 和 http_extra_header 模式；拒绝时报告实际能力缺口，不启动用户登录。
- 归档根必须有 `.openai/hosting.json` 和 `dist/server/index.js`；不能只对 dist 内部打包成 server/index.js。脚本已固定并校验此布局。单独打包可运行 `npm run package:site -- <源码目录外的绝对路径.tar.gz>`。
- 已有站点升级或新建站点均保持为 owner-private，使用私人发布工具；环境变量变更后必须部署才生效。保留返回的版本和部署 ID；仅非终态继续查部署状态，不重复保存/发布。
- 不要求用户在本步骤登录。用户首次打开私人 Site 时由平台处理登录，首次可信请求固定拥有者。

## 4. 同一个 canonical 插件

- 发布 MCP 后，调用 `get_site(include_mcp_connection: true)`，复制其实际 plugin_id；不要调用 create_plugin 再建第二个插件。
- 通过 Plugin Creator get_plugin_files 先列文件再读 manifest，保留真实插件 ID、App 绑定、MCP 配置、名称、描述和界面配置。只附加或更新 `plugin/skills/bjtuss-remote/SKILL.md`，按原格式更新版本，用当前 release ID 做更新保护，再读回检查。
- 不上传本模板本地 stdio 桥作为云插件。插件工具应来自本站 `/mcp` 的 13 个工具。
- 检查 MCP 发现和工具目录；如当前会话有本站工具，执行只读 get_status。没有设备/快照是正常待配对状态，不是需要登录学校、刷新或重装插件的理由。
- 当前会话没有新工具时，如实记录待加载，不能仅凭 tools/list 声称完整宿主查询通过。不要自建 OAuth、调用 codex mcp login 或不断开新对话。只在最后交付时按 Sites 返回 ID 展示一次插件安装/连接 UI。

## 5. 最终只交付一次

给用户真实已部署的 Site URL、同一插件的安装/连接入口，以及以下说明：

1. 打开 Site，用自己的 ChatGPT 账号登录。
2. 在“连接设备”中点击“生成配对信息”，复制完整内容；它含本站地址和连接授权，十分钟有效且配对码只能使用一次，请勿转发到聊天。
3. 在支持此流程的新版交大自由行 App → Agent 和 ChatGPT 插件，只粘贴完整配对长码，无需填写地址，选择允许上云的数据并确认。
4. 之后可在 ChatGPT 中查询快照。仅用手机 ChatGPT 查询电脑数据，不需要再把手机 App 配对；手机也需要上传自己的缓存时才单独配对。

助手不请求 owner/connection 或 owner/pairings，不提前生成配对码，不让用户在搭建过程中往返登录。旧版 App 不能解析完整配对信息时应明确需要新版客户端，不能让用户把凭据手工交给助手修改系统存储。

## 错误恢复

- 401/403：保留步骤，用户在最终 Site 页面登录/连接；不能轮换、重建或重装来试错。
- owner_auth_not_configured / device_access_not_configured：部署助手修正机密配置，页面停止自动重试；不能建议用户重复登录。
- health 网络或认证失败：报告该错误；只在读到真实旧版本时要求升级。
- 配对过期：用户在 Site 自行重新生成，不必回到搭建对话。
- 部署成功、插件发布/连接、设备配对、首次上传分别报告，不能互相替代验证。
