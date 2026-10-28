# 课程回放：安卓下载合成提速与跨芯片兼容计划

**状态更正：2026-10-28，部分方案已实现，最新真机测量尚待进行。** 当前验收与后续次序统一见 [四平台审查标准](playback-four-platform-acceptance.md)。下文保留 2026-10-27 的分析与候选方案，不再作为当前未实施清单；硬件上限、耗时收益和跨芯片速度推断仍未经真机测量证实。

- 已实现：受限 `FastMp4PartJoiner` 与旧整理回退、两路 SurfaceTexture 变换矩阵、按名称选择硬件/软件编解码器候选及失败资源释放、保存进度约 250ms 节流。已有本地容器/样本验证，不等于最新平板整节通过，禁止重复开发。
- 未完整实现/验收：增量整理、SAF 直写、进一步合成流水线、两路串行回退、跨芯片稳定性。下文“约27%可预跳过”只是旧假设；既有短样本以 I/P 参考帧为主，不能直接据此跳帧。
- 下一阶段仅 **P0 真机测量与验收（PROMPT2）**；报告明确两个最大瓶颈后才进入 **P1 定向优化（PROMPT3）**。下文原 P1/P2/P3 为历史技术候选编号，不代表自动授权按旧计划全面实施。

分支 `feat/courseware-fallback-playback`（安卓整节验收基线 `3c1740c`）。约束沿用持续目标：**不能通过降帧、丢帧或降低画质冒充提速**；成片必须逐帧完整、音轨与原轨一致。

## 1. 基线：16 分 15 秒花在哪里

数据来自 `memory.md` 记录的不暂停计时任务 `t1793030228149-0`（小米平板 8 Pro，骁龙 8 Elite，10 月 26 日 23:57:08 → 10 月 27 日 00:13:23）：

| 阶段 | 时间点 | 耗时 | 说明 |
|---|---|---|---|
| 下载 3.85 GB | 0 → 8:14 | 8 分 14 秒 | 平均约 7.8 MB/s，≈ 14.2 倍实时 |
| 合成编码 | 0 → 14:25 | 14 分 25 秒 | 与下载重叠；7022 秒媒体 ≈ 8.1 倍实时，输出约 203 帧/秒 |
| 整理分段 | 14:27 → 15:58 | ≈ 1 分 31 秒 | 编码结束后串行 |
| 保存 | → 16:15 | ≈ 16 秒 | 757 MB 再复制一遍到 SAF 目标 |

结论：

- **瓶颈是合成，不是网络**。下载（14.2×）比合成（8.1×）快，合成除开头外几乎没有等过输入。
- 源是教师路 25.6 fps + 课件路 34.5 fps 的 1080p（见 `playback-composite-android-windows.md`），每秒媒体要解码约 60 帧 1080p。8.1× 时 ≈ 每秒硬件解码 490 帧 1080p，同时编码约 200 帧 1920×540。按 8 Elite 视频单元公开规格粗算已用掉约六到七成，**理论余量不到一倍**，需实测确认。
- 结束后的整理和保存（≈ 1 分 47 秒）是纯额外开销，与硬件上限无关。
- 判断：作为手机上两路 1080p 硬件解码再编码，8× 实时属正常量级，不是异常慢；但尾段可以基本消除，合成段是否贴近上限尚未测出。

## 2. 代码审阅发现

### 2.1 性能相关

| 位置 | 现状 | 影响 |
|---|---|---|
| `AndroidCompositionJournal.kt:65` `concatenate` | MediaExtractor + MediaMuxer 逐样本重新封装约 102 段、约 47 万样本 | 757 MB 用 91 秒（≈ 8 MB/s），开销在每样本 JNI/封装，不在磁盘 |
| `PlaybackTaskManager.kt` 合成保存分支（`readFileInChunks` 循环） | 本地 `composite.mp4` 按 1 MB 复制到 SAF；每块调用一次 `update()`，连带刷新通知 | ≈ 16 秒；成片在磁盘上写两次 |
| `AndroidMediaCompositor.kt:211–258` 主循环 | 逐帧串行：等两路解码 Future → 等 `onFrameAvailable`（经 HandlerThread 中转）→ `updateTexImage` → 绘制 → `swapBuffers`（interval 1，编码背压）→ 非阻塞取编码输出；解码只提前一帧 | 硬件三段（解码/GPU/编码）只部分重叠，可能存在同步等待瓶颈 |
| `AndroidMediaCompositor.kt:653` `configureOfflineCodec` | 编码器设 `KEY_PRIORITY=1` + `KEY_OPERATING_RATE=1000`；解码器只设工作速率（取能力上限，封顶 1000），未设优先级；配置被拒时去掉提示用默认参数重试 | 若日志出现 `offline rate hint rejected`，该路以默认（低）时钟运行 |
| `AndroidMediaCompositor.kt:565–574` | 解码输出等待 10 ms 超时，无输出且输入未结束时再 `sleep(1)` | 稳态少见，非主要因素 |
| 课件路 34.5 fps → 输出 25 fps | `advanceTo` 把未选中的帧 `releaseOutputBuffer(false)` 丢弃，约 27% 的课件帧解码后即被丢弃 | 若这些帧为非参考帧，可不送解码器，成片不变 |
| `ProgressiveRecordingPipeline.kt:49` | 两路各 2 个分片连接，总 4 个 | 合成追上网络后才成为瓶颈 |

