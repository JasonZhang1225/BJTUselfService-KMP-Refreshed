# 设计规范遗留项整改清单

`DESIGN.md` 定稿时，现有代码里还有一批不符合规范的历史写法。它们目前能用，只是风格不统一，所以定为**遗留**：新代码不得照抄，旧代码按本清单分批整改。

- 盘点基线：`main` `68339b94`（2026-11-02），用 `rg` 扫描 `multiplatform/shared/src/commonMain`。行号会随后续提交漂移，动手前先重新 `rg` 一遍确认。
- 2026-11-02 恢复：本文原在 `docs/design-guidelines` 分支（快照 `c5bfb763`），随本地分支清理一度丢失，现归档至 `docs/archive/plans/`，并按 `main` 更新状态。P1-1、P1-2 已随 PR #92 合入 `main`；P2-1 的课件、回放列表、回放播放与下载、邮箱已随 PR #95 合入 `main`。
- 每一批单独开 `fix/…` 或 `chore/…` 分支和 PR，不和功能开发混在一起；一个 PR 只做一类整改，便于回归和回退。
- 整改按 `DESIGN.md` 执行，PR 描述回答 `DESIGN.md` 第 14 节自查清单，并附浅色/深色、文字放大的前后对比截图（截图避开个人信息）。
- 完成一项就在本文勾掉，写上 PR 号；全部完成后把本文件的结论移入 `history_full.md`。
- 标为“**需用户决定**”的项，先把选项和截图给用户，用户拍板前不改。

优先级：**P1** 用户直接能感知到的平台不一致；**P2** 影响一致性、改动面中等；**P3** 视觉细节统一，量大、风险低。

---

## P1-1 弹窗统一：确认类转系统警告框，内容类走原生 sheet（已完成，PR #92 合入 `main`）

新增 `AppleAlert`（`feature/shell/AppleAlert.kt`）：iOS 由宿主弹出系统 `UIAlertController`（`iosMain/.../IosNativeAlertPresentation.kt`，iOS 26 起液态玻璃外观），Android/桌面回退为 Material `AlertDialog`。`DESIGN.md` 第 5 节改为“先选类型”：一句话的确认/警告/结果用 `AppleAlert`，有内容的用 sheet。

**原先直接调用 `AlertDialog` 的 5 处**

| 状态 | 位置 | 改为 |
| --- | --- | --- |
| [x] | `feature/settings/IndependentAccountSettings.kt` 清除独立账号配置 | `AppleAlert`，“清除”为 `Destructive`（iOS 红色） |
| [x] | `feature/courseware/CoursewareScreen.kt` 取消目录导出 | `AppleAlert` 三个按钮：保留已完成文件 / 舍弃全部文件（`Destructive`）/ 继续下载（`Cancel`） |
| [x] | `feature/home/LiveActivityButton.kt` 实时动态通知权限说明（仅 Android 出现） | `AppleAlert`，Android 外观不变 |
| [x] | `feature/phyvlab/PhyVlabScreen.kt` 最终提交核对 | 有文件清单和勾选项，改 `AppleSheetOrAlert(fitContentHeight)`：iOS 原生 sheet，勾满才可点“确认最终提交”，提交中不能关闭 |
| [x] | `feature/shell/ReleaseGuideDialog.kt` 启动版本说明 | 多段说明 + 3 秒倒计时，改 `AppleSheetOrAlert(dismissible = false)`：iOS 原生 sheet 且不能下滑关闭，Android 仍是不可点外部关闭的对话框 |

**原先走 `AppleSheetOrAlert`、在 iOS 上显示成 sheet 的确认/提示，转为 `AppleAlert`**

| 状态 | 位置 | 内容 |
| --- | --- | --- |
| [x] | `SettingsScreen.kt` | 清除当前账号离线缓存？/ 清除全部本地数据？/ 卸载前清理？（均为 `Destructive`）、已是最新版本、检查更新失败 |
| [x] | `LibraryScreen.kt` | 确认续借（书名列表并入文字，最多 5 本） |
| [x] | `MailboxScreen.kt` | 确认发送 |
| [x] | `LoginScreen.kt` | 自动登录失败、登录信息未保存 |
| [x] | `CompactAppTopBar.kt` | 部分同步失败（重试 / 关闭） |
| [x] | `AuthenticatedAppShell.kt` | CITEL 同步状态（刷新 / 关闭） |

