# M9 成绩课程性质（必修/限选/任选）切片实施计划

> 基线：M5 成绩切片已完成的 KMP 成绩链路  
> 修改范围：仅 `multiplatform/` 与迁移文档；冻结 Android 根工程保持只读  
> 状态：已实现；`:shared:desktopTest`（新增 20 个测试）与 `:androidApp:assembleDebug` 全绿；真实账号端到端核对（性质标签、排除任选后加权平均）待人工在桌面端验证  
> 前置协调：`GradeScreen.kt`、`LoginScreen.kt` 正被另一 session 修改，实施前先 rebase/对齐，本计划引用的行号以开工时为准

## 目标行为

1. 成绩刷新（手动下拉/同步按钮）时，除 `ln`、`lr` 成绩外，额外抓取培养方案页，得到"课程号 → 课程性质（必修/限选/任选）"映射。
2. 培养方案可能变动，映射**每次手动刷新都重新拉取、整体替换**，不做时间戳节流。
3. 方案抓取失败不阻塞成绩：成绩照常原子替换，映射保留上一次成功的旧数据；映射完全缺失时全部课程按"未知"处理，绝不误判为任选。
4. 成绩卡片可显示课程性质标签；自选课程模式支持按性质批量选择/排除（核心诉求：排除任选课再算加权平均）。
5. 解析层沿用既有安全约定：严格解析，响应正文不写入异常或日志。

## 真实页面验证结论（2026-08-04，真实账号 Chrome DevTools 实测）

- 成绩接口 `score/scores/stu/view/` 的 8 列表格为：序号/学年/课程/学分/成绩/**加分成绩**/上课教师/详细信息——**不含课程性质**；详情弹层只有分数构成与备注。类别信息必须来自培养方案页。
- 方案入口 `/training/training/program/`：表格内含 `a[href*="/training/training/program/stuview/"]` 链接（形如 `stuview/6449/`，id 因学生而异；辅修学生可能多条，需全部抓取合并）。
- 方案详情页有 4 张 table，课程表是含"课程性质"表头的那张（实测约 965 行、943 门课）。**行结构不规则**：平台/课组标题只出现在每组首行且带 colspan，单行 td 数有 8/9/10 三种，禁止按固定列下标解析。稳定规则：找文本匹配 `^[A-Z]\d{3}[A-Z0-9]{4,5}$` 的课程号单元格，其前一格是课程名、后一格是课程性质；性质值只接受 {必修, 限选, 任选}，表头行（td 写的"课程性质"）被此白名单自然过滤。实测同课号无性质冲突。
- 成绩表"课程"列开头即课程号（如 `C312009B 高级英语视听说 [04]`），与方案课程号同格式。实测 ln 13/13、lr 13/13 全部命中映射，lr 分布为必修 8/任选 3/限选 2。
- 英语认定（ctype=en）、留级库（ctype=rm）、课程替代、重修等场景的课可能不在方案中 → 映射查不到即为"未知"。

## 设计要点

### 数据流与降级

挂在 `DefaultGradeRepository.refresh()`（`GradeRepository.kt:85-118`）环节，与成绩共用同一 `SchoolHttpTransport` 会话（SESSION_EXPIRED 语义一致）：

1. `remote.fetchGrades()`（ln+lr）——失败则整体失败、保留旧成绩缓存（现有行为不变）。
2. `programRemote.fetchCourseTypes()`（列表页 → 全部 stuview 详情页）——失败仅记录，不阻塞第 3 步。
3. 成绩快照替换与映射替换合并进**同一事务**（`CacheStore.replaceGradeSnapshot` 扩展）；方案失败时该事务只替换成绩与选择记录，映射表不动。

成绩行**不加 courseType 列**。性质在读时 join：repository 的 `load()`/`refresh()` 返回成绩 + `Map<课程号, 性质>`；课程号从 `Grade.courseName` 开头用 `^[A-Z]\d{3}[A-Z0-9]{4,5}` 提取（`displayCourseName()` 本就已假定课号前缀，一致）。这样 `grade_cache`、`grade_selection_cache` 与全部选择匹配逻辑零改动。

### 新增与修改文件

#### 数据层（`shared/.../data/grade/`）

- 新增 `TrainingProgramHtmlParser.kt`：Ksoup 顶层纯函数 ×2 + sealed 结果，仿 `GradeHtmlParser.kt` 模式：
  - `parseProgramLinks(html): List<String>`——提取全部 `stuview/<id>/` href（去重）。
  - `parseProgramCourseTypes(html): Success/Failure`——按上述"课程号定位 + 性质白名单"规则产出 `Map<String, String>`（课号 → 必修/限选/任选）；找不到课程表 → `TABLE_MISSING`。
- 新增 `TrainingProgramRemoteDataSource.kt`：仿 `GradeRemoteDataSource.kt:36-72` 骨架——GET 列表页 → finalUrl 前缀检查 → 解析链接 → 逐个 GET 详情页（每个前 `delay`）→ 合并映射；失败枚举复用 NETWORK/SESSION_EXPIRED/MALFORMED 语义。
- 修改 `GradeRepository.kt`：`refresh()` 编入方案抓取与降级逻辑；`load()`/`refresh()` 返回结果携带性质映射（接口返回值扩展，或新增 `courseTypesById` 通道）；新增按性质排除选择的方法（见下）。

