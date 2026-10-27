# BJTUselfService 实时工作记忆

> 2026-10-27：分支 `feat/courseware-fallback-playback`，本轮起始同步基线 `6d0609b5`（PR #70），本轮安卓源码已按验收结果提交为 `b05426ec`、`f1f06c79`、`51ad5b3c`、`aae42176`，已推送到远端 `feat/courseware-fallback-playback`（头 `3c1740c`）；功能分支未合入 main。安卓提速与跨芯片兼容的调研计划在 `claude/intelligent-mendel-557os7` 分支提交。源码仍为 `1.8.6-KMP` / Build44，iOS最低18.0。
>
> 用户持续目标：先完成安卓完整验收并分批少量提交，再做 Windows AMD/NVIDIA/Intel 硬件加速和稳定下载合成。安卓三个验收目标已通过，实测不暂停总耗时约16分15秒，9–10分钟性能目标未达到。
>
> **下一步计划：`docs/plans/playback-android-performance.md`**（安卓下载合成提速与跨芯片兼容，2026-10-27 调研完成、未实施）。先按其中 P0 采样，再做 P1；Windows 项见下文。不能把安卓完成当成整个持续目标完成。

## 进行中：安卓修复已验收并提交，待功能分支合主干

- `b05426ec`：共享渐进下载接口、TS压缩帧/配置解析与AAC处理；两路各2个分片连接，总并发仍4，无新增媒体依赖。
- `f1f06c79`：Android两路硬件解码并行、提前一帧，与GL绘制/硬件编码重叠。平板实测为两个 `c2.qti.avc.decoder` 和 `c2.qti.avc.encoder`。此前 `eglSwapInterval(0)` 启用异步队列导致快送时丢帧，已改回1保留编码背压；每段及最终强校验提交/输出帧数。压缩帧和定长索引落盘，不保留整节索引对象；约60–70秒媒体内容提交一个成品段，重启只重做当前未提交段，最终无损整理。旧小MP4和直接MediaDataSource原型已清理。
- `51ad5b3c`：非用户作用域取消保留PAUSED和保存位置，明确取消/退出账号才清理；历史加载取消不覆盖旧记录，立即暂停也执行收尾。增加暂停/继续、下载/合成/整理/保存独立进度、20秒时间常数的平滑ETA。整理阶段可暂停，继续复用完整编码结果；保存失败可只重做整理/复制。真实AppDataGraph关闭、重建会话仍复用应用执行器的回归测试已过。
- `aae42176`：Android应用按账号持有唯一执行器、独立公开媒体transport、applicationContext和URI；页面/会话重建不替换下载执行器。明确退出账号/清除数据先等待任务停止写盘。debug独立入口支持实机完整管线、后台返回、重建、断网、覆盖安装和恢复；仅测试包，不读取学校凭据。

### 整节实机验收证据

- 设备：小米平板8 Pro（`6c5a737e`、Android17），独立 `.playbacktest` debug包，原App未替换。无线ADB `192.168.10.2:36171` 已授权，已换AC充电器，电量回升；后续显式指定无线序列号，不能误操作模拟器或原App。
- 恢复验收 `t1793028955283-0`：10月26日23:35:55→23:51:17 DONE。每路703片，总源3,853,388,624字节；成片 `Downloads/android-goal-final-20261026.mp4`，753,571,868字节、1:57:02.56、1920×540/25fps H.264，175564帧，AAC 44.1kHz单声道302430包。每个视频PTS间隔严格40000us；整段 `ffmpeg -v error -xerror -threads 0 -i goal-final-with-recovery.mp4 -f null -` 退出0。音轨SHA256与原轨完全一致：`61fcd3f23860e39eda9f7f39669d9de62ceb8f24430028bf69e83d91d1f99c09`。
- 此轮后台返回、页面重建不掉任务，服务同实例且lastStartId=1；下载96%/合成23%手动暂停，保留686/668个原片、23个完成编码段。强停重开继续，从1568280000us恢复，仅补约141MB；最终102个成品段，前23个元数据完全未变，恢复后136357=136357帧。首/中/尾和恢复边界画面、平板媒体查看器实际播放已检查。覆盖安装后DONE仍在。含人工暂停约65秒，总约15分22秒，不作为无干预成绩。
- 最终不暂停计时 `t1793030228149-0`：10月26日23:57:08→10月27日00:13:23 DONE，目标 `Downloads/android-goal-timed-20261026.mp4`，757,329,607字节。源码增加独立FINALIZING整理进度后再次真实全量下载，无断点复用。175564视频帧、每帧PTS间隔40000us、AAC302430包与上述SHA256完全一致，整段ffmpeg解码退出0。强停重开后DONE保留，原始临时分片已清理，通知服务结束。
- 该轮下载完成首次观测00:05:22（约8分14秒，源字节平均约7.8MB/s，前慢后快）；编码含等待输入在00:11:33完成，175564提交=175564输出；整理首次观测00:11:35，保存首次观测00:13:06，DONE首次观测00:13:23。整理约1分31秒、保存/收尾约16秒；阶段观测间隔5秒。下载与编码重叠，不能相加；全程约16分15秒，不能声称达到9–10分钟。
- 同期UI计速约3.9MB/s时系统约3.75MB/s；网络随后恢复，平均仍接近8MB/s。ETA按下载与合成瓶颈估算，最终“整理中”53%及不足1分钟已实机观察。浅/深色、1.4倍字体、预览/双路播放/全屏、三次2/4/8秒重试及耗尽后继续的既有实机证据仍见下方与原目录。