保留 sheet 的：发现新版本（带更新说明）、续借结果（应还日期与失败原因两段彩色列表）、校园卡/校园网充值（二维码）、请输入验证码、前往日期、首页同步状态。

顺带修正：`AppleSheetOrAlert` 的 Android/桌面回退对话框此前忽略 `confirmEnabled` / `dismissEnabled`，现在两端都生效（物理在线最终提交依赖它）；`AppleSheet` / `AppleSheetOrAlert` 新增 `dismissible`。

待验证：iOS 模拟器已看清缓存警告框、已是最新版本、课程详情与作业变动 sheet；其余警告框与 sheet 未逐个打开，Android/桌面回退未实机看。物理在线最终提交只能用 fixture，不做真实提交。

PR #92 Codex review 修正：物理在线最终提交核对 sheet 曾误传 `showDismissButton = false`，iOS 上“返回检查”消失；已恢复，规则写入 `DESIGN.md` 5.3（`showDismissButton = false` 会去掉含 `dismissLabel` 在内的全部关闭入口，禁用用 `dismissEnabled`）。

**追加：iOS sheet 一律测高、底部不截断**（同一分支）

- `AppleSheet` 在 iOS 上对所有 `scrollableBody = false` 的正文按内容测高，不再开固定半屏/全屏；新增 `Modifier.sheetBodyScroll(state)`，测高时由 sheet 统一滚动。课程详情（首页×3、课表）、成绩详情、作业变动、作业上传、课程回放详情、课件下载详情、教室占用“学期与教学周”改为测高。
- 仍为全高的例外：~~选课列表、回放选课列表~~（已由 P2-1 改为下拉选单，sheet 删除）、附件预览、CITEL 作业详情、物理作业详情（整页工作区，原 680dp 限高会在底边上方截断，已改为铺满）、发现新版本。全高 sheet 经 `LocalBottomBarClearance` 把安全区放进滚动内容末尾；Android/桌面回退里该值分别置为系统底栏 inset 与 0，避免页面的底栏净空串进弹层。

---

## P1-2 成绩页课程性质色改用 `CourseCategoryPalette`（已完成，PR #92 合入 `main`；限选色未目视）

- [x] `feature/grade/GradeTypeColors.kt` 的 `courseTypeColors()` 改为直接转调 `courseCategoryColors()`，删除重复色值。
- [x] 限选色按用户决定统一为调色板的玫红（`#F4D7E6` / `#9C3F70`，深色 `#48243A` / `#F0B2D2`）；成绩页原琥珀色移除，课表、首页、小组件不变。

---

## P2-1 未接入液态玻璃顶栏的页面

`DESIGN.md` 第 3 节：新页面必须 `scrollUnderTopBar = true`。以下页面是在规范之前写的（位置均在 `feature/shell/AuthenticatedAppShell.kt` 的 `SectionDestination`）：

| 状态 | 页面 | 现状 | 整改 |
| --- | --- | --- | --- |
| [x] | 邮箱一级页 `AppSection.MAILBOX`（已完成，PR #95 合入 `main`） | 原先未开 `scrollUnderTopBar`，紧凑端用“切换”文字按钮 + Material 下拉切换文件夹 | 已开 `scrollUnderTopBar`。紧凑端邮件列表改为 `TopScrollLazyColumn`：文件夹下拉选单（`AppleMenuPickerCard`，副标题为未读数）、“共 N 封”、失败横幅、邮件和“加载更多”都是列表项；原“切换”文字按钮 + Material 下拉删除。宽屏三栏、加载/会话失效/内嵌写信只补顶部净空，宽屏未实机回归。 |
| [x] | 回放播放与下载 `PlaybackDetailRoute`（已完成，PR #95 合入 `main`） | 原先未开 `scrollUnderTopBar`，正文未用 `TopScrollColumn` | 已开 `scrollUnderTopBar`，正文改用 `TopScrollColumn` 并消费顶/底净空，画面预览滚动时穿到玻璃栏后（iOS 模拟器已看）。该页内的“下载任务”视图只补顶部净空，未接玻璃。 |

