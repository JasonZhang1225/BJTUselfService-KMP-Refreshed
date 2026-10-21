# BJTUselfService 实时工作记忆

> 最后更新：2026-10-21。
> 当前分支：`fix/android-native-navigation-session`（基于 mine/main@c94d18a，尚未合入；PR #31/#32/#33 均已合入）；已整合原 `feat/ai-agent-dev`、`fix/mis-sso-auth` 与原 main 的全部历史；本地和 mine 只保留 `main` 与 `claude/宣传图制作`（2026-10-20 已删除 #31–#33 的已合并分支）。分支清理备份见 `.artifacts/branch-cleanup-20261020/before-cleanup.bundle`。
> KMP 源码版本：`1.8.5-EmergencyFix2` / Build 40；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。`history_full.md` 尚无 EmergencyFix2 发版条目。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 已完成工作的细节见 `history_full.md` 末尾日志。
> 认证现状（2026-10-20，PR #29/#30 已纳入 main）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码（设置 → 物理在线账号设置）；智慧教学仍卡在学校的旧 CAS 跳转。

> 最近只读操作：2026-10-14 使用已安装 bjtuss 完成本机作业缓存导出；产物仅放 `.artifacts/`，未修改源码、未联网刷新，保留原有暂存与未提交改动。

## 进行中

- Android 原生详情页点击失效排查（PR #34，`fix/android-native-navigation-session`）：用户提供 HarmonyOS 4.2 / AOSP 12 录屏；页面入口无跳转但校园网/校园卡弹窗可打开，后台往返未恢复；用户回忆底栏似乎仍能切换，未确定。
  - 修复：`NativeSessionHandoff` 按根宿主令牌保护会话，旧宿主的清理不能覆盖新宿主；MainActivity 使用固定发布回调；Android 点击原生入口时同步重新交接当前会话并直接跳转，避开壳层协程延迟。debug `NativeNavigation` 仅记录路由/会话是否存在，不记录账号或 Cookie。
  - 已验证：隔离 Gradle 工程直接编译仓库中的 `NativeSessionHandoff.kt` / `NativeSessionHandoffTest.kt`，4 项通过；用旧 Registry 无条件清理语义作对照，2 项保护测试失败。`git diff --check` 通过。
  - 完整共享验证：`./gradlew :shared:desktopTest --no-configuration-cache`，936 项全部通过（含新增 4 项）；首轮 22 项渲染测试因 Skia 缓存目录只读失败，指定可写 Java 用户目录后通过。代码提交 `c6eb350` 的 Android CI（`:shared:compileAndroidMain :androidApp:compileDebugKotlin`）已通过；Apple CI 仍在执行。当前 Linux 环境没有 Android SDK/项目签名与 Apple 工具链，未做 APK 打包/真机验证。
  - 边界：只修复已识别的会话生命周期风险和异步跳转依赖；没有华为真机或 logcat，无法证明录屏仅由该路径触发，也未做华为实机验收。未改版本、未合并、未发版。

## 当前痛点

- 智慧教学（课程平台作业/课件，MIS module 28）仍被学校跳到已下线的 `cas.bjtu.edu.cn/o/authorize`，浏览器同样 404，属学校侧问题；待学校改到 SSO 后再适配 `SmartPlatformEndpoint` 握手白名单。
- 同步卡顿：Beta3 已到可接受范围。剩余长帧为同步详情 sheet 弹出（约 50ms）、进入作业页（约 49ms）和原生滚动带来的导航栏布局；`checkSession` 改后台后未复采；壳层随任一状态流整体重组（约 0.4 秒/次同步），彻底解决需拆分壳层。
- 小组件：PR #8 的 ad-hoc 签名后，侧载包的小组件共享容器是否可读尚未在真机确认；周视图、中号两栏、脚注只经 CI 编译，未在模拟器浅/深色下查看，小屏 iPhone 周视图可能偏挤；“正在上/还剩 N 分钟”未在上课时段实机观察。
- 首页物理在线/CITEL 日程项进入二级详情只在代码层实现，模拟器只验证了演示实验。
- “保存二维码并扫一扫”未真机验证（Android MediaStore 保存/去重、扫码后进入小程序/缴费页）；iOS 每次保存会多一张；Android 二维码弹窗未在平板上看。
- HyperOS 小窗↔全屏来回切换未实测（adb 无法切换窗口模式）。
- 课件 15 分钟大文件超时未用 >30 秒真实下载验证；iOS/Android“未开放下载”标记只验证编译。
- 在 Chrome 登录智慧教学会顶掉 App 会话，App 自动恢复登录失败一次（需手动刷新），未排查。
- macOS 附件默认应用打开、iOS 保存面板未点验；`HomeworkFileGateway` 已被邮箱复用，可改名为通用文件网关。

## 已知限制（非缺陷，排查时先看这里）

- 教务系统 `aa.bjtu.edu.cn` 会临时拒绝 443 连接（2026-10-20 晚间约 1.5 小时，MIS 同时可达）；PR #33 起 App 照常进入主界面，仅成绩/课表/考试同步失败并保留缓存。复现可在 `KtorSchoolHttpTransport.executeOn` 临时对 aa 抛异常，测完撤销。
- 校历公众号文章不能由第三方 App 直接在微信内打开（invalid source），走系统浏览器。
- 小米“跟随应用内设置”覆盖安装清不掉：装新包后卸载重装，或在“使用高刷新率的应用”里打开交大自由行 KMP。
- 本地 Gradle 偶发被 Maven Central 429 限流，此时测试与 iOS 编译交给 PR CI。

## 迁移期遗留（长期，无排期）

- iOS Keychain 合法签名往返、正式签名发布（无 Apple Developer Team）。
- 物理在线真实上传、Windows 触摸屏；邮箱真实发送/删除/移动/附件上传；真实作业上传、退出账号往返（等学校侧条件）。
- 验证码发布级正确率需至少 300 张独立留出集。

## 接下来

- 后续开发统一从最新 `main` 建分支，PR 以 `main` 为目标。

1. 在真机已有版本（SideStore 保留扩展）上验证小组件共享容器与各尺寸显示。
2. 华为 HBN-AL10（HarmonyOS 4.2）装含 PR #32/#33 的新包：确认课表小组件尺寸（logcat `CourseWidget` 标签）与教务不可达时不再闪退。
3. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。
