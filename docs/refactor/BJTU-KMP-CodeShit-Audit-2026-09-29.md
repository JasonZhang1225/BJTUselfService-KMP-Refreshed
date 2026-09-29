# BJTUselfService-KMP 代码味道复查报告（1.8.0）

> 审计日期：2026-09-29 · 审计对象：`multiplatform/` HEAD `413a47a`（版本 1.8.0 / Build 21）
> 对照：[2026-09-17 屎山审计](BJTU-KMP-CodeShit-Audit-GLM-2026-09-17.md) 及其第九节修复里程碑（P0/P1/P2a/P3 已完成，P2b 部分完成）
> 方法：行数/文件统计 + 巨型函数测量 + 对照上轮问题清单的源码核对。纯只读，未改业务代码。

---

## 一、TL;DR

| 维度 | 2026-09-17 | 2026-09-29 | 一句话 |
|---|---|---|---|
| **整体质量** | 6.8 / 10 | **7.2 / 10** | 数据层仍然干净；P0 拆壳已落地，但壳层自己长成了新的万物文件 |
| 精简程度 | 6 / 10 | **6.5 / 10** | 加载/空态与端点、jsonEscape 已收敛；UI 屏级复制和巨型 Composable 还在 |
| 屎山程度 | 5.5 / 10 | **5.0 / 10**（越低越好） | 命名/安全/测试纪律保持；债务从「成绩页错包」变成「导航壳膨胀」 |

**一句话：上轮最危险的错位（`GradeScreen` 塞应用壳）已经拆开，没有回到万物文件；新的热点是 `AuthenticatedAppShell.kt`（2000 行，主函数约 1786 行）。这不是回归，是拆出来之后继续长。**

---

## 二、代码量与结构

| 模块 | 文件数 | 行数 | 相对 09-17 |
|---|---|---|---|
| `shared/commonMain`（kt/kts/sq） | 150 | 37,958 | 126 文件 / 33,669 行 → +4.3k 行 |
| `shared/commonTest` | 96 | 13,438 | 89 / 12,352 |
| `shared/androidMain` | 14 | 587 | 12 / 543 |
| `shared/iosMain` | 18 | 2,026 | 17 / 1,728 |
| `shared/desktopMain` | 18 | 1,239 | 14 / 948 |
| `shared/appleMain` | 3 | 267 | （当时未单列） |
| `shared/desktopTest` | 12 | 1,447 | — |
| `shared/iosTest` | 1 | 89 | — |
| `androidApp` / `desktopApp` / `windowsApp` / `iosApp` | 43 | ~7,720 | 宿主+iOS 壳（Liquid 导航在 Swift） |
| **Kotlin 合计（find \*.kt）** | — | **59,171** | 当时合计约 52,525 |

`@Test`：**563**（当时 474）。测试/主代码比：commonTest 13.4k / commonMain 38.0k ≈ **35%**（当时 37%）。主代码涨得比测试略快，仍明显高于普通 vibe coding 项目。

注释率（commonMain，行首 `//` `/*` `*`）：约 **6.6%**（当时 5.1%）。仍然偏低，但关键决策注释（周数语义、加密迁移、ATS、Cookie 边界）质量高。不做专项补注释。

`expect` 约 19 / `actual` 约 57，比例健康。

真实 `TODO`/`FIXME`/`HACK`：**0**。命中的 `TODO` 都是邮箱「待办邮件」文件夹枚举，不是欠条。

---

## 三、巨型文件 TOP 15（Kotlin）

