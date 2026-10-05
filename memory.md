# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-05。
> 当前阶段：功能与 audit 已合入 main 并推送，临时分支与 fc51 工作树已清理。
> 版本：1.8.1 / Build 22。功能提交 67c2d0f。

## 已完成

- 物理实验同步、安全保存独立凭据、校园网实时读取与失败缓存；真实只读查询读到 4 个实验。
- 课程表显示考试/实验，重叠并排；日历支持包含实验，沿用课程 UID 参与范围替换。
- 实验周数沿用参考名称规则，专题/软磁/GPS模拟/设计为两自然周，其余一周，服务器无独立周数字段。
- 四项核心同步默认开启；实验独立开关保留。实验弹层网站说明、底部滚动与图例边框/排序已修复。
- 首页玻璃以 audit 6f1ec08 定版为基线；导航按页面保存进度，教室短页补充实际弹性位移。用户确认 iOS 和 Android 实测正常。
- 功能阶段共享 578 项、Swift 35 检查通过；14 张合成深浅色图与三端 Release 安装包完成，路径/哈希在功能报告。
- audit 安全、作业握手、启动同步与日历精确范围替换保留，详细记录见 docs/security/。

## 当前注意事项

- 实验持续周数仍需按实际安排复核；EventKit、安全存储及 Windows 原生运行证据需相应平台验收。
- 本轮安装包仍 1.8.1 / 22，iOS unsigned，macOS ad-hoc 未公证；没有创建 Release 或新标签。

- 合并后共享 596 项与 Swift 35 检查通过，Android/macOS/Windows/iOS 两目标编译通过。

## 当前流程

主目录 `/Users/zjg/BJTUselfService` 位于 main，合并提交 e759c1c 已推送 mine/main。功能分支、audit 的本地/远端引用及 fc51 工作树已删除；安装包保留 Downloads。

功能报告：`docs/migration/physics-lab-schedule-result-2026-10-05.md`。