以下页面现在用 `staticTopBar = true`（内容滚不进栏后）。`DESIGN.md` 要求优先把固定头改成列表首项，逐个评估能否改：

| 状态 | 页面 | 评估方向 |
| --- | --- | --- |
| [ ] | 课程表 `AppSection.SCHEDULE` | 色块概览是不可滚动的全览表格，保留现状；按日列表视图评估能否接入玻璃。 |
| [x] | 课件 `AppSection.COURSEWARE`、回放列表 `AppSection.PLAYBACK`（已完成，PR #95 合入 `main`） | 两页改为 `scrollUnderTopBar = true`。紧凑端与更新日志同一层级：横幅、课程下拉选单（`AppleMenuPickerCard`，`DESIGN.md` 6.1）、目录/课节标题和列表都在同一个 `TopScrollLazyColumn` 里；“点击此处选择课程”按钮与选课 sheet 删除；回放默认选第一门课。宽屏仍是侧栏列表，只补了顶部净空。剩余直接 `LazyColumn`：`CoursewareScreen.kt` 3 处（未接入页面的 `CoursewareCoursePickerList` 探针、宽屏两栏）、`PlaybackWorkspace.kt` 3 处（宽屏课程/课节栏、下载任务页），都不是紧凑端主滚动。下载任务页仍不随顶栏玻璃，待评估。回放默认选课曾因 `LaunchedEffect` 以 `courseId` 为键、`showCourse` 先改 `courseId` 而取消自身请求，停在“正在读取课节…”，已改为在页面 scope 启动。 |
| [ ] | 教室详情、教室占用详情（选中教学楼后） | 节次/日期选择头较高，改成首项后注意横屏矮视口。 |

验收：iOS 模拟器确认顶部时栏透明、上滑出现玻璃、短列表回弹时也有玻璃、顶栏下没有纯色带；Android/桌面无回归。

---

## P2-2 页面里硬编码的语义颜色

`DESIGN.md` 第 9 节：页面里不写 `Color(0xFF…)` 表达通用语义。以下颜色都有明确语义，但散落在各页面、各自判断深浅色（`background.luminance() < 0.5f`），也不响应“增强对比度”：

| 状态 | 位置 | 语义 |
| --- | --- | --- |
| [ ] | `feature/assignment/AssignmentCardHeader.kt:44-46` | 作业来源色（课程平台 / 物理在线 / CITEL） |
| [ ] | `feature/phyvlab/PhyVlabScreen.kt:412-427` `PhyVlabStatusPalette` | 截止状态色（已提交 / 逾期提交 / 临近截止 / 已逾期） |
| [ ] | `feature/home/HomeScreen.kt:1292-1320`、`1885-1895` | 首页日程标记色（作业日、待交、已交、即将开始）、利好变化绿 |
| [ ] | `feature/shell/MoreWorkspace.kt:142-143` 等 10 处 | 应用页磁贴图标色（校园卡、校园网等） |
| [ ] | `feature/classroomoccupancy/ClassroomOccupancyScreen.kt:998` | 教室空闲色 |
| [ ] | `LoginScreen.kt:1147-1149` | 登录输入框底色、文字、描边（用 `isSystemInDarkTheme()`，不跟随应用内主题设置） |

整改方向：新建一个集中的语义色板（与 `CourseCategoryPalette` 同级，浅/深两套，支持增强对比度），各页改为引用；色值本身先保持不变，只搬位置，避免顺带改观感。登录页改为取 `MaterialTheme.colorScheme`，确认与系统深色设置不一致时的表现（**需用户确认**登录页是否有意跟随系统而非应用主题）。品牌图标（`RepositoryBrandIcons.kt`）的品牌色属于合理例外，不改。

---

## P3-1 不在规范档位内的圆角

`DESIGN.md` 第 8 节档位：卡片 14–16.dp，小块/行 10–12.dp，胶囊 50% / 999.dp。下列值不在档位内：