| 文件 | 行数 | 09-17 | 变化 |
|---|---|---|---|
| `feature/shell/AuthenticatedAppShell.kt` | **2000** | 当时还在 GradeScreen 里 | **新热点** |
| `feature/course/CourseScheduleScreen.kt` | 1736 | 1536 | +200（课表能力继续加） |
| `feature/mailbox/MailboxScreen.kt` | 1614 | 1609 | 持平 |
| `feature/home/HomeScreen.kt` | 1612 | 1486 | +126 |
| `feature/homework/HomeworkScreen.kt` | 1443 | 1397 | +46 |
| `feature/grade/GradeScreen.kt` | **1213** | **3811** | **−2598（P0 保持）** |
| `LoginScreen.kt` | 1188 | 1325 | −137（P1 保持） |
| `feature/phyvlab/PhyVlabScreen.kt` | 1159 | 1120 | +39 |
| `feature/classroomoccupancy/ClassroomOccupancyScreen.kt` | 1085 | 968 | +117 |
| `feature/courseware/CoursewareScreen.kt` | 960 | 978 | −18 |
| `feature/course/CourseScheduleScreenModelTest.kt` | 933 | — | 测试文件，健康 |
| `feature/course/CourseScheduleScreenModel.kt` | 741 | — | 模型变厚 |
| `feature/phyvlab/PhyVlabScreenModel.kt` | 740 | — | |
| `data/homework/HomeworkRemoteDataSource.kt` | 710 | — | |
| `feature/classroom/ClassroomScreen.kt` | 694 | — | |

壳层拆分后的邻居（均在 `feature/shell/`，合计约 6500 行）：`CompactAppTopBar` 666、`CompactBottomNavigation` 363、`MoreWorkspace` 377、`AuthenticatedSessionFactory` 334、`AppRoute` 323、`HomeSyncItems` 311、`AppSidebar` 283。P0 把错包问题解决了，但壳层总面积比拆之前更大——Liquid 原生导航、顶栏玻璃、同步胶囊都加在这里。

---

## 四、上轮高项对照

| 上轮问题 | 本轮状态 | 证据 |
|---|---|---|
| `GradeScreen.kt` 万物文件 + 壳层错位 | **已修保持** | 1213 行，包内是成绩页；`App.kt` 调 `feature.shell.AuthenticatedAppShell` |
| `LoginRoute` 内联 12+ Repository | **已修保持** | 装配在 `rememberAuthenticatedSession`（`AuthenticatedSessionFactory.kt:79`）；`LoginRoute` 仍约 531 行，管登录状态机 + `logout` 闭包 |
| `AuthenticatedAppShell` 1425 行 | **未拆，且涨到约 1786 行** | 上轮第九节已明确「内部 SectionDestination 抽离暂缓」。本轮确认暂缓决定被遵守，但文件继续膨胀 |
| `SectionDestination` 790 行局部函数 | **仍在，约 534 行**（1173–1707） | 比当时短一些（部分页面动作外提），仍捕获全部 feature model |
| `DestinationPage` | 约 268 行局部函数（905 起） | 顶栏/返回/刷新策略集中点 |
| `HomeAgendaSection` 438 行 | **约 415 行**（530–944） | 几乎没瘦 |
| println 无 release 开关 | **已修保持** | `AppLog` expect/actual；业务点走 `AppLog.d` |
| Live probe 混入 CI | **已修保持** | `BJTU_LIVE_PROBE=1` 才跑 |
| URL 分散 | **P3a 保持** | `SchoolEndpoints.kt` 集中 AA/CAS/PHYVLAB/教室；智慧平台 origin 仍在 `SmartPlatformEndpoint`，邮箱 MIS 入口仍在 `MailboxScreenModel.kt:23` |
| 双份 `jsonEscape` | **业务侧保持单一**；桌面日历桥接又写了一份 | `util/JsonEscape.kt` vs `DesktopSystemCalendarGateway.kt:103` |
| 5 对 Screen 加载态复制 | **P2b 部分保持** | `feature/common/WorkspaceStates.kt` 已抽加载/空态；周选择器、失败横幅、作业/课件会话仍各写各的 |

`logout` 仍留在 `LoginRoute`（`LoginScreen.kt:466-511`），上轮明确「搬移无净收益」——操作十几个局部状态。本轮同意维持。

教室人数评估故意 `createSchoolHttpTransport()` 新建客户端（`AuthenticatedSessionFactory.kt:191-194`），注释写明不带学校 Cookie。不是复制粘贴事故。

---

## 五、当前问题（按严重度）

### 【高】1. `AuthenticatedAppShell` 再次成为万物文件（2000 行）

一个函数里同时有：导航状态、12 个 feature 的同步编排、宽屏三栏 / 紧凑底栏 / iOS 原生 tab 三种壳、`SectionDestination`、`DestinationPage`、物理在线自动同步门、外链 https 升级。

