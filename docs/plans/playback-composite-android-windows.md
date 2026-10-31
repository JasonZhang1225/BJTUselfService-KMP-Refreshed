# 归档：Android 合成与后台、早期 Windows 收尾（历史）

**2026-10-31**：Android/iOS **产品侧已关闭录像下载**；本文不再安排移动端下载/合成测试或优化。现行工作见 [四平台标准](playback-four-platform-acceptance.md) 与 [macOS 计划](playback-macos-acceptance.md)。下文为截至 2026-10-26 前后的历史实现笔记，仅供排查。

2026-10-28 更正：Windows 已结项；下文“无声音”“CPU慢速”“文字占位”等是旧状态。

分支 `feat/courseware-fallback-playback`。

## 合成清晰度（2026-10-25 追加，已实现）

服务端每路录像只有 **1920×1080 一个源**：`master.m3u8` 里只列一个 `EXT-X-STREAM-INF`（`RESOLUTION=1920x1080`，
码率约 2.0–2.2 Mbps，教师路 25.6 fps、课件路 34.5 fps、学生路 25 fps），另有一条 `EXT-X-I-FRAME-STREAM-INF`
只是给拖动定位用的关键帧索引，不是清晰度档位。同目录没有别的播放列表（`playlist-low/playlist_720/720/…` 全 400/502，
`?quality=2`、`?hd=0` 被忽略）。**所以没有低清源可切**，清晰度只能在合成时由编码器决定。

用户决定：**只在「合成」路径加清晰度三档，播放器不动**（播放器只有 1080p 可播，最多只能切纯音频流，不算清晰度；
单路下载仍保持 1080p 无损转封装，不引入本地重编码）。

实现：

- `domain/courseware/CoursePlayback.kt` 新增 `PlaybackCompositeQuality`：高 1920×540 @2.5 Mbps、中 1280×360 @1.2 Mbps、
  低 960×270 @0.6 Mbps。每半边都是源的一半，保持 16:9 不变形。
- `VideoCompositor.composeSideBySide` 的 `width/height` 参数换成 `quality`（分辨率与码率一起传）。
  - Android：编码器尺寸用 `MediaCodecInfo.VideoCapabilities.isSizeSupported` 判断，不支持时**按档位退一档**并在任务卡片提示。
  - iOS：`AppleVideoCompositor` 用 `quality.width/height/bitRate`。
  - macOS：Swift 小程序命令行加一个码率参数（`compose <左> <右> <输出> <宽> <高> <码率>`）。
- 界面：合成开关下方加三个 `FilterChip`（高/中/低 + 尺寸），并实时显示“尺寸、码率、每小时体积、两小时约多少”。
- 体积预期：约 1.0 / 0.5 / 0.2 GB 每小时（两小时的课约 2.0 / 1.0 / 0.4 GB）。

验证（模拟器）：三档都在设备内合成成功，ffprobe 确认输出 1920×540 / 1280×360 / 960×270、25 fps、音频仍是教师路；
抽帧确认左右并排与方向正确；App 界面三档切换与体积文案正确（“高 2.5 Mbps 约 1.0 GB/小时”“中 1.2 Mbps 约 0.5 GB/小时”
“低 0.6 Mbps 约 0.2 GB/小时”）。`PlaybackTaskManagerTest` 新增一条断言所选清晰度确实传到合成器。

未验证：iOS/macOS 上的实际合成（Swift 与 Kotlin/Native 改动只经编译期检查，iOS 需在 macOS 上编译并实跑）；
真实课堂内容下三档的体积差（模拟器用的是纯色测试流，码率打不满）。

## 下载进度：体积、速度与实时活动（2026-10-25 追加，已实现）

### 体积与速度

下载任务卡与回放页顶部横幅各加一行：左边“已下载 / 预计总量”，右边“当前速度”（如 `774.3 MB / 约 3.4 GB` · `254 KB/s`）。

- 速度按“已下载字节 ÷ 这些字节对应的**录像时长**”算。直接用字节÷墙钟时间会被首个分片的突发下载放大：
  实测首片 2.5 MB 瞬间到手会算出 6.3 MB/s / 86.7 GB，实际整路只有 254 KB/s / 3.4 GB。
- 总量按“已完成各路的实际字节 + 剩下各路按速率×录像时长”估；估不出时只显示已下载部分。
- 合成阶段切到输出体积估计（码率×录像时长），速度清空。