### 构建与未验证边界

- `build-finalizing-progress.log`：Android assembleDebug和66项相关测试全过；新增会话组合根重建测试 `test-session-rebuild.log` 通过。最终完整 `:shared:desktopTest` 共1176项、1171通过，5个既有Windows环境失败仍为DesktopAgentIntegrationTest 3和MacUninstallCleanerTest 2，新增回放相关用例均通过。提交后相关联合复查记录在 `build-committed-android.log`。
- `:androidApp:minifyReleaseWithR8` 成功（`build-release-r8.log`），只编译/缩减，未签名或安装正式包。正式 `assembleRelease` 因本机缺上传密钥口令在配置阶段停止；正式签名仍待CI。Apple共享目标本轮未构建，不能引用旧CI证明本次源码。
- 第一轮旧串行整节 `t1793017919242-0` 曾完整解码且音轨一致，纯合成约16分半；不能用它替代新流程验收，也不把已发现丢帧的11秒/160秒原型当性能成绩。
- 尚保留旧测试任务 `t1793024019616-0`（PAUSED 60.5%，703×2原片、64成品段）和 `t1792993910544-0`（两路完整原始MP4，供性能对照/Windows复用）。不得误当进行中的新任务或误删原App数据。
- 源码、构建、JSON状态、逐帧时轴、音轨校验、视频和截图证据在忽略目录 `.artifacts/android-full-acceptance/`、`.artifacts/tablet-playback/`。真实账号自动刷新的原现场未直接复现；已用平板页面重建与真实业务组合根回归覆盖取消路径。未复现的ANR/资源过量等历史退出不能宣称已全部根治。

## 接下来：安卓合成提速与跨芯片兼容（计划见 `docs/plans/playback-android-performance.md`）

- 基线拆解：下载8分14秒（≈14.2×实时）与合成重叠，合成到14分25秒才完（≈8.1×实时、约203帧/秒），之后整理≈1分31秒、保存≈16秒。瓶颈是合成而非网络；按公开规格粗算视频单元已用约六七成（未实测）。
- 已识别问题：整理逐样本重封装（约8MB/s）；保存二次复制且每1MB刷新一次状态；合成主循环逐帧串行、解码只提前一帧；解码器未设优先级、需查 `offline rate hint rejected`；课件路34.5fps约27%帧解码后即丢弃。
- 跨芯片：只用标准MediaCodec/Surface/EGL，不依赖高通私有接口。风险为中低端芯片速度、三个并发硬件会话被拒无回退、编码器自行丢帧会令帧数核对反复失败、着色器未用 `SurfaceTexture.getTransformMatrix()`（1088对齐裁剪隐患）。
- 推荐顺序：P0 采样（日志三项耗时、只解码/只编码/完整流程基准、源帧参考类型）→ P1 分段盒层面直接拼接+增量整理+直写目标（预计尾段≈1分47秒→≈10秒）与变换矩阵修正 → P2 按采样结论选两条流水线并行或跳过不被选中的非参考帧 → P3 下载并发A/B；兼容性加固并行推进，需一台天玑设备验收。
- 预期：只做P1约14.5分钟；合成≥14×实时后约8.5–9分钟。均未实施、未实测，不能当作已达成。

## 接下来：Windows硬件加速与稳定性

- 按用户安排与安卓提速计划排序后启动；目标为AMD/NVIDIA/Intel可用硬件路径、可观的下载合成速度和稳定恢复。
- 当前旧Windows实现是软件解码/CPU缩放拼接、编码优先硬件，默认仍无音轨；需先采样和检查实际GPU/工具链，再实施完整GPU路径并验证。尚未宣称全平台硬件加速已完成。
- 9–10分钟是安卓后续性能目标，当前实测未达到，路径见上节计划；不能通过降帧/丢帧冒充提速。整个持续目标仍在进行。


## 已合入功能分支、待主干：CITEL 折扣时间配色与考试 sheet 测高（PR #70）

- `isFavorableChange` 把“折扣时间”延后与截止时间延后同等视为向好（绿色），补 `HomeChangeFieldTest` 两条断言。
- 考试页紧凑端“考试详情”和“添加到日历”两个 sheet 由 `needsFullHeight` 改为 `fitContentHeight`：iOS 走按正文定高的自定义 detent，安卓/桌面底部 sheet 按内容高度展开；正文不再自带 verticalScroll，由 sheet 滚动。未在模拟器/真机验收。

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
- 旧任务 `t1792993910544-0` 今晚接回后确认已下载两路并转好 MP4，总源 3,853,388,624 字节，停在合成初期，进程已不在；两路输入保留作同课程性能基准，未将旧部分输出当成完成。
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

1. 按 `docs/plans/playback-android-performance.md` 推进安卓提速：先 P0 采样，再 P1（直接拼接/直写目标、变换矩阵），P2/P3 依采样结论决定；每步单独验收与提交。
2. 之后按顶部持续目标推进 Windows 硬件加速、速度和稳定性，不重复旧安卓待办。
3. 用本轮新包验收 iOS 下载活动数量、原生返回/全屏与 PR #69 展开态两阶段对齐、长课名和大字号；桌面验证全屏进入/退出。
4. 回放分支合 main、改版本或正式发版由用户另行决定；当前只合并 PR #69 到功能分支并手动打包。
5. 后续新开发从最新 main 建分支；保留以上其他真机待验证与当前痛点，不恢复已解决的旧待办。
