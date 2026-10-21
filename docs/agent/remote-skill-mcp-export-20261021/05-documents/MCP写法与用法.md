# MCP 写法与用法（当前实现）

## 1. 文件分工

- `src/mcp.js`：六个工具的名称、说明、JSON Schema、调用分派及 MCP JSON-RPC 响应。
- `src/query.js`：来源/课程/日期/任务筛选、授权范围映射，以及移除全局筛选元数据。
- `src/core.js`：持久快照与任务队列；任务领取、续租、回执、幂等、权限与过期处理。
- `src/index.js`：HTTP 路由、平台/拥有者/设备鉴权和 `/mcp` 入口。
- `04-windows-query-reference/RemoteQuery.kt`：电脑即时查询参数和筛选；`RemoteSnapshotBuilder.kt` 复用本机聚合和 JSON 输出。

## 2. 六个工具

| 工具 | 参数 | 行为 |
| --- | --- | --- |
| `get_status` | `{}` | 读取设备在线、权限、快照逐来源同步时间和最新全量刷新任务元数据；不访问学校 |
| `query_snapshot` | 可选 `sources`、`course`、`date`、`taskId` | 读取 Site 已存快照；不创建刷新任务 |
| `request_live_query` | 必需 `sources`、`freshness`、`idempotencyKey`；可选 `course`、`date`、`taskId` | `local` 读取电脑缓存；`latest` 刷新指定来源后返回；先得到任务 ID |
| `request_refresh_all` | 必需 `idempotencyKey` | 请求电脑全量刷新；先得到任务 ID |
| `request_citel_problem` | 必需字符串 `taskId`、`idempotencyKey`；可选 `includeFullText` | 指定 CITEL 编程题；只有用户明确授权获取题面/用例全文才设 `includeFullText=true` |
| `get_request_result` | 必需 `requestId` | 读取任务状态和结果；`queued`/`running` 均表示尚未完成 |

精确类型、枚举和长度约束以同目录 `MCP工具定义.json` 为准。当前参数不支持 `from/to/week/pending/kind/appFilter`；不能把 CLI 选项直接传入 MCP。

`sources` 常用值为 `courses`、`exams`、`homework`（课程平台）、`phyvlab`（物理在线）、`citel`、`physicsLab`、`physicsDemo`、`grades`、`mailUnread`；实际可读取的来源仍受设备上云范围限制。CLI 的课程平台作业来源名为 `course`，MCP 选择来源时使用 `homework`。

## 3. 调用顺序

普通提问先调用 `query_snapshot`，必要时先 `get_status` 核对授权范围和真实同步时间。明确要求最新时调用 `request_live_query`；明确要求全量刷新时调用 `request_refresh_all`。

创建请求后保存返回的 `requestId`，用 `get_request_result` 跟踪，不把排队当作完成。同一意图失败重试沿用 `idempotencyKey`，新意图使用新键。`partial/failed/expired/revoked` 需要按状态如实说明；不要绕过 `scope_denied`。

## 4. JSON-RPC / JSONL 示例

正常 ChatGPT 插件调用由宿主处理协议；以下适用于本地开发桥。每行是一个完整 JSON 对象；示例中的课程和 ID 均为虚构。

初始化和发现：

