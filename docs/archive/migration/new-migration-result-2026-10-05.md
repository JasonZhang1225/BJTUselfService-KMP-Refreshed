# NewMigration 四项改造验收

日期：2026-10-05。分支：`NewMigration`，基于 `25bfeb5`。源码已做本地初步提交，未推送；版本号未调整。实现位于独立 `multiplatform/` 工程，冻结的原 Android 工程未修改。

## 需求与证据

| 要求 | 最终行为 | 验证证据 |
| --- | --- | --- |
| 首页日程带上课表，放在下面 | 选中日期的作业、考试、物理在线之后显示“当天课表”，包含课程名、时段、教室和教师；点击进入课表 | `HomeScheduleTest` 验证官方教学周、假期空档、星期、课程时段排序、排除下学期选课表、未确定周数为空；macOS 与 iOS 合成页面实际显示考试在前、课程在后，切到无课程的星期一显示空提示 |
| 合并教室人数与占用入口 | “教室查询”保留官方楼宇、房间和占用；仅在同一楼宇中给匹配的官方房间补人数和采样时间；第三方新增房间不产生官方行 | `ClassroomPeopleTest` 验证准确/唯一匹配、缺失与歧义；`ClassroomPeopleIntegrationTest` 验证人数失败不改官方状态、旧楼宇响应不泄漏到新楼宇；macOS/iOS 显示官方 SY101、SY102，只有 SY101 显示 18/90，额外 999 房间未出现，模拟人数失败后官方行和颜色保留 |
| 占用判断沿用 KMP | 未修改占用解析器和分类策略，FREE/OTHER/UNKNOWN 等仍沿用原逻辑 | 当前 diff 限于人数模型/展示/入口，不涉及官方解析与分类；既有占用解析与模型测试通过 |
| 修复 iOS 更新日志、正文宽度、标题 | 标题固定“发现新版本”；版本独立放正文；正文两侧各 20dp 留白，滚动区域使用 sheet 可用高度；两列以上表格不再遗漏后续列；移除设置页重复弹窗 | iPhone 17 Pro / iOS 27 模拟器实际标题完整，45 段合成日志滚动到“日志末尾验收标记”，点击“暂不更新”后弹窗完全关闭 |
| 暂不更新后 24 小时不自动弹出 | 保存绝对到期时间；页面重建不重复自动检查；24 小时内重建模型仍抑制自动弹窗；设置中主动检查更新仍可使用；界面未增加冷却时间说明 | `SettingsScreenModelTest` 验证持久化、到期前 1ms/到期时边界、连续 6 次自动请求只检查一次及手动检查；`CacheStoreTest` 验证推迟时间落盘读取 |
| 更多改为应用，带图标方块/矩形 | 应用使用响应式图标网格，窄屏两列、较宽三列、宽屏四列；物理实验同步设置保留独立方块 | macOS 1080dp/420dp 与 iOS 窄屏，深浅色、1.5 倍文字实际检查；新增功能均有图标 |
| 设置可选底栏，固定首页/应用，最多 6 项 | 默认保持原六项；自选最多四项；全部取消后只有首页和应用；底栏与应用入口互斥；底栏、侧栏及 UIKit 描述项来源相同；取消物理在线底栏项不关闭功能本身 | `NewMigrationNavigationTest` 验证非法/重复配置归一化、上下限、应用互斥和动态原生路由；设置与缓存测试验证保存及空配置；桌面实际取消四项到两项，再选考试/课件/教室/邮箱到六项，其他选项不能勾选且四项从应用中消失；Swift 图标映射和原有 reloadTabs 回调已检查并编译 |

底栏偏好和更新推迟时间使用现有设置表的新键，无数据库 schema 变化。旧安装没有底栏配置时使用原默认项，显式空列表使用两项；取消固定项或超量/重复/无效的保存配置不会破坏上下限。

## 构建与自动测试

工作目录：`multiplatform/`。

```sh
./gradlew :shared:desktopTest :shared:compileKotlinIosArm64 :shared:compileKotlinIosSimulatorArm64 :shared:compileAndroidMain :androidApp:compileDebugKotlin :windowsApp:compileKotlinWindows :desktopApp:createDistributable --max-workers=2 --console=plain
```

结果：`BUILD SUCCESSFUL in 3m 35s`。JUnit XML 汇总 **610 tests / 0 failures / 0 errors / 0 skipped**。

```sh
swiftc iosApp/iosApp/NativeNavigationGlassState.swift iosApp/tests/NativeNavigationGlassStateTests.swift -o /tmp/newmigration-swift-tests
/tmp/newmigration-swift-tests
```

结果：`Navigation glass regression: 35 checks passed`。

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug -sdk iphonesimulator -destination 'id=6857828A-CCEA-47D0-B26A-93462734DD91' -derivedDataPath ../.artifacts/NewMigration/ios-debug CODE_SIGNING_ALLOWED=NO build
```

结果：`BUILD SUCCEEDED`。模拟器已安装并实际运行 Debug 入口。

桌面增加 `--narrow` 合成验收尺寸参数后单独执行 `:desktopApp:createDistributable`，结果 `BUILD SUCCESSFUL in 25s`。`git diff --check` 通过。

完整构建日志保存在本机忽略目录 `.artifacts/NewMigration/evidence/`；JUnit XML 保存在 `multiplatform/shared/build/test-results/desktopTest/`。

## 实际界面验收与边界

- 显式 `--new-migration-smoke` 使用合成数据、内存设置模型和假仓库，未读取真实凭据、未发起网络请求。iOS 入口位于 Swift DEBUG 条件中。桌面以精确源码路径启动，`--narrow` 初始宽度 420dp；宽屏为 1080dp。
- 使用 Computer Use 读取、操作和检查 macOS/iOS 界面；首页顺序/空状态、教室正常/加载/补充失败、应用网格、设置上下限和选项互斥均已观察。文字放大通过验收入口注入 1.5 倍字体，不更改用户系统设置。
- `--update-notes-smoke` 调用实际 `AppUpdateResultDialog` 与 iOS 原生 sheet，合成 45 段日志。两次拖动到末尾可看到完整第 45 段和末尾标记，标题与操作固定可见，点击暂不更新实际关闭。
- 24 小时等待用注入时钟和真实 CacheStore 持久化测试验证，未声称实际等待一天。UIKit 可配置 tab 的数据流、控制器复用与图标映射已源码检查、桥接规则测试和 iOS 构建验证；合成页面导航视觉使用共享底栏。
- Windows、Android 和 iOS 真机未进行本轮安装验收；本轮为受影响目标编译、共享逻辑回归及 macOS/iOS 模拟器界面验收。未测试真实人数服务在线可用性，也未执行邮件、上传或课程操作。
- 源码桌面实例与模拟器测试进程已结束，用户原有 macOS 安装版未关闭。

## 回退

本次没有新 schema 或服务器写入。回退实现时同步移除底栏/更新推迟设置的读取和写入即可，旧设置表中的新增键可以保留；原默认导航和既有官方教室占用逻辑仍可使用。分支已做本地初步提交，交付不包含推送、合并、发布或 PR。
