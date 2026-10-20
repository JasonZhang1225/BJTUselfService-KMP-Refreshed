# BJTUselfService 实时工作记忆

> 最后更新：2026-10-20。
> 当前分支：`fix/auto-login-aa-link-crash`（已合入 mine/main@6e92d071，已推送 mine，PR 待合入）；已整合原 `feat/ai-agent-dev`、`fix/mis-sso-auth` 与原 main 的全部历史；本地和 mine 统一仅保留 main。分支清理备份见 `.artifacts/branch-cleanup-20261020/before-cleanup.bundle`。
> KMP 源码版本：`1.8.5-EmergencyFix1` / Build 39（桌面数值版本 1.8.5）；最新 GitHub Release 为 `v1.8.5-EmergencyFix1`（2026-10-20，本次查询 isPrerelease=false；旧日志记录的是发布时状态）。本次未替换本机安装版。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 已完成工作的细节见 `history_full.md` 末尾日志。
> 认证现状（2026-10-20，PR #29/#30 已纳入 main）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码（设置 → 物理在线账号设置）；智慧教学仍卡在学校的旧 CAS 跳转。

> 最近只读操作：2026-10-14 使用已安装 bjtuss 完成本机作业缓存导出；产物仅放 `.artifacts/`，未修改源码、未联网刷新，保留原有暂存与未提交改动。

## 进行中

- `claude/huawei-widget-size`（PR 待合入）：HarmonyOS 4.2 用户反馈周视图大卡片地点被截断、今日课程只显示一条。推断桌面不报 Android 12 尺寸列表，旧代码落到写死兜底（周视图按 350×360 排、今日课程系统挑最小的 350×170 中号）。改为依次取尺寸列表 → MIN/MAX 四个值 → 按屏幕宽估算，并以 `CourseWidget` 标签在 logcat 记录上报来源与尺寸。本地 Gradle 被 429 限流，仅 CI 验证；未在华为设备实测，推断待用户装包确认。
- `fix/auto-login-aa-link-crash`（PR 待合入）：MIS 可达但 aa.bjtu.edu.cn 拒绝连接时，冷启动自动登录在 `LaunchedEffect` 中因 `linkAcademicSystem()` 抛 `SchoolNetworkException` 未捕获而每次启动闪退（华为 HBN-AL10 实机 14 次 + iOS 模拟器旧版 SIGABRT 均复现，KMP 迁移起即存在）。修复：`SchoolLoginProtocol.linkAcademicSystem()` 网络异常返回 false（取消仍抛出）；按用户要求，教务关联失败不再阻止登录，直接进入主界面，课表/成绩/考试保留缓存并在同步详情就地显示失败，刷新时经 `SchoolSessionRecovery` 重新关联（未加额外弹窗，避免与升级说明弹窗叠加）。`SchoolLoginProtocolTest` +3；desktopTest 931 零失败，四端编译与签名模拟器 xcodebuild 通过；iOS 模拟器验证：教务真实断开时（弹窗版）及教务恢复后用临时传输层拦截 aa（最终版，补丁已撤销未提交）均直接进入主界面，同步详情仅成绩/课表/考试失败；教务正常时同步全部完成。未在华为实机装新包验证。原始日志在 `.artifacts/crash-HBN/`。

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
2. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。
