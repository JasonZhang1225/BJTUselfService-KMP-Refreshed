# BJTUselfService 实时工作记忆

## 已合入：作业要求格式（PR #75，2026-10-28）

PR #75 原工作分支 `fix/homework-rich-text`，基于视频回放分支 `feat/courseware-fallback-playback` 的 `8e063741`。物理在线作业要求保留换行和常见文字样式并兼容旧缓存；课程平台详情共用格式展示。共享格式、物理在线解析和缓存测试通过，Windows/桌面编译与 Android debug 构建通过；Apple 编译交 PR CI，真实页面及界面状态尚未验收。旧物理在线缓存需刷新详情才能获得格式。

**实现与验证**

- 物理在线保存去除属性和主动内容的格式片段，同时保留纯文本供旧调用方使用；课程平台展示支持换行、粗体、斜体、下划线和删除线。图片与复杂网页布局仍需打开网页。
- 本地执行 `:shared:desktopTest --tests *SchoolRichTextTest --tests *PhyVlabHtmlParserTest --tests *PhyVlabCacheCodecTest` 通过；`:windowsApp:compileKotlinWindows :desktopApp:compileKotlin :androidApp:assembleDebug` 通过（离线、Android debug 签名）。iOS/macOS 原生与设备视觉、真实服务器数据未验证。


> 2026-10-28：当前分支 `fix/courseware-streaming-save`，基于 `feat/courseware-fallback-playback` 的 `8e063741`；提交 `aef782c0` 已推送，PR #74 指向 `feat/courseware-fallback-playback`；旧提交的手动 KMP 打包 run `40650703855` 成功，安全检查 run `40650707435` 的 Apple 任务取消；同提交 PR 自动安全检查 run `40650671363` 成功。课件流式保存、批量进度和 Windows 现代保存面板待合入视频回放分支；Android 目录 URI 与单文件大小修复已获用户认可，待合入回放分支。其他平台的视频下载暂搁置。 最新 Windows 测试包 `交大自由行 KMP-2.0.0-Alpha-local-20261028-152439-059.msi` 已放下载文件夹，未安装。

> 2026-10-28：用户明确标记 **Windows 课程回放开发与本轮验收完成**；代码已提交推送 `133cf398` 到 `feat/courseware-fallback-playback`。四端审查标准随本轮文档提交；跨平台安全检查 run `40631769655` 四项全部通过。新 MSI `local-20261028-111451-103` 已在下载文件夹；代理未执行安装。沿用 2.0.0-Alpha / Build45，不改版本号，功能分支未合 main。
>
> 2026-10-27：分支 `feat/courseware-fallback-playback`，回放与设备显示已提交 `19585a4a`，已合并远端 PR #72（`8847b785`），功能分支尚未合入 main。源码版本为 `2.0.0-Alpha` / Build 45，发布说明草稿 `docs/releases/v2.0.0-KMP.md`；iOS 系统版本与桌面安装器使用数字 `2.0.0`，iOS 最低 18.0。
>
> 用户持续目标：Windows 硬件加速与下载合成并行已完成本机隔离验收；安卓计划及现有修改保留，设备暂不在身边，ADB 与实机验收留待后续。安卓旧版整节验收实测不暂停总耗时约16分15秒，9–10分钟性能目标未达到。
>
> **统一标准与后续工作：`docs/plans/playback-four-platform-acceptance.md`**。Windows 已结项；Android 按 PROMPT2 先做 P0 真机测量，再按 PROMPT3 做 P1 定向优化；iOS/macOS 先补当前包真实整节与能力差距验收。旧 Android 计划已加已实现事项更正，禁止重复实现。

## 已完成：Windows 保存结果入口、预览与最终验收（2026-10-28，功能分支待主干）

