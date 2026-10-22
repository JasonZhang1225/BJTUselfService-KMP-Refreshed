# 交大自由行远程模板（Site 端，模板版本 1.8.6-KMP，协议 v1，快照 schema 2）

本目录是 Agent Remote 计划 PR 3 的服务端最小实现：每位用户部署一份**只属于自己**的私人 Site，
Windows 上的交大自由行主动连接它，上报快照、领取即时任务。Site 不登录学校、不保存学校凭据。

与 KMP Gradle 工程相互独立，不加入任何 Gradle 构建；KMP 端对应的 DTO 在
`shared/src/commonMain/.../agent/remote/RemoteProtocol.kt`，两端共用 `fixtures/` 做契约测试。

## 当前状态（必须先读）

| 项目 | 状态 |
| --- | --- |
| 协议、D1 schema、配对/快照/任务 API、清理 | 已实现；`npm test` 用 Node 内置 SQLite 执行真实迁移 SQL 验证 |
| **owner-private Site 的机器访问（平台门槛）** | 维护者独立实例已用 Windows Ktor 实测；新实例仍须验证 `OAI-Sites-Authorization` 与应用设备授权两层边界 |
| 拥有者身份 | Sites 身份头匹配机密 `OWNER_USER_ID`；确认 owner-private 后可显式开启首次可信身份固定；`OWNER_TOKEN` Bearer 用于受控机器调用。服务访问令牌不能固定拥有者身份 |
| 运行时适配 | Workers ESM 入口，D1 绑定 `DB`；`npm run build` 生成 `dist/`；维护者实例已部署并完成真实 D1 协议往返 |
| MCP 工具、Skill | 11 个工具，与电脑端 `bjtu` 命令行同参数同结构（见仓库 `docs/agent/agent-query-contract.md`）；云端优先查询 Skill、本地 stdio 开发插件；聊天宿主工具接入须单独验收 |

**电脑端远程上传默认关闭**。每个新实例通过平台门槛验证后，由用户自行配对并授权来源；初次联调用虚构数据，不得为了绕过门槛把 Site 或数据 API 改成匿名公开。

模板本身不携带任何部署身份。具体实例的部署与验证状态记录在仓库的 `docs/agent/windows-local-verification-2026-10-21.md`，
不能用某个维护者实例的连通结果保证新用户实例也已连通。

## 多设备（0.4.0）

一个 Site 可同时绑定电脑（desktop）和手机/平板（mobile）。电脑定时上传快照并轮询执行即时任务；手机只在 App 里每次刷新后上传快照，不领取任务。
查询时每个上云范围取“该范围同步时间最新”的设备快照（`remote.dataFrom` 标出来源）；即时任务只派给最近在线的电脑，没有电脑时返回 `no_device`。
升级只需按顺序执行 `migrations/0002_multi_device.sql`（`drizzle/` 同步提供），已有绑定、快照和任务不受影响。

## 身份分层

1. **平台访问控制**（owner-private）：先于应用代码生效。机器请求需要平台支持的服务访问材料；它不是用户身份，也不能代替设备授权。
2. **设备令牌**：配对成功时只返回一次的 `bjtud_…`，Windows 存入 DPAPI；Site 只存 SHA-256 摘要，撤销即失效。
3. **拥有者**：生成配对码、查看状态与快照、创建即时任务、撤销设备、暂停查询、清除云端数据。
4. **学校账号绑定**：`accountBindingId` 是电脑端生成的不透明标识（不是学号）。切换学校账号必须重新配对。

## 接口（JSON，`/api/v1`）

设备接口带 `Authorization: Bearer <设备令牌>`，可带 `X-BJTU-Protocol: 1`；拥有者接口带 `Authorization: Bearer <OWNER_TOKEN>`。

