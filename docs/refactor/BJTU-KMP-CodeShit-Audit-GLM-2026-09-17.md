# BJTUselfService-KMP-Refreshed 代码屎山审计报告

> 审计日期：2026-09-17 · 审计对象：`multiplatform/`（活跃开发）为主，根目录 `app/`（冻结安卓版）不在重构范围内
> 方法：静态扫描（行数/函数长度/嵌套深度/重复块/注释率/硬编码）+ 人工抽查关键文件 + git 历史分析

---

## 一、TL;DR

| 维度 | 评分（10 分制） | 一句话结论 |
|---|---|---|
| **整体质量** | **6.8 / 10** | 数据/领域层是有纪律的工程（8.5），UI 层是 vibe coding 重灾区（4.5），测试和文档纪律拉高了下限 |
| 时间维度 | — | 5.25 万行代码 17 个活跃日产出（2026-08-03 起），典型 AI 辅助节奏；理解成本低、重构成本中等 |
| 精简程度 | 6 / 10 | 依赖极简（0 个无用库），但 5 对 Screen 存在结构性复制粘贴 |
| 屎山程度 | 5.5 / 10 | 命名/注释/安全纪律好；但巨型文件 + 巨型函数 + 职责错位构成主要技术债 |

**一句话：这不是一座屎山，而是半座——数据层和领域层干净得不像 vibe coding 产物，问题高度集中在 feature UI 层的 6 个巨型文件里。**

---

## 二、代码量与结构概览

| 模块 | 文件数 | 行数 | 说明 |
|---|---|---|---|
| `shared/commonMain` | 126 | 33,669 | 核心业务 + 共享 UI |
| `shared/commonTest` | 89 | 12,352 | 474 个 `@Test`（测试/主代码比 37%） |
| `shared/iosMain` | 17 | 1,728 | 平台实现 |
| `shared/desktopMain` | 14 | 948 | 平台实现 |
| `shared/androidMain` | 12 | 543 | 平台实现 |
| `windowsApp` / `androidApp` / `desktopApp` | 16 | 2,293 | 宿主壳 |
| **合计** | **283** | **52,525** | — |

注释率：主代码 5.1%（低，但关键处注释质量高，多为解释"为什么"的中文注释）。

---

## 三、维度一：时间成本

### 3.1 开发投入（实际发生）

git 历史：KMP 重写共 66 commits / **17 个活跃提交日**（2026-08-03 → 2026-09-17，约 6.5 周日历时间），产出 5.25 万行。

- 平均 **≈3,000 行/活跃日** —— 人工开发者有效代码率约 50–150 行/人日，此速率只可能是 AI 生成 + 人工审核的模式。
- 换算为传统开发等价工作量：约 **350–1,000 人日（1.5–4.5 人年）**，被压缩到 6 周内完成。

### 3.2 各模块理解成本（新人上手）

| 模块 | 规模 | 理解成本 | 备注 |
|---|---|---|---|
| 架构总览 | — | 0.5 天 | 分层清晰 + CLAUDE.md/goal.md/memory.md 四件套文档极好 |
| `data/*`（各数据源） | ~7.7k 行 | 每模块 1–2 小时 | 模式统一：RemoteDataSource + fixture 测试 |
| `domain/*` | ~1.4k 行 | 合计 1 小时 | 纯业务规则，最干净 |
| `auth` + LoginScreen | ~2.5k 行 | 0.5–1 天 | 登录协议 + 状态机复杂但注释到位 |
| `feature/grade`（含 AppShell） | 4,369 行 | **1–2 天** | ⚠️ 需要啃 3811 行单文件 |
| 其他 feature UI | ~11k 行 | 每模块 2–4 小时 | 模式重复，看懂一个约等于看懂全部 |

### 3.3 维护与重构成本

| 操作 | 成本 | 原因 |
|---|---|---|
| 修改数据解析逻辑 | 低 | fixture 测试保护，474 个 @Test |
| 修改业务规则 | 低 | domain 层独立 + GradeRulesTest 等 |
| 修改 UI 布局/交互 | 中→高 | 巨型 Composable 内多状态交织，局部改动易牵连 |
| 新增 feature | 中 | 可照抄既有模式，但会加剧样板重复 |
| **重构 AppShell（P0）** | 2–3 人日 | 见改进建议 #1 |
| **DI 装配抽离（P1）** | 1–2 人日 | 见改进建议 #2 |
| UI 样板去重（P2） | 2–3 人日 | 见改进建议 #3 |
| 全部技术债清偿 | ~1.5–2 周 | — |