**不是错包**（已经在 `feature/shell`），而是**单文件单函数超过可审阅上限**。Liquid 之后每次改顶栏/底栏/某个一级页的刷新胶囊都要进这 1800 行。

上轮暂缓理由（`SectionDestination` 参数化要 30+ 参数）仍然成立。本轮建议不要「为拆而拆」，只把已经独立的概念挪出主函数：

- `DestinationPage` → 已有 `CompactAppTopBar` 的文件或新文件；
- 宽屏 `Row` / 紧凑 `Box` 两套布局各一个文件；
- `SectionDestination` 的 `when (route)` 分支保持与壳状态的闭包，或改成 `AuthenticatedSession` 上的按段渲染接口（工作量大，单独立项）。

预估：只挪 `DestinationPage` + 两套布局骨架，1–2 人日，主函数可降到 ~900 行，不碰页面业务。

### 【中】2. 一级业务 Screen 继续在 1000–1700 行

课表 1736、邮箱 1614、首页 1612、作业 1443。模式重复，看懂一个约等于看懂全部，但每次加筛选/颜色/横滑都在原文件追加。P2b 剩余项（周选择器、失败横幅）上轮已判定结构已分化，本轮仍同意缓行，除非某屏再涨 300 行以上。

### 【中】3. `LoginRoute` 仍是 531 行状态机

DI 已抽出，剩下的是登录 UX：静默入场、验证码放弃即登出、清理失败文案、原生 tab 占位。再拆收益有限。不要和壳层拆分绑在同一 PR。

### 【中】4. `CourseScheduleScreenModel` / `PhyVlabScreenModel` 超过 700 行

业务规则开始从 Screen 渗进 Model。比 UI 巨型函数好维护，但课表周跟随 / 校历空档已经出过真实 bug（`413a47a`）。继续加逻辑时优先抽纯函数 + 单测，而不是再往 Composable 里塞。

### 【低】5. 桌面日历 `jsonEscape` 重复

`DesktopSystemCalendarGateway.kt:103` 应改 import `shared.util.jsonEscape`。半小时。

### 【低】6. 邮箱 / 智慧平台 origin 未进 `SchoolEndpoints`

`MailboxScreenModel.kt:23` 的 MIS module 26、`SmartPlatformEndpoint` 的明文 origin 仍分散。学校改域名时不止一处。P3a 当时有意把智慧平台白名单留在安全模块——保持，但 MIS 邮箱 URL 可以进 `SchoolEndpoints`。

### 【低】7. 测试比主代码略降

563 个 `@Test` 仍是资产。壳层 2000 行几乎没有直接 UI 测试（有 `NativeShellBridgeTest`、设置/成绩/课表模型测）。不要求 Compose UI 测试铺开；改导航时至少保住现有 desktopTest。

---

## 六、做得好的（保持）

1. 分层名字稳定：`Screen` / `ScreenModel` / `Repository` / `RemoteDataSource` / `LocalDataSource`。
2. 依赖目录一份 toml，无 Gson/Moshi/org.json 回潮。
3. 调试日志可关，Live 探针有环境开关，模型 `toString` 脱敏。
4. 领域层仍有真实规则（成绩排序、校历周槽、作业筛选偏好），不是空壳。
5. P0 拆包没有在后续 Liquid / 邮箱 / 物理在线里被塞回去——`GradeScreen` 没有重新吸收壳层。

---

## 七、建议（进入 audit 分支时）

安全修复优先于味道（见同日安全报告）。味道只做小而可验证的：

1. **可选 P0 瘦身**：`DestinationPage` 与两种窗口布局移出 `AuthenticatedAppShell` 主函数；不参数化 `SectionDestination`。
2. **半小时**：日历桥接改用共享 `jsonEscape`。
3. **不要**在本轮重做五对 Screen 去重、不要为注释率加班、不要把 `logout` 搬出 `LoginRoute`。

若目标是给上游看「我们的工程是否可接手」：数据层、测试、安全边界比巨型 UI 文件更有说服力。巨型壳层要在 Issue 里诚实写上，并给出「先拆布局、后拆路由表」的路线，而不是假装已经整洁。
