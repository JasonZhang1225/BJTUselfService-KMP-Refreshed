# 交大自由行远程模板（Site 端，模板版本 0.1.0，协议 v1）

本目录是 Agent Remote 计划 PR 3 的服务端最小实现：每位用户部署一份**只属于自己**的私人 Site，
Windows 上的交大自由行主动连接它，上报快照、领取即时任务。Site 不登录学校、不保存学校凭据。

与 KMP Gradle 工程相互独立，不加入任何 Gradle 构建；KMP 端对应的 DTO 在
`shared/src/commonMain/.../agent/remote/RemoteProtocol.kt`，两端共用 `fixtures/` 做契约测试。

## 当前状态（必须先读）

| 项目 | 状态 |
| --- | --- |
| 协议、D1 schema、配对/快照/任务 API、清理 | 已实现；`npm test` 用 Node 内置 SQLite 执行真实迁移 SQL 验证 |
| **owner-private Site 的机器访问（平台门槛）** | **未验证**。没有创建任何 Site，也没有实测 `OAI-Sites-Authorization` 等平台机器访问凭据能否让 Windows 请求进入应用代码 |
| 拥有者身份 | 暂用部署机密 `OWNER_TOKEN`（Bearer）；平台身份可用后在 `src/index.js` 的 `authorizeOwner` 替换 |
| 运行时适配 | 采用 Workers 风格入口（`export default { fetch, scheduled }`、D1 绑定名 `DB`）；实际 Sites 构建/路由约定未核对 |
| MCP 工具、Skill | 不在本期（计划 PR 5） |

因此在平台门槛实测通过前，**电脑端远程上传默认关闭**，只允许用虚构数据联调；不得为了绕过门槛把 Site 或数据 API 改成匿名公开。

## 身份分层

1. **平台访问控制**（owner-private）：先于应用代码生效。机器请求需要平台支持的服务访问材料；它不是用户身份，也不能代替设备授权。
2. **设备令牌**：配对成功时只返回一次的 `bjtud_…`，Windows 存入 DPAPI；Site 只存 SHA-256 摘要，撤销即失效。
3. **拥有者**：生成配对码、查看状态与快照、创建即时任务、撤销设备、暂停查询、清除云端数据。
4. **学校账号绑定**：`accountBindingId` 是电脑端生成的不透明标识（不是学号）。切换学校账号必须重新配对。

## 接口（JSON，`/api/v1`）

设备接口带 `Authorization: Bearer <设备令牌>`，可带 `X-BJTU-Protocol: 1`；拥有者接口带 `Authorization: Bearer <OWNER_TOKEN>`。

| 方法与路径 | 调用方 | 说明 |
| --- | --- | --- |
| `GET /api/v1/health` | 任意 | 协议与模板版本 |
| `POST /api/v1/owner/pairings` | 拥有者 | 生成 8 位一次性配对码，10 分钟有效 |
| `POST /api/v1/device/pair` | 电脑 | 用配对码换设备令牌；新配对会撤销旧设备（首版一台活跃设备）；10 分钟内失败 10 次锁定 |
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

## 数据范围

`schedule`、`exams`、`assignments` 默认上云；`grades`、`mail` 需用户在电脑端单独开启。快照 `data` 只能含已授权范围的键，
否则 `scope_violation` 拒收；即时查询也不能绕过（未授权成绩时 `query` 含 `grades` 返回 `scope_denied`）。

快照字段：`schemaVersion/deviceId/accountBindingId/snapshotId/sequence/generatedAt/uploadedAt/scopes/sources/data`。
`sources.*.fetchedAt` 是各来源真实同步时间，失败来源保留旧时间，不能用上传时间冒充。

## 清理

`scheduled()`（建议每小时）与每次拥有者请求都会执行有界批次清理：过期任务置 `expired`，24 小时前的结果清空，
7 天前的任务元数据删除，过期配对码删除。默认只保留每台设备的最新快照。

## 本地验证

```bash
cd multiplatform/remote-site-template
npm test   # Node ≥ 22.5；无第三方依赖
```

测试只使用 `fixtures/` 中的虚构数据。

## 部署（实施 PR 6 时按当时 Sites 文档核对）

1. 用平台的 Sites 流程创建 owner-private Site，绑定 D1 数据库为 `DB`，依次执行 `migrations/*.sql`。
2. 设置机密 `OWNER_TOKEN`（≥24 位随机串），不要写入源码或聊天。
3. 配置每小时一次的定时触发调用 `scheduled`。
4. 在拥有者页面生成配对码，在电脑端设置里填入 Site 地址与配对码。

分发包不得包含作者的项目 ID、Site 地址、令牌、学校账号、快照、数据库或 Git 身份；升级只追加迁移，不覆盖用户绑定与数据。