---

## 四、维度二：精简程度

### 做得好的 ✅

1. **依赖零冗余**：commonMain 仅 11 个依赖全部在用；无 Gson/Moshi/org.json 混用（符合 CLAUDE.md 收敛规则）；平台层各 driver 按需分配。
2. **TODO/FIXME 计数：0**（罕见地干净）。
3. **数据层函数短小**：如 `isServerRejectionMessage` 5 行、`nextSemesterLabel` 10 行，逻辑收敛。
4. **测试/主代码比 37%**：12.3k 行测试，对 vibe coding 项目是稀缺资产。

### 问题 ❌

**P2-重复代码（跨文件 12 行级重复块统计）**

| 重复对 | 块数 | 性质 |
|---|---|---|
| `ExamScheduleScreen` ↔ `HomeworkScreen` | 30 | 加载态/失败横幅/列表骨架样板复制 |
| `CoursewareRemoteDataSource` ↔ `HomeworkRemoteDataSource` | 26 | 智慧平台登录、会话、下载逻辑两处实现 |
| `ClassroomOccupancyScreen` ↔ `CourseScheduleScreen` | 24 | 教学楼/周选择器 UI 重复 |
| `ClassroomOccupancyScreen` ↔ `GradeScreen` | 12 | 同上 |
| `ClassroomScreen` ↔ `ClassroomOccupancyScreen` | 10 | 同上 |

估算：跨文件结构性重复约 **800–1,200 行**，可由共享 UI 组件与共享"智慧平台会话客户端"消除 60% 以上。

**P2-工具函数多处手写**：`jsonEscape()` 在 `HomeworkRemoteDataSource.kt:685` 与 `CoursewareCacheCodec.kt:178` 各有一份私有实现；手写字符串拼接构造 JSON 而非用 kotlinx.serialization。

**P2-巨型文件（前 9 名）**

| 文件 | 行数 |
|---|---|
| `feature/grade/GradeScreen.kt` | **3,811** ⚠️ |
| `feature/mailbox/MailboxScreen.kt` | 1,609 |
| `feature/course/CourseScheduleScreen.kt` | 1,536 |
| `feature/home/HomeScreen.kt` | 1,486 |
| `feature/homework/HomeworkScreen.kt` | 1,397 |
| `LoginScreen.kt` | 1,325 |
| `feature/phyvlab/PhyVlabScreen.kt` | 1,120 |
| `feature/courseware/CoursewareScreen.kt` | 978 |
| `feature/classroomoccupancy/ClassroomOccupancyScreen.kt` | 968 |

**过度设计检查**：未发现明显过度设计。expect/actual 17/51 比例健康，`Unavailable*` 空实现模式克制。唯一接近过度设计的是 `AppSection`/`MoreGroupSections` 等导航枚举体系（可接受）。

---

## 五、维度三：屎山程度（技术债务）

### 做得好的 ✅

1. **命名一致**：`Screen/ScreenModel/Repository/RemoteDataSource/LocalDataSource` 模式贯穿全库；无 `Util2`、`NewManager` 之类的腐烂命名。
2. **无深嵌套地狱**：最深花括号 12 层出现在 `CourseScheduleScreen.kt`，属 Compose UI DSL 堆叠，逻辑层嵌套正常（≤6）。
3. **无安全红线违规**：未发现"信任所有证书"；错误日志注释明确脱敏策略；`SmartPlatformEndpoint` 有白名单与恶意域测试。
4. **domain 层有真实业务抽取**：`GradeRules`（342 行 + 12 测试）、`AcademicCalendarExport` 等，非摆设。

### 问题 ❌（按严重度）

#### 【高】1. `GradeScreen.kt` 是"万物文件"（3,811 行）

一个放在 `feature/grade` 包的文件里塞了：

