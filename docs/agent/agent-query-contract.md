# Agent 查询契约（数据集 schema 2）

本机 CLI（`bjtu <命令> --json`）、电脑即时查询（`request_live_query`）和私人 Site 的 MCP 工具使用**同一套查询参数、同一种返回结构**。
这份文档是两端的约定；实现与测试位置：

| 部分 | KMP（Kotlin） | Site 模板（JavaScript） |
|---|---|---|
| 数据集（规范化数据，所有日期换算在这里一次完成） | `shared/.../agent/AgentDataset.kt` | 不生成，只读取电脑上传的快照 `data` |
| 查询引擎（筛选、状态、组装返回） | `shared/.../agent/AgentQuery.kt` | `remote-site-template/src/agent-query.js` |
| 契约样例 | `AgentQueryContractTest` | `test/agent-query.test.js` |

两端测试读取同一组文件 `remote-site-template/fixtures/agent-query/`：`dataset.json`（虚构数据集）、`queries.json`（查询）、`expected.json`（期望输出）。
改参数、字段或文案时两边同时改，再运行 `node scripts/update-agent-query-fixture.mjs` 重新生成期望输出并**人工审阅差异**，最后跑 `npm test` 与 `:shared:desktopTest`。

## 命令与工具对照

| CLI | MCP 工具 | 参数（CLI 写作 `--名字 值`，数组用逗号） |
|---|---|---|
| `homework` | `get_homework` | `status`、`sources`、`course`、`range`/`date`/`from`+`to`/`week`（按截止日）、`includeUnbooked`、`detail` |
| `calendar` | `get_calendar` | `range`/`date`/`from`+`to`/`week`、`kinds`、`course` |
| `courses` | —（即 `get_calendar` 的 `kinds: [class, lab]`，默认本周） | 同 calendar |
| `exams` | `get_exams` | `status`（upcoming/all）、`course` |
| `grades` | `get_grades` | `course`、`term` |
| `mail` | `get_mail` | `unread`、`keyword`、`limit` |
| `status` | `get_status`（外加 Site 设备与快照信息） | 无 |
| `cache update` | `request_refresh_all` / `request_live_query` 的 `freshness: latest` | — |
| `citel show <id>` | `request_citel_problem` | `taskId`、`includeFullText` |
| — | `request_mail_message` | `messageId`（电脑读取正文，学校会标记已读） |

`range` 取值：`today`、`tomorrow`、`this_week`、`next_week`、`next_7_days`；与 `date`、`from`/`to`、`week` 只能选一种。范围最多 120 天。

## 返回结构

```json
{
  "schemaVersion": 2,
  "command": "homework",
  "origin": "local_cache | cloud_snapshot | live_local | live_latest",
  "generatedAt": "2026-10-21（周三）22:30",
  "today": "2026-10-21",
  "timeZone": "Asia/Shanghai",
  "currentWeek": 8,
  "query": { "…": "规范化后的参数（含默认值）" },
  "freshness": { "staleAfterHours": 4, "sources": [ { "source": "phyvlab", "label": "物理在线", "syncedAt": "…", "age": "7 小时前", "status": "success" } ],
                 "stale": [], "unknown": [], "failed": [] },
  "summary": { "…": "按命令不同" },
  "items": [],
  "notes": []
}
```

- `calendar` 用 `days`（范围内每天一项，没有安排也列出）代替 `items`，每项 `{ date, weekday, week?, items: [...] }`。
- 出错时只有外层字段加 `error: { code, message }`（`bad_request`、`not_allowed`、`no_data`、`no_snapshot`、`schema_mismatch`）。
- Site 工具额外附 `remote: { device: { name, lastSeen }, snapshotReceived, snapshotScopes }`。

### 作业（`homework`）

一次返回课程平台、物理在线、CITEL 全部来源。`summary` 给出 `total/pending/overdue/submitted/shown/hiddenUnbooked` 与 `bySource`（每个来源的 `enabled` 与各状态数量）。

每项：`id`（`course_platform:…`/`phyvlab:…`/`citel:…`）、`source`、`sourceLabel`、`course`、`title`、`status`（`pending` 未交未截止、`overdue` 已截止未交、`submitted`）、`statusLabel`、`due`、`dueDate`、`remaining`（仅 pending），以及按来源出现的 `open`、`type`、`judgeStatus`、`score`、`discountFrom`、`discount`、`allowLate`、`submittedCount`、`totalCount`、`enrollment`、`matchedBooking`；`detail=true` 时附 `content`、`url`。

### 日期规则（只在电脑端换算一次）

- 所有时间都是北京时间文字，例如 `2026-10-25（周日）24:00`。
- **截止**时刻恰好是 00:00 时写作前一天 `24:00`（与 App 首页一致），`dueDate` 为前一天。输出里不再出现次日日期或 ISO 时刻，避免 AI 把它理解成“26 日”或“25 日 0 点”。
- 开放、折扣、上课等其他时刻按实际写。
- 查询引擎只比较 `date` 字符串和 `epoch`（数据集内部字段，不输出）计算状态与剩余时间。

## 远程快照

快照 `schemaVersion: 2`，`data` 就是数据集 JSON，键为已授权的上云范围：`schedule`（课表、物理/演示实验，覆盖前 7 天到后 56 天）、`exams`、`assignments`、`grades`、`mail`（收件箱最近最多 99 封摘要）。
超出课表覆盖范围的日期，`get_calendar` 会在 `notes` 说明，需要时用 `request_live_query`。
旧版（schema 1）快照会被 Site 拒绝（`schema_mismatch`），电脑端和 Site 模板需要一起升级到模板 0.3.0。