- 用户确认：在线播放、真实下载已验证；不再排查网卡速度。授权检查 D 盘根目录两份成片并执行压力测试，新增完整路径、“打开视频”“打开文件夹”，同时修复 Windows 预览只有占位文字的问题。
- 成片检查：D 盘两份 MP4 只读检查完成，教师左/课件右的首尾画面正常，H.264/AAC；大学物理 1280×360、175564 帧、7022.56 秒，智慧高铁 1920×540、175582 帧、7023.28 秒。两份 `ffmpeg -v error -xerror -threads 2 -i <成片> -f null -` 全解码退出 0。高铁尾部课件路“无视频信号”是已录入画面的源内容，非缺失 MP4 轨道。证据 `.artifacts/windows-playback-final/`；原视频未修改。
- 当前源码：`PlaybackTaskManager.kt` / `HomeworkFileGateway.kt` 增加可选输出位置，`WindowsHomeworkFileGateway.kt` 提供完整路径；`PlaybackWorkspace.kt` 与新 `PlaybackSavedFileActions` 各端文件实现路径选择/换行及两按钮，打开系统默认播放器/父文件夹。旧任务缺失路径时明确提示，不猜目录；清理缓存/重启后新路径仍保留，字段兼容旧 JSON。
- Windows 预览原来只查找 macOS 截帧助手，故只显示占位文字。`PlaybackPlayerHost.desktop.kt` 现在只下载第十分钟附近一片白名单 HLS，复用 TS→MP4 与 `BJTUPlaybackCompositor.cpp` 新增 thumbnail 命令输出 BMP；最大两条并发、24 张成功缓存、30 秒超时，支持取消与失败重试。`WindowsPlaybackCompositor.kt` 对助手释放加锁，避免多个预览同时写入同一临时文件。Android/iOS 文件操作接口默认关闭。
- 最终联合命令（JDK21、`multiplatform/`）：`-I ..\.artifacts\windows-playback-final\validation.gradle -I ..\.artifacts\android-account-debug-signing.gradle :shared:desktopTest --tests '*Playback*' --tests '*Hls*' --tests '*Courseware*' --tests '*TsToMp4*' :shared:dumpFinalSharedClasspath :windowsApp:windowsTest :windowsApp:dumpFinalPlaybackClasspath :desktopApp:compileKotlin :androidApp:assembleDebug :windowsApp:packageMsi -x downloadWix --offline --no-configuration-cache --console=plain --continue`，BUILD SUCCESSFUL（3m12s）；共享专项138项无失败/跳过，Windows33项无失败、1项真实站点跳过（硬件用例显式启用并通过）。完整共享重跑1218项、仅既有Windows环境5项失败，新增用例全部通过。新测试文件为 `PlaybackSavedFileActionsTest.kt`、`WindowsPlaybackPreviewTest.kt`，并扩充 `WindowsPlaybackRecoveryTest.kt`。首轮新增测试的非void返回已修正，最终报告无此失败。
- 压测初次前三轮成功，第四轮因同时重编共享 JAR 导致 NoClassDefFoundError，已废弃该组成绩。固定项目测试类/JAR副本后，`java -cp <windows-frozen-classpath> org.junit.runner.JUnitCore team.bjtuss.bjtuselfservice.windows.WindowsPlaybackHardwareAcceptanceTest` 独立运行五轮全过（每轮27–31秒）；共35个入队任务、25份成片全解码，覆盖排队、暂停恢复、取消、强杀helper后后续推进与临时文件清理。不是多小时耐久或Intel/CPU整节压力测试。
- UI：三张实际Compose离屏图已检查浅色/深色/440宽1.6倍字号；Computer Use 实际点击新卡片，文件缺失会就地提示，父文件夹成功打开，视频交给系统打开方式选择器（未改默认播放器）。隔离窗口指定当前helper后，真实学校公开HLS的教师/课件两路预览均显示正常；未登录其他账号、未覆盖安装。初次共享测试UI类路径没有Windows资源，显示助手缺失，已用明确本地helper路径重启复验。证据 `.artifacts/windows-playback-final/{release-check.log,shared-full-final/,hardware-frozen-results.jsonl,ui-*.png,manifest.json}`。
- 本轮交付：包含上述源码的新 MSI 已在下载文件夹（见下节），实际解包核对构建标识和原生helper哈希一致。用户已明确结项并授权提交推送；主干合入/发版另行决定，代理未安装新包。iOS共享测试、macOS共享测试/镜像已由本轮CI通过；Apple真机运行仍待设备验收，本机仅完成macOS JVM宿主编译；Intel真机、CPU整节、多小时耐久继续保留未验证边界，不作为本轮Windows完成阻塞。

## 四端审查标准与下一阶段（2026-10-28）

- 新文件 `docs/plans/playback-four-platform-acceptance.md` 统一 F01–F14 功能合同、媒体帧/PTS/DTS/AAC/完整解码要求、阶段计时与证据模板，以已完成 Windows 为参照；区分实现、测试、平台完成、四端完成与发布就绪。
- Android 按 PROMPT2 先完成小米平板8 Pro 的 P0 真实测量，枚举确认序列号、全部操作显式 `adb -s`，只安装 `.playbacktest` 隔离包；快整理、矩阵、候选回退已经实现，不重复开发。报告后按 PROMPT3 对最大两个瓶颈做 P1 定向优化，一项一测一提交。iOS/macOS 先整节，iOS AAC 重编码与两端持久恢复/文件重新访问差距明确列待办。本次不操作设备或实施上述优化。
- 两份旧计划 `playback-android-performance.md`、`playback-composite-android-windows.md` 顶部已更正状态，保留原分析作历史，避免把旧“未实施/四端完成/Windows无声”等描述当作当前结论。
- Windows 代码 `133cf398` 已推送。安全检查 [40631769655](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/40631769655) 精确 head 为 `133cf398`，四项全部成功，含 macOS 全部共享测试、iOS 共享测试、Apple 原生检查/镜像、Android debug/优化版和 Windows 镜像。随后只改文档，应用源码树 `5f7c58c7a8c0a8da20aad31366663612c1b304ad` 不变；没有创建PR、合主干、改版本或发布。链接检查与 `git diff --check` 通过；原始最终状态在 `.artifacts/playback-four-platform-review/ci-final.json`。

## Windows 视频回放与下载完成度复核（2026-10-28，初次结论及后续更新）

- 当前结论：**Windows 本轮已完成**。初次复核时未将在线回放标通过，后续已由用户确认，并补齐上节成片、压力、路径/预览验证；不再沿用旧的“尚不能结项”结论。
- 本次实际读取：共享回放/HLS/合成测试 XML 无失败；Windows 默认测试报告共 32 项、0 失败、2 跳过；独立 `hardware-queue-verified.xml` 证实可选 RTX 4060 硬件测试已另行运行通过（26.44 秒，五项输出全解码）。`full-exact-head.log` 证实完整共享测试 1214 项仍有既有 Windows 环境 5 项失败，不能称全量全绿。连接器重新确认安全检查 run `40545883628` 四项 success。
- 整节下载约 5 分 37 秒、NVIDIA/AMD 整节合成、帧数与 AAC 哈希等结论沿用下文既有工作记录；其指向的 `.artifacts/windows-gpu-playback/` 当前工作目录不存在，本次未重新核验这些原始产物。
- 在线播放调用链为共享 `PlaybackPlayerPage.kt` 生成 HTML，桌面 `PlaybackPlayerHost.desktop.kt` 写本地页面并交默认浏览器。早期自动化因 file:// 页面策略未验收；用户本次明确确认在线播和真实下载已验证，以用户验收为准，不再列作未完成入口。
- 初次复核所缺的在线播放/真实下载已由用户确认，D盘成片检查与新UI隔离验收见上节。Intel真机、CPU整节作为兼容性边界保留。Windows暂停保留下载分片、继续重新合成是当前明确限制。功能分支未合主干，不写已合入日志、不改版本或发布状态。