| 方法与路径 | 调用方 | 说明 |
| --- | --- | --- |
| `GET /api/v1/health` | 任意 | 协议、数字兼容版本 templateVersion 和完整发布版本 releaseVersion（后者供展示） |
| `POST /api/v1/owner/pairings` | 拥有者 | 生成 8 位一次性配对码，10 分钟有效 |
| `POST /api/v1/owner/connection` | 拥有者 | 用户登录后自行生成完整配对信息，含本站服务访问授权；搭建助手不调用 |
| `POST /api/v1/device/pair` | 电脑/手机 | 用配对码换设备令牌；`deviceKind` 为 `desktop`（默认）或 `mobile`。0.4.0 起可同时绑定多台设备（上限 5），只撤销同名同类型的旧绑定；10 分钟内失败 10 次锁定 |
| `PUT /api/v1/device/snapshot` | 电脑 | 上传最新完整快照（≤1 MiB）；同 `snapshotId` 重传幂等，序号不增大拒收 `stale_snapshot` |
| `PUT /api/v1/device/scopes` | 电脑 | 立即更新允许上云的范围；Site 按此再次过滤已存快照 |
| `POST /api/v1/device/poll` | 电脑 | 原子领取最早一条任务并设置 120 秒租约；无任务返回 `task: null`；心跳最多约每分钟落库一次 |
| `POST /api/v1/device/tasks/{id}/renew` | 电脑 | 续租（总执行预算约 10 分钟） |
| `POST /api/v1/device/tasks/{id}/result` | 电脑 | 提交结果（≤256 KiB）；同租约重复提交幂等；旧租约提交 `stale_lease` |
| `GET /api/v1/owner/status` | 拥有者 | 设备、最近在线、快照元数据与逐源同步时间、任务计数 |
| `GET /api/v1/owner/snapshot` | 拥有者 | 最新快照（已按当前范围过滤） |
| `POST /api/v1/owner/tasks` | 拥有者 | 创建即时任务（`query`/`refresh_all`/`citel_problem`，参数白名单），相同 `idempotencyKey` 不重复入队，返回 202 |
| `GET /api/v1/owner/tasks/{id}` | 拥有者 | 任务状态与结果；未完成时为 `queued`/`running`，不能当作已取得最新 |
| `POST /api/v1/owner/devices/{id}/revoke` | 拥有者 | 撤销设备，未完成任务置为 `revoked` |
| `PUT /api/v1/owner/queries` | 拥有者 | `{"paused": true}` 暂停查询（快照读取与建任务返回 423） |
| `DELETE /api/v1/owner/data` | 拥有者 | 删除全部快照与任务（设备绑定另行撤销） |

任务状态：`queued → running → succeeded/partial/failed`；未领取 5 分钟或租约与重试（3 次）耗尽为 `expired`；撤销绑定为 `revoked`。
网络是至少一次交付：电脑端靠租约 ID 与本地结果记录去重，传结果失败只重传结果，不重做学校刷新。

拥有者网页自动跟踪最新的全量刷新任务：排队、执行中、完成、部分完成、失败或过期。电脑默认每 5 秒检查指令，
不必等待半小时同步。网页在任务进行时每 2 秒查询进度，完成后再次读取快照状态，自动更新各来源同步时间；
空闲时每 10 秒更新设备状态，页面隐藏时暂停读取，回到前台继续。普通状态轮询不会发起学校刷新。
刷新按钮在任务执行期间禁用，响应丢失后重试沿用幂等键；重新打开页面也能恢复跟踪当前设备的最新任务。

## 数据范围

`schedule`、`exams`、`assignments` 默认上云；`grades`、`mail` 需用户在电脑端单独开启。快照 `data` 只能含已授权范围的键，
否则 `scope_violation` 拒收；即时查询也不能绕过（未授权成绩时 `query` 含 `grades` 返回 `scope_denied`）。

快照字段：`schemaVersion/deviceId/accountBindingId/snapshotId/sequence/generatedAt/uploadedAt/scopes/sources/data`。
`sources.*.fetchedAt` 是各来源真实同步时间，失败来源保留旧时间，不能用上传时间冒充。

