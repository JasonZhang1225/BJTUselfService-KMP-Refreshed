# Windows 桌面端移植计划（M14-Windows）

> 状态：规划完成，待用户批准后实施
> 基线：`windows-dev` 分支 HEAD `93b3d0a`（1.7.2-KMP-A tag `b85747f` 之后 1 个提交，即「更新弹窗渲染 Markdown」）
> 目标：KMP Windows 桌面应用，UI 复用 `commonMain` 共享 Compose，功能对齐当前基线，验证码自动识别复用原版 Android torch 模型

## 1. 范围

### 1.1 交付物

- `multiplatform/shared` 新增 `jvm("windows")` target（独立于 macOS 的 `jvm("desktop")`），新增 `windowsMain` source set。
- 新建 `multiplatform/windowsApp` Gradle 模块：Compose Desktop Windows 入口，打包 EXE/MSI。
- `multiplatform/tools/captcha` 增加 Windows 验证码评测入口（复用同一 24 张固定冒烟集 manifest）。
- 文档：`goal.md` / `memory.md` / `history_full.md` 新增 Windows 移植节。

### 1.2 功能对齐（基线 93b3d0a 全量）

登录（验证码自动识别）、首页/周议程/DDL/信息流、成绩（自选/筛选/排序/变动弹窗/限选配色）、课程表（周导航/日期跳转/列表概览/性质色块/交替半格）、考试（单条/导出）、作业（列表/详情/附件下载/上传）、课件（树形/下载）、教室人数估计、教室占用查询（M11）、校历成绩单、邮箱、设置（更新检测/更新弹窗 Markdown/清除数据）、菜单命令、浅深色。

### 1.3 UI 复用

不修改 `commonMain` 共享 UI；Windows 复用 `App()` 与全部业务页面。`windowsApp` 仅提供窗口宿主 + 菜单命令。

## 2. 关键决策

### 2.1 模块架构（实施后修正）

- **`shared` 不新增 target**：KMP 不允许同一模块声明多个 jvm target（`jvm() Kotlin Target Already Declared`）。
- **`multiplatform/windowsApp` 独立 KMP 模块**：`jvm("windows")` target，`implementation(project(":shared"))` 复用 shared 的 desktop 产物——即复用全部 commonMain 业务/UI 和 desktopMain 平台实现（`currentPlatform` 等已按 OS 自适应）。
- **`shared/src/desktopMain/Platform.desktop.kt` 唯一共享改动**：按 `os.name` 返回 Windows/macOS displayName；`PlatformFamily` 枚举不修改，Windows 复用 `MacOS` 桌面 UI 分支（commonMain 对枚举的穷尽 when 不区分桌面平台）。
- macOS 专属实现（CoreML helper、Keychain JNA、trackpad 桥）在 Windows 上加载失败时优雅回退；凭据、验证码、缓存、文件网关由 windowsApp 注入覆盖。

### 2.2 验证码：复用 torch 模型（BJTUCaptcha.pt）

- 模型文件：`multiplatform/androidApp/src/main/assets/BJTUCaptcha.pt`（23.6MB TorchScript 冻结图，输入 `1×3×42×130` `[0,1]` RGB/CHW，输出 `8×1×15` logits）。
- Windows 推理：**DJL PyTorch 引擎**（`ai.djl.pytorch:pytorch-engine:0.33.0` + `ai.djl.pytorch:pytorch-native-cpu:2.5.1:win-x86_64`）。Java 侧仅做图片解码/缩放（ImageIO → `130×42`）、`[0,1]` 归一化与 CHW 组装，与 Android `AndroidTorchCaptchaRecognizer` 语义一致；logits 复用共享 `decodeCaptchaLogits`（同一 15 类字符表 / 8 时间步 / CTC 折叠）。
- 模型分发：复制到 `windowsApp/src/windowsMain/resources/`，运行时解出到临时目录后加载。
- **对齐验证（已通过）**：确定性合成图分别用 Python torch（2.13.0+cpu）与 Windows DJL 推理，120 个 logits **max abs diff 0.000006、argmax 逐时间步完全一致**。
- 诊断参数：`--verify-captcha-model=<图>`（表达式/答案）、`--dump-captcha-logits=<图>`（原始 logits）。

