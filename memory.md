# BJTUselfService KMP 迁移工作记忆

> 最后更新：2026-09-18
> 当前分支：`main`；显示/打包版本 **`1.7.6-KMP`**。正式提交 `ac10ba1`、CI 修复 `eb42f1e` 已推送并打正式 tag `v1.7.6-KMP`（run 35185935353 五 job 全绿，正式 Release 已发布）。签名加固 `584d26c` 与安全修复 `edbfa55`（M2/M3/M4 + 审计归档）已在本地 `main` **提交但未推送**。
> 阶段状态：**标记点版本 `1.7.6-KMP-GLM5.3-Security-Verified` 已打 tag 推送（`mine` fork，无 CI 触发、不发 Release）；屎山修复全部完成（`1409ba0`）；2026-09-18 新功能：首页变动接入物理在线、课程表列表视图按课程类型着色、邮箱未读标识修复（蓝点解析 + 读完即时清除）。审计与进度见 `docs/refactor/BJTU-KMP-CodeShit-Audit-GLM-2026-09-17.md` 第九节。** 邮箱主体能力保持自 `v1.7.5-KMP`，真实邮箱写操作、Apple 真机签名等仍按已知限制保留。
> 分支创建点：`9d8da18`；上游对照基线：`v1.7.0@419313d`；KMP 自身基线：**`v1.7.3-KMP-B` (`a342615`)**；当前正式版本 **`1.7.6-KMP`**；上一发布 `v1.7.5-KMP` 保留。
> 完整历史与已归档的验收细节：见 `history_full.md`（按里程碑归档，只读）
> 本文件是实时工作记忆，不是只追加日志：任务开始读、结束改，只保留当前接续工作需要的状态。

## 1. 本阶段已做到（≤10 行）

- **2026-09-18 屎山修复里程碑 P0（工作区未提交）**：`GradeScreen.kt` 3811→1174 行；壳层（AppRoute/AuthenticatedAppShell/侧栏/顶栏/底栏/更多页/首页同步聚合）迁至 `feature/shell/` 7 个新文件，顶层 private→internal；`App.kt`/`LoginScreen.kt`/导航测试引用同步。AVD 实机回归首页/成绩/课表/更多 + 底栏导航通过。`SectionDestination`（790 行局部 Composable）抽离暂缓——捕获壳层全部状态，参数化 30+ 参数，并入 P2b。
- **P1（已提交 `1409ba0`）**：`LoginRoute` 内联 DI 装配（~210 行）抽为 `feature/shell/AuthenticatedSessionFactory.kt` 的 `rememberAuthenticatedSession`；`LoginScreen.kt` 1325→1138 行。关键决策：工厂复用 LoginRoute 同一 `SchoolLoginProtocol` 实例（`loginProtocol` 参数）；`logout` 仍留 LoginRoute 经 `onLogout` 回调传入。AVD 验证静默自动登录→四 tab→设置→退出登录回登录页；手动重新登录路径未实机复测（代码为原样搬移）。
- **P2a（已提交 `1409ba0`）**：新增 `logging/AppLog` expect/actual（Android Log + 宿主 BuildConfig.DEBUG 开关、iOS isDebugBinary、desktop stdout）；替换 PhyVlabDebug/KtorSchoolHttpTransport/HomeworkRemoteDataSource×3 的 println；Live 探针测试已有 `BJTU_LIVE_PROBE=1` 开关无需改。AVD logcat 实测 AppLog 输出正常。KMP android library 不支持 buildFeatures DSL，Android 开关放在 androidApp `MainActivity.onCreate`。
- **2026-09-18 下午新功能（已提交 `49f6be5` 并推送 `mine`）**：①首页「数据变动」接入物理在线——`HomeChangeDomain` 加 PHYVLAB、`phyvlabChangeRecorder`（复用泛型 changeRecorder 工厂，identity=courseId+id，detail 含课程名/截止/完成态）、`PhyVlabScreenModel` 加 changeRecorder 参数在 refresh 成功后 diff（activityFetchFailed 时不记录）、工厂装配接线、`toAppSection` 映射；新增 `PhyVlabChangeRecorderTest`（ADDED/MODIFIED/DELETED+空变化）。②课程表列表视图（DAY 模式）`CourseListCard` 底色改为「色块概览」同源课程类型配色（courseTypesByCode 沿 CompactDayPager→DayScheduleSlotRow→CourseListCard 透传）。③邮箱未读修复（`01a3efe`）：根因是 Coremail 只对已读邮件返回 `flags.read=true`、未读整个 read 键缺失，而解析 `?: true` 把缺失当已读→无蓝点无加粗。改 `== true`（缺失即未读）。AVD logcat 探针确认（未读邮件 flags 空、已读 read=true）+ 新增单测。④读完邮件蓝点不消失（`66ab080`）：AVD 实测证明 readMessage.jsp 已在服务端置 \Seen（读完点刷新蓝点即消失），但返回不刷新列表也不本地更新该项 isRead→改 openMessage 成功拉详情后乐观置对应项 isRead=true，返回即无蓝点无加粗。首页「新邮件」计数走 MIS 状态接口（另一套系统），不随单封读取即时变化。新增 openingUnreadMessageMarksItReadLocally 用例；desktopTest 508 项仅 2 例既有失败。验证：desktopTest 506/508（2 例既有失败）+ 三端编译 + AVD 实测列表视图着色（必修粉/限选橙正确）；首页 PHYVLAB 变动依赖真实数据变化，逻辑由单测覆盖。
- **标记点 tag**：`1.7.6-KMP-GLM5.3-Security-Verified`（无 v 前缀不触发 CI `v*-KMP*` 规则）已推 `mine`；版本号 4 处同步（AppUpdateChecker/Android versionName/iOS Info.plist/测试断言），versionCode 保持 17。注意 git 推送目标：`origin`=HFDLYS 上游无权限，**推送用 `mine`**（JasonZhang1225 fork）。
- **实机验收（2026-09-18）**：P0/P1/P2a 打包本地包（`~/Downloads/BJTUselfServiceKMP-1.7.6-KMP-local.dmg` + iOS unsigned ipa，Build 17），用户 macOS/iOS 实测通过，基本标记实机通过。
- **P3a/P3b/P2b 部分（已提交 `1409ba0`）**：`network/SchoolEndpoints.kt` 集中 12 个文件的端点常量（注意 `"$SchoolEndpoints.X"` 模板必须写 `${SchoolEndpoints.X}`，曾致 6 测试失败）；`util/JsonEscape.kt` 去重；`feature/common/WorkspaceStates.kt` 共享加载/空态（Grade/Exam/Homework/Course/Courseware 5 屏 10 个同款 Composable 委托化）。P2b 剩余项（周选择器/SectionDestination）因结构分化或参数化过重继续暂缓。
- **本轮验证**：desktopTest 502/504（2 例 `PackagingCiAsciiConfigTest` 既有失败）；iOS sim、Android debug 编译通过；AVD `emulator-5554`（Pixel_10_Pro_XL arm64，KMP 包名 `team.bjtuss.bjtuselfservice.kmp`，勿与冻结版 `team.bjtuss.bjtuselfservice` 混淆）。
- **2026-09-18 安全审计修复里程碑**：已提交 `edbfa55`（M2/M3/M4 + `docs/security/` 归档）；M5 签名验证通过；H1/H2/M1 暂缓。细节已归档 `history_full.md`。
- **2026-09-16/17 作业提交协议、首页周卡片、1.7.6 正式发布**：细节已归档 `history_full.md`（1.7.4～1.7.6 段）。