### 实时活动

- **iOS**：新增 `PlaybackDownloadAttributes`（ActivityAttributes）、`PlaybackDownloadWidget`（小组件扩展）、
  `PlaybackDownloadCoordinator`（宿主）。
  - 灵动岛收起态：左边环状进度（圆环里放阶段图标），右边两排——上面是阶段（下载中/拼接中），下面是百分比。
  - 展开态与锁屏/通知实时活动：课节标题、当前步骤、体积与速度。
  - 共享层 `IosPlaybackBackground` 用 `NSNotification` 把状态发给 Swift 宿主（ActivityKit 只有 Swift 接口）；
    新活动类型独立于日程实时活动，两者可以同时存在。
  - 任务结束先把终态（已完成/失败/已取消）推给宿主，锁屏上留 45 秒再消失。
- **Android**：`PlaybackForegroundService` 的常驻通知用 `Notification.ProgressStyle` 画进度并请求
  promoted ongoing（状态栏胶囊里是系统画的环形）；通知标题是阶段、副标题是课节标题、正文是步骤·体积·速度，
  胶囊短文字是百分比。终态在通知上多留 5 秒再撤。
- 共享层把 `PlaybackBackgroundHost` 改成收 `PlaybackBackgroundState`（标题/阶段/步骤/进度/体积/速度），
  两端只负责渲染，文案与进度口径一致。

验证（模拟器）：真实下载时通知标题“下载中”、正文“下载教师画面（第 1/2 路）· 300.1 MB / 约 3.4 GB · 254 KB/s”、
胶囊短文字“3%”、`requestPromotedOngoing=true`；任务卡与横幅显示同样的体积与速度；干净取消后 SAF 文档被删除
（只剩强杀进程造成的残留）。iOS 侧本机无 Xcode，只经人工复核与 pbxproj 核对。

## 下载并发（2026-10-26 追加，已实现）

课程回放的 HLS 分片下载改成**并发抓取**（零体积增量，不引入 aria2 之类的大依赖）：

- 共享层新增 `data/courseware/ParallelDownload.kt`：`fetchInParallelOrdered(count, concurrency, fetch, onResult)`
  —— 同时在飞的任务数有上限（默认 4），结果**按索引顺序**交付，内存里同时驻留的分片数有界
  （worker 交付完才去取下一个，这就是背压）；任一分片失败 → 整个下载失败、异常原样抛出。
- `HlsSegmentSource` 新增 `fetchConcurrently(...)`，默认实现退回 `fetchInOrder`：
  iOS 用系统后台下载会话的实现不受影响，保持原样；`DirectHlsSegmentSource` 覆盖成并发版。
- `HlsRecordingDownloader` 改调 `fetchConcurrently`，**进度口径不变**（已完成分片数 / 已写字节数），
  写盘字节与串行版逐字节一致（测试里与串行基线对比断言）。
- 失败信息补上分片序号与**脱敏后的地址**（去掉查询串，录播地址可能带 token）。
- 网络层：录播服务器 `lmtN.bjtu.edu.cn` 的每主机并发从 2 放宽到 4，与分片并发数一致
  （`defaultHostRequestPolicy`）；否则并发会被闸门压回 2 条连接。

未并发的是**课件大文件**（`CoursewareRemoteDataSource.streamStoredDownload`）：它直写用户选好的文件，
学校静态服务器的并发 Range 行为没有实测过，保持逐段串行（每段 4 MB，已经不是一次性整文件下载）。

验证：`:shared:desktopTest` 1140 项、5 失败（`DesktopAgentIntegrationTest` / `MacUninstallCleanerTest`，
与本次改动无关的 Windows 环境既有问题）；新增 `HlsParallelDownloadTest` 覆盖并发写出的字节与串行一致、
进度口径一致、在飞请求数有上限且不会把整路录像读进内存、单个分片失败时异常带分片信息且不泄露 token、
取消后不写盘不留半成品、只实现 `fetchInOrder` 的来源能退回串行。`:androidApp:assembleDebug` 通过。
iOS 目标无法用 Gradle 在本机编译（Kotlin/Native 的 Apple 目标要 macOS），由 PR CI 验证；
单独验某个 iOS 源文件可以用下面的 konanc 办法（见「iOS 端编译修复与本机验证手段」）。

## iOS 端编译修复与本机验证手段（2026-10-26）

