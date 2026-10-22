# BJTUselfService 实时工作记忆

> 最后更新：2026-10-22。
> 当前分支：`main`。2026-10-22 按用户授权直接在本地主干提交数据保护修复并推送（禁用自动删库、凭据保护、启动取消后重试；1088 项测试通过，四端相关编译通过；未重新打包），细节见日志。2026-10-22 所有者明确授权将本次全部修改直接推送 main：完整长码 App 配对、公共模板及文档提交 `27efa42b` / `9de3eade` / `08b9286b` 已进入主干；以 `--force-with-lease` 推送，保留原有历史。模板 PR #54 已关闭。私人 Site 已部署成功（源码 `495efffe`、`has_mcp=true`）。本机唯一远端 `origin` = `JasonZhang1225/BJTUselfService-KMP-Refreshed`，GitHub 已脱离 fork。验证与交付细节见日志；本地三端包已生成（源码 `6f4b07d8`，未发版；用户已覆盖安装 IPA，未包含本次数据保护修复）：Release APK 约 5.8 MiB、DMG 约 82.5 MiB、供 SideStore 自签的 Release IPA 约 22.0 MiB，均为 arm64；目录 `~/Downloads/BJTUselfService-1.8.6-Beta-6f4b07d8-20261022/`，含 SHA256SUMS.txt 和构建说明。
> 上次任务分支：`docs/agent-setup-main`（基于 `main@9c7eb55a`；Agent 搭建入口改 main，按用户授权直接推送；验证见日志）。PR #40 图书馆、#41 灵动岛/实时活动/手表、#42 作业搜索与教室当前节次、#46 课程实时动态（Android Live Updates + 双端课前准点出现）均已合入（合并提交 `64c24ab`、`6f5c36f`、`8189baf`、`aac1816`）；#45 验证码模型移除与安装包瘦身（合并提交 `a58e7c6`）、#48 iOS 作业上传修复与上传页统一（合并提交 `ad2a082`）均经所有者实测后合并（2026-10-22），合并后主干安全检查 #179 与打包 #113 见日志；#50 AI Agent 与 ChatGPT 私人远程查询（合并提交 `a18369d`）已合入；#51 v1.8.6 发布说明（`docs/releases/v1.8.6.md`，合并提交 `7513348`）已合入；#52 启动弹窗合并与 1.8.6-Beta 版本号待所有者合并。细节见 `history_full.md`。远端另保留 `claude/宣传图制作`。
> KMP 源码版本：`1.8.6-Beta` / Build 42（已推 `debug-1.8.6-Beta` 标签打包，尚未发版）；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。iOS 最低系统 18.0。
> 2026-10-22 仓库清理：冻结原 Android 工程（`app/` 与根 Gradle 工程、`release.yml`/`debug.yml`）与验证码 torch 模型（含 Windows `BJTUCaptcha.pt`、DJL）已移除，存档于标签 `archive/legacy-android-and-captcha-model`（`8189baf`）；Windows 验证码改为手动输入。只保留 `origin` = `JasonZhang1225/BJTUselfService-KMP-Refreshed`；所有者已确认在 GitHub 解除 fork 关系，本机远端配置也已对齐。
> 认证现状（2026-10-20）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码；智慧教学仍卡在学校的旧 CAS 跳转。
> 已完成工作的细节见 `history_full.md` 末尾日志。

## 进行中

- 本地安装包验证记录（未发版）：`main@6f4b07d8` / `1.8.6-Beta` / Build 42。`JAVA_HOME=<Android Studio JBR> DESKTOP_PACKAGE_JAVA_HOME=<Temurin 25> ./gradlew :androidApp:assembleRelease :desktopApp:packageDmg` 成功；`xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination generic/platform=iOS -derivedDataPath ../.artifacts/local-package-6f4b07d8/ios-derived CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY= build` 成功。APK 经 apksigner、aapt 验证；DMG 经 hdiutil verify、只读挂载后的 codesign/版本/架构检查，已卸载验证卷；IPA 经仓库 sign-ios-sideload-app.sh 依次 ad-hoc 签名并验证 App Group、宿主/小组件版本与 codesign，unzip -tq 通过。产物已交付下载目录，日志 `.artifacts/local-package-6f4b07d8/`；未安装或替换 App，未验证真实安装/配对，不代表发布签名成功。