- **应用级导航壳层**：`AppRoute`、`AppSection`、`bottomNavSections`、5 个 Detail Route（L312–385）
- **主壳 Composable** `AuthenticatedAppShell`（L387，**单函数 1,425 行**，耦合全部 12 个 feature model）
- **多平台导航决策**：`SectionDestination`（L1022，790 行）、`toAppRoute`、`shouldOpenNativeSectionRoute`
- **侧栏/顶栏/底栏/更多页**：`AppSidebar`、`CompactAppTopBar`、`TopBar*Capsule`×5、`CompactBottomNavigation`、`MoreWorkspace` 等 15+ 个壳层组件
- **home 逻辑泄漏进 grade**：`classroomIdleStatusText`、`buildHomeSyncItems`（L192–311）
- 成绩页本体：`GradeWorkspace`、`GradeFilterSheet`（280 行）、`GradeList` 等

**位置错误的铁证**：`App.kt:113` 被迫用全限定名 `team.bjtuss...feature.grade.AuthenticatedAppShell` 调用——壳层放错包，调用方连 import 都绕着走。

#### 【高】2. `LoginRoute` 内联整个 DI 装配（680 行）

`LoginScreen.kt:150` 起：Composable 内手动 `remember(...) { DefaultXxxRepository(...) }` 装配 12+ 个 Repository/ScreenModel（L505–544+），同文件内还定义了操作 15 个可变状态的局部 `fun logout`（L444，386 行段）。**组合根逻辑内嵌在 UI 渲染路径里**——渲染、装配、业务、对话框全在一个函数。

#### 【高】3. 巨型 Composable 清单（≥150 行的 9 个 UI 函数）

`AuthenticatedAppShell` 1425 · `SectionDestination` 790 · `LoginRoute` 680 · `HomeAgendaSection` 438 · `CourseScheduleWorkspace` 292 · `GradeFilterSheet` 280 · `CoursewareWorkspace` 253 · `PhyVlabWorkspace` 235 · `HomeWorkspace` 213。Compose 惯例上限约 100 行，这些函数内部状态交织，改动回归风险高。

#### 【中】4. 调试 println 直接进主代码，无 release 开关

- `data/phyvlab/PhyVlabDebug.kt:10`（println 包装成 `phyVlabDebug`，有脱敏纪律但**不可关闭**）
- `network/KtorSchoolHttpTransport.kt:153`（请求失败 println，同样无开关）
- `data/homework/HomeworkRemoteDataSource.kt:261/311/330`
- `desktopApp/Main.kt:44`

**违反自家 CLAUDE.md 的红线**："调试日志必须可在 release 中关闭"。

#### 【中】5. 连真实服务的探针测试混入常规测试

`desktopTest/.../diagnostics/LiveCourseScheduleProbeTest.kt`（连真实教务服务的 Live 探测，大量 println）。诊断工具与回归测试未隔离，CI 无网络时会失败或产生噪声。

#### 【中】6. 硬编码 URL 分散在各数据源

`SchoolLoginProtocol.kt` 已集中一部分，但 `CourseScheduleRemoteDataSource.kt:12–21`、`GradeRemoteDataSource.kt:11–13`、`ExamScheduleRemoteDataSource.kt:11`、`ClassroomOccupancyRemoteDataSource.kt:15–17`、`MailboxRemoteDataSource.kt:19–20` 各自定义端点常量。学校域名变更时需改 6+ 个文件。

#### 【低】7. 手写 JSON 拼接

`HomeworkRemoteDataSource.kt:675–703`：字符串拼接构造 JSON + 手写 `jsonEscape`（两处重复）。可精确控制格式但脆弱。

#### 【低】8. 注释率 5.1%

UI 巨型函数内部几乎无注释；数据层关键决策有高质量注释（如 `CourseScheduleUiState.weekResolved` 的状态语义文档），分布不均。

---

## 六、按模块问题汇总表