#### 存储层

- 修改 `Cache.sq`：新增
  ```sql
  CREATE TABLE program_course_type_cache (
      account_scope TEXT NOT NULL,
      course_id TEXT NOT NULL,
      course_type TEXT NOT NULL,
      PRIMARY KEY (account_scope, course_id)
  );
  ```
  及 select/insert/delete 查询；同步更新 `countAllRows`（`Cache.sq:96-104` 硬编码表清单）。
- 新增 `2.sqm`：仅 `CREATE TABLE program_course_type_cache ...`（纯增量，无 ALTER、无回填）。
- 修改 `CacheStore.kt`：加映射读/写方法；`replaceGradeSnapshot` 扩展为三表事务；**`clearAccount`（:305-315）与 `clearAll`（:317-327）必须加对应 delete**，防止退出登录残留。
- 修改 `GradeLocalDataSource` / `CacheStoreGradeLocalDataSource`：加映射读写接口。

#### domain 层

- 修改 `domain/grade/Grade.kt`：新增 `enum class CourseType { REQUIRED, LIMITED, ELECTIVE, UNKNOWN }`（DB 存中文原文，domain 用枚举，转换集中在 data 层）。
- 修改 `domain/grade/GradeRules.kt`：新增纯函数 `filterGradesByType(grades, typeByCode, excludedTypes)`；`gradesForCalculation` 组合学期 + 性质过滤。
- 修改 `domain/grade/GradeSelection.kt`：仿 `selectionRecordsExcludingSemesters`（:41-44）新增 `selectionRecordsExcludingTypes(...)`，实现"把某性质课程从已选记录中剔除"。

#### UI 层

- 修改 `GradeScreenModel.kt`：
  - `GradeUiState`（:24-54）加 `courseTypesByCode: Map<String, CourseType>` 与（可选）`excludedCourseTypes: Set<CourseType>`；派生属性给每门成绩解析性质。
  - 仿 `selectAllVisible`/`clearSelectedSemesters`（:175-197）新增 `selectAllByType(type)` / `deselectByType(type)`，走选择记录持久化通道。
- 修改 `GradeScreen.kt`：
  - `GradeSelectionActions`（:1054-1073）加一组性质 chips：必修/限选/任选（各带数量），点击在"全选该性质 / 取消该性质"间切换——此即"排除任选课算成绩"入口。
  - `GradeRow`/卡片角落显示性质小标签（未知不显示）。
  - 普通模式的性质筛选 chips 为可选范围，开工时按 UI 空间再定，不做也可交付核心价值。

#### 组装

- 修改 `LoginScreen.kt`：仿 `LoginScreen.kt:490-499` 的 `remember` 手工组装，给 grade repository 注入 `TrainingProgramRemoteDataSource(transport.value)`。

### 测试

- 解析器：内联 fixture 复现实测结构（8/9/10-td 行、colspan 组标题、任选/限选/必修、表头 td 行、无课程表页面）；错误对象断言不含正文。
- Remote：复用 `QueueTransport` fake（`GradeRemoteDataSourceTest.kt:53-64`），覆盖多 stuview 合并、列表页无链接、SESSION_EXPIRED 重定向。
- Repository：方案失败时成绩照常替换且旧映射保留；方案成功时三表同事务；映射缺失课程解析为 UNKNOWN。
- domain：性质过滤、按性质排除选择记录、与学期筛选组合。
- 迁移：`CacheStoreTest` 仿 `:94-122` 补 v2→v3 迁移用例，`Schema.version` 断言更新为 3。
- ScreenModel：性质 chips 选择/排除、刷新后选择不丢。

### 本切片明确修改 SQLDelight schema

`m5-grades-plan.md:59` 的"不改 schema"声明到此为止：本切片新增一张独立表（`2.sqm` 纯 CREATE TABLE），不改 `grade_cache`/`grade_selection_cache` 任何列，无数据回填风险。

## 验证

1. `:shared:desktopTest` 零失败，核对新增测试数。
2. `:androidApp:assembleDebug`、`:shared:compileKotlinIosSimulatorArm64`、`:desktopApp:createDistributable`。
3. 真实账号登录桌面端/iOS 模拟器，手动刷新成绩，确认：性质标签正确（对照教务方案页抽查）、排除任选后加权平均变化符合手算、方案页可重复刷新无状态错位；真实数据截图脱敏。
4. 复核 `git diff --check`、凭据泄漏与冻结边界。

## 回退方式

新增的 `TrainingProgram*` 文件可直接删除；`2.sqm` 为纯增量表，保留空表不影响其他功能；`grade_cache` 与选择记录全程未动，回退后成绩切片行为与 M5 完全一致。

## 性能评估

每次手动刷新多 2 个 GET（列表页 + 详情页，辅修学生详情页 ×N），详情页约数百 KB、千余行，Ksoup 解析为毫秒级；沿用现有 `requestDelayMillis` 节流即不会给教务系统带来可感压力。

