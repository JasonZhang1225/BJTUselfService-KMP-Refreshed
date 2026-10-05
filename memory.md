# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-05。
> 当前阶段：iOS 教室人数估计短列表模糊修复。
> 基线：`c82617c` detached；修改尚未提交。版本 1.8.1 / Build 22。

## 本阶段已做到

- 物理实验同步、考试/实验课表投影、缓存与日历开关已完成，真实只读 Kotlin 查询读到 4 个实验。
- 周数沿用参考项目名称规则：专题、软磁、GPS模拟、设计为连续两自然周，其余一周；服务器没有找到独立周数字段。
- 实验弹层补充 wlsy.bjtu.edu.cn 账号密码说明，移除重复标题，正文与安全区改为可滚动布局。
- 图例“物理实验”改“实验”；实验和考试加同规格描边，排在普通课程类型后。
- 查明当前基线遗漏 audit/mine/audit 的 6f1ec08 玻璃渐显修复，仅移植相关玻璃/首页/滚动代码及测试。
- 原生导航按页面保存和恢复模糊进度，隐藏页回调不修改可见栏；取消侧滑、返回短页与更多页恢复都有 Swift 状态回归。
- 共享/JVM 578 项及 Swift 35 个检查通过，Android/macOS/Windows/iOS 两目标编译通过；14 张合成 Compose 图复查深浅色和放大字体。
- 新 DMG/APK/unsigned IPA 已放 Downloads，文件名标记 physicslab-ui-fix-20261005；完整 iOS Release 构建、各包结构与签名状态校验通过。

- 用户实测确认前轮导航正常，Android 已测试；本轮 BuildingList 补充实际弹性位移报告，复用首页算法，578 项与 iOS 两目标编译通过。

## 当前痛点

- 用户已确认最新 iPhone 顶栏修复实测正常，前轮导航与 Android 测试正常。
- 实验持续周数仍需结合实际安排复核；EventKit 和安全存储仍需签名应用实机验收。

## 接下来

1. 本轮 classroom-glass-fix-20261005 unsigned IPA 已放 Downloads，构建及封装校验通过；复测教室人数估计短列表上滑及回弹的顶栏模糊。
2. 用户已授权提交推送，并按功能分支 → audit → main 合并及清理工作树。

完整实现与验证：`docs/migration/physics-lab-schedule-result-2026-10-05.md`。