| 模块 | 问题 | 位置 | 严重度 |
|---|---|---|---|
| feature/grade | 万物文件 + 壳层错位 + 1425 行函数 | `GradeScreen.kt`（全文件） | **高** |
| login | DI 内联 + 680 行函数 + logout 闭包 | `LoginScreen.kt:150–830` | **高** |
| feature/home | 438 行 `HomeAgendaSection` | `HomeScreen.kt:449` | 中 |
| feature/course | 巨型文件 1536 行 + 嵌套 12 层 | `CourseScheduleScreen.kt` | 中 |
| feature/exam↔homework | 30 块结构性重复 | `ExamScheduleScreen.kt` / `HomeworkScreen.kt` | 中 |
| data/homework↔courseware | 26 块重复（智慧平台会话/下载） | `HomeworkRemoteDataSource.kt` / `CoursewareRemoteDataSource.kt` | 中 |
| data/phyvlab | 不可关闭的调试输出 | `PhyVlabDebug.kt:10` | 中 |
| network | 错误日志无 release 开关 | `KtorSchoolHttpTransport.kt:153` | 中 |
| desktopTest | Live 探针混入常规测试 | `diagnostics/LiveCourseScheduleProbeTest.kt` | 中 |
| 多处 data | URL 端点分散定义 | 见五.6 列表 | 中 |
| feature/classroomoccupancy↔course | 24 块 UI 重复 | 两个 Screen | 中 |
| data/homework | 手写 JSON/重复 jsonEscape | `HomeworkRemoteDataSource.kt:675–703` | 低 |
| 全库 | 注释率 5.1% | UI 层为主 | 低 |

---

## 七、优先改进建议（按 ROI 排序）

1. **【P0·2–3 人日】拆解 `GradeScreen.kt`**
   把 `AuthenticatedAppShell`/`SectionDestination`/`AppSidebar`/`CompactAppTopBar`/`CompactBottomNavigation`/`MoreWorkspace` 及 AppRoute 体系迁到 `feature/shell`（该包已存在，只有 4 个文件），`GradeScreen.kt` 只留成绩页本体。壳层 Composable 再按顶栏/侧栏/底栏拆文件，`AuthenticatedAppShell` 拆到 ≤300 行。**这是唯一动一发牵全身的文件，先做它。**

2. **【P1·1–2 人日】DI 装配移出 `LoginRoute`**
   `AuthenticatedSession` 已存在——把 12+ 个 Repository/Model 的 `remember(...) {}` 装配块抽成独立工厂（如 `AuthenticatedSessionFactory`），LoginScreen 只消费。`logout` 移入 coordinator。

3. **【P2·2–3 人日】抽共享 UI 组件，消 5 对 Screen 重复**
   至少抽：同步状态胶囊（`TopBar*Capsule` 系）、失败横幅（`GradeFailureBanner` 及各屏同款）、加载/空态、教学楼/周选择器。预计净删 800+ 行。

4. **【P2·1 人日】日志系统化**
   `println` → `expect fun appLog()` + 平台 actual（Android Log / os_log / stdout 可配置），release 关闭。同时清掉 `LiveCourseScheduleProbeTest` 的常规性（移独立 sourceSet 或加开关）。

5. **【P3·0.5 人日】端点集中化**
   各 RemoteDataSource 的 URL 常量收敛进 `SchoolLoginProtocol.kt` 风格的单一 `SchoolEndpoints`。

6. **【P3·0.5 人日】`jsonEscape` 等工具去重**
   收敛到 util 包单一定义；长期用 kotlinx.serialization 替代手写拼接。

**总投入约 7–10 人日**，可把整体评分从 6.8 推到 8+；其中 P0+P1（4 个工作日）解决全部"高"级债务。

---

## 八、结语

这个仓库展示了一种典型的**"纪律化的 vibe coding"**：CLAUDE.md 的架构红线（分层、expect/actual、依赖收敛、脱敏、fixture 测试）+ memory/goal/history 文档体系，让 AI 在数据层和领域层产出了接近人工工程质量（甚至更好——474 个测试、0 个 TODO）；但 AI 在 UI 层缺乏"文件该在哪里结束"的判断，堆出了 3811 行的万物文件和内联 DI。**债务集中、边界清晰、测试保护充分——这是最好的还债状态，趁 composable 还没继续膨胀，现在拆正当时。**

---

## 九、屎山修复里程碑（2026-09-18 起）

> 本节为修复进度跟踪，随修复推进持续更新。基线 commit：`82fe7a6`（安全修复收口后）。
> 验收纪律：每项修复需通过全量 `commonTest`（desktop 目标）+ Android debug 编译；P0/P1 涉及导航与登录 UI，另需 AVD 模拟器实机回归。全部改动先留在本地工作区，由用户决定提交节奏。

### 优先级与暂缓决策

按「高严重度债务优先、纯低价值重复缓行、注释率不专项投入」排序：