### 下载速度显示差异排查（2026-10-28）

- 用户截图：App 40.7 MB/s，TrafficMonitor 下载 224.64 MB/s。本机只读检查 `D:\TrafficMonitor\config.ini`：`select_all=true`、`unit_byte=true`、采样 1000ms；官方文档确认“统计所有连接”会累加连接流量。机器同时存在 WLAN、Meta Tunnel、WSL/VMware 虚拟网卡；多接口重复统计是高读数的主要怀疑，尚未捕获截图同一时刻的逐接口数据，不能断言 224.64 的全部来源。当前 WLAN 链路 1.2 Gbps（理论约150 MB/s，不能解释224.64 MB/s的单无线链路外网下载）。本次3秒网卡采样时已无高速下载，不作为截图同期验证。
- 源码确认 App 汇总两路 `transferredBytes`，排除本地缓存重放，每500ms按最近约4秒采样；字节在完整分片按顺序落盘并交给渐进管线后计入，属于有效分片处理速率，有平滑与回调延迟，并非底层网络瞬时速率。未发现固定倍数漏算；未改测速工具配置。用户已要求网卡速度不用再管，本事项停止跟进，不列待验收。官方依据：https://github.com/zhongyang219/TrafficMonitor/wiki/Select-the-Monitoring-Network-Connections 。

## 进行中：KMP Package 打包标识

- 已本地提交，尚未合入 main：四端文件名与设置页显示 Actions 工作流运行序号和重试次数（如 `129.2`）；本地构建显示 `local-yyyyMMdd-HHmmss-SSS`，时间固定北京时间。每次执行生成任务都会更新本地标识，可能触发共享代码重编译。不改变产品版本、系统构建号或更新比较；仅重跑失败任务可能让四端标识不同。
- 北京时间验证：将 Gradle JVM 默认时区设为 `America/Los_Angeles`，两次生成时间均与北京时间相符且不同；CI 标识 `130.2` 不变。最终四端和界面尚需验证。
- 本地时间改动后 `:shared:desktopTest :windowsApp:compileKotlinWindows :androidApp:assembleDebug --continue`：Windows 编译、Android debug APK 成功；共享测试 1214 项、上述 5 项既有 Windows 环境失败，整条命令因测试失败。桌面 jar 中实际常量已核对为北京时间 `local-20261028-093234-789`；未安装应用。
- 使用 JDK 21，`-I ..\.artifacts\android-account-debug-signing.gradle :shared:desktopTest --tests '*AppUpdateCheckerTest*' :windowsApp:compileKotlinWindows :androidApp:assembleDebug` 成功，Gradle 配置缓存已保存；生成任务依次验证 `130.1`、`130.2` 和 `local`。完整 `:shared:desktopTest` 1214 项、5 项失败：既有 Windows 环境中的 DesktopAgentIntegrationTest 3 项和 MacUninstallCleanerTest 2 项。工作流 YAML 解析和 `git diff --check` 通过。
- 本机上传密钥文件与用户 Gradle 签名配置均已存在；2026-10-28 使用临时 JAR 完成签名和验签。此前 Android debug 验证使用调试密钥，尚未构建正式签名 APK。Apple 目标和真实 Actions 四端打包需要后续验证。

## 已完成：CI 安检两条回放测试

- 失败运行 [40514068604](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/40514068604)（`dddf6aeb`）已由 `40ccbfbf` 修好。
- 重跑 [40521044091](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/40521044091) 四项全绿：Remote site、iOS/macOS security tests、Windows app image、Android debug/release。

## 进行中：本机 2.0.0-Alpha MSI 已复制到下载文件夹

- 最新包：`C:\Users\zjg\Downloads\交大自由行 KMP-2.0.0-Alpha-local-20261028-111451-103.msi`，88,743,936 字节，SHA256 `D9CC8A4413448329BE29F0A725017113AA00898DA6EBCE15AD4801936BE9FBA1`。包含现已提交的路径按钮和Windows预览修复；只读解包核对共享jar构建标识、当前helper SHA256 `DA00E625584B6CAA1FA033FDB691F7B7E6B8441C43349DE965589A7BE3CA3601` 一致，源包和下载副本哈希相同。未安装。证据 `.artifacts/windows-playback-final/{manifest.json,verify-and-copy.ps1,package.wxs}`。
- 2026-10-28 按用户要求基于当前提交 `701409e7` 重新打包。新文件 `C:\Users\zjg\Downloads\交大自由行 KMP-2.0.0-Alpha-local-20261028-094654-102.msi`，88,719,360 字节，SHA256 `875A28A567F0407E60F4EBB054036C0359D007920F1F3016B1E07A67BD14B53B`。旧的无时间戳下载包保留；未安装、未改 Program Files，未改产品版本、提交、推送或发布。
- JDK 21 执行 `.\gradlew.bat :windowsApp:windowsTest :windowsApp:packageMsi -x downloadWix --offline --no-configuration-cache --console=plain`，BUILD SUCCESSFUL（1m49s）；Windows 测试 32 项、0 失败/错误、2 跳过（可选硬件和真实站点用例）。首次默认配置缓存命令因 Compose `downloadWix` 使用 Task.project 失败；最终跳过下载并复用本机已有 `~/.gradle/compose-jb/wix311.zip`，没有修改构建源码或安装依赖。
- 使用缓存 WiX 的 dark 只读解包新 MSI，实际核对 MSI 内共享 jar 常量为 `local-20261028-094654-102`，回放 helper 与当前编译产物 SHA256 相同（`431A1EC55214D5E8868EC3A43E410F5E146D373381A17BC94BBBCD3CF6E9300F`），安装器版本为数字 `2.0.0`。源包与下载副本哈希相同。证据 `.artifacts/windows-package-20261028/{build-final.log,manifest.json,verify-and-copy.ps1}`；本次未重跑共享全量测试、硬件整节或在线回放界面，安装与真实账号验收仍待后续。

