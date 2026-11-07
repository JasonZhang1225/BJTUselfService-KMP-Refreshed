# M5 成绩纵向切片结果

> 日期：2026-07-30  
> 基线：官方 `v1.7.0@419313d` 及已登录 Android 脱敏视觉/行为记录  
> 状态：实现与构建通过；iOS/macOS 真实登录后交互验收待完成

## 已实现

- 复用登录后的 `SchoolHttpTransport` 与 Cookie 会话，顺序请求教务成绩 `ctype=ln`、`ctype=lr`；两类均成功后才进入本地替换。
- 严格解析教务成绩表，保留 v1.7.0 的字母/代表分数映射与课程名显示规则；异常不携带响应正文。
- Repository 先返回当前账号缓存，再自动同步；网络、会话失效、页面结构变化和缓存失败均为可区分状态。
- 成绩与自选课程记录在同一 SQLDelight 事务内替换，避免半份快照；数据库 ID 或分数变化后仍按稳定课程身份恢复选择，并保留暂时不可见的选择记录。
- 支持学期多选、原始/升序/降序三态排序、自选课程加权平均、全选/清空及课程详情。
- 登录成功后直接进入成绩应用壳；退出会清除内存会话、安全凭据和当前账号普通缓存。

## Apple 端布局

- iPhone/紧凑窗口为单列结构，刷新和退出在顶部可达，详情使用底部 sheet，复选控件使用 48 dp 目标。
- macOS/宽窗口使用固定侧栏、成绩列表和常驻详情区；同步时保留现有内容，仅显示线性进度，不用整页闪烁或无意义弹跳。
- 页面包含加载、缓存、同步、空、筛选无结果、解析失败、会话过期和本地缓存失败反馈。

## 自动化与构建证据

- `:shared:desktopTest`：57 个测试，`failures=0`、`errors=0`、`skipped=0`；M5 覆盖解析、双请求、部分失败不覆盖、原子快照失败保持、选择恢复、筛选/排序/自选和缓存状态。
- 同次 Gradle 门禁：`:shared:compileTestKotlinIosSimulatorArm64`、`:shared:compileKotlinIosSimulatorArm64`、`:shared:compileKotlinIosArm64`、`:androidApp:assembleDebug`、`:androidApp:assembleRelease`、`:desktopApp:createDistributable`，结果 `BUILD SUCCESSFUL in 3m 25s`。
- Xcode 26.6、iPhone 17 Pro iOS 26.5、arm64、`CODE_SIGNING_ALLOWED=NO`：`** BUILD SUCCEEDED **`；仅保留 ICU 对最低 iOS deployment target 的既有链接警告。
- Android release 清单为 `team.bjtuss.bjtuselfservice.kmp`、`0.1.0`、minSdk 28、targetSdk 34，仅含正式 `MainActivity`，不含 debug `SecuritySmokeActivity`。
- macOS 与 iOS Simulator 可执行文件均为 Mach-O arm64；Android release APK SHA-256 为 `60eb276f2cb134674b08e271fc115cd5a0282f08707c71edff4ba05f51928fe7`。
- 真实凭据值在 `MisSecret.md` 之外的扫描命中数为 0；`git diff --check` 通过。
- 冻结 Android 边界聚合 SHA-256 仍为 `a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f`。

## Computer Use 运行边界

当时最新的成绩切片 iOS Simulator App 与 macOS 自包含 App 已重新部署/重启。Computer Use 已确认两端均显示 `KMP · M5` 登录界面，凭据字段有效，并已获取新的 CAPTCHA；当前停在提交前。此后仓库又加入课程表切片，但其最新产物尚未安装，因此该 CAPTCHA 会话只能用于成绩切片验收，不能证明课程表运行。

最新无凭据截图也已做视觉检查：iPhone 17 Pro 浅色紧凑布局中说明、账号、密码、保存选项和“获取验证码”均在首屏内，无横向滚动或文字截断；macOS 深色宽窗口的介绍区与登录卡分区清晰，字段、按钮和说明均完整显示。该证据只覆盖登录前界面，不替代登录后的成绩页验收。

尚未执行，因此不能写成通过的项目：

1. macOS CIO 的真实 MIS/AA 登录与成绩请求。
2. iOS/macOS 真实成绩数量、缓存写入和同步状态。
3. 学期筛选、三态排序、自选课程、详情 sheet/详情栏的真实数据交互。

> **2026-07-30 更新**：上述"此后产物尚未安装"已不成立。当日含五个业务入口的最新构建已装入 iOS 与 Android 模拟器，Desktop 123 项测试与三端构建全部通过。成绩页真实数据验收当前受服务器验证码风控阻塞（见 `m5-homework-plan.md` 末尾记录），待风控解除后补验。
4. 脱敏后的登录后视觉证据及两端行为对照。

上述四项需要在 Computer Use 的 CAPTCHA 当次确认后批量完成。本状态不是功能失败，也不缩小 M5 的验收范围。