### 2.2 跨芯片兼容相关

整套只用 Android 标准 API：MediaCodec、编码器 Surface 输入、EGL/GLES2（`EGL_RECORDABLE_ANDROID`、`GL_OES_EGL_image_external`），均为 CTS 要求能力，**不依赖高通私有接口**。`createDecoderByType` / `createEncoderByType` 在高通上取 `c2.qti.*`，在 MTK 上取 `c2.mtk.*`，在三星上取 `c2.exynos.*`。唯一的高通特定处是工作速率取 1000 而非 `Int.MAX_VALUE`（规避部分高通固件溢出）。风险点：

1. **速度取决于视频单元**：天玑 9300/9400 与骁龙旗舰同级，预计同量级；中端（标称 4K30/60 解码）约 2–4× 实时，一节课 30–60 分钟；只有软件编解码（`c2.android.*`）时需数小时。当前没有区分硬件/软件，也没有耗时预估。
2. **三个并发硬件会话被拒时没有退路**：`createDecoderByType` 失败或资源不足（`CodecException.isResourceError`）只会去掉速率提示重试一次，不会换编解码器、改软件或单路先行。另外 `H264SurfaceEncoder.supportsSize` 查的编码器（`findEncoder`）与实际 `createEncoderByType` 创建的不一定是同一个。
3. **编码器自行丢帧会使任务反复失败**：`AndroidMediaCompositor.kt:271` 帧数核对不一致即抛错。部分厂商编码器在输入远快于 25 fps 时可能跳帧，换芯片后可能每次都停在“编码器丢帧”。
4. **未使用 SurfaceTexture 变换矩阵**：`EglComposerRenderer.kt:256` 片元着色器直接用 0–1 纹理坐标并手动翻转 v，没有调用 `getTransformMatrix()`。1080p 解码缓冲常按 1088 行对齐，裁剪信息在该矩阵里；忽略它可能在部分芯片出现底边几行绿/黑边或轻微拉伸。平板目检未发现，但不能推广到 MTK 等。
5. `PARAMETER_KEY_REQUEST_SYNC_FRAME` 可能被忽略：现有逻辑退回 10 秒自然 IDR 切段，可接受。

## 3. 推荐路径

### P0 先采样（不改行为）

1. 从 `.artifacts/android-full-acceptance/` 找计时任务的 logcat，统计 `PlaybackCompose frames=… decodeMs drawMs encodeMs` 三项占比，并检查是否出现 `offline rate hint rejected`、实际 `decoder=`/`encoder=` 名称。没有留存则用 debug 入口重跑一段（例如 10 分钟媒体）采集。
2. 在 `PlaybackSelfTestActivity`（debug 包）加三个基准，各跑同一段源数据：
   - 只解码两路（输出到 Surface 后直接丢弃）的最大帧率；
   - 只编码（GL 清屏帧）1920×540 的最大帧率；
   - 完整流程；可选再加“两条独立流水线处理不同时间段”。
   完整流程明显低于 min(解码, 编码) → 同步等待瓶颈；接近 → 硬件瓶颈。
3. 源视频帧类型：`ffmpeg -i <ts> -c copy -bsf:v trace_headers -f null -`（或 `ffprobe -show_frames -show_entries frame=pict_type`）确认两路是否有 B 帧/非参考帧（`nal_ref_idc == 0`）及 GOP 长度。
4. 可选：Perfetto 抓 `gfx`/`video` 与视频单元时钟，确认合成时编解码硬件负载与降频情况。