安全检查 run `40224424937` 里只有 apple 作业失败，根因在
`shared/src/iosMain/.../media/IosPlaybackBackground.kt`，三处**只会在 Kotlin/Native 上暴露**的错误：

1. `task.updateTitle(title, subtitle: detail)` —— 把 Kotlin 的命名参数写成了冒号形式，
   编译器报 `Unexpected type specification` / `Expecting ')'`（不是"未解析引用"，很容易看成拼写问题）。
2. `@Volatile` 丢了 `kotlin.concurrent.Volatile` 导入 —— **Native 上 `@Volatile` 不是默认导入**
   （JVM 靠 `kotlin.jvm.*` 默认导入才不用写；Native 的默认导入里没有 `kotlin.concurrent.*`），
   缺失会报 unresolved reference。
3. `continuedTask = nil` —— Kotlin 2.4 的 Native 后端已不提供 `nil` 字面量，报 unresolved reference，
   只能用 `null`。

第 1 处的语法错误会让编译器**在解析阶段就停住**，第 2、3 处因此被完全挡住；只修语法错误再跑 CI
才会继续暴露后两处。教训：iOS 侧的改动要把"语法错误"和"解析错误"分开清。

### 本机（Windows）直接编译 iOS 目标的办法

Kotlin/Native 的预置发行版自带 **Apple 平台 klib**，所以在 Windows 上也能编译 iOS 目标（只编 klib，
不需要 Xcode、不做链接）：

```
~/.konan/kotlin-native-prebuilt-windows-x86_64-2.4.10/bin/konanc.bat \
  -target ios_simulator_arm64 -p library -o out.klib <源文件目录>
```

用法：把要验的真实源文件连同少量桩类型（跨源集依赖的 `PlaybackBackgroundState`、
`PlaybackBackgroundHost`）拷到临时目录一起编译。上面那三处错误，用这个办法在提交前就全部暴露了；
再把命名参数故意改回冒号形式，本地报错与 CI 报出的字样完全一致，说明这个探测对同类错误是敏感的。

**局限**：Gradle 侧仍然编不了 iOS 目标。即使加 `-Pkotlin.native.enableKlibsCrossCompilation=true`，
`shared` 的 `uikitSheet` cinterop 需要 Xcode 头文件，Kotlin 插件会直接禁用 Apple 目标
（"Cross Compilation with Cinterop Not Supported"）。所以完整的 shared 编译、iOS 模拟器测试、
Swift 代码编译仍只能靠 CI / macOS。

`konanc` 还有两个硬限制：**只能出 klib 或 dylib**，`-produce framework` 在非 macOS 宿主上被直接拒绝
（"Target ios_simulator_arm64 is not available for output kind 'FRAMEWORK' on the mingw_x64 host"），
所以**框架专属的 ObjC 导出/lowering 问题没法在 Windows 上复现**，只能靠 `KMP package` 验。

### 框架链接才会暴露的问题：Kotlin `object` 不能继承 ObjC 类

`KMP package`（不是安全检查）第一次跑这个分支时，iOS 作业在 `:shared:linkReleaseFrameworkIosArm64` 挂掉：

```
java.lang.IllegalArgumentException: Allocation of Obj-C class CLASS OBJECT
  name:Delegate modality:FINAL visibility:private
  superTypes:[platform.darwin.NSObject; platform.Foundation.NSURLSessionDownloadDelegateProtocol]
  should have been lowered
```

日志里的上下文说明是**文件级 `$init_global()`** 在给 `Delegate` 分配对象时炸的。
`Delegate` 是 `HlsSegmentSource.ios.kt` 里的 `private object Delegate : NSObject(), NSURLSessionDownloadDelegateProtocol`。

结论与修法：

- **继承 ObjC 类的 Kotlin `object` 在框架构建里不能用**。它的单例会在 `$init_global()` 里分配，
  而编译器对这种 ObjC 类的分配路径没有做 ObjC 化，直接报 "should have been lowered"。
- 改成**普通 class**、在运行时创建即可（`delegate = Delegate(this)`）。模块里其他 ObjC 委托
  （`WebNavDelegate`、`PlaybackNavigationDelegate`、`SheetDelegate`、`IosDocumentPickerDelegate`）
  全都是普通 class、运行时创建，所以一直没事——**`object` 是全模块唯一一处例外**。