## 修订记录（真实账号验证后）

2026-08-04 真实账号端到端验证发现三个问题，均已修复并补测试：

1. **课程号贪婪吞字母（C 语言程序设计无标签）**。证据：成绩页课程列 `M202015B\nC语言程序设计 [01]` 经成绩解析器去空白后变为 `M202015BC语言程序设计[01]`；前缀正则贪婪匹配把课程名首字母 C 吞进候选，得到 9 字符 `M202015BC`，映射查不到误判为 UNKNOWN，而方案页 `M202015B` 实为必修。实测方案页 957 门课课程号全部 8 字符。修复：map 感知回退——`GradeRules.courseTypeForCourseName` 先查贪婪候选，查不到且长度 > 8 时回退前 8 字符再查；`courseTypeOfGrade` 与 `GradeSelection.selectionRecordsExcludingTypes` 等所有查映射路径统一走该函数。
2. **体育类任选课重分类为限选**。证据：按方案页 rowspan 跟踪重建课组上下文后，任选课分布在"体育类课程【4.0】"92 门、"美育素养类课程【2.0】"92 门、"其他素养类课程【3.0】"660 门；用户拍板只有体育类任选重分类为限选（业务规则：体育课计入必修/限选绩点）。实现：`parseProgramCourseTypes` 增加按列的 rowspan carry（列索引 → 组标签+剩余行数），组标题单元格只出现在每组首行且带 rowspan/colspan，每门课取列序最内层组标签；性质为任选且最内层组名含"体育"时存"限选"。
3. **自选模式 chips 三态 + "其他类别" chip**。问题：勾选 UNKNOWN 课程后三个 chips 全取消不会动它，再按"必修"又把它算进去且无提示。修复：chips 增加第 4 个"其他类别"（覆盖 UNKNOWN）；每个 chip 三态——全部选中（selected 样式）/ 部分选中（tertiaryContainer + "已选/总数"文案）/ 未选中（默认样式），点击行为为"全选状态 → 取消该性质全部，其余状态 → 全选该性质"；`GradeUiState.selectionStateForType` 提供三态派生逻辑。

### 修订记录 2（体育课独立为"体育"类别，用户决策）

2026-08-05 用户拍板：学校口径混乱（培养方案 PDF 标体育专项"必修"、教务系统记"任选"、成绩单记任选），体育课在 App 内单独开一类"体育"，不再按必修/限选/任选归类。

- `CourseType` 新增 `PHYSICAL_EDUCATION`，`displayName()` 返回"体育"。
- 解析规则变更：撤销上一轮"体育类课程组的任选→限选"，改为**最内层课组名含"体育"的课程，无论课程性质列写的是什么（必修/任选），一律记为"体育"**。课组跟踪（rowspan carry）直接复用，覆盖体育Ⅰ（必修）、92 门专项课（任选）、体育健康教育与测试上/下（必修）等组内所有行。
- `courseTypeByStoredText` 白名单加 `"体育"`，`storedText()` 相应支持。
- UI：自选模式 chips 由 4 个变 5 个，顺序 必修/限选/任选/**体育**/其他类别，三态逻辑（全选/部分/未选中、颜色、已选/总数文案）对体育 chip 同样生效；成绩行标签按 `displayName()` 通用渲染"体育"。

### 修订记录 3（2026-08-05 第二轮）

1. **排序文案**：`GradeSortOrder.ORIGINAL` 的按钮文案由"排序：原始"改为"排序：成绩更新顺序"（全仓唯一展示点）。
2. **分类未加载时不要全部归为"其他"**：此前 `courseTypesByCode` 空 map 时所有成绩解析为 UNKNOWN，chips 显示"其他类别 N/N"误导用户。改为显式三态——`GradeSnapshot.courseTypesByCode` 与 `GradeUiState.courseTypesByCode` 类型改为可空（null = 映射从未成功同步）；`CacheStoreGradeLocalDataSource.courseTypes()` 缓存表无行时返回 null（培养方案正常约 940 行，空表即从未同步成功）；`GradeUiState.courseTypeOf` 返回 `CourseType?`（null = 未同步，非 null 查不到才为 UNKNOWN），`courseTypeCounts` 未加载时为 emptyMap；`GradeSelectionActions` 在未加载时渲染一行提示"课程性质未同步，下拉刷新后可用分类筛选"，不渲染分类 chips。domain 层 `courseTypeOfGrade`/`filterGradesByType` 签名不变。
3. **课程性质配色（浅色+深色）**：新增 `feature/grade/GradeTypeColors.kt`，按 `CourseType` 返回 (container, onContainer, border)，用 `isSystemInDarkTheme()` 区分深浅色（与 App.kt 跟随系统深浅色一致）。必修深红/限选浅红/任选靛蓝/体育绿/其他类别灰，深色模式为低饱和深底+亮字保证对比度与不刺眼。应用：成绩行标签 Surface 按类别取色；自选模式 chips 全选态填该类别实色底+onContainer 文字，部分选中态改"浅底描边"（container 35% 透明度 + 类别色 border + 已选/总数文案），未选中态默认样式。