### 2.3 Windows 平台实现（windowsApp windowsMain，注入覆盖）

| shared desktop 默认（macOS） | windowsApp 注入覆盖 |
|---|---|
| `MacOsKeychainCredentialVault` | **DPAPI**（JNA `Crypt32.CryptProtectData`，DATA_BLOB 16 字节结构），密文 base64 存 `java.util.prefs` |
| `createDesktopCacheStore`（~/Library） | `%LOCALAPPDATA%/BJTUselfServiceKMP/bjtuselfservice_cache.db`（SQLDelight JDBC，同套恢复逻辑） |
| `DesktopCoreMlCaptchaRecognizer` | `WindowsTorchCaptchaRecognizer`（DJL，见 2.2） |
| `DesktopHomeworkFileGateway`（owner Frame） | `WindowsHomeworkFileGateway`（AWT FileDialog + 原子写，去掉 macOS 目录对话框属性开关） |
| 系统日历（EventKit helper） | `UnavailableSystemCalendarGateway`（课程表/考试导出走共享 ICS，与 Android 一致） |
| `currentPlatform` | 共享 desktopMain 已按 OS 自适应（displayName） |

### 2.4 windowsApp 模块

- 结构：`jvm("windows")` + `implementation(project(":shared"))`；`mainClass = team.bjtuss.bjtuselfservice.windows.MainKt`。
- 依赖：compose `desktop-jvm-windows-x64`、jna、ktor-cio、sqldelight-jdbc、DJL engine+native。
- 窗口：1080×720 起、最小 720×520，关闭即退出（不做 macOS 隐藏窗口生命周期）。
- 打包：`packageExe`（jpackage 安装器）；`packageMsi` 需要 WiX 未启用。打包需完整 JDK（JBR 无 jlink/jpackage）：`WINDOWS_PACKAGE_JAVA_HOME` 环境变量或 build 内默认 `C:/Users/zjg/jdk21/jdk-21.0.8+9`。
- 测试：`windowsTest` 覆盖 DPAPI 往返/清除/篡改防护（`WindowsDpapiCredentialVaultTest`）。

## 3. 平台差异与理由

- 导航：Windows 无系统菜单栏（Compose `MenuBar` 是 macOS 专属），主窗口内用共享壳层导航 + 窗口顶部命令菜单（用 AWT 菜单或共享页面内导航）；不把底部导航强塞。
- 验证码字段：无原生硬件键盘差异；用共享 Compose 字段。
- 日历：无系统日历 API，用 ICS 导出（与 Android 一致）。
- 触控板分页：Windows 无 AppKit phase，用滚动距离分页回退。

## 4. 修改文件清单

新增：
- `multiplatform/windowsApp/`（build.gradle.kts、`src/windowsMain/` 下 Main.kt、WindowsTorchCaptchaRecognizer.kt、WindowsAccountSecurityStore.kt、WindowsCacheStore.kt、WindowsHomeworkFileGateway.kt、resources/BJTUCaptcha.pt；`src/windowsTest/` 下 WindowsDpapiCredentialVaultTest.kt）
- `docs/migration/windows-port-plan.md`（本文件）

修改：
- `multiplatform/settings.gradle.kts`：`include(":windowsApp")`
- `multiplatform/gradle/libs.versions.toml`：新增 djl 版本与库、compose-desktop-windows-x64、jna-platform
- `multiplatform/shared/src/desktopMain/.../Platform.desktop.kt`：displayName 按 OS 自适应（唯一 shared 改动，不影响 macOS 行为）

不修改：`desktopApp`（macOS 构建不受影响）、`androidApp`、根冻结 Android 工程、`commonMain` 共享代码。

## 5. 构建与验证命令（本机 Windows）

本机无 Android SDK → Android/iOS 目标无法在本机编译；**macOS `desktopApp` 不回归由「desktopApp 未被改动、shared desktop 目标编译通过」保证**，完整三端构建验证留待 Mac 环境复跑。