证据放 `.artifacts/android-perf/`，不进 Git。

### P1 消除尾段（把握最大，预计 ≈ 1 分 47 秒 → ≈ 10 秒）

1. **分段直接拼接**：各段编码配置一致（`sameConfig` 已校验），改为在 MP4 盒层面合并：解析每段 `moov` 的 `stsz/stco|co64/stts/stss/ctts` 与 `mdat` 范围，原样大块复制 `mdat`（`FileChannel.transferTo`），合并样本表重写一份 `moov`。时间戳沿用现有全局时间与按 AAC 帧数连接的规则。复用 `TsToMp4Remuxer` 的盒写出代码。
2. **增量整理**：每提交一段即追加到成品数据区，编码结束时只剩最后一段和写 `moov`。
3. **直接写到目标**：整理输出直接写入用户选择的 SAF 目标（`openFileDescriptor(uri, "rw")` 可随机写时）；不支持时退回现有复制，复制循环改为按进度节流 `update()`。
4. 测试：脱敏合成样本的多段拼接与现有 `concatenate` 输出逐样本一致（时间戳、关键帧、音频帧数、csd）；中断后恢复；目标不可随机写的回退路径。实机重跑整节，复核 175564 帧、40000 us 间隔、音轨 SHA256 与 ffmpeg 全解码。

### P1 修正 SurfaceTexture 变换矩阵（小、低风险）

着色器改为用 `getTransformMatrix()` 变换纹理坐标（保留现有等比缩放与黑边逻辑），每帧 `updateTexImage` 后读取。验收：首/中/尾抽帧与当前成片对比边缘行，无裁剪变化或更正确；模拟器与平板各一遍。

### P2 合成提速（依 P0 结果选择）

- **同步瓶颈** → 两条独立流水线并行处理不同 60 秒时间块：调度器按“输入已下载到块末尾”领取块，每块从源关键帧预滚（复用 `primeBefore`），块首编码 IDR；日志改为支持不连续块的提交与按块确定性的音频分配。或先尝试 MediaCodec 回调模式加深解码前瞻。
- **硬件瓶颈** → 减少工作量而不改变成片：若课件路被丢弃帧是非参考帧，按索引时间戳预先算出“输出会选中的帧”，未选中的非参考帧不送解码器。必须保证成片逐帧不变。
- 统一解码器与编码器的 `KEY_PRIORITY`/工作速率设置做 A/B；最后才考虑大核软件解一路分担硬件（功耗与发热代价）。
- 目标：合成 ≥ 14× 实时时总耗时由下载决定，约 8.5–9 分钟；达不到则如实记录上限。

### P3 下载并发（合成追上网络之后）

两路各 3–4 个连接做 A/B，观察学校服务器是否限速或报错；保持按顺序交付、断点与重试语义不变。

### 兼容性加固（与 P1/P2 并行，可在获得非高通设备后验收）

1. 编解码器选择层：枚举并优先 `isHardwareAccelerated`（API 29+），检查 `getMaxSupportedInstances`、`getSupportedPerformancePoints` 预估耗时并在任务卡提示；查询尺寸与实际创建用同一个编解码器（按名称创建）。
2. 资源不足回退：换同类其他硬件编解码器 → 软件 → 两路串行，任务卡说明原因。
3. 编码器丢帧：先降工作速率重试当前段，仍失败再提示；不放宽帧数核对。
4. 日志记录实际编解码器名、合成帧率与回退路径，便于对比不同芯片。
5. 至少在一台天玑设备（或云真机）跑一段完整流程。

## 4. 预期与验收

| 方案 | 预计总耗时（同课程、同网络） |
|---|---|
| 现状 | ≈ 16 分 15 秒 |
| 只做 P1 | ≈ 14.5 分钟 |
| P1 + P2 使合成 ≥ 14× 实时 | ≈ 8.5–9 分钟（下载决定） |
| 再加 P3 | 取决于学校服务器 |

每步验收沿用安卓整节标准：不暂停计时、帧数与 PTS 间隔、音轨 SHA256、ffmpeg 全解码退出 0、暂停/强停恢复、浅/深色与大字号进度展示。回退方案：每步独立提交，可单独回滚；P1 保留旧 `concatenate` 与复制路径作为回退直到实机验收通过。
