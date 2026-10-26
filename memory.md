# BJTUselfService 实时工作记忆

> 2026-10-26：当前分支 `feat/courseware-fallback-playback`。PR #69 已合入此分支，合并提交 `e0e8fa9a`；包含既有 PR #68、安卓修复 `e9a457f4`、iOS/全平台全屏与二级页修复 `358601c7`。源码为 `1.8.6-KMP` / Build 44，iOS 最低 18.0；当前功能分支尚未合入 main。
>
> 回放修复代码与相关 CI 已完成，真机验收仍有边界：安卓整节下载合成由用户下午继续检查，尚未收到完整输出结果；iOS App 的实时活动/原生返回/全屏，以及桌面全屏的实际界面尚未验收。不能把编译、短样本或 DOM fixture 当成整节真机成功。

## 进行中：课程回放分支收尾

### 已提交的行为

- 课件备用下载：未开放网页下载的资源仍可在既有接口/白名单内取原件，按 Range 分段流式保存；不新增明文或鉴权范围。课程回放是独立入口，使用学校 HTTPS HLS，无 Cookie 的公开媒体通道。
- 下载与合成：纯 Kotlin TS→MP4 无损转封装；可选教师/课件两路左右合成，声音取教师路（Windows 限制见后）；提供高/中/低三档 1920×540 @2.5 Mbps、1280×360 @1.2 Mbps、960×270 @0.6 Mbps。源只有 1080p 一档，不存在可切换的低清源。
- 并发与计速：普通分片最多 4 条并发、固定滑动窗口，按顺序交付；速度为单调时钟最近 4 秒实际字节增量，每 500 ms 采样，断流归零，断点重放不计网络流量；预计总量独立计算。未引入 aria2 或 ffmpeg，课件 Range 下载仍串行。
- 安卓 `e9a457f4`：原生 MediaPlayer/TextureView 预览与双路同步播放；全屏切横屏并排，退出继续播放。修复相同通知状态误停前台服务引起的闪烁与启动超时；服务立即 startForeground，同一 ID 2102 原位节流更新，静态图标。TS 解析/MP4 元数据构建离开 Main。网络初次失败后按 2/4/8 秒重试 3 次，耗尽后保留分片和继续入口。AtomicFile 记录、账号摘要隔离的私有工作目录、完整 TS 原子落盘、SAF 持久授权支持进程重启恢复；仅无 query/userinfo 的公开媒体 URL 可落盘。取消/移除/退出账号清理断点。
- iOS `358601c7`：整次任务最多申请一次 BGContinuedProcessingTask，系统进度活动生效时不再同时开自绘下载活动；旧系统或申请失败才回退 ActivityKit。Swift 单 worker 串行 update/end、两秒节流、清理重复自绘活动、结束时清空后台待建状态；成功/失败如实上报，避免累计“已完成”系统条目。保留上课活动。分片仍走系统后台会话（全部入队、每主机 4 连接），按共享 2/4/8 秒策略重试；退避协程归当前批次，取消后不再入队。
- 所有平台全屏：安卓使用原生全屏；iOS 开启 WKPreferences 元素全屏；iOS WKWebView、macOS Safari、Windows 默认浏览器共用的 HTML 增加全屏/退出及 F 快捷键，请求包含两路视频和控件的整个容器，保留播放位置，横屏并排。iOS 手机和平板回放详情改为 PLAYBACK_DETAIL 原生 push 二级页，旧壳回退到页面返回栈，不再使用详情 sheet；保存后可在同页查看任务。
- PR #68：已整合灵动岛展开态裁切、作业卡片开始/截止/评分与 CITEL 状态变化、macOS SDK 启动器、widget 编译检查。PR #69：展开态和锁屏计时行以隐藏且无辅助功能语义的 0:00 占位固定行高，避免开课前/上课中高度变化；展开态课名 headline→title3。保留单行缩放规则。
- iOS 26 申请标识从 Info.plist 白名单读取，兼容 SideStore 改 bundle ID；后台下载/合成仍受系统时限约束，旧系统合成需回前台。历史 Kotlin/Native 命名参数、Volatile/null 和 NSObject 单例链接问题已修，不再列作待办。

### 验证证据与未验证边界

