# BJTUselfService 实时工作记忆

> 最后更新：2026-10-21。
> 当前分支：`feat/library`（图书馆借阅功能开发中，未提交/未推送；基于 `main@f454d3a`，该提交已包含 PR #35 合并）。
> KMP 源码版本：`1.8.5-EmergencyFix2` / Build 40；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。`history_full.md` 尚无 EmergencyFix2 发版条目。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 已完成工作的细节见 `history_full.md` 末尾日志。
> 认证现状（2026-10-20，PR #29/#30 已纳入 main）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码（设置 → 物理在线账号设置）；智慧教学仍卡在学校的旧 CAS 跳转。

> 最近只读操作：2026-10-14 使用已安装 bjtuss 完成本机作业缓存导出；产物仅放 `.artifacts/`，未修改源码、未联网刷新，保留原有暂存与未提交改动。

## 进行中

- 图书馆借阅功能（`feat/library`，未提交/未推送）：新增 `data/library/`（模型、endpoints、SSO OAuth2 登录协议、remote、repository）、`feature/library/`（LibraryModel、LibraryScreen）、`AppSection.LIBRARY` + 应用页磁贴 + 底栏自定义候选 + 首页到期提醒卡片；续借支持单本/全选批量并带确认弹窗，不做离线缓存。
  - 协议证据（2026-10-21 真实抓包）：SSO 会话有效时授权页直接回 `opac.bjtu.edu.cn/OAuth2/bjtu/index?code=...`；回调页 HTML 内一次性 `data/time/enc`；`/OAuth2/bjtu/login` 返回 `{"status":true}` 并写 opac Cookie（`p_auth_token` 等）；`/find/*` 只靠 Cookie 鉴权（不带 SPA 动态 JWT 头也能读取，已实测）。
  - 已验证（本机 Windows，JDK 21，仓库 Gradle 9.3.1）：`:androidApp:assembleDebug`、`:windowsApp:compileKotlinWindows` BUILD SUCCESSFUL；`:shared:desktopTest` 975 项中 5 项失败均为 macOS 专属旧测试（`DesktopAgentIntegrationTest` 3、`MacUninstallCleanerTest` 2，Windows 上本来就会失败），图书馆 36 项（协议 6、数据层 16、模型 13、真实链路 1）全部通过。IDE 自动改的 Gradle 9.8.0 wrapper / daemon JVM / tooling.parallel / `.idea` 已全部还原，wrapper 保持 9.3.1 并保留 SHA256 校验。
  - 本机构建准备：生成了仅测试用 keystore `~/.android/bjtu-kmp-upload.keystore`（alias `bjtu-kmp-local`，口令属本机环境信息，未记录在仓库），`multiplatform/local.properties` 写入 sdk.dir；对应本地签名口令写在用户级 `~/.gradle/gradle.properties`（均被 gitignore/仓库外，不提交）。AGP 已自动安装 SDK Platform 36。
  - 顺手修复 Android Studio 同步失败：`desktopApp/build.gradle.kts` 的 `resolveDesktopPackageJavaHome()` 在无 `JAVA_HOME` 的 IDE 守护进程里直接 error，导致整仓 sync 失败；已加 `System.getProperty("java.home")` 兜底（打包仍要求完整 JDK）。另注意 `multiplatform/gradle/wrapper/gradle-wrapper.properties` 被 Android Studio 改成 Gradle 9.8.0（未提交，需用户决定保留或还原 9.3.1）。
  - 待办：Android 真机验证（用户用 Android Studio 打包）；续借为写操作尚未真实执行。
  - 真实链路联调（`LibraryLiveTest`，用户凭据，2026-10-21）：登录 + 用户信息 + 当前借阅 1 条 + 历史 8 条读取成功（`cardStatus=1`）。关键修复：`/OAuth2/bjtu/login` 返回 `status:true` 后还必须跟随 `find/sso/login/bjtu/0?data/time/enc`，这一步才写入 API 鉴权所需的 `SESSION` Cookie；只做到 login 会得到 401。
  - 真机续借验证（2026-10-21）：续借成功但界面一度显示"失败原因 · 2026-11-20 17:50:25"。根因：续借响应 `data.result` 是 loanId→值，`fail==0` 时值是**新应还日期**、`fail>0` 时才是失败原因；已按 fail 区分，成功时展示"新的应还日期"，并把新日期带进成功文案；补了 2 个回归测试（数据层 16 项、模型 13 项全过），APK 已重打。
  - UI 反馈修复（2026-10-21）：整张借阅卡可点选（复选框/续借按钮仍独立响应）；借出/应还改为 FlowRow，放不下时整段换行避免"应还日期"被拆两行；已重编译 APK 与 Windows 源码运行。
  - Windows 标题栏修复（2026-10-21）：`windowsApp/Main.kt` 原无条件设置 `DWMWA_USE_IMMERSIVE_DARK_MODE=1`，浅色系统下标题栏纯黑；改为按注册表 `AppsUseLightTheme` 设置 0/1，并在窗口生命周期内每 2s 在 IO 线程轮询（实测单次 JNA 读注册表 ≈40µs，CPU 占用 ≈0.002%），运行中切换 Windows 应用深浅色也能跟随（未用 WndProc 子类化，避免 JNA 回调风险）。
  - 应用目录修复（2026-10-21）：`applicationSections` 原来是"候选 − 底栏"，把已加到底栏的入口从「应用」页移除；改为返回全部功能入口（底栏只是快捷方式），更新 `NewMigrationNavigationTest` 断言，APK 与 Windows 源码实例已重打。
- iOS 课程详情关闭后卡片无响应（PR #35）：已合入 main（`f454d3a`），修复细节见 `history_full.md` 待补日志。

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

