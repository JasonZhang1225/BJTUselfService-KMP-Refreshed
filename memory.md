# BJTUselfService KMP 迁移工作记忆

> 最后更新：2026-09-16
> 当前分支：`main`；显示/打包版本 **`1.7.6-debug-1`**。主体提交 `eca1a3a`、`25947a9`、`591c905`、`d5b1029`、`40e7f9b`、`fa1e1d6`、`d8a89e9`、`20e08d6`、`64e1956` 已推送；本轮普通作业提交协议头修复待提交。Windows 本地 MSI 已成功生成，远端普通打包未触发，未打 tag、未建 Release。Windows 本机无法打 IPA，由用户在 Mac 打包。
> 阶段状态：**173B 基座已同步；M13 代码层初步开发完成。校历入口已移除失效下载接口并改为公众号文章。M15 邮箱已完成 Coremail 只读文件夹/列表/详情扩展，宽屏三栏与紧凑端文件夹选择/二级阅读 UI 按 Apple Mail 方向重做；紧凑端邮箱主页、邮件详情和写信/回复现统一采用平台原生页面层级（Android Activity、iOS UIKit push），与两个教室查询入口保持一致；根页面转场期间不再先显示内嵌详情，避免重复视觉跳转。紧凑端当前文件夹 banner 负责文件夹切换，邮箱右上角胶囊显示“刷新”；HTML 表格正文已结构化渲染。当前已补上写信/回复首版和 `MAILBOX_COMPOSE` 原生编辑页，发送前确认但未实际发送，详情返回统一到左上角。Windows 与 Android x86_64 模拟器均已用真实登录态核对邮箱主页、当前文件夹 banner、刷新控件和编辑页，Android 另核对普通刷新、邮件详情、回复预填和发送确认。Mac 已有真实登录态列表/详情证据，真实发送/删除/附件下载和真实登录后的 iOS 邮箱验收未完成。M16 VPN 仅保留调研，当前不开发。PR #3 已合入。Android 已改为本地/CI 共用上传签名（证书 SHA-256 `5d0dabc3…c773`）。`v1.7.4-KMP` Release Android 包为仅 `arm64-v8a`、无 debug 文件名（142,270,345 字节）。本轮已在 `C:\Users\zjg\Android\Sdk` 恢复 Android SDK/`adb`/模拟器；Android x86_64 debug 构建、安装、登录和邮箱视觉回归均完成，实体 iPhone 仍缺 provisioning profile。当前版本为 `1.7.5-KMP`。**
> 分支创建点：`9d8da18`；上游对照基线：`v1.7.0@419313d`；KMP 自身基线：**`v1.7.3-KMP-B` (`a342615`)**；当前调试版本 **`1.7.6-debug-1`**（本轮只打普通 CI artifacts，不打 tag、不建 Release）；上一发布 `v1.7.4-KMP` 保留。
> 完整历史与已归档的验收细节：见 `history_full.md`（按里程碑归档，只读）
> 本文件是实时工作记忆，不是只追加日志：任务开始读、结束改，只保留当前接续工作需要的状态。

## 1. 本阶段已做到（≤10 行）