- `358601c7` 的安全检查 [40281987671](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/40281987671) 四项全绿：真实 ActivityKit 宿主 Swift typecheck、iOS 共享测试、macOS 全部共享测试/应用镜像、Android debug/release、Windows 镜像及模板检查。
- PR #69 原头 `e549ed6e` 的安全检查 [40282193442](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/40282193442) 四项全绿，包含 widget Swift 编译。冲突仅 memory.md；先合入最新基线为 `3ecb547e` 再通过 GitHub 合并。PR 应用源码树哈希 `4a2c8e9c56e9cd8199c7df86801338ff04e30abe` 未变，文档整合没有改应用代码。
- Windows 本地最后联合检查：`:shared:desktopTest --tests '*Playback*' --tests '*Hls*' --tests '*NativeShellBridgeTest' --tests '*SecurityReleaseConfigTest' :androidApp:assembleDebug :windowsApp:compileKotlinWindows` 成功，51 项相关测试 0 失败；完整 1163 项有既有 Windows 环境 5 失败（DesktopAgentIntegrationTest 3、MacUninstallCleanerTest 2），Apple CI 的桌面测试全部通过。日志 `.artifacts/ios-playback-fixes/`。
- 小米平板 `6c5a737e` 独立 `.playbacktest` debug 包保留原 App 数据，已实测预览/播放/全屏/退出、三次退避约 2.0/4.0/8.0 秒、耗尽后继续、短断网后自动恢复、强停后 206 片与 SAF 目标恢复、短时后台下载，同一前台服务持续运行。实际崩溃根因为 ForegroundServiceDidNotStartInTimeException，非已证实的 OOM。
- 完整课程任务 `t1792993910544-0` 已启动，两路各 703 片；最后可读采样为 13:56:33 教师流 688/703、1,791,032,384 字节、总任务进度 26.4%。平板随后断开 ADB，无法发送 force-stop，机上可能继续运行；用户决定下午自行检查。接回后先查原任务/输出，不重复发任务或点取消清断点。目标 `Downloads/验证课程_测试录像_教师+课件.mp4`。取证与状态脚本 `.artifacts/tablet-playback/`。
- 旧模拟器验证仅每路 16 片/约 160 秒，不是整节完成；Windows 合成探针与 iOS 模拟器合成用例也不等于真实 App 端到端成功。iOS 活动去重、详情返回和全屏旋转、PR #69 两阶段对齐/长课名/大字号仍待真机界面验收。Apple 完整编译/共享测试仅 CI 验证。
- 共享播放器 JS 语法及 DOM fixture（双路容器全屏、退出、播放/位置保持、横屏、WebKit 回退）已过；本地 file:// 页面被浏览器安全策略拒绝，未绕过，因此桌面真实全屏视觉尚未验收。
- 证据目录：`.artifacts/playback-fixes/`、`.artifacts/pr68-integration/`、`.artifacts/tablet-playback/`、`.artifacts/ios-playback-fixes/`、`.artifacts/pr69-integration/`。完整旧过程可用 `git show e0e8fa9a:memory.md` 查阅；备份在 `.artifacts/pr69-integration/memory-before-consolidation.md`，不把旧“未提交/待 CI”当成当前状态。

### 当前明确限制

- Windows 原生 Media Foundation helper 不打包 ffmpeg，MSI 已验证约 84.27 MiB。当前默认合成仍无音轨；解码+AAC 共存会报 MF_E_SINK_HEADERS_NOT_FOUND，with-audio 仅实验入口。没有可用硬件 H.264 编码器时兼容兜底未完成，约 28 ms/帧的旧探针不能证明整节性能。详见 `docs/plans/playback-composite-android-windows.md`。
- 跨进程任务持久化目前只有 Android 网关接入；iOS 没有同等的任务记录恢复保证。系统后台会话或继续处理任务不等于 App 进程重启后能恢复任务队列。
- 原生前台服务、后台会话和继续处理任务不保证永不被系统终止；已验证的 Android 断点能恢复，不应把旧版本丢失的内存记录声称为已找回。

## 构建与交付