## 进行中：1.8.6 发布说明与历史 xattr 转义（已提交并推送）

- GitHub Release `v1.8.6-KMP` 已补四端 Github 下载列。
- `faccdb88`：本地 `docs/releases/v1.8.6-KMP.md` 用线上正文覆盖（保留标题与日期）；历史版本 macOS `xattr` 路径补为 `交大自由行\ KMP.app`。已推送到 `feat/courseware-fallback-playback`，尚未合入 main。

## 进行中：安卓修复已验收并提交，待功能分支合主干

- `b05426ec`：共享渐进下载接口、TS压缩帧/配置解析与AAC处理；两路各2个分片连接，总并发仍4，无新增媒体依赖。
- `f1f06c79`：Android两路硬件解码并行、提前一帧，与GL绘制/硬件编码重叠。平板实测为两个 `c2.qti.avc.decoder` 和 `c2.qti.avc.encoder`。此前 `eglSwapInterval(0)` 启用异步队列导致快送时丢帧，已改回1保留编码背压；每段及最终强校验提交/输出帧数。压缩帧和定长索引落盘，不保留整节索引对象；约60–70秒媒体内容提交一个成品段，重启只重做当前未提交段，最终无损整理。旧小MP4和直接MediaDataSource原型已清理。
- `51ad5b3c`：非用户作用域取消保留PAUSED和保存位置，明确取消/退出账号才清理；历史加载取消不覆盖旧记录，立即暂停也执行收尾。增加暂停/继续、下载/合成/整理/保存独立进度、20秒时间常数的平滑ETA。整理阶段可暂停，继续复用完整编码结果；保存失败可只重做整理/复制。真实AppDataGraph关闭、重建会话仍复用应用执行器的回归测试已过。
- `aae42176`：Android应用按账号持有唯一执行器、独立公开媒体transport、applicationContext和URI；页面/会话重建不替换下载执行器。明确退出账号/清除数据先等待任务停止写盘。debug独立入口支持实机完整管线、后台返回、重建、断网、覆盖安装和恢复；仅测试包，不读取学校凭据。

### 整节实机验收证据

- 设备：小米平板8 Pro（Android17），独立 `.playbacktest` debug包，原App未替换。无线ADB曾授权，已换AC充电器，电量回升；后续确认连接状态并显式指定平板，不能误操作模拟器或原App。
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

## 进行中：安卓合成提速与跨芯片兼容（计划见 `docs/plans/playback-android-performance.md`）

- 基线拆解：下载8分14秒（≈14.2×实时）与合成重叠，合成到14分25秒才完（≈8.1×实时、约203帧/秒），之后整理≈1分31秒、保存≈16秒。瓶颈是合成而非网络；按公开规格粗算视频单元已用约六七成（未实测）。
- 基线问题：整理逐样本重封装（约8MB/s）；保存二次复制且每1MB刷新一次状态；合成主循环逐帧串行、解码只提前一帧；解码器未设优先级。现已增加受限快速整理、保存进度节流与纹理矩阵，均待设备验证。
- 跨芯片：只用标准 MediaCodec/Surface/EGL，不依赖高通私有接口。现已按硬件优先、软件随后尝试编解码器，并在启动失败时释放资源；仍没有两路串行回退，编码器自行丢帧也可能令帧数核对反复失败。这些路径与纹理矩阵修正均未跨设备验收。
- 推荐顺序：P0 采样（日志三项耗时、只解码/只编码/完整流程基准、源帧参考类型）→ P1 分段盒层面直接拼接+增量整理+直写目标（预计尾段≈1分47秒→≈10秒）与变换矩阵修正 → P2 按采样结论选两条流水线并行或跳过不被选中的非参考帧 → P3 下载并发A/B；兼容性加固并行推进，需一台天玑设备验收。
- 预期：只做P1约14.5分钟；合成≥14×实时后约8.5–9分钟。尚未实测，不能当作已达成；增量整理、SAF 随机写、P2/P3 和跨芯片回退仍未完成。
- 2026-10-27 本地新改动：两路 SurfaceTexture 每次更新后读取各自变换矩阵，着色器按矩阵采样；大文件保存进度最多每 250ms 更新。MP4 盒快速整理仅接受严格检查通过的分段，否则回退原 MediaMuxer。编解码器按名称创建并在资源不足时尝试其他候选；新索引记录编码器名，恢复已提交分段时沿用原编码器，旧索引没有名称时仍依赖分段配置核验。第二路初始化失败会释放第一路和已创建资源；渐进输入每次提供独立格式，速率提示被拒时可真正退回默认格式。Android debug 和 release R8 已构建，尚未做 ADB、画面或跨芯片验证。
- Kotlin 快速整理离线复用 3 个既有 Android MediaMuxer 分段，与原整理结果的 2493 视频包、6891 音频包逐包内容、PTS、DTS、大小和关键帧标志完全一致，ffmpeg 全解码退出 0。重复分段构成 102 段、444 MB 的压力样本，84,762 视频包和 234,294 音频包的内容及时间戳均正确，全解码退出 0；Windows 本机仅作逻辑/容器验证，不代表平板速度或整节新版本验收。
- 既有整节日志 `goal-android-live.log` 的 175000 帧累计 `decodeMs=240737 / drawMs=376512 / encodeMs=236458`（这些是主循环等待/工作时间，不等于硬件利用率）；该日志未见 `offline rate hint rejected`。已有两路重封装 MP4 的前 60 秒分别只有 I/P 帧；屏幕路前三秒 trace 的 VCL 参考值均非零。此样本不足以支持计划中“约 27% 可跳过非参考帧”的推断，勿直接跳帧优化。设备恢复后仍需解码/编码单项基准。

