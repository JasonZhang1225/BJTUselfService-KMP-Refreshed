# BJTUselfService 文档索引

本目录按用途与生命周期分类组织仓库文档。实时开发状态见根目录 [`memory.md`](../memory.md)，协作原则见 [`AGENTS.md`](../AGENTS.md)，界面设计规范见 [`DESIGN.md`](../DESIGN.md)，已完成工作的只追加日志见 [`history_full.md`](../history_full.md)。

## 目录结构

| 目录 / 文件 | 用途 | 维护规则 |
| --- | --- | --- |
| [`iOS-sign-guide.md`](iOS-sign-guide.md) | 用户侧 iOS 安装与签名指南（LiveContainer / SideStore） | 随签名或包安装方式变化更新；由根 `README.md` 与发版说明引用 |
| [`images/`](images/) | README 宣传图等静态资源 | 保持路径稳定 |
| [`releases/`](releases/) | 各版本发版说明与内嵌更新日志数据源 | **禁止重命名已有文件**：由 `scripts/sync-bundled-changelog.py`、`.github/workflows/kmp-package.yml` 与 `BundledChangelogTest` 消费 |
| [`agent/`](agent/) | AI 助手（本机 CLI / Skill 与私人远程 Site / MCP）现行契约与搭建指南 | `agent-query-contract.md` 与 `multiplatform/remote-site-template/agent-query-contract.md` 保持一致 |
| [`build/`](build/) | 构建、打包与分发机制说明（macOS SDK 启动器、双更新源、Gitee 镜像） | 随打包脚本与工作流演进更新 |
| [`security/`](security/) | 安全审计报告、修复汇总与真机核验记录 | 按日期归档备查；由根 `README.md` 引用 |
| [`archive/`](archive/) | 已收口阶段的历史文档存档（KMP 迁移 M0–M17、阶段性计划、回放验收、交接记录与代码质量审计） | 只读历史参考，不代表当前状态（含 `archive/migration/`、`archive/agent/`、`archive/playback/`、`archive/plans/`、`archive/refactor/`） |

## 常用现行文档

- **AI 助手与远程 Site**
  - [`agent/agent-query-contract.md`](agent/agent-query-contract.md)：本机 `bjtu` CLI 与远程 MCP 工具共用的查询参数、返回结构与快照 schema。
  - [`agent/remote-setup.md`](agent/remote-setup.md)：Windows 本地开发预览、一句话创建私人 Site 与多设备配对说明。
  - [`agent/ai-agent-plan.md`](agent/ai-agent-plan.md)：本机 CLI + Skill 早期设计计划（仅供历史参考；验证码模型等早期假设已调整，现行行为以 `agent-query-contract.md` 为准）。
- **构建与更新分发**
  - [`build/macos-sdk-launcher.md`](build/macos-sdk-launcher.md)：macOS 打包 App 的 SDK Launcher 原理与签名验证。
  - [`build/dual-update-sources.md`](build/dual-update-sources.md)：GitHub / Gitee 双更新源策略与防回退规则。
  - [`build/gitee-mirror.md`](build/gitee-mirror.md)：GitHub Release 自动同步 Gitee 镜像配置。