- 本轮手动 KMP package 从当前功能分支最新已推送提交触发，不改版本号或标签，不创建 Release，也不设置 mirror_tag/promote_test；只生成四端测试包。
- 本次运行链接、实际 head SHA 和启动状态记录在 `.artifacts/pr69-integration/package-dispatch.json`；打包结果以 [KMP package Actions](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/workflows/kmp-package.yml) 为准。旧包不能代表此次源码。
- 旧回放四端包 run 40249534874/40252333772、旧源码 24e2786c 的 run 40269212333 都不含此次全部修复。2026-10-22 的 Build42/43 本地产物与配对测试记录见 history_full.md 和上述旧 memory，只作历史取证。
- iOS Release IPA 的 framework 链接、宿主与扩展/ad-hoc 签名由 KMP package 验证；安全检查通过不能替代打包和 SideStore 安装。当前源码版本仍为 1.8.6-KMP / Build44。

## 其他仍需跟进的事项

- 设置页“版本与项目”重构（claude/kind-darwin-u2fxah）的 PR/合入状态未在本次核验，保留为待确认，不混入回放 PR。旧离屏测试证据 `.artifacts/settings-version-card/`。
- 私人 Site 的线上版本与手机/电脑真实长码配对、同时上传、ChatGPT 手机授权、Windows 混合 DPI 托盘与退出回调仍需验证；不能用旧构建或虚构数据界面当成端到端成功。
- 既有真机待验收：小组件侧载共享容器与各尺寸、上课前后实时活动切换、通知权限说明、版本弹窗只出现一次、CITEL/编程题真实上传、系统保存/预览/二维码入口、Windows 原生缓存/DPAPI 往返。旧 Build42/43 包的检查不是当前 Build44 真机证据。

## 当前痛点

- iOS 覆盖安装后丢独立密码、设置重置及首页字号/布局异常：用户决定暂按偶发处理，原因未定（SideStore 仅为猜测），停止继续追查。已在主干禁用自动删库并保护凭据；日程等待重启后恢复。若复发再取真机证据；Beta2 / Build43 下载包已包含保护修复；旧 Build42 包仍未替换。

- 智慧教学（课程平台作业/课件）：直连 module 28 仍被学校跳到已下线 CAS，但 1.8.5-EmergencyFix2 起回退经本科服务平台 `jwcZhjx` 建立会话，作业/课件可正常获取（见日志 2026-10-20）；学校改到 SSO 后可去掉回退。
- 同步卡顿：Beta3 已到可接受范围。剩余长帧为同步详情 sheet 弹出（约 50ms）、进入作业页（约 49ms）和原生滚动带来的导航栏布局；`checkSession` 改后台后未复采；壳层随任一状态流整体重组（约 0.4 秒/次同步），彻底解决需拆分壳层。
- 小组件：PR #8 的 ad-hoc 签名后，侧载包的小组件共享容器是否可读尚未在真机确认；周视图、中号两栏、脚注只经 CI 编译，未在模拟器浅/深色下查看，小屏 iPhone 周视图可能偏挤；“正在上/还剩 N 分钟”未在上课时段实机观察。
- 首页物理在线/CITEL 日程项进入二级详情只在代码层实现，模拟器只验证了演示实验。
- “保存二维码并扫一扫”未真机验证（Android MediaStore 保存/去重、扫码后进入小程序/缴费页）；iOS 每次保存会多一张；Android 二维码弹窗未在平板上看。
- HyperOS 小窗↔全屏来回切换未实测（adb 无法切换窗口模式）。
- 课件 15 分钟大文件超时未用 >30 秒真实下载验证（未开放下载的大文件已改为分段下载，不受此限）；iOS/Android“未开放下载”标记只验证编译。
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

1. 先获取用户下午的整节下载合成结果，接回平板后核对原任务、最终时长、首尾可解码、左右并排与声音，取新增崩溃证据。
2. 用本轮新包验收 iOS 下载活动数量、原生返回/全屏与 PR #69 展开态两阶段对齐、长课名和大字号；桌面验证全屏进入/退出。
3. 回放分支合 main、改版本或正式发版由用户另行决定；当前只合并 PR #69 到功能分支并手动打包。
4. 后续新开发从最新 main 建分支；保留以上其他真机待验证与当前痛点，不恢复已解决的旧待办。