## 已完成：Windows 硬件加速与稳定性证据（功能分支待主干）

- Windows 原生辅助程序使用 D3D11 共享纹理硬件解码、双路缩放和编码，并原样保留左路 AAC。AAC 原问题是类型硬编码及配置丢失；更正后短样本通过。无可用 GPU 时回退 CPU；增加进程退出时关闭 helper、下载记录和保存目标恢复，已提交到功能分支。
- 最终 MSI 内相同 helper（SHA256 `cca44b0d…`）已分别完成整节双路合成（不含下载）：NVIDIA RTX 4060 笔记本 GPU 用 318.41 秒，VideoDecode 峰值 98.5%、VideoEncode 40.7%；AMD Radeon 780M 用 530.45 秒，Video Codec 峰值 100%。两份成片均 175564 帧、7022.56 秒、AAC 302430 包，逐包视频 PTS 间隔 40000us，音轨 SHA256 与原始一致，ffmpeg 全解码退出 0，中间帧教师/课件左右位置正确。样本、计数器和校验结果在 `.artifacts/windows-gpu-playback/gpu-{nvidia,amd}-final-full.*`。Intel 设备用户暂时没有，留待后续实测。
- Windows 已改为两路 HLS 分片下载时同步建立压缩帧索引，原生 Media Foundation 源在输入增长时供 GPU 解码和拼接；暂停/中断保留下载分片，但 Windows 尚不能保留中途编码结果，继续时从头合成。完整成片持久化后可恢复保存。新增自动优先 NVIDIA、启动失败尝试其他硬件（含 Intel）再回退 CPU，任务卡片显示实际合成设备。该阶段 helper SHA256 `1ea7c434926e1a1bdbb97c23d0fb22bdda4da39931cb646349e3f14f7542fccf`；整节计时使用此前的 `35ff3b64…`，新 helper 已验证 NVIDIA/AMD/CPU 短片与界面，未重新跑整节。
- 为渐进 H.264 输入从 SPS/PPS 生成标准 `stsd` 视频描述，恢复系统硬件解码吞吐；160 秒真实录像的 NVIDIA 普通输入/渐进输入分别约 8.8/8.5 秒（修复前渐进输入约 25.2 秒），AMD 约 13.8/13.7 秒。两份渐进成片均 4001 帧、6891 AAC 包，40ms 视频时间间隔、音轨 SHA256 与输入一致，ffmpeg 完整解码通过；两块显卡均验证输入尚未结束就已有合成输出。Intel 未实测。
- Windows 隔离 UI 真实录像已完整下载两路共 3,853,388,624 字节，刷新页面不丢任务；合成中暂停后保留两路各 703 个分片，关闭重开后恢复记录。新 helper 从本地断点重新合成并保存约 5 分半，输出 2,220,228,617 字节，175564 视频帧、302430 AAC 包、时长 7022.56 秒，视频每帧 40ms，音轨 SHA256 `61fcd3f23860e39eda9f7f39669d9de62ceb8f24430028bf69e83d91d1f99c09`，整段 ffmpeg 解码通过，首/中/尾左右画面正确。恢复过程 NVIDIA 解码约 98%、编码约 40%。
- 同一版本在独立空目录从零下载、合成、保存，2026-10-27 20:26:37→20:32:14（5 秒采样），总约 5 分 37 秒；两路 3,853,388,624 字节下载约 1 分 37 秒，下载完成时合成约 29%，全程无暂停。成片帧数、时长、音轨哈希、40ms 视频时间间隔及完整 ffmpeg 解码均与恢复验收一致；Computer Use 打开 VLC，实际播放并跳转到约 1:04，教师和课件正常并排。此轮网络均速约 39.7 MB/s，不等同于 8 MB/s 网络下的实测；测试中磁盘空余最多减少约 9.8 GB（含成片及临时文件，受同盘其他活动影响），完成后下载分片和渐进索引已清理。强制纯软件兼容路径用 10 秒样本检查了 251 帧、431 个 AAC 包、音轨哈希及完整解码，未测整节性能。
- 此前串行版本的 Windows 隔离 UI 验收使用 `.artifacts/windows-gpu-playback/ui-harness/`：13% 暂停、关闭重开并继续，刷新不丢任务；完成下载后合成中关闭，helper 随主进程退出，两路 MP4 保留。继续后整节保存成功、重开仍显示完成；成片帧数、时长和音轨校验均正确。一次测试中误在窗口运行时重建共享 jar 导致类加载失败，随后关闭窗口、重建并完整复测；此窗口不代表正式安装版或真实账号流程。
- Windows 测试覆盖恢复目标、账号隔离、helper 管道与取消、合成暂停/中断恢复和保存失败复用已合成文件；独立主进程强制退出探针证实 helper 结束。CPU 处理配硬件编码及强制纯软件编码 10 秒均通过：251 帧、431 AAC 包、音轨哈希与源一致、ffmpeg 全解码退出 0，画面抽查正常；软件样本不能外推整节速度。
- 此前离线联合构建 `:androidApp:assembleDebug :androidApp:minifyReleaseWithR8`、47 项共享回放相关测试、4 项 Windows 恢复测试、`:windowsApp:createDistributable :windowsApp:packageMsi` 成功，镜像及 MSI 包内 helper 是 `35ff3b64…`，不含后来的显卡选择和设备显示。新改动运行共享任务/设备状态等 30 项测试、4 项 Windows 恢复测试、Android debug 构建通过；NVIDIA 自动选择、强制 AMD、软件模式短片和模拟 NVIDIA 启动失败后转 AMD 均校验音视频，Intel 优先级及回退经模拟测试，未在 Intel 真机验证。隔离界面验证 NVIDIA 具体型号、CPU 软件模式、页面刷新与暂停状态。另以无音轨 H.264 High profile 合成样本在 NVIDIA/AMD 各检查 100 帧和完整解码。Apple 目标在 Windows 不可构建；安卓新改动未连接 ADB 或做实机验收。使用本机既有 Gradle 缓存与 WiX，未安装依赖。当时未测真实账号/正式安装流程；后续用户已确认在线播和真实下载，见顶部结项记录。
- 完整 `:shared:desktopTest` 在干净进程环境下运行 1193 项、1188 项通过；仍有既有的 `DesktopAgentIntegrationTest` 3 项和 `MacUninstallCleanerTest` 2 项在 Windows 因符号链接权限、macOS 脚本/路径假设失败，回放相关用例无失败。日志 `.artifacts/windows-gpu-playback/desktop-full-test-clean-env.log`。初次运行误将禁用的可选环境变量设置为空字符串，触发 4 个诊断用例；已清理该次生成的空文件，后用真正移除变量的环境重跑。
- 先前 MSI 在 `multiplatform/windowsApp/build/compose/binaries/main/msi/`，只生成未安装；它不含最新设备显示和版本号，不能代表当前源码。MSI 使用项目现有升级码，安装可能替换既有安装版，不能当作隔离测试包。此前 `.artifacts/windows-gpu-playback/package-msi.log` 记录的是旧版本。
- 9–10分钟仅是 Android 后续性能目标，当前安卓实测未达到；不能通过降帧/丢帧冒充提速，不影响 Windows 本轮完成状态。