`scopesRevision` 是权限范围的单调版本（配对时从 1 开始）。`PUT device/scopes` 显式更新权限，
同版本同范围重传幂等，旧版本或同版本不同范围拒收。快照只能携带当前授权和版本，不再隐式扩大权限；
旧客户端无版本的范围请求只能缩小授权，扩大时需更新电脑端。数据库已经有范围版本列，无破坏迁移。
全局筛选提示不能旁路暴露被关闭范围的课程名，Site 读取时会移除旧快照里的这类元数据。

## 清理

`scheduled()`（建议每小时）与每次拥有者请求都会执行有界批次清理：过期任务置 `expired`，24 小时前的结果清空，
7 天前的任务元数据删除，过期配对码删除。默认只保留每台设备的最新快照。

## 本地验证

```bash
cd multiplatform/remote-site-template
npm test   # Node ≥ 22.5；无第三方依赖
npm run build
npm run dev # http://127.0.0.1:4173，仅虚构数据、内存数据库；重启重置
```

测试只使用 `fixtures/` 中的虚构数据。

`plugin/` 是完整、自包含的本地开发插件，`mcp.json` 通过 Node 启动 `server/stdio.mjs`，
默认连接上述虚构预览。其 `skills/bjtuss-remote/SKILL.md` 约束普通查询只读快照、明确要求最新才排队。
源码可供本机支持的插件安装流程使用；本次没有修改用户插件配置或安装插件。

MCP `POST /mcp` 支持 `server/discover`、`initialize`、`tools/list`、`tools/call`、`ping`。
工具为 `get_status`、`get_homework`、`get_calendar`、`get_exams`、`get_grades`、`get_mail`（读云端快照，与电脑
`bjtu` 命令同参数同结构，引擎在 `src/agent-query.js`），以及 `request_live_query`、`request_refresh_all`、
`request_citel_problem`、`request_mail_message`、`get_request_result`（创建/读取电脑任务）。
普通查询不创建任务；即时查询参数是 `command` + 对应查询工具的参数 + `freshness` + 幂等键。
`queued/running` 仅表示未完成，结果需再次查询。CITEL 获取全文须显式 `includeFullText=true`。
改查询参数、字段或文案后运行 `node scripts/update-agent-query-fixture.mjs`，审阅 `fixtures/agent-query/expected.json` 的差异；
KMP 端 `AgentQueryContractTest` 用同一份样例校验 Kotlin 引擎。

干净模板打包（输出文件在源码目录外）：

```text
node scripts/package-template.mjs <绝对输出路径.tar.gz>
```

脚本使用明确文件清单，并重新生成没有 project_id 的 manifest；不带 `.git`、`.env`、
数据库、`dist`、用户身份或运行凭据。正式 Site 必须使用 Sites 自动创建的 canonical 私人插件，
不要把本地开发桥另行上传为第二个云端插件。搭建步骤见包内 `SETUP.md`。

## 私人部署

完整、可复用搭建步骤见模板内 [SETUP.md](SETUP.md)，查询约定见 [agent-query-contract.md](agent-query-contract.md)。搭建助手先完成私人部署、拥有者初始化配置、设备连接机密和 canonical 插件 Skill；最后统一交付 Site 与插件入口。用户随后登录 Site 自行生成配对信息，不在搭建对话中取码。

自 1.8.6-Beta2 起（含 1.8.6-KMP），“生成配对信息”会通过拥有者专属 API 返回绑定本站、十分钟有效的配对信息。新版 App 从中取得一次性码和本站服务访问授权，存入系统安全存储。普通八位码保留为旧接口兼容；全新设备请使用完整配对信息，不需要助手访问本机安全存储。

分发包不得包含作者的项目 ID、Site 地址、令牌、学校账号、快照、数据库或 Git 身份；升级只追加迁移，不覆盖用户绑定与数据。