- **安全检查发现不了这个问题**：apple 作业跑的是 `:shared:iosSimulatorArm64Test`（编 klib、跑测试，
  不做 framework 链接），只有 `KMP package` 的 iOS 作业会 `linkReleaseFrameworkIosArm64`。
  所以"安全检查绿了"不等于"iOS 能打包"。

## 已就绪的共享部分（四端共用，不需要改）

- `media/TsToMp4Remuxer.kt`：录播 HLS 分片（H.264 Baseline + AAC-ADTS）无损转 MP4，`moov` 在前。
- `feature/courseware/PlaybackTaskManager.kt`：任务队列（一次一个）、阶段/进度、取消、失败清理；合成时只开一个保存目标，否则每路一个。
- `media/VideoCompositor.kt`：合成接口与 `PlaybackPlatformHooks`（`compositor`、`backgroundHost` 可由宿主注入）。
- 保存走 `HomeworkFileGateway.openStreamingDestination`（Android 已实现：SAF 创建文档 + `ContentResolver.openOutputStream`）。
- 进度页：课程回放页顶部“下载任务”。

## Android：合成（已实现并在本机模拟器验证）

实现位置：

- `shared/src/androidMain/.../media/AndroidMediaCompositor.kt`：管线（解码 → OpenGL → 编码 → 封装）。
- `shared/src/androidMain/.../media/EglComposerRenderer.kt`：EGL 上下文 + GLES2 着色器（左右并排、等比缩放、黑边居中）。
- `shared/src/androidMain/.../media/VideoCompositor.android.kt`：`defaultVideoCompositor()` 返回它；设备没有支持 Surface 输入的 H.264 编码器时 `isAvailable=false`，任务自动改为分别保存各路 MP4。

管线与原计划的对应：

1. 两个 `MediaExtractor` 各选视频轨，两个 `MediaCodec` 解码器输出到各自的 `SurfaceTexture`（外部 OES 纹理）。
2. 编码器 `MediaCodec` `video/avc`，`COLOR_FormatSurface`，码率 2.5 Mbps，I 帧间隔 10 s，帧率 25 fps；`createInputSurface()` 接 EGL（`EGL_RECORDABLE_ANDROID`）。
3. 以 25 fps 输出时钟 t 逐帧：两路各自推进到 pts ≤ t 的最新帧（`releaseOutputBuffer(render=true)` 后等 `onFrameAvailable` 再 `updateTexImage`），片元着色器把左路画到左半、右路画到右半（等比缩放，不足处黑边），`eglPresentationTimeANDROID(t)` 后 `swapBuffers`。
4. `MediaMuxer`（MP4）写编码输出；声音：第三个 `MediaExtractor` 选左路音轨，`readSampleData` 原样 `writeSampleData`（AAC 直通，不重编码）。
5. 进度 = t / 总时长；协程取消时 `stop/release` 全部并删除输出文件；`MediaCodec` 异常转成 `CompositionException`。
6. 编码器不支持 2560 宽时（`MediaCodecInfo.VideoCapabilities.isSizeSupported`）降到 1920×540 再试，并在任务卡片上提示实际尺寸。

踩过的坑（已修）：

- 着色器程序必须在 `eglMakeCurrent` **之后**创建，否则 `glCreateShader` 直接返回 0（GL 错误码还是 0，报错信息是空的）。
- 解码器输出到 SurfaceTexture 的画面上下翻转：片元着色器里取 `1.0 - v`。

### Android：后台下载与合成（前台服务，已实现并验证）