## 已完成：Windows 课程回放多任务、缓存与恢复验收（功能分支待主干）

- `a9bd0b52` 修复任务到达锁的调度次序可能与入队次序不同的问题；隔离测试覆盖三个任务的并发提交、先前任务完成/失败/暂停/取消和取消排队项，不改变 GPU 管线。
- `c5b70c28`：Windows 保存临时文件在记录落盘后才创建；取消、移除或完成先记录清理意图，失败保留记录并提示重试；无法恢复的记录、未关联工作目录和损坏记录不再被猜测清理。写盘失败保留分片；保存完成与记录更新之间的异常退出若结果不明确，提示先检查视频，避免覆盖。JVM 清理不跟随符号链接或 Windows junction；共享状态使用原子更新，安卓已提交目标在取消时不再删除。
- 文件归属：账号工作目录保存 `segments-*` 的 TS/`.part`/播放列表指纹、`windows-input-*` 的压缩样本/索引/配置、`composite.partial.mp4`、完成标记和 `composite.mp4`；用户目标目录只按记录定位 `.bjtu-save-*.tmp` 与最终输出；账号目录的 `tasks.json` / `tasks.json.new` 是任务记录及其原子写入文件。无法归属的旧临时文件不扫描用户目录、不自动删除。
- Windows 独立 JVM 在保存阶段被强制结束后，新管理器恢复已合成文件并完成保存；测试核对已存在视频、缓存与临时文件。隔离界面已观察一运行两排队、失败后下一项推进，以及浅色/深色和大字号提示。该阶段隔离测试未使用正式安装、真实账号或原有下载；后续真实流程用户验收见顶部。
- 可选实机测试 `WindowsPlaybackHardwareAcceptanceTest` 用仓库合成 TS 和实际 Windows helper，在 NVIDIA RTX 4060 硬件路径验证三个排队任务、暂停恢复、取消与强制结束 helper 后的下一项推进；5 个输出均为 H.264/AAC、1920×540 且 ffmpeg 全段解码成功，已存在视频与无关临时文件保留，任务工作根目录和 `tasks.json.new` 无残留。以 `BJTU_PLAYBACK_HARDWARE_ACCEPT=1` 运行 `:windowsApp:windowsTest --tests '*WindowsPlaybackHardwareAcceptanceTest*'`，需本机已有 ffmpeg/ffprobe；证据 `.artifacts/playback-queue-lifecycle/hardware-queue-verified.xml`。
- 该阶段共享回放/课件等测试 144 项全过；Windows 默认全量测试 32 项、0 失败、2 跳过（含上述可选硬件测试，另已显式运行通过）；Android 使用已有 debug keystore 的 `:androidApp:assembleDebug` 通过，未安装。最终代码的完整 `:shared:desktopTest` 1214 项中 5 项是既有 Windows 环境失败（DesktopAgentIntegrationTest 3、MacUninstallCleanerTest 2）。代码提交 `c5b70c28` 的跨平台安全检查 [40545883628](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/40545883628) 四项通过，包含 macOS 全部共享测试、iOS 共享测试、Windows 镜像和 Android debug/优化版；安卓实际设备未验收，旧的无归属 `.bjtu-save-*.tmp` 无法安全自动定位。


## 已合入功能分支、待主干：CITEL 折扣时间配色与考试 sheet 测高（PR #70）

- `isFavorableChange` 把“折扣时间”延后与截止时间延后同等视为向好（绿色），补 `HomeChangeFieldTest` 两条断言。
- 考试页紧凑端“考试详情”和“添加到日历”两个 sheet 由 `needsFullHeight` 改为 `fitContentHeight`：iOS 走按正文定高的自定义 detent，安卓/桌面底部 sheet 按内容高度展开；正文不再自带 verticalScroll，由 sheet 滚动。未在模拟器/真机验收。

## 进行中：课件大文件保存与 Windows 文件选择器（2026-10-28）