| 编号 | 事项 | 审计建议 | 本里程碑决策 | 理由 |
|---|---|---|---|---|
| **P0** | 拆解 `GradeScreen.kt`（3811 行万物文件）：壳层迁 `feature/shell` | P0 | **本轮立即做** | 唯一动一发牵全身的文件；越晚拆 composable 膨胀越重 |
| **P1** | DI 装配移出 `LoginRoute`（680 行函数） | P1 | **本轮做**（P0 验证通过后） | 高级债务第二项；拆完 P0 后壳层调用链清晰，顺势承接 |
| **P2a** | 日志系统化（println → 可关闭的 appLog + Live 探针隔离） | P2 | **本轮做** | 违反 CLAUDE.md 红线「调试日志必须可在 release 中关闭」，属于合规债非风格债 |
| **P2b** | 抽共享 UI 组件，消 5 对 Screen 重复（~800 行） | P2 | **暂缓** | 纯重复代码，风险低但工作量大（2–3 人日）；等 P0/P1 落地、签名发版节奏稳定后单独切片 |
| **P3a** | 端点集中化（`SchoolEndpoints`） | P3 | **暂缓** | 0.5 人日小活，但涉及 6+ 数据源改动面广，与 HTTP 白名单授权范围耦合，单独切片更安全 |
| **P3b** | `jsonEscape` 等工具去重 | P3 | **暂缓** | 低风险纯清理，可与 P3a 或 P2b 合并到后续清理切片 |
| — | 注释率 5.1% 提升 | 不建议专项 | **不做** | 拆 P0/P1 本身会带来结构自文档化；专项补注释 ROI 低 |

### 进度状态表

| 编号 | 状态 | 完成日期 | 验证证据 |
|---|---|---|---|
| P0 | **已完成**（`SectionDestination` 内部函数抽离暂缓，见下） | 2026-09-18 | `GradeScreen.kt` 3811→1174 行；壳层 7 文件迁入 `feature/shell/`；desktopTest 502/504 通过（2 例为 `PackagingCiAsciiConfigTest` 既有失败）；iOS sim + Android debug 编译通过；AVD（Pixel_10_Pro_XL, arm64）实机回归：首页/成绩/课程表/更多页 + 底栏导航 + 「已同步/登录中」胶囊全部正常 |
| P1 | **已完成** | 2026-09-18 | `LoginRoute` 内联装配块（约 210 行）抽为 `feature/shell/AuthenticatedSessionFactory.kt` 的 `rememberAuthenticatedSession`；`LoginScreen.kt` 1325→1138 行；desktopTest + 三端编译同上；AVD 实机验证：冷启动静默自动登录（缓存档案入场 +「登录中」胶囊）→ 主界面四 tab → 设置页 → 「退出并清除登录信息」→ 回登录页表单清空；**未验证边界**：退出后再手动登录路径（entryLoggingIn=false）未实机复测（凭据填写受工具链限制），该路径代码为原装配逻辑原样搬移 |
| P2a | **已完成** | 2026-09-18 | 新增 `logging/AppLog` expect/actual（Android Log+宿主 BuildConfig.DEBUG 开关 / iOS isDebugBinary / desktop stdout）；替换 PhyVlabDebug、KtorSchoolHttpTransport、HomeworkRemoteDataSource×3 的 println；Live 探针测试核实已有 `BJTU_LIVE_PROBE=1` 环境开关（默认跳过），无需改动；AVD logcat 实测 `[phyvlab] refresh start` 经 AppLog 输出正常 |
| P2b | **部分完成**（2026-09-18 实机验收后启动） | 2026-09-18 | 加载/空态去重：新建 `feature/common/WorkspaceStates.kt`（`WorkspaceLoadingState`/`WorkspaceEmptyState`），Grade/Exam/Homework/Course/Courseware 5 屏 10 个同款 Composable 改为委托（净删约 120 行，各屏包装函数保留、调用点不动）。**其余暂缓**：周选择器两屏结构已分化（sheet vs pager）；智慧平台会话基础 `SmartPlatformEndpoint` 已是共享文件（审计 26 块已部分腐化）；`SectionDestination` 抽离维持原判断（30+ 参数）。desktopTest 502/504 + 三端编译通过；AVD 冒烟（成绩/作业空态文案路径未专项截图，靠编译与既有 UI 测试覆盖） |
| P3a | **已完成** | 2026-09-18 | 新建 `network/SchoolEndpoints.kt` 集中 AA_ORIGIN/AA_ROOT/AA_HOME_URL/CAS_ORIGIN/SMART_MODULE_URL/PHYVLAB_ORIGIN/ROOM_VIEW_URL；12 个文件删本地常量改引用。**踩坑记录**：`"$SchoolEndpoints.X"` 字符串模板解析为对象 toString，必须写 `${SchoolEndpoints.X}`——曾引发 6 个测试失败（fixture URL 断言收到对象地址）与 const 编译错误，已全量修复。跨对象引用的 `const val` 需降级为 `val`。desktopTest 502/504 + 三端编译通过 |
| P3b | **已完成** | 2026-09-18 | 两份完全相同的 `jsonEscape()` 收敛到 `util/JsonEscape.kt`，HomeworkRemoteDataSource/CoursewareCacheCodec 改 import 引用。desktopTest 502/504 通过 |