- 已合入功能的待真机验证（不阻塞）：
  - 灵动岛/实时活动：收起态最终版截图（等实际上课时）；开课时“即将上课”切“正在上课”；两个实时活动并存时的最小态小圆环；Live 菜单读屏自定义操作。
  - iOS 26 预约启动（PR #46）：真机上的启动提醒声音与灵动岛展开效果；重叠/紧挨日程的实际表现；划掉系统启动的那一项后不再出现。
  - Android 实时动态（PR #46）：非 HyperOS 的原生 Android 16 上的样式；长时间不打开 App、系统降低待机分组后闹钟是否仍准点；最近任务未加锁时划掉 App（强停）会清掉闹钟和通知（按规则推断，未实测）。
  - 图书馆：登录流程在 Copilot 修复后改过，需真机重新登录一次；iOS 横屏布局（浮动续借胶囊、顶栏高度）；Android 真机；续借只在早期版本实测过。
  - 作业搜索：iOS 系统搜索按钮展开/取消动画、搜索中进详情再返回；Android 返回键收起搜索；上课时段教室当前节次光晕与空闲/占用标签（浅/深色）。
  - Windows：移除 torch 模型后，遇到验证码时手动输入路径未在 Windows 真机走过；PR #45 原生库裁剪后的 MSI 启动与本地缓存（SQLite）读写、DPAPI 凭据读取未实机验证（Windows 冒烟未在最终提交上运行）。
  - 作业上传（PR #48）：CITEL 上传与编程题单文件提交未真机实测；Android/桌面上传由对话框改为底部 sheet，未实机查看；sheet 内保存面板（日历导出）与快速查看的真机表现未点验。
  - 完整长码配对（main@08b9286b）：真实手机与线上 Site 的配对、手机/电脑同时上传、原生容器交互、此次签名包的 Keychain 往返、Windows 实机未验证；已安装 App 需更新后才有单输入框。
  - AI Agent / ChatGPT 插件（PR #50）：手机 ChatGPT 端到端查询（卡在插件补授权，需在手机上重新连接后验证新工具目录）；电脑与手机同时配对的实机验收；Windows 托盘在混合 DPI 多屏下的位置与退出回调。
  - 1.8.6 版本说明弹窗（PR #52）：新装/升级各走一遍，确认后不再出现；Android 版本说明后接通知权限说明的顺序。
  - PR #45 登录收紧：需要验证码时的手动输入弹窗、静默自动登录失败后的引导弹窗、Android release（R8）包的签名覆盖安装，均未真机走过。

## 当前痛点

- iOS 覆盖安装后丢独立密码、设置重置及首页字号/布局异常：用户决定暂按偶发处理，原因未定（SideStore 仅为猜测），停止继续追查。已在主干禁用自动删库并保护凭据；日程等待重启后恢复。若复发再取真机证据；当前下载目录旧 IPA 未含保护修复。

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
- 使用中会话过期且学校恰好要求验证码时，后台会话恢复会失败，提示“点右上角刷新”也无效，需退出重登或重开 App 才会看到验证码（各端已无识别模型）。用户 2026-10-22 决定暂不在主界面加验证码弹窗。
- 本地 Linux 容器跑 `:shared:desktopTest` 时 `DesktopAgentIntegrationTest.installWritesWorkingScriptAndSkill` 因缺命令（退出码 127）失败，main 上同样如此，与改动无关。

## 迁移期遗留（长期，无排期）

- iOS Keychain 合法签名往返、正式签名发布（无 Apple Developer Team）。
- 物理在线真实上传、Windows 触摸屏；邮箱真实发送/删除/移动/附件上传；真实作业上传、退出账号往返（等学校侧条件）。

## 接下来

- 后续开发统一从最新 `main` 建分支，PR 以 `main` 为目标。

1. 在真机已有版本（SideStore 保留扩展）上验证小组件共享容器与各尺寸显示。
2. 华为 HBN-AL10（HarmonyOS 4.2）装含 PR #32/#33 的新包：确认课表小组件尺寸（logcat `CourseWidget` 标签）与教务不可达时不再闪退。
3. 真机过一遍“进行中”列出的待验证项。
4. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。
5. Android 首次打开的通知权限说明（分支 `feat/android-notification-intro`）：说明框已在启动队列里排在 1.8.6 版本说明之后，只经编译验证，未在模拟器/真机走过。多余启动弹窗已于 PR #52 合并为一个 1.8.6 版本说明。
6. 清理 main 里经 1.8.5 紧急修复带进来的半成品 AI 代码（PR #50 合入前的计划，未开始）。