- 平板反馈单文件页顶无总大小、右侧一直显示准备，以及 Android 整课/文件夹导出在旧版和本版均失败。源码确认单文件未传递列表标注大小，宽屏详情漏传进度；已修复并在无响应总长时注明“课件标注大小”，转换件不误用原文件大小。Android 原先把 OpenDocumentTree 返回的树 URI 直接作为 createDocument 的父文档 URI；现使用 getTreeDocumentId + buildDocumentUriUsingTree 构造目标文档 URI，失败提示区分授权与写入。修复原因是明确的 API 不匹配，但未连接 ADB，尚不能断言平板真实导出已成功。
- 用户要求本地上传密钥签名 APK，暂不推送或远端打包。本地 `:shared:desktopTest --tests '*Courseware*' :windowsApp:compileKotlinWindows :desktopApp:compileKotlin :androidApp:assembleDebug -I ..\.artifacts\android-account-debug-signing.gradle --offline --no-configuration-cache --console=plain --continue` 成功，课件 84 项通过；随后不使用调试签名覆盖脚本，运行 `:androidApp:assembleDebug --offline --no-configuration-cache --console=plain` 成功。`apksigner verify` 验证签名与本机上传密钥证书一致，包名 `team.bjtuss.bjtuselfservice.kmp`，versionCode 45，arm64-v8a；APK dex 包含树 URI 转换、单文件大小状态及错误提示。测试包 `C:\Users\zjg\Downloads\交大自由行-KMP-2.0.0-Alpha-课件修复-local-20261028-155201-718-arm64.apk`，49,725,720 字节，SHA256 `59ED02117AB400320CC91060A5C9ABD5E8E18B8794EC5D503F95D768916BF577`，下载副本哈希一致。用户表示此版本可以；平板 SAF 的具体操作与结果未逐项记录，iOS/macOS 原生目标未验证。

- 批量取消先确认，可选择保留已完成文件、舍弃本次目录、继续下载；未完成文件请求清理。非用户主动的协程取消默认保留已完成文件；保留时会话关闭失败不会转而删除目录，提示用户检查。测试覆盖首个文件完成后取消的两种选择，以及取消恰逢文件目标创建时清理句柄。初次边界测试失败，修复后重新运行通过；真实应用弹窗和实际文件系统交互、iOS 编译/云盘仍未验收。
- 最新 Windows 测试包 `C:\Users\zjg\Downloads\交大自由行 KMP-2.0.0-Alpha-local-20261028-152439-059.msi`，88,829,952 字节，SHA256 `7F195EE79C84A24AF2076562D00C8A517126ECBE9F3243B61307C4280CD4A434`。课件专项与 Windows 测试、Android debug、桌面编译和 MSI 打包成功；解包核对共享 UI/模型、Windows 面板、回放 helper 与构建标识，下载副本哈希一致。未安装。证据 `.artifacts/courseware-cancel-package/`。

- 用户反馈批量导出缺少当前文件序号和大小：现显示“第 1/6 个：文件名”，优先用响应实际总长度；未知时显示课程列表的标注大小并注明来源，标注也缺失则显示总大小未知，未知长度不伪造百分比。转换件不沿用原件标注大小。格式与取消清理测试、Windows 测试及 Android/桌面构建通过；iOS 构建、真实应用界面与大文件仍未验证。
- 新测试包 `C:\Users\zjg\Downloads\交大自由行 KMP-2.0.0-Alpha-local-20261028-151429-562.msi`，88,813,568 字节，SHA256 `2E753712CA772ECA880733729766D51CC71FA453DEC327B53EF4C83FDC5DA1A5`。命令 `:shared:desktopTest --tests '*Courseware*' :windowsApp:windowsTest :windowsApp:packageMsi :desktopApp:compileKotlin :androidApp:assembleDebug -I ..\.artifacts\android-account-debug-signing.gradle -x downloadWix --offline --no-configuration-cache --console=plain --continue` 成功；Windows 33 项、0 失败、2 跳过，MSI 解包核对共享界面与模型 class、Windows 面板及回放 helper 和构建标识，下载副本哈希一致。未安装。证据 `.artifacts/courseware-size-package/`。

- 普通课件先选择保存位置，再按下载票据流式写入；单文件详情显示进度，未知长度显示已下载字节。批量目录接入四端分块写入，按完成文件和当前文件进度显示，取消时请求清理本次目标；备用下载对忽略 Range 的响应在读取正文前拒绝，票据失败且尚未写入时仅回退原件。
- Windows 单文件保存（含视频）及目录选择改用系统 IFileDialog，绑定应用窗口。独立窗口探针观察到现代面板、模态归属、取消返回和文件夹选择返回；未在真实账号的应用页面完成端到端下载验收。
- 2026-10-28 按用户要求本地打包：`C:\Users\zjg\Downloads\交大自由行 KMP-2.0.0-Alpha-local-20261028-150301-335.msi`，88,813,568 字节，SHA256 `31BC34B65CA6B07D43DF0259575C01057547BC6E5304F7F43B31937F38817E90`。`JDK 21 .\gradlew.bat :windowsApp:windowsTest :windowsApp:packageMsi -x downloadWix --offline --no-configuration-cache --console=plain` 成功；Windows 测试 33 项、0 失败、2 跳过。MSI 解包核对课件模型和文件面板 class 与本次编译一致、回放 helper 哈希一致、构建标识匹配；源包与下载副本哈希一致。未安装，安装器与现有应用共享升级标识。证据 `.artifacts/courseware-package/`。
- 已运行离线课件专项和本地 HTTP 流式测试，82 项通过；Windows/桌面 JVM 编译和 Android debug 构建通过。最后补充的等待态也已随同命令重新通过。iOS/macOS 原生目标与真机、iCloud 文件提供方、真实 500 MB 课件和完整界面状态尚未验证；本机 Windows 不能编译 iOS。该项待合入视频回放分支；本轮测试包未安装或发布。

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
- 小米平板独立 `.playbacktest` debug 包保留原 App 数据，已实测预览/播放/全屏/退出、三次退避约 2.0/4.0/8.0 秒、耗尽后继续、短断网后自动恢复、强停后 206 片与 SAF 目标恢复、短时后台下载，同一前台服务持续运行。实际崩溃根因为 ForegroundServiceDidNotStartInTimeException，非已证实的 OOM。
- 旧任务 `t1792993910544-0` 今晚接回后确认已下载两路并转好 MP4，总源 3,853,388,624 字节，停在合成初期，进程已不在；两路输入保留作同课程性能基准，未将旧部分输出当成完成。
- 旧模拟器验证仅每路 16 片/约 160 秒，不是整节完成；Windows 合成探针与 iOS 模拟器合成用例也不等于真实 App 端到端成功。iOS 活动去重、详情返回和全屏旋转、PR #69 两阶段对齐/长课名/大字号仍待真机界面验收。Apple 完整编译/共享测试仅 CI 验证。
- 共享播放器 JS 语法及 DOM fixture（双路容器全屏、退出、播放/位置保持、横屏、WebKit 回退）已过；早期自动化 file:// 页面被测试浏览器拒绝。后续 Windows 在线播放由用户确认通过；该旧阻塞不再适用于 Windows，Apple 当前包界面仍需设备验收。
- 证据目录：`.artifacts/playback-fixes/`、`.artifacts/pr68-integration/`、`.artifacts/tablet-playback/`、`.artifacts/ios-playback-fixes/`、`.artifacts/pr69-integration/`。完整旧过程可用 `git show e0e8fa9a:memory.md` 查阅；备份在 `.artifacts/pr69-integration/memory-before-consolidation.md`，不把旧“未提交/待 CI”当成当前状态。

