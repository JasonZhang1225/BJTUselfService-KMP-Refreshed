# 远程 Skill 与 MCP 写法、用法导出

导出时间：2026-10-21（北京时间）。此目录用于阅读、审查和交流，内容保持现状，不修正已有行为。

## 从哪里开始看

| 内容 | 文件或目录 |
| --- | --- |
| 当前远程 Skill 原文 | `01-remote-plugin/skills/bjtuss-remote/SKILL.md` |
| 六个 MCP 工具的写法与调用说明 | `05-documents/MCP写法与用法.md` |
| 完整工具 JSON Schema | `05-documents/MCP工具定义.json` |
| MCP 初始化响应、原始 instructions | `05-documents/MCP初始化响应.json` |
| MCP 服务实现 | `02-mcp-site-server/src/mcp.js` |
| 快照查询与筛选 | `02-mcp-site-server/src/query.js` |
| 配对、授权、数据库、任务与快照 | `02-mcp-site-server/src/core.js` |
| HTTP 与鉴权入口 | `02-mcp-site-server/src/index.js` |
| 本地 stdio 插件与桥接器 | `03-local-development-plugin/` |
| Windows 端查询、快照生成及 CLI 对照源码 | `04-windows-query-reference/` |
| 来源版本与文件校验 | `provenance.json`、`SHA256SUMS.json` |

## 来源和版本

- 远程插件：同一 canonical 私人插件，版本 **1.0.2**，release `pluginrel_6ad8c1212c848191baf033854407cbb5`。
- 插件服务在本次导出时连接失败；`01-remote-plugin` 来自本会话更新后已成功回读并验证过的原文。本次没有重新确认远端是否又有变化。
- MCP 服务源码：最近成功发布的 Site checkout，提交 `f02ebf048cd4774836a5f4e1af3182bac38a3ef4`。导出时该 checkout 无未提交改动；本次没有再次访问线上运行时确认部署变化。
- Windows 和本地开发模板：本地 `agent-remote` 提交 `40dde0f851e5fbaf087a55b3fc11a457c78554d7`。
- `02-mcp-site-server` 的 `src/` 来自上述发布源码；测试、虚构 fixture、迁移、构建脚本和通用 manifest 来自本地模板。
- 不包含平台访问凭据、设备令牌、学校账号、真实缓存、运行数据库或生产环境变量。`.app.json` 含现有插件的 App 绑定 ID，属于配置标识。

## 当前已知差异，原样保留

1. 远程 Skill 1.0.2 已要求沿用拉取结果的已处理日期时间，禁止再次加减一天。
2. MCP `initialize.instructions` 及本地 CLI `AgentSkill.kt` 仍保留旧的“午夜归前一天 24:00”说法，有解释歧义。本次导出没有修改它们。
3. CLI 和 MCP 复用业务聚合/序列化函数，但查询参数、返回包装、默认数据新鲜度和可用权限不同；详见用法文档。
4. 用户报告的物理在线 DDL 漏项尚未定位；源码导出不代表已解决该问题。

## 本地阅读和演示

`02-mcp-site-server` 是可运行的虚构数据预览，Node 要求见其中 `package.json`。在该目录运行 `npm run dev`，另一个终端运行 `node ../03-local-development-plugin/server/stdio.mjs` 即可向桥接器输入 JSONL 请求。

本地演示仅访问回环地址和虚构数据。`01-remote-plugin` 保留线上 App 绑定；正常使用该私人插件应通过 ChatGPT/ Codex 已连接的插件工具调用。