1. `./gradlew :shared:compileKotlinDesktop :windowsApp:compileKotlinWindows` —— shared desktop + windowsApp 编译。
2. `./gradlew :windowsApp:windowsTest` —— DPAPI 保险库往返/清除/篡改防护测试。
3. `./gradlew :windowsApp:run --args="--verify-captcha-model=<图>"` —— 验证码表达式识别；`--dump-captcha-logits=<图>` 导出原始 logits。
4. **logits 对齐验证（已完成）**：Python torch 参考 vs Windows DJL，max abs diff 0.000006、argmax 完全一致。
5. `./gradlew :windowsApp:createDistributable` → 直接运行 app-image EXE；`packageExe` 生成 jpackage 安装器（需 `WINDOWS_PACKAGE_JAVA_HOME` 指向完整 JDK）。
6. 运行打包后 EXE，验证窗口、登录页、共享主题渲染（本机已截图验证深色主题配色精确匹配共享 token）。

## 6. 风险与回退

- **真实正确率待验**：logits 已与 Python torch 逐值对齐（同模型同语义），但 24 张真实冒烟集样本不在本机；真实正确率评测需样本集到位后执行（`tools/captcha/evaluate_model.py` 路径可扩展 Windows 入口）。
- DJL 加载 `.pt` 失败 → 可回退 ONNX + onnxruntime（当前已验证 DJL 直载成功，风险低）。
- Compose 1.12.0-beta03 Windows 运行时问题 → 记录并单独修，不回退版本（与 Android/macOS 保持同版本）。
- 本机无 Android SDK：Android 目标编译验证在 Mac/装有 SDK 的环境补做。
- 下载 libtorch 2.5.1（~150MB）与 Gradle 9.3.1 需要网络，已确认 Maven Central 可达。

## 7. 验收

- [x] `shared` desktop 目标编译通过；`windowsApp` 可运行，登录页/共享页面正常显示（截图验证深色主题配色精确匹配）。
- [x] 验证码推理与 Android 语义完全对齐：Python torch 参考 vs Windows DJL logits 三图 max abs diff ≤0.000012、argmax 逐时间步一致（策略第 4/5/6 层）。
- [x] DPAPI 凭据保险库测试通过（往返/清除/篡改防护）。
- [x] 功能入口清点：全部 14 个共享页面（首页/成绩/课程表/考试/作业/课件/教室占用/教室人数/邮箱/校历文章/成绩单下载/设置/更多）走同一 `AppSection` 路由，Windows 与 macOS 共享代码路径；关键屏模型测试（课程表 28/成绩/设置）通过；`shared:desktopTest` 384/385 通过（唯一失败为 macOS Keychain 平台边界）。
- [x] macOS `desktopApp` 构建配置未被改动（git diff 不含 desktopApp；shared desktop 编译通过）。
- [x] 文档三件套更新完成（memory.md 已更新；history_full.md 已归档 M14 节；goal.md 已加 M14 节；本文件为规划与验收）。

### 验收补充记录（2026-08-16，合并后）

- [x] **合并**：`windows-dev` 推送远端，PR #2（windows-dev → main）经 gh 合并，merge commit `47fb5e6`；本地 main 已同步。
- [x] **用户人工确认**：用户明确「人工测试基本通过了」，决定不再做真实登录逐页复测（原「全入口可达」项由用户人工确认替代，不再要求账号会话证据）。
- [ ] **验证码真实正确率**：24 张固定冒烟集样本不在本机，未评测；logits 已与 Python torch 逐值对齐（max abs diff ≤0.000012、argmax 一致）证明同模型同语义。用户已人工确认登录基本通过，此项作为已知边界记录，待样本集到位后补评。
- [ ] **发布**：按用户要求**暂不发布**——未创建 tag、未发布 Release、未上传安装器。Windows 安装器已生成本地：`windowsApp/build/compose/binaries/main/exe/BJTUselfServiceKMP-1.7.3.exe`。
