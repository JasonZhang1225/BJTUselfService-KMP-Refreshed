# M5 成绩纵向切片实施计划

> 基线：官方 `v1.7.0@419313d` 隔离源码与已登录 Android 视觉/行为记录  
> 修改范围：仅 `multiplatform/` 与迁移文档；冻结 Android 根工程保持只读  
> 状态：实现与自动化构建完成；iOS/macOS 真实成绩登录与交互验收待 CAPTCHA 当次确认

## 目标行为

登录 MIS 与教务系统成功后，进入可实际使用的成绩页面，而不是停留在“登录成功”卡片。成绩切片同时具备：

1. 先读取当前账号的本地缓存，再自动同步教务系统 `ln`、`lr` 两类成绩。
2. 两类请求全部解析成功后才原子替换缓存；任一失败时保留完整旧缓存并给出可重试状态。
3. 按课程名、成绩、学分去重，保留 v1.7.0 的成绩字母/分数映射和加权平均规则。
4. 支持学期多选筛选、原始/升序/降序三态排序、课程详情。
5. 支持“自选课程计算”，选择按账号持久化；数据库 ID 变化、成绩变化或部分快照不得错误丢失选择。
6. 退出时清除内存会话、安全凭据和当前账号缓存；界面始终提供可理解的退出路径。

## 新增与修改文件

### 数据与状态

- 新增 `shared/.../data/grade/GradeHtmlParser.kt`：严格解析教务成绩表，不把响应正文写入异常或日志。
- 新增 `shared/.../data/grade/GradeRemoteDataSource.kt`：复用登录后的 `SchoolHttpTransport` Cookie 会话，顺序请求 `ln`、`lr`。
- 新增 `shared/.../data/grade/GradeRepository.kt`：协调远端、SQLDelight 缓存、账号隔离和自选记录恢复。
- 新增 `shared/.../feature/grade/GradeScreenModel.kt`：集中管理加载、刷新、筛选、排序、自选和详情状态；网络副作用不从普通重组路径触发。

### 共享 UI 与登录衔接

- 新增 `shared/.../feature/grade/GradeScreen.kt`：实现加载、缓存、刷新、空、错误、正常、自选和详情状态。
- 修改 `LoginScreen.kt`：登录成功后切换到成绩应用壳；登录协议和成绩 Repository 共享同一 transport。
- 必要时修改 `App.kt`：只负责主题和窗口分类，不引入平台对象到共享状态。

### 测试与记录

- 新增成绩 HTML 正常、字母分数、缺列、无表格和去重测试。
- 新增 Repository 缓存优先、完整刷新、部分失败不覆盖、选择恢复和按账号隔离测试。
- 新增状态模型加载、刷新失败、筛选/排序、自选清理和详情选择测试。
- 完成后更新 `goal.md`、`memory.md` 和本文件的实际结果。

## 平台布局

- iPhone/紧凑窗口：单列，顶部显示加权平均与同步状态；学期和排序操作可换行；详情使用底部 sheet；触控目标不小于约 44–48 dp。
- iPad/中等窗口：单列但增加内容宽度约束，避免卡片无限拉伸。
- macOS/宽窗口：固定导航/账号区域 + 成绩主区；主区采用列表—详情，点击行不会遮挡上下文；刷新和退出均可直接到达。
- 动效仅用于系统组件的状态切换，不给普通出现/消失添加弹跳；加载时保留已有内容，避免刷新造成整页闪烁。

## 验证

1. `:shared:desktopTest`，并核对新增测试数与零失败。
2. `:androidApp:assembleDebug :androidApp:assembleRelease`。
3. `:shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64`。
4. Xcode iPhone 17 Pro / iOS 26.5 arm64 未签名构建、安装与运行。
5. `:desktopApp:createDistributable`，启动自包含 `.app` 并验证窄/宽窗口。
6. 使用 Computer Use 在官方 Android 对照页和新 iOS/macOS 页面检查正常、筛选、排序、自选、详情、加载/错误/空状态；真实数据截图必须脱敏。
7. 复核 release 清单、凭据泄漏、`git diff --check` 和冻结边界聚合 SHA-256。

## 回退方式

本切片不修改 SQLDelight schema，也不触碰冻结 Android 工程。若成绩切片运行失败，可删除新增的 `data/grade`、`feature/grade` 文件并恢复 `LoginScreen.kt` 的登录成功卡片；现有 M4 数据库和安全存储仍可独立运行。

## 当前结果

- `ln`、`lr` 远端请求、严格 HTML 解析、去重、账号隔离缓存、选择记录恢复和缓存优先状态已实现。
- 成绩行与自选记录改为同一 SQLDelight 事务替换；本地替换失败测试证明旧快照保持不变。
- iPhone/紧凑布局采用单列与底部详情 sheet；macOS/宽布局采用固定侧栏和列表—详情。
- 当前 57 个 Desktop 测试零失败、零跳过；Android debug/release、iOS Simulator/arm64、macOS distributable 和 Xcode Simulator build 均成功。
- 两端最新构建已实际启动并重新到达 CAPTCHA 页面；尚未提交 CAPTCHA，因此不能宣称真实成绩数据与交互已经通过。完整证据边界见 `m5-grades-result.md`。