1. `androidApp/.../playback/PlaybackForegroundService.kt`：`foregroundServiceType="dataSync"`，通知渠道“课程回放下载”，通知带进度条与“打开”。
2. Manifest：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`（已有 `POST_NOTIFICATIONS`）。
3. `MainActivity.onCreate` 里注入：`PlaybackPlatformHooks.backgroundHost = AndroidPlaybackBackgroundHost(applicationContext)`；`update(active=true)` 时开服务并更新通知，`active=false` 时停服务。任务本身仍跑在 `AppDataGraph` 的会话作用域里，服务只负责保活；已在后台时不再重复启动服务（系统会拒绝），改为直接更新通知。
4. 进程被杀后任务不会恢复（与 iOS/macOS 一致），任务卡片显示失败即可。

### Android 验证结果（本机 x86_64 模拟器，API 36）

- `./gradlew :androidApp:assembleDebug` 通过。
- `androidApp/src/debug/.../PlaybackSelfTestActivity`（仅 debug 构建存在的自测入口，release 包不含）跑通了 remux 与合成：ffmpeg 造的 1280×720 H.264+AAC 分片 → 设备内转封装成 MP4 → 两路合成。
  - 输出 1920×540（模拟器编码器不支持 2560 宽，按计划降到 1920×540）、H.264、25 fps、音频 AAC 48 kHz 双声道，ffprobe 全部核对通过，ffmpeg 解码零错误。
  - 抽帧确认：左半为教师路、右半为屏幕路，画面方向正确；16:9 + 4:3 混排时 4:3 一路左右留黑边；音频频谱确认取的是左路（教师）声音。
- 真实 App 流程：登录 → 应用 → 课程回放 → 选课程/课节 → “下载并合成” → SAF 选位置 → 任务页显示“下载中 4%” → 切到后台后通知栏进度继续走（`android.progress` 持续变化）→ 取消后临时目录与下载目录都清空。
- 修掉一个真实缺陷：SAF“创建文档”的 MIME 写死成 `video/mp2t`，系统会按 MIME 补扩展名，MP4 被存成 `xxx.mp4.ts`；改为通用类型后文件名正确。
- 未验证：模拟器上整节课（两小时、约 5 GB）下载到合成完成的全程耗时；真机上的硬件编码路径与发热；Android WebView 的 HLS 预览/播放（模拟器上预览是黑画面）。

## Windows

### 当前状态（2026-10-26：合成已实现，画面轨）

Windows 端现在也支持「左右并排合成」，实现为**随包分发的原生小程序** `BJTUPlaybackCompositor.exe`
（只用系统自带的 Media Foundation，不打包 ffmpeg；编译产物约 280 KB，对 MSI 体积影响可忽略）。

- 源码：`windowsApp/src/main/native/BJTUPlaybackCompositor.cpp`。
- 构建：`windowsApp/build.gradle.kts` 的 `compileWindowsPlaybackCompositor` 任务——用 `vswhere` 找 MSVC 的
  `vcvars64.bat`，`cl /MT` 静态链接 CRT（不依赖 VC++ 运行库）。找不到 C++ 工具链时**跳过编译**，
  运行时合成功能自动降级为「两路分别保存为 MP4」，App 其余功能不受影响。
- 分发：小程序以资源形式进 jar（`native/BJTUPlaybackCompositor.exe`），运行时释放到
  `%LOCALAPPDATA%\BJTUselfServiceKMP\tools\BJTUPlaybackCompositor-<内容摘要>.exe`
  （文件名带摘要，版本变化不会覆盖正在运行的旧文件；MSI 卸载会连带清掉这个目录）。
- 调用：`shared/src/desktopMain/.../media/WindowsPlaybackCompositor.kt`，与 macOS 的
  `MacHelperVideoCompositor` 同款调用方式（`compose <左> <右> <输出> <宽> <高> <码率>`、
  逐行 `progress 0.123`、非 0 退出码 + stderr 一行原因）。
- 分派：`defaultVideoCompositor()` 按 `os.name` 分派（Windows 走新实现，macOS 保持原来的 AVFoundation 小程序）。
  两端共用 shared 的 jvm 目标，所以分派只能放在共享的 desktop 源集里。

管线：两个 `IMFSourceReader` 软件解码出 NV12 → YUV 域 box 滤波等比缩放 + 左右并排（letterbox 居中）
→ `IMFSinkWriter` 写 H.264 MP4（优先硬件编码器，失败退软件）→ 固定 25 fps、pts = 帧号 × 400000（100 ns）。

### Media Foundation 经验（踩过的坑，写下来免得重踩）

1. `MF_MT_FRAME_SIZE` 是 HI32=宽、LO32=高；`MF_MT_FRAME_RATE` 是 HI32=分子、LO32=分母（写反会变成 1/25 fps）。
2. NV12 的**色度平面每行仍是整幅宽度字节**（只是行数减半）：横向偏移按像素算、行步长按整幅宽度算；
   误按半宽写会让色度整片错位（输出看起来是 25%/50%/25% 三段）。
3. 写入器会拒收 `CopyAllItems` 复制出来的媒体类型（`MF_E_INVALIDTYPE`）：直接用原类型。
4. 源端原生 AAC 类型缺 `MF_MT_AUDIO_SAMPLES_PER_FRAME`、块对齐是 1，要按 AAC-LC 规范值重建类型
   （48000/2ch/16bit/block 4/1024spf/128 kbps）。
5. 样本写入顺序必须**先声音后画面**，否则 MP4 写入器会卡死（全局时间戳倒序）。
6. 读到文件末尾时 MF 返回「成功但样本为空」，要据此判定结束，否则会无限补最后一帧（曾把 4 秒源写成 144 秒）。
7. MF 不提供可靠的轨道时长：自己解析 MP4 的 `moov`（`mvhd`，退回各轨 `mdhd`）拿秒数；
   真正的结束点以「解码到文件末尾」为准。
8. 只用 Vista 时代就有的接口（不要用 MF2 的 `MFCreateMediaBufferFromMediaType`），
   否则旧系统会因为导入符号缺失而起不来；`IMFSample::AddBuffer` 是 Vista 就有的，可以放心用。
9. 新版 Windows 的 Sink Writer 匹配不到旧式软件 H.264 编码器（`SetInputMediaType` 报
   `MF_E_INVALIDMEDIATYPE`），硬件编码器正常——依赖显卡硬件编码，没有独显的机器可能失败。

### 已知限制

- **声音轨暂时没有**：AAC 压缩流直通在独立探针里能写成功，但在 helper 里「解码器 + AAC 轨」同时存在时
  `Finalize` 必报 `MF_E_SINK_HEADERS_NOT_FOUND`（已排除类型组合、ASC blob、写入顺序、节流属性、
  读写顺序等假设，差异未定位）。因此默认只输出画面轨，`--with-audio` 保留实验入口。
- 合成速度约 28 ms/帧（1920×540），两小时课程约 84 分钟 CPU，尚未优化。
- 没有独显的机器可能没有可用的 H.264 硬件编码器（软件编码器在 Win10+ 上匹配不到）。

### 原来的设计思路（历史，已按此实现）

和 macOS 一样做成随安装包分发的原生小程序 `BJTUVideoCompositor.exe`，Kotlin 侧沿用 `MacHelperVideoCompositor` 的调用方式（同样的 `compose <左> <右> <输出> <宽> <高>` 参数、逐行 `progress 0.123`、非 0 退出码 + stderr 原因），只是查找路径换成 MSI 安装目录。

1. **语言与构建**：C++17 + Windows SDK（只链接系统自带的 `mfplat.lib`、`mfreadwrite.lib`、`mfuuid.lib`），在 Windows 上用 MSVC 编译；`windowsApp/build.gradle.kts` 加一个编译任务，把 exe 放进打包资源。不建议用 JNA 直接调 Media Foundation：COM 虚表在 JNA 里要逐个手写，代码量和出错面都更大（JNA 本身已在依赖里，但不划算）。
2. **读取**：两个 `IMFSourceReader`（`MFCreateSourceReaderFromURL`），视频输出类型设为 NV12。
   实测硬件解码给的是 D3D11 纹理、拿不到系统内存里的像素，所以解码固定走软件路径。
3. **拼画面**：以 25 fps 输出时钟 t，两路各自读到 pts ≤ t 的最新帧；在 CPU 上做 NV12 box 滤波缩放并左右拼到一张
   1920×540（按清晰度档位）的 NV12 画面上，黑边居中。
4. **编码与写文件**：`IMFSinkWriter`（`MFCreateSinkWriterFromURL`，`MF_READWRITE_ENABLE_HARDWARE_TRANSFORMS=TRUE`），视频输出 `MFVideoFormat_H264`、按档位码率、25 fps。
5. **声音**：读左路音轨的 AAC 压缩流原样写入 Sink Writer（直通）；目前被上面的 `MF_E_SINK_HEADERS_NOT_FOUND` 卡住。
6. **进度与取消**：进度按已写帧数 / 估计总帧数，每 0.1% 打一行；Kotlin 侧取消时 `destroyForcibly()`，半成品由 Kotlin 删除（与 macOS 相同）。
7. **兜底**：编码器拒绝目标尺寸时按档位退一档；全部失败返回非 0，任务卡片提示“可以关掉合成后分别下载”。

### Windows 合成验证结果（本机）

- helper 编译：`cl /nologo /utf-8 /EHsc /std:c++17 /O2 /W3 /MT … mfplat.lib mfreadwrite.lib mfuuid.lib propsys.lib ole32.lib /ENTRY:wmainCRTStartup`，
  产物 287,232 字节（`/MT` 静态 CRT，不依赖 VC++ 运行库）。
- 合成（ffmpeg 造的 1280×720 H.264+AAC 样本 → 1920×540）：ffprobe 确认 `h264 / 1920x540 / 25/1 fps / 4.000 s`；
  抽帧扫描第 0 行像素：x=0…959 全是红（教师路），x=960 起是另一路的颜色，左右并排正确。
- 合成（**学校重封装出来的 MP4**：`TsToMp4Remuxer` 的 `ftyp + moov + 64 位 mdat` 结构，用仓库里
  `shared/src/desktopTest/resources/media/synthetic-0.ts` 经 `RemuxSampleProbeTest` 转出来）：
  左路用 ffmpeg 样本、右路用重封装样本，合成成功并用 ffprobe/抽帧复核（左右颜色分布正确）——
  说明 Media Foundation 能解我们自己的重封装格式。
- 时长：不传 `--duration` 时靠自解析 `moov` 得到总帧数；修复"最后一帧被丢掉"后，
  4.032 s 的源合成出 4.000 s（此前是 3.96 s）。
- 打包：`:windowsApp:packageMsi` 本机成功；清理编译中间产物后 MSI **88,363,008 字节（84.27 MiB / 88.4 MB）**，
  比 1.8.6 基线（87,678,976 字节）只多 **684,032 字节（+0.64 MB）**，远低于 100 MB 红线。
  用 `msiexec /a` 解包确认合成小程序确实在安装包里
  （`app/windowsApp-windows-*.jar` → `native/BJTUPlaybackCompositor.exe`，287,232 字节）。
  CI 产物同样核对过（run `40246599815` 的 MSI：88,817,664 字节 → 清理后应为 88,363,008 字节量级）。
- **打包坑**：编译中间产物不能写在资源目录里面。整个 `build/generated/windows-native` 目录都是 jar 的资源根，
  之前 `.obj`（568 KB）和 `.bat` 落在其中的 `tmp/` 下，会跟着进安装包。现在中间产物改放
  `build/tmp/windows-native`，编译时清掉资源目录里的残留 `tmp/`，资源处理再排除 `tmp/**`、`**/*.obj`、`**/*.bat`。
  修完 jar 从 548,934 字节降到 409,746 字节。
- CI：`kmp-security-check.yml` 的 Windows 作业新增断言——app image 里必须能找到
  `native/BJTUPlaybackCompositor.exe` 且不超过 5 MB（缺了说明打包机没有 C++ 工具链）。
- 共享层新增 `WindowsPlaybackCompositorTest`：小程序按内容摘要落盘、旧版本自动清理、
  系统属性可覆盖路径、没有小程序时返回 null（合成降级而不是让 App 起不来）。

### Windows 实机验证结果（本机，Edge 156 / Chrome 155）

- `./gradlew :windowsApp:compileKotlinWindows` 通过；源码实例实跑过一遍完整流程。
- 回放页在浏览器播放：**Edge 156 原生播放 HLS**，两路并排同步播放正常，可切声音来源/画面布局/倍速；因此不需要在文案里额外建议改用 Chrome。
- 画面预览显示文字占位（教师画面/屏幕课件/学生画面三张卡片），与计划一致。
- AWT 保存面板：每路一个，文件名带课程/时间/画面标签，保存类型“所有文件 (*.*)”。
- “下载任务”进度页：阶段、百分比、当前步骤（“下载教师画面（第 1/2 路）”）、保存为、取消都正常。
- 取消后：下载目录里的临时文件与 App 临时工作目录都清空，无残留。
- 合成：2026-10-25 时显示“当前平台暂不支持合成，两路会分别保存为 MP4。”；2026-10-26 起 Windows 也有合成，
  界面不再提示不支持。
- 关窗行为：Windows 上点关闭默认**退出 App**（`onCloseRequest` → `exitApplication()`，只有开了“关闭窗口时收进托盘”才留在后台）。已把进度页提示改成“关闭窗口默认会退出 App 并中止任务；在设置里开启‘关闭窗口时收进托盘’后，关窗口可继续下载。”，实测点关闭后进程退出。
- 未验证：Windows 合成在真实课堂录像上的端到端结果（helper 用真实重封装 MP4 验证过，App 内流程未实跑）；
  Edge/Chrome 之外的浏览器；关窗后收进托盘时任务是否继续（需要先在设置里开启该选项再实测）。