| 值 | 位置 | 建议 |
| --- | --- | --- |
| 17.dp | `AggregatedAssignmentScreen.kt:137`、`AssignmentIgnoreUi.kt:85`、`AssignmentUi.kt:52`、`ExamScheduleScreen.kt:500`、`LibraryScreen.kt:283,447,536,788` | 卡片，改 16.dp |
| 18.dp | `ChangelogWorkspace.kt:83`、`ClassroomOccupancyScreen.kt:840`、`CourseScheduleScreen.kt:628`、`ExamScheduleScreen.kt:281`、`GradeScreen.kt:377,943`、`HomeworkScreen.kt:613`、`MailboxScreen.kt:593`、`CompactAppTopBar.kt:228`（返回按钮按压区） | 卡片改 16.dp；返回按钮按压区改圆形或 16.dp |
| 22.dp | `AggregatedAssignmentScreen.kt:212`、`CourseScheduleScreen.kt:1921`、`ExamScheduleScreen.kt:554`、`GradeScreen.kt:1020`、`HomeworkScreen.kt:1035` | 先看是大卡片还是胶囊：胶囊改 `percent = 50`，大卡片改 16.dp |
| 20 / 24.dp | `MoreWorkspace.kt:150`（磁贴）、`AppSidebar.kt:200`（侧栏选中态） | 核对用途后归档：磁贴按卡片 16.dp，选中态若为胶囊改 `percent = 50` |
| 11 / 13.dp | `ClassroomOccupancyScreen.kt:897`、`CourseScheduleScreen.kt:1136,1678`、`MailAttachmentUi.kt:217`、`MailboxScreen.kt:687,925,1220` | 小块，改 12.dp |
| 9.dp | `MailAttachmentUi.kt:251` | 改 10.dp |
| 6–8.dp | `ClassroomOccupancyScreen.kt:974,1117,1160`、`CourseScheduleScreen.kt:1016,1646`、`GradeScreen.kt:977`、`HomeScreen.kt:1885,1924`、`AssignmentFilterControls.kt:191` | 多为课表格子、色条、徽标等极小元素，10.dp 反而太圆。**需用户决定**：是否在 `DESIGN.md` 增加“极小元素 6–8.dp”一档；确认前不改。 |

验收：每个文件改完对比前后截图，确认没有元素因圆角变化而显得突兀；与课表、成绩的离屏渲染测试一起跑 `:shared:desktopTest`。

---

## P3-2 不在规范档位内的间距

`DESIGN.md` 第 8 节档位：4 / 6 / 8 / 10 / 12 / 14 / 16 dp。下列 `spacedBy` 不在档位内（只统计元素间距，图标尺寸、1.dp 描边不在此列）：

| 值 | 位置 | 建议 |
| --- | --- | --- |
| 3.dp | `ChangelogWorkspace.kt:105`、`ClassroomOccupancyScreen.kt:969`、`CourseScheduleScreen.kt:1640,1641,1895`、`HomeworkScreen.kt:1358,1383`、`MailboxScreen.kt:632,1006`、`PhyVlabScreen.kt:860` | 多为标题与副标题的紧密行距，改 2 或 4.dp；课表格子内可能是为了塞下文字，先截图确认 |
| 5.dp | `AssignmentIgnoreUi.kt:144`、`CourseScheduleScreen.kt:1555`、`ExamScheduleScreen.kt:511` | 改 4 或 6.dp |
| 7.dp | `CourseScheduleScreen.kt:656,1322,1849`、`CoursewareScreen.kt:424`、`PlaybackWorkspace.kt:366`（均为宽屏课程侧栏）、`MailAttachmentUi.kt:174`、`MailboxScreen.kt:1207`、`CompactAppTopBar.kt:314` | 改 6 或 8.dp |
| 9.dp | `MailboxScreen.kt:1215` | 改 8 或 10.dp |
| 18.dp | `AggregatedAssignmentScreen.kt:153`、`AssignmentFilters.kt:51`、`GradeScreen.kt:532`、`HomeworkScreen.kt:715,734`、`AuthenticatedAppShell.kt:2427` | 改 16.dp |

这一项纯视觉，量最大、收益最小，建议放在最后，或在改到对应文件时顺手处理（单独成提交）。

---

## 不属于整改范围

- `feature/shell/AppleSheet.kt` 内部的 `ModalBottomSheet` / `AlertDialog`：这是 `AppleSheet` 的非 iOS 回退实现，本来就该在这里。
- 小组件（`CourseScheduleWidget`、Android 桌面小组件）：受系统小组件规范约束，另行评估。
- `RepositoryBrandIcons.kt` 的品牌色。