- **第一阶段收口 + `1.7.1-KMP`/`1.7.2-KMP` + M12 + `1.7.2-KMP-A` + M14 Windows**：细节见 `history_full.md`。
- **2026-08-17 `1.7.3-KMP-B` 基座**：教学周改 `getTimeList`；作业容错对齐 1.7.0；CI、Windows MSI ASCII 修复、macOS JDK/iOS 任务拆分均已合入 `a342615`。2026-08-29 实测学期末 `getTimeList` 与 `room_view` 都可能误给第 1 周，现用当前学期校历按日期校正并把校正值写回缓存：只有当前日期命中当前学期校历时才允许覆盖；校历未确认时保留可追溯缓存，无缓存显示未知，禁止把远端裸第 1 周展示给用户。教学周范围统一为 1–30。
- **Windows 移植（M14）**：DPAPI 凭据保险库、%LOCALAPPDATA% 缓存、AWT 文件网关、系统浏览器、Ktor CIO、GB18030、验证码推理、品牌图标与打包链路已实现；细节见 `history_full.md`。
- **M13 物理在线首版**：CAS/OAuth2 白名单握手、课程/作业/首页安排、按学号隔离缓存、失败提示、窄屏原生详情、自动同步“仅校园网”；Mac 真实登录态可读 3 门课、32 个活动。真实上传未执行。调研与结果见 `docs/migration/m13-phyvlab-integration-*.md`。
- **2026-08-29 M13 作业截止状态**：物理在线作业列表与详情统一按当前北京时间判断：已完成为绿色，未完成且未到截止为黄色，未完成且已到截止为红色并加粗；缺少可靠截止时间时保持中性，不猜测颜色。详情页拿到明确提交状态/时间时优先覆盖列表完成标记，新增截止边界与提交信号回归测试；Android/Windows 真实账号当前样本均为已完成，未执行真实提交。
- **2026-08-29 iOS/macOS 最新产物**：`BJTUSelfService-KMP-1.7.4-KMP-iOS-unsigned.ipa` 与 `BJTUselfServiceKMP-1.7.4.dmg` 已放入 `/Users/zjg/Downloads`；iOS Bundle ID `team.bjtuss.bjtuselfservice.kmp.ios`、版本 `1.7.4-KMP`、Build `15`，IPA 包内无 `_CodeSignature`，SHA-256 `6a9b37300270b83680e935018b0ecab88b08dc8817b4f22625252b14603a3cd3`；macOS DMG SHA-256 `239ccc9e82d360c31a15f6b49939f94391822b665bda8c9257f8de7055d5650c`。最新 iOS Simulator Debug 已安装并启动到登录页；实体机缺 provisioning profile。
- **2026-08-28 Android 共用签名**：本机 `~/.android/bjtu-kmp-upload.keystore` 与当前 Release APK 同一证书（SHA-256 `5d0dabc3…c773`）。`:androidApp` debug/release 都用这把钥匙；GitHub Secrets 已写入 `BJTU_ANDROID_KEYSTORE_BASE64` / `STORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`。密钥文件不进 Git。`v1.7.4-KMP` APK 为 `BJTUSelfService-KMP-1.7.4-KMP-arm64-v8a.apk`（142,270,345 字节）。旧 Actions 包需先卸载再装。
- **2026-08-28 冻结 Android CI**：PR #3 合入后 `Build Debug APK`（run 33162675067）因 `maven.aliyun.com` 502 失败。已在 Actions 上改写 Maven 源为 Google/Maven Central（不改冻结 `settings.gradle.kts`），纯 KMP/文档提交不再触发这份旧打包。复跑 [33165162229](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/33165162229) 成功（4m49s，已上传 APK）。
- **2026-08-27 同步失败提示收口**：首页失败胶囊同时弹出模块清单并重试；物理在线失败且有缓存时顶栏为“同步失败·正显示缓存”，横幅改为校园网说明，不再显示原始 `network` 诊断。
- **2026-08-27 打包与图标**：`1.7.4-KMP` 四端产物曾由 CI 上传；macOS DMG 文件图标已换成圆角透明留白版本。开发版 `1.7.4-KMP-DEV` 仅作 Windows 验收副本，当前对外版本是 `1.7.4-KMP`。
- **2026-08-28 校历入口替换**：KMP “更多”中的校历改为打开指定公众号文章；移除 bksy 校历下载数据源、解析器、下载状态与相关测试，冻结根 Android `app/` 未修改。相关共享桌面测试、iOS Simulator 测试通过。
- **2026-08-28/29 M15/M16 首轮调研与 M15 切片**：直接 Chrome DevTools MCP 确认 Coremail XT5 传统打包前端、收件箱/详情 JSON 请求，以及 `vpn` 部分代理、`libvpn` 全代理的官方 OTP 登录入口；物理在线公开 HTTPS 首页可达，但未输入凭据、安装 VPN 或改系统设置。M15 已加入原始 JSON HTTP 传输、Coremail 列表/详情解析与只读响应式邮箱页；本轮将邮箱 UI 重做为宽屏文件夹—列表—阅读三栏、紧凑端列表→详情二级页，并补齐加载/空/失败态和自绘图标；修正三栏阈值按邮箱内容区而非外层窗口判断。Mac 真实登录态的列表→详情回归通过；最新 iOS Simulator 仅验证登录首屏。脱敏单测、桌面/iOS Simulator 单测和 Android/桌面编译通过。
- **2026-08-29 macOS 启动链路补丁**：确认登录页原生凭据输入框的同步 JNA 创建会造成 AWT EventQueue 与 AppKit 主线程互等；改为后台创建、完成后异步挂载，源码构建可正常显示单个“交大自由行 KMP”登录窗口。验证结束后已按精确路径回收源码实例，未关闭用户安装版。
- **2026-08-29 多窗口验证链路已定位**：`desktopApp:run` 的 Gradle 前台进程结束后，源码子 JVM 可能继续运行；连续启动会与已打开的 `/Applications` 安装版形成多个同名窗口。`Main.kt` 只有一个 `Window`，生命周期回调不创建新窗口。已停止本轮源码实例，规则已写入 `CLAUDE.md`；后续每次只启动一个源码实例并按精确路径回收。
- **2026-08-29 安装版/源码版对照**：`/Applications/交大自由行 KMP.app` 已用包含 M15 三栏阈值修复的最新 `BJTUselfServiceKMP.app` 逐文件覆盖，Info 版本为 `1.7.4`/Build `15`，签名校验通过；当前安装版已重新启动到登录页。验证时仍必须只保留一个明确路径的实例，不能用相同 Bundle ID 区分源码版与安装版。
- **2026-08-29 M15 邮箱文件夹扩展**：按 Coremail 实际树节点 FID 修正收件箱 `1`、待办 `-5`、草稿 `2`、已发送 `3`，并加入已删除 `4`、垃圾邮件 `5`、病毒邮件 `6` 的只读列表；紧凑端内嵌详情隐藏邮箱页顶栏返回，只保留“返回邮件列表”。桌面/iOS Simulator 测试通过，真实登录后的新侧栏尚待用户点验。
- **2026-08-29 M15 紧凑端文件夹入口**：Windows 默认邮箱内容区约 820dp，三栏门槛下原先只显示收件箱列表；`MailboxScreen.kt` 已加入“当前文件夹 / 切换”菜单，复用 7 个 Coremail FID。Windows 源码版真实登录态已视觉核对收件箱 216 封、已发送 36 封和待办空状态；Chrome DevTools MCP 已核对网页侧相同文件夹及 FID。Android SDK/`adb`/x86_64 模拟器已恢复，本轮 Android debug 已构建、安装并完成登录，邮箱主页与文件夹入口视觉验收已完成。
- **2026-08-29 M15 邮箱主页重复跳转修复（历史尝试）**：曾尝试让紧凑端从“更多”进入邮箱时留在 `MainActivity`，以消除旧页面到新 Activity 的重复视觉跳转；该方案导致邮箱一级页没有平台转场，且与教室查询的导航层级不一致，现已由下一条记录替换。
- **2026-08-29 M15 邮箱导航层级对齐教室查询**：紧凑端从“更多”进入邮箱重新调用 `onOpenNativeRoute("MAILBOX")`，启动 `NativeDetailActivity`；点击邮件进入新的 `MAILBOX_DETAIL` Activity，写信/回复进入 `MAILBOX_COMPOSE` Activity。Android 已验证任务栈为主 Activity → 邮箱列表 Activity → 邮件详情 Activity，平台转场恢复；宽屏/Windows 仍保留当前壳内布局。
- **2026-08-29 M15 邮件详情重复跳转定位与修复**：Android 慢放截图显示列表点击后先出现 `MainActivity` 的内嵌详情加载/正文，再出现 `NativeDetailActivity`；logcat 对应一次 `NativeDetailActivity` OPEN 转场。`MailboxWorkspace` 现在在存在原生详情回调时保持根页列表，仅让 `MAILBOX_DETAIL` 原生页显示共享模型中的加载/正文状态；修复后慢放各帧均直接为“邮件详情”，没有中间内嵌详情页。
- **2026-08-29 M15 邮箱标题与刷新控件收口**：紧凑端将文件夹卡片和列表标题合并为单一当前文件夹 banner，显示文件夹名、邮件总数，并把“切换”放入 banner；顶栏只保留“写信”和同步/刷新状态，宽屏列表刷新也改用右上角文字胶囊，加载时显示“同步中”，移除破碎的自绘刷新箭头。Android 与 Windows 真实登录态视觉核对通过。
- **2026-08-29 M15 HTML 表格正文修复**：通过直接 Chrome DevTools MCP 核对两封真实邮件，确认一封是标准 HTML 表格，另一封包含多张 Word/Coremail 表格及嵌入式 `<style>`；旧实现把表格标签压成纯文本且把样式规则泄漏进正文。`SchoolRichText.kt` 现用 Ksoup DOM 遍历输出段落/表格块，跳过 `style`、`script` 等节点；`MailboxScreen.kt` 以带边框、可横向滚动的网格显示表格。新增两组解析回归测试；共享桌面测试、Windows 编译、Android x86_64 debug 构建均通过，Android 真实登录态已核对两封样本均无 CSS 泄漏且表格可见。未记录邮件正文、地址、Cookie 或带会话参数的 URL。
- **2026-08-29 M15 写信/回复首版**：按直接 Chrome DevTools MCP 取证的 Coremail 协议，新增 `compose.jsp?ctype=normal/reply` 草稿初始化和 `mbox:compose` `action=deliver` 发送适配；新增 `MAILBOX_COMPOSE` 原生二级路由，写信/回复共用收件人、抄送、主题、正文编辑页，回复自动带入收件人、主题和原文引用，发送前必须确认，取消/系统返回尽力清理临时草稿。紧凑端详情已移除正文内单独的“返回邮件列表”，唯一返回固定在左上角；Android 已核对详情返回、写信、回复预填和发送确认，Windows 已核对写信页，未实际发送邮件。
- **2026-08-29 M15 当前文件夹入口微调**：按用户反馈将“切换”从邮箱顶栏移入下方当前文件夹 banner；顶栏仅保留“写信”和同步/刷新状态，避免在窄屏中出现写信、切换、状态、刷新挤在一行。Android 与 Windows 源码版均已重新视觉核对，banner 内菜单可正常打开。
- **2026-08-29 M15 基本完成收口**：用户确认邮箱原生页面层级、邮件详情、写信/回复首版、HTML 表格正文、刷新/重试职责和右上角“刷新”胶囊均符合预期；M15 代码与首轮真实账号验收基本完成，真实发送/删除/移动/附件写操作及登录后的 iOS 页面仍明确保留为后续风险验收项。
- **2026-08-29 Windows 触摸兼容层平台门禁**：`DesktopTouchScroll` 现在只允许 Windows 桌面目标安装 `draggable + dispatchRawDelta`，macOS、Android、iOS 即使调用方传入 `enabled = true` 也回到平台原生滚动；新增跨平台门禁回归测试。小米平板真实设备仍待用户用新包复测。
- **2026-08-31 邮箱空详情/长转圈**：上一封 NativeDetailActivity 仍在栈里时会随共享状态重组，把 `DisposableEffect` 改绑到新代次，销毁时清掉下一封 loading；上一轮把空页改成 spinner 后变成长时间转圈。ADB：`6c5a737e` 栈为 Main + 两层 NativeDetail，00:50 截图后 00:57 仍在画 spinner。已去掉该 onDispose，详情页按 `pendingMessageId` 自行重试。邮箱模型测试与 debug APK 已重装到平板。
- **2026-09-16 作业/校历/iOS/会话恢复收口**：物理在线详情不再把页面中的任意日期误认成提交时间；提交与批改独立显示，未提交显示 `（未提交）`，真实提交按截止时间显示 `（按时提交）`/`（逾期提交）`，旧缓存中的伪提交日期也会丢弃；Android 模拟器最终包已核对。秋季校历周 4 映射到 `2026-10-05`，校历跳过假期，课表可单独导出后续周并保留教学周号。iOS 校历按钮改用现代 UIKit `openURL`，Windows 无法运行 iOS 模拟器，Mac/Xcode 验证待用户完成。会话恢复统一接入 Android、iOS、Windows 和 macOS/桌面 JVM：回前台只对当前已失效页自动重试一次；失败停留当前页；右上角刷新在内存凭据可用时最多重新认证两次，不自动跳登录页。Windows 与桌面 JVM 编译通过。
- **2026-09-16 首页截止与物理在线页面**：首页周日期格有作业/物理在线截止时使用红色容器，详情行的“截止/物理截止”标签使用错误色；物理在线页面现在与首页、课表、作业平级，紧凑端底栏入口直接替换一级页面，不再跳 `PHYVLAB_DETAIL`；页面只保留“课程作业”，首页总议程仍保留全部课程。CDP 已核对 Moodle 日历事件的 `mod_assign`/`due` 属性、作业链接和课程归属；Android 最终包视觉核对通过，两门课不再在物理在线页混排。
- **2026-09-16 课件刷新/下载**：修复课件页刷新只更新课程目录、却沿用缓存 `childrenLoaded=true` 导致新课件必须重新选课才出现的问题；刷新后会使各课程顶层课件失效并自动重新拉取，重新进入页面也保持最新列表。下载票返回 HTML 登录页时改判为会话失效并清空课件数据源会话；网络切换/临时无效响应对只读下载自动恢复或重试一次。共享 `desktopTest` 课件测试通过；Android debug 安装到已登录模拟器后实际看到新 `Chap17&18-ThermoGas-2020-9-5`，刷新、退出再进入仍显示，点击下载成功进入系统保存面板，未保存本地文件。
- **2026-09-16 物理在线总开关与详情路由**：移除“更多”中的物理在线页面入口，改为总开关；开启同时启用自动同步和“作业 → 物理在线 → 更多”底栏入口，关闭则不自动同步、不显示首页物理日程和底栏入口；开关固定放在“更多”的物理在线行，设置页的两处旧开关已移除。Android 模拟器已验证关闭/开启后底栏分别为 5/6 项，开关状态持久化；根页面自动同步不会在 `NativeDetailActivity` 重跑，作业详情保留 `selectedActivity`，已显示“未提交/未评分”等详情内容。
- **2026-09-16 课表模式文案**：课表切换按钮改为“色块概览-点击展开”和“课程详情-列表展示”，Android 模拟器已核对新文案。
- **2026-09-16 Windows 侧栏与安装器中文名**：宽屏侧栏现在复用物理在线总开关，开启时显示物理在线一级入口，关闭时隐藏且保留“更多”中的开关入口。Windows `packageName`、描述和菜单组改回固定中文，CI 以 UTF-8 代码页构建，不再注入英文环境变量；本地 MSI `交大自由行 KMP-1.7.6.msi` 已构建成功（113,700,673 字节，SHA-256 `666604A399DE27438E7C912FEC3A088383CDFB17FBC6926F6B659C0985BC8757`）。未触发远端打包。
- **2026-09-16 首页周切换**：首页作业/考试/物理在线日程卡新增左右按钮；Android/iOS 使用 `HorizontalPager` 手指横滑，Windows/macOS 复用课表的触摸板横向分页适配。目标教学周优先采用校历真实周一日期，因此国庆空档也不会把第 4 周算成 9 月 28 日；当前日期落在教学周之间时显示“非教学周”，展示对应自然周，绝对日期的截止事件仍会落入对应日期（正好 00:00 的截止沿用归前一天规则）。共享首页边界测试、Windows 编译和 Android 编译通过；Android x86_64 调试包已安装到 `emulator-5554`，无障碍树显示第 2 周及左右切换按钮；iOS 仅能在 Mac 验证。
- **2026-09-16 物理在线详情缓存优先**：打开作业详情时立即注入 `assignmentDetailsByActivity` 中的本地详情，网络请求继续后台刷新；有缓存时显示“正在更新提交与批改状态…”，无缓存时才显示空详情读取状态。网络失败时保留缓存并显示失败提示，成功后替换为最新详情。`PhyVlabScreenModelTest`、Windows 编译和 Android 编译通过；iOS 仍待 Mac 验证。
- **2026-09-16 首页周/日卡片高度隔离**：移动端首页周卡片的 `HorizontalPager` 按“教学周 + 当前选中日期”记录实际内容高度，切换周或切换星期都会重新测量；周 2 的长日程不会再把周 3 或同周其它日期撑成同样高度。目标页未测量时暂时自然测量，避免切换到远处周页时被旧高度裁剪。首页测试、Windows 编译和 Android 编译通过；最新 x86_64 调试包已覆盖安装到 `emulator-5554` 并启动成功；iOS 仍待 Mac 验证。
- **2026-09-16 首页高度缓存刷新失效**：发现作业/考试/物理在线数据从空状态或缓存状态刷新后，旧的周/日高度仍被复用，导致日期格显示“3项”但事件文字被裁掉。高度映射现在随三类数据或加载状态变化自动清空并重新测量；最新 x86_64 调试包 `1.7.6-debug-1` 已覆盖安装到 `emulator-5554`，等待页面稳定后无障碍树可见开始、截止和物理开始事件。测试、Windows 编译和 Android 打包通过；iOS 仍待 Mac 验证。
- **2026-09-16 首页周分页竞态**：同步刷新时 `currentWeek` 与分页器页码映射可能互相覆盖，造成跳回第 1 周、左右按钮像失效；现区分自动跟随与手动选周，分页器只在 settled page 且非程序滚动时回写，周映射变化和数据刷新时重新对齐。首页/作业回归测试、Windows 编译和 Android x86_64 构建通过；模拟器已验证第 2→3→2→4 周按钮、目标周高度和同步完成后仍停留第 4 周。
- **2026-09-16 普通作业提交协议对齐 Android 1.7.0**：`rpUpload.shtml` multipart 文件 MIME 固定为 `application/octet-stream`；`sendStuHomeWorks` 只依赖 HTTP 2xx，不再强制正文出现 `success`，并保留原作者的上传回执字段和 `fileList` 字段。共享作业测试、Android 构建通过；最新版已覆盖安装到已登录模拟器，目标 `.docx` 已选到最终提交按钮前，真实提交尚未再次点击确认。
- **2026-09-16 普通作业上传回执定位**：诊断包确认旧 KMP 写请求附带 AJAX/`sessionid`/Referer 头时，`rpUpload.shtml` 返回 HTTP 200 的 `STATUS/MSG` 错误 JSON，而不是四字段上传回执；网页手动上传后 App 重新同步已显示目标作业“提交状态 · 已提交”。现按 Android 1.7.0 去掉两个写请求的智慧平台查询头，只保留 Cookie；共享回归测试、Windows 编译、Android x86_64 构建通过，修复包已覆盖安装。因目标作业已提交，未重复执行第二次真实提交。
- **2026-08-30 小米平板 HyperOS 刷新率**：`25091RP04C` / HyperOS 3 上 KMP 前台曾被 PowerKeeper 锁到 60Hz，设置页显示「跟随应用内设置」。根因是 `SWITCHING_TYPE_NONE` 会忽略窗口 `preferredRefreshRate`，启动预热 WebView 或声明 120Hz 反而会让小米按应用内 60Hz 投票。现已清掉窗口刷新率声明、关闭 ARR 省电降帧、去掉 `MainActivity` WebView 预热。实机 `dumpsys display`：KMP 前台 `mActiveRenderFrameRate=120.00001`，与原版切换往返后仍是 120。
- **2026-08-30 平板宽屏课表横滑**：横屏走桌面课表布局。第一版自定义滑一下再播 `AnimatedContent`，不跟手、不能连滑、没有边缘拉伸。现 Android/iOS 宽屏表格改用和竖屏相同的 `HorizontalPager`（跟手、可连滑、边缘 Stretch）；Mac/Windows 仍是触摸板 + `AnimatedContent`。课表模型测试与 Android debug 构建通过，包已装到小米平板。
- **2026-08-30 Windows 课表连滑**：精密触摸板惯性尾流在 180ms 节流结束后会被当成第二次翻页。累加器改为翻页后丢掉同方向惯性，直到滚动事件停顿才接受下一次滑动；反向立即解锁。Mac 原生 AppKit 路径未改。
- **2026-08-30 `1.7.5-KMP` 版本统一**：显示版本 `1.7.5-KMP`，Android `versionCode` 16，iOS/macOS Build 16，Windows/macOS `packageVersion` 1.7.5。MSI 同版本覆盖表保留。待 Mac/iOS 验收后发 Release。
- **2026-08-29 `maildev` 提交前状态**：在 `main@c181dbf` 基础上创建 `maildev`，本轮不修改 `main`；补充发件箱按收件人显示、特殊文件夹分组和会话失效重置，新增导航/FID/解析回归测试。`:shared:desktopTest` 与 `:shared:iosSimulatorArm64Test` 均通过，冻结根 Android 文件无差异；最终一次 Xcode Simulator 构建由用户中断，未把中断写成通过，当前没有残留构建进程。