### 当前明确限制

- 较早提交的 Windows helper 默认无音轨；功能分支的新实现已修 AAC 并验证硬件合成。纯软件兜底仅经过 10 秒样本验证；`docs/plans/playback-composite-android-windows.md` 只代表历史状态。
- 跨进程任务持久化已由 Android 与 Windows 网关接入，并在隔离 UI 重启测试中验证；iOS 没有同等的任务记录恢复保证。系统后台会话或继续处理任务不等于 App 进程重启后能恢复任务队列。
- 原生前台服务、后台会话和继续处理任务不保证永不被系统终止；已验证的 Android 断点能恢复，不应把旧版本丢失的内存记录声称为已找回。

## 构建与交付

- 本轮手动 KMP package 从当前功能分支最新已推送提交触发，不改版本号或标签，不创建 Release，也不设置 mirror_tag/promote_test；只生成四端测试包。
- 本次运行链接、实际 head SHA 和启动状态记录在 `.artifacts/pr69-integration/package-dispatch.json`；打包结果以 [KMP package Actions](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/workflows/kmp-package.yml) 为准。旧包不能代表此次源码。
- 旧回放四端包 run 40249534874/40252333772、旧源码 24e2786c 的 run 40269212333 都不含此次全部修复。2026-10-22 的 Build42/43 本地产物与配对测试记录见 history_full.md 和上述旧 memory，只作历史取证。
- iOS Release IPA 的 framework 链接、宿主与扩展/ad-hoc 签名由 KMP package 验证；安全检查通过不能替代打包和 SideStore 安装。当前源码版本为 2.0.0-Alpha / Build45，Apple 新版本尚未在本机编译或安装。
- 2026-10-27 版本号回归（本机 Windows）：`:shared:desktopTest` 1195 项 0 失败（`emergencyfix2-checks.gradle` 排除既有 DesktopAgentIntegrationTest 3 项与 MacUninstallCleanerTest 2 项），`:windowsApp:windowsTest` 18 项 0 失败 1 跳过，`:windowsApp:compileKotlinWindows` 与 GPU 选择测试 exe 已按新源码编译，`:androidApp:assembleDebug` 产物 `versionCode=45` / `versionName=2.0.0-Alpha`。日志 `.artifacts/v200-alpha-regression.log`。未跑正式签名包、MSI/DMG 或 iOS 编译。

## 其他仍需跟进的事项

- 设置页“版本与项目”重构（claude/kind-darwin-u2fxah）的 PR/合入状态未在本次核验，保留为待确认，不混入回放 PR。旧离屏测试证据 `.artifacts/settings-version-card/`。
- 私人 Site 的线上版本与手机/电脑真实长码配对、同时上传、ChatGPT 手机授权、Windows 混合 DPI 托盘与退出回调仍需验证；不能用旧构建或虚构数据界面当成端到端成功。
- 既有真机待验收：小组件侧载共享容器与各尺寸、上课前后实时活动切换、通知权限说明、版本弹窗只出现一次、CITEL/编程题真实上传、系统保存/预览/二维码入口、Windows 原生缓存/DPAPI 往返。旧 Build42/43 包的检查不是当前 Build45 真机证据。

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

1. Android P0：设备可用时按新四端标准和 PROMPT2，在独立 `.playbacktest` 包做真实测量/整节/恢复验收，所有 ADB 命令显式指定已确认序列号；不覆盖正式 App，不重复实现快速整理/矩阵/候选回退。
2. Android P1：上一阶段报告证实最大两个瓶颈后再按 PROMPT3 定向优化，每次一项、同输入复测、独立提交；9–10分钟仅目标。增量整理、SAF直写、进一步流水线和跨芯片/串行回退只作待评估候选。
3. iOS/macOS：按新标准先真实整节验收与采样，再决定加速/恢复；iOS AAC重编码、两端跨进程恢复及文件重新访问能力差距明确列待办。Windows已完成，不重启P0收尾；Intel/CPU整节仅保留兼容性边界。主干合入、改版本、正式发版由用户另行决定。
4. 后续新开发从最新 main 建分支；保留以上其他真机待验证与当前痛点，不恢复已解决的旧待办。