## 2. 当前痛点（≤8 条）

- **屎山修复已提交（`1409ba0`）**：P2b 剩余（周选择器去重、`SectionDestination` 抽离）经 2026-09-18 评估明确不做——结构已分化/参数化过重，抽象成本高于维护成本；本地 main 领先 origin 三个提交（`584d26c`/`edbfa55`/`1409ba0`）未推送。
- **安全修复实机验证（初步完成）**：M2/M3/M4 已随 2026-09-18 本地实机包（DMG/IPA）经用户初步实测通过；下一版正式包 bump versionCode 后复核。
- **`PackagingCiAsciiConfigTest` 既有失败（2 例）**：1.7.6 打包收口遗留，待单独修复。
- **普通作业列表「提交人数 0/63」口径待确认**：详情正确，列表与网页端 93/99 不一致，需核对 `submitCount` 字段来源。
- **Windows MSI 卸载清凭据待复测**：与 M3 相关，下一版安装包顺带复测。
- **iOS 真机签名/连接**：Bundle ID 无匹配 provisioning profile；签名安装与 Keychain 往返未取得证据。
- **M13/M15 保留边界**：物理在线真实上传未执行；邮箱真实写操作待单独切片；验证码发布级准确率待扩样。

## 3. 接下来 1～3 个阶段

1. **推送与下一版规划**：本地 main 三个提交（签名加固/安全修复/屎山修复）待推送；下一版 bump versionCode 后四端打包 + 实机回归（iOS 卸载重装、桌面清理按钮复核）。
2. **安全修复实机验证**：iOS 卸载重装清除、桌面全量清理按钮已随本地包初步实测（用户「基本通过」），下一版正式包复核。
3. **正式包安装回归**：从下一版 Release 覆盖安装四端产物检查。

## 维护规则

- 每次开始目标模式任务时先读本文件；结束前必须再次更新。
- 已完成事项压缩为一行保留在“本阶段已做到”；里程碑真正完成时，把细节归档进 `history_full.md` 并从本文件删除。
- 痛点解除即删除或改写，不保留已经失效的阻塞描述。
- 近期计划只保留接下来 1～3 个可执行阶段；远期内容留在 `goal.md`。
- 事实、命令结果和验证边界要具体；不能把计划写成已完成。
- 分支、基线、最新 Release 或工作区状态发生变化时，更新文件顶部摘要。
- 不在本文件写入账号、密码、Cookie、令牌、真实验证码会话或其他敏感信息。