### 遗留后续项（本轮未做，已明确边界）

- **`AuthenticatedAppShell` 本体仍约 1425 行**（所在文件 1617 行）：顶栏/侧栏/底栏/更多页已拆出为独立文件，但其内部的 `SectionDestination`（790 行局部 Composable）捕获壳层全部本地状态（12 个 feature model + 导航回调），参数化需 30+ 参数，强行抽离回归风险大于收益。建议与 P2b（共享 UI 组件抽取）合并为一个切片处理。
- `desktopApp/Main.kt:44` 的 println 为验证码模型 CLI 验证模式的功能性输出（供脚本解析），非调试日志，保留。

### 操作历史

- 2026-09-18：审计报告归档至 `docs/refactor/`，建立本里程碑（P0/P1/P2a 本轮执行，P2b/P3 暂缓，注释率不专项投入）。工作基线 `82fe7a6`。
- 2026-09-18：P0 完成。脚本化切分 `GradeScreen.kt`：壳层区域（AppRoute 体系/AuthenticatedAppShell/侧栏/顶栏/底栏/更多页/首页同步聚合）迁至 `feature/shell/` 7 个新文件（顶层 `private` 提升为 `internal`）；`App.kt`、`LoginScreen.kt` 引用改为 shell 包；导航测试补 shell import；`GradeWorkspace`/`GradeChangeNoticeDialog` 提升 internal 供壳层调用。AVD 回归时曾误启动冻结版旧应用（包名 `team.bjtuss.bjtuselfservice`），纠正为 KMP 包名 `team.bjtuss.bjtuselfservice.kmp` 后重新验证。
- 2026-09-18：P1 完成。新增 `AuthenticatedSessionFactory.kt`（`rememberAuthenticatedSession`，含原装配块全部 remember 依赖链）；关键决策：`sessionRecovery` 复用 `LoginRoute` 的同一 `SchoolLoginProtocol` 实例（新建实例会丢失登录会话状态），故工厂增加 `loginProtocol` 参数；`logout` 闭包仍留在 LoginRoute（操作 15 个局部状态，搬移无净收益），通过 `onLogout` 回调传入工厂。
- 2026-09-18：P2a 完成。Android 侧尝试在 shared 模块启用 buildConfig 失败（KMP android library 插件 DSL 不支持），改为 androidApp 宿主在 `MainActivity.onCreate` 按 `BuildConfig.DEBUG` 设置 `AppLog.enabled`；iOS `Platform.isDebugBinary` 需 `@OptIn(ExperimentalNativeApi::class)`。
- 2026-09-18：P0/P1/P2a 打包本地实机验证包（`~/Downloads/BJTUselfServiceKMP-1.7.6-KMP-local.dmg` + `BJTUSelfService-KMP-1.7.6-KMP-local-iOS-unsigned.ipa`，iOS Build 17）；用户实测 macOS/iOS 通过，基本标记实机通过。
- 2026-09-18：P3a/P3b/P2b(部分) 完成。P3a 收敛 12 个文件的端点常量到 `SchoolEndpoints`；P3b jsonEscape 去重；P2b 抽 `WorkspaceStates` 共享加载/空态。全部改动仍在本地工作区，等用户决定提交。