```jsonl
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"review-example","version":"1.0.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

普通读取和精确筛选：

```jsonl
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_status","arguments":{}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"query_snapshot","arguments":{"sources":["homework","phyvlab","citel"]}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"query_snapshot","arguments":{"sources":["phyvlab"],"date":"2026-10-25"}}}
```

用户明确要求即时查询或全量刷新后：

```jsonl
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"request_live_query","arguments":{"sources":["homework","phyvlab","citel"],"freshness":"latest","idempotencyKey":"example_latest_0001"}}}
{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"request_refresh_all","arguments":{"idempotencyKey":"example_refresh_0001"}}}
{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"get_request_result","arguments":{"requestId":"req_example000001"}}}
```

用户明确要求指定 CITEL 题目全文后：

```jsonl
{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"request_citel_problem","arguments":{"taskId":"1234","includeFullText":true,"idempotencyKey":"example_problem_0001"}}}
```

`req_example000001` 仅展示请求形状，实际查询结果时必须替换为创建工具返回的真实 requestId。上述写入工具会向虚构预览建任务；不要直接向真实 Site 批量重放示例。

## 5. 返回结果怎么读

当前 MCP 服务把业务结果序列化为 `result.content[0].text` 中的 JSON 字符串，工具调用方需要解析这一层；成功时 `isError=false`。业务错误通常为 `isError=true` 且文本 JSON 含 `error.code/message`。HTTP 身份错误和 JSON-RPC 协议错误还可能出现在更外层，不能只看业务字段。

云端作业快照的主要路径：

```text
snapshot.data.assignments.homework.items
snapshot.data.assignments.calendar.items
snapshot.data.assignments.citel.tasks
```

`homework.items` 来自课程平台、物理在线、CITEL 的聚合；`calendar.items` 包含开放、折扣、截止事件，同一作业可能有多种事件；`citel.tasks` 是独立任务视图。只读课程日历不能代替读取作业，多个视图也不能简单相加而重复计数。

即时查询完成后另套任务结果：电脑返回内容在 `task.result.data`，其中业务数据在 `task.result.data.data`，作业路径因此为 `task.result.data.data.assignments.homework.items`。CITEL 题面在 `task.result.data.problem`。失败时检查 `task.result.error`，不能只检查是否有 data。

逐来源新鲜度读取 `snapshot.sources.<source>.fetchedAt` 或即时结果 `sources`；`uploadedAt/receivedAt` 不能代表学校数据刷新时间。物理在线和 CITEL 是否启用、范围是否允许、是否有缓存及刷新失败都可能影响结果，空列表不能直接解释为“没有任务”。

## 6. 日期规则

以工具返回的已处理日期时间为准。`agendaDate/dueAgendaDate` 是归属日，`midnightDeadline=true` 时在该日期显示 `24:00`，不得再次加减一天。

虚构示例：

```json
{"source":"phyvlab","courseName":"示例课程","title":"示例作业","dueTime":"2026-10-26T00:00:00+08:00","dueAgendaDate":"2026-10-25","midnightDeadline":true}
```

上例显示 **10 月 25 日 24:00**。原始 `dueTime` 是同一时刻的表示，不能覆盖已处理归属日；按日期筛选时属于 25 日。周、月范围筛选也应沿用已处理归属日。

目前 `MCP初始化响应.json` 以及本地 CLI Skill 仍有旧的简短“午夜归前一天”文案，已原样导出以供审阅；远程 Skill 1.0.2 才明确说明不要重复换算。

## 7. 与本地 CLI 的差异

| 项目 | CLI | 当前 MCP |
| --- | --- | --- |
| 默认读取 | 本机缓存 | 云端上次收到的授权快照 |
| 参数 | 日期范围、周次、未交、多课程、种类、App 筛选、未预约实验等 | 来源、单个日期、单个课程关键词、任务 ID；即时查询另有 freshness |
| 外层 | `schemaVersion/command/generatedAt/timeZone/freshness/data` | 云端为 `device/snapshot/freshness/query`，即时为任务和 result 包装 |
| 作业路径 | `data.items` | `snapshot.data.assignments.homework.items` |
| 刷新 | 命令结果及退出码 | 排队、领取、异步结果 |
| 读取授权 | 本机 Agent 权限 | 单独上云范围和 Site 身份 |

底层复用业务聚合/序列化函数，但没有完全统一接口。云端默认日历覆盖昨天起共 21 天，作业聚合列表不能等同于这个日历窗口；即时查询指定 date 时生成该日的日历。

## 8. 本地虚构预览

在 `02-mcp-site-server` 中运行 `npm run dev`，服务监听 `http://127.0.0.1:4173`。另一个终端运行：

```text
node ../03-local-development-plugin/server/stdio.mjs
```

向桥接器 stdin 输入上述 JSONL。本地桥默认仅连接虚构预览；生产连接的环境变量只有键名写在源码中，本导出未包含任何真实值。Windows Kotlin 参考文件用于阅读调用链，不能单独编译为 App。