## 2. 当前痛点（≤8 条）

- **Windows 安装器品牌化受限**：jpackage 安装向导 UI（横幅、右上角图标、进度框）无参数可定制；安装完成后的 EXE/快捷方式/窗口/任务栏图标已是品牌 logo。若用户要完全品牌化安装向导，需引入 Inno Setup 等替代打包管线（未授权、未规划）。
- **构建环境**：compose 1.12.0-beta03 要求 compileSdk 37；Android SDK/`adb`/模拟器已恢复到 `C:\Users\zjg\Android\Sdk`，x86_64 debug 验证通过构建、安装、登录和邮箱页面视觉回归。Mac 侧 Xcode 27.0、iOS Simulator/iphoneos arm64 构建和 macOS arm64 分发构建均通过；实体机和登录后的 iOS 邮箱仍待设备/签名条件。
- **打包 JDK**：JBR 无 jlink/jpackage，需完整 JDK（本机 Microsoft JDK 21 `C:/Users/zjg/jdk21/jdk-21.0.8+9`，`WINDOWS_PACKAGE_JAVA_HOME` 可覆盖）。
- **Windows MSI**：旧版曾遇到 `light.exe 311`（中文 description 进 MSI 字符串表）；当前本地与 CI 均改为 UTF-8 中文安装器元数据，仍需在下一次远端普通打包中实际确认 GitHub runner 的 WiX 环境。KMP Android 共用上传签名已写入 GitHub Secrets（`BJTU_ANDROID_KEYSTORE_BASE64` 等四项），与本机 `~/.android/bjtu-kmp-upload.keystore` 同一把钥匙。
- **Windows 卸载清凭据待复测**：请装带卸载清理的 MSI 后再卸，确认 AppData 缓存和注册表凭据被删。
- **iOS 真机签名/连接**：generic iPhoneOS unsigned 构建通过，但当前 Bundle ID 没有匹配 provisioning profile；实体 iPhone 在 `devicectl` 中为 `unavailable`，合法签名、安装、Keychain 往返仍未取得证据。
- **验证码发布级准确率仍待扩样**；课件深层变化仍缺自然样本；官方 1.7.0 / KMP PyTorch 2.1 在 API 37.1 有 16 KB page-size 提示。
- **M13 现场解析差异与可选编辑页**：详情页状态标签是 `作业状态`（未交为“尚未批改”），不是 fixture 的 `提交状态`；filemanager 的 context/client/repo 在脚本 JSON 里，不在 DOM `data-*`。Mac 真实登录态课程/活动和主详情读取成功；已提交作业没有可用 filemanager，辅助 `action=editsubmission` 页返回 404 时按可选能力处理，不再覆盖主详情。真实上传仍未执行；Android/iOS 登录后 M13、REST token、Unity 外链仍待后续。arm64-only APK 不能装 x86_64 模拟器。
- **M15 邮箱功能边界**：只读文件夹/列表/详情、分页加载与 Apple Mail 方向的响应式 UI 已完成首轮；紧凑端邮箱主页、邮件详情和写信/回复统一走平台原生页面层级。快速开合邮件空页/长转圈已修并推送，待 iOS 用新 `main` 复测。Android 启动不再预热 WebView。本地正文缓存、转发、删除、移动、附件上传/下载和真实发送仍待单独切片。

## 3. 接下来 1～3 个阶段

1. **验收 `1.7.6-debug-1`**：当前 Android 模拟器验收已覆盖，Windows 本地 MSI 已构建；普通 CI artifacts 打包按用户要求暂停，待确认后再恢复；本轮不打 tag、不建 GitHub Release。
2. **M15 邮箱读写验收与扩展**：真实发送、删除、移动、附件下载仍待单独切片。
3. **M13 Apple 端补验**：实体 iPhone 取得合法 provisioning profile 后安装；真实上传仍需用户明确确认。

## 维护规则

- 每次开始目标模式任务时先读本文件；结束前必须再次更新。
- 已完成事项压缩为一行保留在“本阶段已做到”；里程碑真正完成时，把细节归档进 `history_full.md` 并从本文件删除。
- 痛点解除即删除或改写，不保留已经失效的阻塞描述。
- 近期计划只保留接下来 1～3 个可执行阶段；远期内容留在 `goal.md`。
- 事实、命令结果和验证边界要具体；不能把计划写成已完成。
- 分支、基线、最新 Release 或工作区状态发生变化时，更新文件顶部摘要。
- 不在本文件写入账号、密码、Cookie、令牌、真实验证码会话或其他敏感信息。
