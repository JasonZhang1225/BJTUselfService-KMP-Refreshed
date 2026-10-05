# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-05。
> 当前阶段：NewMigration 后续拖动排序、AIO 鸣谢与课件弹窗修复完成；三端安装包已放入 Downloads。
> 当前分支：NewMigration，基于 main 的 25bfeb5；本地已有初步提交，未推送。
> 正式发布仍为 v1.8.1 / Build 22；本次未调整版本号或发布安装包。

## 已完成

- 首页选中日期在作业、考试、物理在线之后显示课表；使用当前学期官方教学周，排除下学期选课表和假期空档。
- 教室入口统一为“教室查询”；官方列表/分类不变，只给匹配房间补第三方人数和采样时间。
- iOS 更新弹窗标题固定“发现新版本”，版本独立显示，正文留边并可完整滚动；移除重复弹窗。
- 暂不更新持久化抑制自动提示 24 小时，页面重建不重复请求；手动检查保留。
- “更多”改“应用”图标网格；设置可配置底栏，首页/应用固定，自选最多四项，网格与底栏互斥。
- 原四项改造回归后共享测试 611 项、Swift 35 检查通过；Android/macOS/Windows/iOS 两目标编译及 iOS Simulator 构建通过。
- 合成数据实际验收 macOS 窄/宽窗口、iOS 窄屏、深浅色、1.5 倍文字、教室人数失败以及 45 段更新日志滚动/关闭。

- 后续：底栏已选项置顶并支持触控拖动换序；设置/README 补 AIO 社区版链接与贡献说明；课件选课和详情弹窗高度/滚动修复，iOS 20 门课程滚动到底通过。
- 本轮最新 Android release APK、Mac DMG、iOS unsigned IPA 已构建与校验，下载文件及哈希见后续报告。

## 当前注意事项

- 本轮未进行 Android/Windows/iOS 真机安装及真实网络服务验收；iOS unsigned 仍需自签，Mac DMG 仍为本地 ad-hoc 签名，见后续报告。
- 合成测试入口不读取凭据、不联网、不持久化；测试源码桌面进程已退出，用户安装版未关闭。
- v1.8.1 既有物理实验实际持续周数、EventKit、安全存储与 Windows 原生能力的验收边界继续保留。

## 接下来

1. 用户安装 Downloads 中的三个 NewMigration 包体验并反馈。
2. 按反馈调整；如用户提出提交、合并或发布，再基于本分支确认交付范围。

验收报告：`docs/migration/new-migration-result-2026-10-05.md`；后续与安装包：`docs/migration/new-migration-followup-2026-10-05.md`。
本机日志：`.artifacts/NewMigration/evidence/`。完整已完成记录见 `history_full.md`。
