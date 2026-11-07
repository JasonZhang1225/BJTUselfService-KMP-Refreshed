# M5 课程表纵向切片结果

> 日期：2026-07-30  
> 基线：官方 `v1.7.0@419313d` 及 Android 脱敏行为/视觉记录  
> 状态：实现与构建通过；最新安装和真实登录后验收待完成

## 已实现

- 教师映射、本学期课表、选课课表和 HTTPS 当前周提示复用登录后的 `SchoolHttpTransport`；四步全部成功后才返回远端快照。
- 两类 7×7 HTML 表格解析到共享 `Course`，保留 `slot * 8 + day` 位置映射、同格多课程、教师回退及 0/1–26 周语义；错误不携带响应正文。
- 课程与账号对应的当前周元数据在同一 SQLDelight 事务替换；网络、会话、解析或缓存失败时保留完整旧快照。
- 状态模型支持缓存优先、首次跟随当前周、手动选周、切换本学期/选课课表、选择星期、课程详情和可重试错误。
- 登录后共享应用壳已由单一成绩页扩展为“成绩 / 课程表”导航；两个 Repository 复用同一登录 transport 和 Cookie 会话。

## Apple 端设计

- iPhone：不复制 Android 的八列小字网格，改为星期选择与七个节次纵向列表；周选择使用底部 sheet，课程详情也使用底部 sheet，不要求横向滚动。
- macOS：侧栏直接切换成绩/课程表；课程主区保留七日周网格，右侧常驻详情，刷新时不清空现有内容。
- 课表类型、周数、当前周、同步来源和失败状态均有显式文字反馈；普通刷新不使用弹跳或遮挡式加载。

## 测试与构建证据

- 新增 12 项：解析 4、远端 2、Repository 2、状态模型 3、SQLDelight 账号快照 1。
- 在 M5 成绩切片已确认的 57 项基础上，全量 `:shared:desktopTest` 共 69 项通过；定向课程表测试也单独通过。
- `:shared:compileTestKotlinIosSimulatorArm64`、`:shared:compileKotlinIosArm64`、`:androidApp:assembleDebug`、`:androidApp:assembleRelease`、`:desktopApp:createDistributable` 同次 `BUILD SUCCESSFUL in 18s`。
- Xcode 26.6、iPhone 17 Pro iOS 26.5、arm64、`CODE_SIGNING_ALLOWED=NO`：`** BUILD SUCCEEDED **`；仍只有既有 ICU deployment target 链接警告。

## 尚未证明

Xcode 构建成功后，最新 iOS 安装与启动被执行环境拒绝，原因是“工作区额度不足”；同一限制也阻止了本轮末尾的聚合审计命令。为避免规避授权机制，没有改用其他工具间接安装。

因此以下仍未完成：

> **2026-07-30 更新**：上述“安装被拒绝”的阻塞已解除。当日含五个业务入口（成绩/课程表/考试/作业/课件）的最新构建已成功装入 iOS 与 Android 模拟器，Desktop 123 项测试与三端构建全部通过。课程表真实数据与视觉验收当前受服务器验证码风控阻塞（见 `m5-homework-plan.md` 末尾记录），待风控解除后补验。

1. 最新课程表构建在 iOS Simulator 和 macOS 自包含 App 的首帧。
2. CAPTCHA 登录后真实本学期/选课课表及当前周解析。
3. iPhone 单日列表、1–26 周 sheet、macOS 七日网格和详情的视觉/交互检查。
4. 本轮最终冻结摘要、凭据泄漏和 `git diff --check` 复核；上一次成绩切片复核均通过，本切片只编辑 `multiplatform/` 与迁移文档，但仍不以间接推断替代最终复跑。

当前 Simulator 中仍是上一版成绩构建，并停留在 CAPTCHA 提交前；不能用它证明本课程表切片已运行。
