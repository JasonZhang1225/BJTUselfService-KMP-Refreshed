# 归档：安卓录像下载合成提速与跨芯片兼容（历史）

**状态：2026-10-31 起不再作为产品计划。**

产品已关闭 Android / iOS 的录像下载与合成入口（仅在线播放，见 `playbackRecordingDownloadAvailable`）。本文原 P0 真机测量、P1–P3 优化与 9–10 分钟目标 **全部取消为产品交付**。

| 现行文档 | 用途 |
| --- | --- |
| [playback-four-platform-acceptance.md](playback-four-platform-acceptance.md) | 总标准；移动端只验播放 |
| [playback-macos-acceptance.md](playback-macos-acceptance.md) | **现行**桌面下载后续（Mac） |
| 本文 / `playback-composite-android-windows.md` | 历史分析；禁止按文重新排移动端下载开发 |

若需查阅旧基线（约 16 分 15 秒整节、瓶颈拆解、FastMp4PartJoiner 等已实现项），请用 `git log` / `git show` 查看本文件在 **2026-10-30 及更早** 的版本，或本机 `.artifacts/android-full-acceptance/`、`.artifacts/android-perf/`（若仍存在）。仓库内不再维护可执行的安卓下载测试步骤正文。
