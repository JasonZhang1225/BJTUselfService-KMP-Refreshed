# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-09-26（`v1.7.9` 四端 CI 打包成功，发布页已挂上四个安装包）
> 当前分支：`main`。集成分支只保留 `main`。
> 工作区：发布提交包含全端 `1.7.9` / 构建号 20、日程加载中的日期动画，以及 `docs/releases/v1.7.9.md`；本轮液态玻璃优化收尾改动（见下）已提交。
> `history_full.md` 是只读历史归档；本文件只记录当前事实、未决事项和下一步，不重复历史细节。

## 当前目标

- 安全审计 P1/P2 代码修复已完成，并已按用户要求并入 `Liquid` 和 `main`（`0f773c0`）。Mac 正式安装的全量清理体验已验收通过；iPhone/Android 与 CAS/WebView 仍待验。
- P0 凭据轮换由用户处理，用户确认暂按已解决。
- 本轮按用户确认将最低 iOS 版本从 15.0 提高到 16.0。
- iOS 真机安装需要 Mac/Xcode 与可用签名；设备端验收步骤列于 PR #4。

## 本阶段已做到

- 2026-09-26 发布 `v1.7.9`：run `36226768700` 的 Android、Windows、macOS、iOS 和 GitHub Release 全部成功。发布页 `https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/releases/tag/v1.7.9` 含 APK、未签名 IPA、DMG、MSI。显示版本 `1.7.9`，Android/iOS/macOS 构建号 20。冻结 Android 流水线对该标签已跳过。`Liquid` 与 `codex/security-p1-p2` 的本地和远端分支已删除，只留 `main`。`docs/releases/v1.7.8-Liquid.md` 与 `v1.7.9.md` 已改成 GitHub 发布页上改过的正文。
- 2026-09-26 首页日程在「日程加载中」时，iOS/Android 点选日期也会播放内容过渡。横滑切周仍等周数确认后再启用。本地曾覆盖 `~/Downloads` 的 APK 与未签名 IPA；正式四端包以 CI 发布页为准。
- 2026-09-26 PR #4 已并入 `main`（合并提交 `0f773c0`）。上游 `origin`（HFDLYS）未动。
- 2026-09-21 完成只读安全复查（1.7.8-Liquid）：上轮安全修复全部保持、无回归、无高危；新增中危 M1（WebView Cookie 登出不清理）、M2（iOS Keychain 卸载残留）、M3（macOS 无卸载清理）、M4（桌面/Windows 明文缓存）+ 低危 L1-L10，报告在 `docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`。
- 2026-09-23 完成 M1-M4 与审查低危项的本轮修复：登出清理平台 WebView 数据并请求 CAS 注销；桌面缓存 AES-GCM 加密、旧库升级时重建；macOS 全量清理截断 WAL/VACUUM、删除凭据和偏好标记并退出；iOS 缺少安装内“记住密码”标记时清除残留 Keychain 项。另修正验证码放弃路径的清理竞态、phyvlab 外链改写、debug 安全测试页面导出权限、Wrapper 哈希、OkHttp 固定版本、验证码 ONNX 迁移、桌面日志默认开启和旧 Liquid 标签误触发旧发布流水线。
- 最新 CI `35825484777` 通过：Android `:shared:compileAndroidMain :androidApp:compileDebugKotlin` 编译成功；iOS 模拟器 503 项测试 0 失败，桌面 536 项通过；Xcode Debug/Release deployment target 均为 16.0。原生 Keychain 测试在 CI 因 OSStatus `-25291` 无可用服务而未实际执行，须真机验收。
- 2026-09-26 Mac 本机：桌面全量 537 项通过；隔离集成测试用真实 Keychain、独立 Preferences 与临时数据库验证清理后重开无残留；DMG 构建、镜像校验和镜像内应用严格签名校验通过。用户授权后覆盖 `/Applications/交大自由行 KMP.app`，升级启动恢复原登录态；在设置页全量清理后立即退出，八张缓存/设置表合计 0 行、正式登录 Keychain 条目与偏好标记均不存在，重启仍为登录页。缓存加密密钥保留，README 有手动删除说明；本地 DMG 未经公证。
- 用户反馈加密升级后成绩默认行序与原来不同，且切换正逆序看不出效果。确认加密缓存写入和重开均保留抓取行序；成绩页旧默认却是原序倒排，筛选面板也遮住切换结果。按用户明确要求改为默认教务网页当次行序、逆序整表翻转，并在点方向后关闭面板。本机桌面 539 项通过、DMG 重新打包及校验通过；覆盖安装后默认、逆序、切回正序均在 UI 验证，当前停在正序。
- 用户随后要求按钮“正序”在前、“逆序”在后，已改并覆盖本机安装。安装复测时抓到课表触控板原生桥接从 AWT 界面线程同步进入 AppKit 导致辅助功能读取超时，改为后台创建并在取消时释放；本机重新构建、539 项桌面测试通过，成绩筛选按钮位置与原序首项实测正确。
- 用户确认最低 iOS 版本提到 16.0；L9 DPAPI 编译期附加熵作为可选残余项保留（同用户偏好存储的随机熵无法提升其威胁边界）。
- Liquid 里程碑与 1.7.8-Liquid 四端发布已归档至 `history_full.md`「M17 Liquid」节。
- GitHub Release `v1.7.8-Liquid` 已正式发布，自动流水线 run `35579588977` 全部成功，发布页为 `https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/releases/tag/v1.7.8-Liquid`；发布正文已补齐，包含四个 CI 产物。
- 作业筛选未提交改动：截止区新增「隐藏已提交」（`subStatus=已提交` 或 `idSnId!=null` 即过滤，可与隐藏过期叠加）；芯片外观改动已按用户要求回撤，hover 灰圈保持原样；筛选状态按账号存 metadata（课程多选 JSON、隐藏开关、排序，退出清账号数据时一起清，恢复时裁掉不存在的课程），每次打开面板都是上次的样子；移动端紧凑列表不再渲染选中高亮（详情是原生二级页，无可靠 pop 回调；选中色只留宽屏双栏），修返回后旧选中颜色残留。桌面 17 项作业测试全过，AndroidMain 编译通过，已提交。
- 首页议程按提交状态变绿（未提交）：当天截止项全部已提交则整天格子标绿，有一项没做仍标红，过期与否不影响；每条已提交事项的“截止”二字单独标绿。物理在线事件新增 `submitted` 信号（课程页完成标记或详情提交信息，关联不上按未做；`mergeEvents` 去重取或、缓存编解码兼容旧包），首页含物理在线一起判定。物理在线/首页/作业相关桌面测试全过（`PhyVlabScreenModelTest` 5、`HomeAgendaTest` 10），AndroidMain 编译通过；浅色/深色、真机目视待确认，已提交。
- 首页启动跳第 1 周（未提交）：刚进首页校历未确认时回退第 1 周槽（日期恰是本周一）；校历确认后真实第 3 周同一天，旧跟随逻辑只比开始日期→认为无变化→错的第 1 周钉死，需手动切换。已改成整槽比较（含周号），`nextAutoFollowedWeekSlot` 可单测（5 项），手动选周仍永不覆盖；课程侧未动。桌面相关测试全过，AndroidMain 编译通过，已提交。
- 首页顶栏玻璃丢失（未提交）：首页自制上报读 `layoutInfo.visibleItemsInfo`，数学上和通用上报等价，但在当前 Compose 下不再可靠触发重算，上报卡 0；作业页用通用上报一直正常。已把首页改回 `ReportTopScrollListState`，与各页统一。桌面测试+Android 编译通过；iOS 真机滚动态待用户确认，已提交。
- 2026-09-26 23:05 已按用户要求用首页周跟随修复版工作区重打 Mac DMG 并覆盖安装：`~/Downloads/BJTUselfServiceKMP-1.7.9.dmg`（SHA-256 `85e499e4…91`），`hdiutil verify` 与构建/安装两处 `codesign --verify --deep --strict` 均通过。旧安装版已正常退出并删除，新包从 DMG 挂载复制到 `/Applications/交大自由行 KMP.app`，挂载已卸载；DMG 未公证，新版尚未启动，打开后验证启动不再闪第 1 周。
- 2026-09-27 00:21 已按用户要求用当前工作区打出 iOS unsigned IPA：`~/Downloads/BJTUSelfService-KMP-1.7.9-iOS-unsigned.ipa`（41873187 字节，SHA-256 `d8e03e98…9c`），版本 1.7.9 / Build 20，Bundle ID `team.bjtuss.bjtuselfservice.kmp.ios`，arm64 无签名无 PlugIns。Xcode 27.0 `generic/platform=iOS` Release `BUILD SUCCEED`（Gradle 阶段用 Android Studio JBR 跑以避 JDK 25 的 KGP 崩溃）；打包残留已清理，需自行签名侧载，未上真机。
- 2026-09-27 01:09 筛选×增删交互测试补完（删课裁选回全量、增课直接可见可选、重启只恢复现存课程；桌面作业 17 项全过）后重打双端：Mac DMG `~/Downloads/BJTUselfServiceKMP-1.7.9.dmg`（SHA-256 `d4fb7432…cd`，`hdiutil verify`+签名通过，已覆盖到 `/Applications`）；iOS 后台增量构建成功，同名 IPA 已覆盖 `~/Downloads`（SHA-256 `06087532…ab`，1.7.9/Build 20/arm64 无签名）。DMG/IPA 均未公证/未签名，需自行处理；新版 Mac 尚未启动。
- 2026-09-27 按用户要求全端版本号改为 1.8.0（构建号 20→21）：`AppUpdateChecker.CURRENT_VERSION`、Android versionName/Code、桌面 `desktopPackageVersion`/`packageBuildVersion`、Windows `packageVersion`、iOS Info.plist 短版/Build；版本断言测试同步。已用该版本打出 `~/Downloads/BJTUSelfService-KMP-1.8.0-iOS-unsigned.ipa`（SHA-256 `61d38634…dc`，1.8.0/Build 21/arm64 无签名无 PlugIns，Xcode 27.0 一次过）。Mac/Windows 未重打；已提交，未 tag。
- 2026-09-27 含首页玻璃上报统一修复重打双端：Mac `~/Downloads/BJTUselfServiceKMP-1.8.0.dmg`（SHA-256 `89d31341…64`，`hdiutil verify`+签名通过，未覆盖安装）；iOS `~/Downloads/BJTUSelfService-KMP-1.8.0-iOS-unsigned.ipa` 已覆盖（SHA-256 `836b4f8a…71`，1.8.0/Build 21/arm64 无签名）。均未公证/未签名；已提交，未 tag。

## 当前痛点（≤8 条）

- 安全审计报告剩余设备验收：iPhone Keychain 卸载重装清理、带登录态的 CAS/WebView 登出、Android 真机验证码效果。CI 中 unsigned iOS 测试宿主的 Keychain 未覆盖。
- 日程加载中的日期动画尚未在真机复测。正式包未公证；iOS IPA 仍需自行签名。
- iOS 标题栏真玻璃全页铺开（本轮提交）：`fcfdb09` 已推 `mine/Liquid`，tag `v1.7.8-Liquid` 改绑过去，kmp-package 四件全绿换新。玻璃跟手：桥接改连续 `scrollProgress`，各页上报真实偏移（`LocalReportTopScroll`，首项之后视为全盖）；首页首项是整张高卡，通用上报在其进栏时仍读 0，改为读首项 `offset` 负值（静止 0/上滑即有值/过滚仍 0，其他页不动；之前 `beforeContentPadding - offset` 静止也糊的方案已撤回）。课表/课件/教室详情/占用详情用 `staticTopBar` 保持静态栏；邮箱列表去重刷新图标。短内容留栏下、横幅收进列表首项；其余平台零影响。真机滚动态用户已确认完美。
- Compose Material3 alpha 依赖 compileSdk 37 豁免开关（`android.experimental.disableCompileSdkChecks`），正式版后应移除。
- `SecurityReleaseConfigTest.gradleDistributionAndWrapperArePinnedAndCiValidatesThem` 在 HEAD 已失败（要 `release.yml` 含 Liquid 守卫，文件中没有；本轮未动该文件）：全量 553 项中唯一失败项，与本轮改动无关，修它等于改 CI 发布守卫，需单独决策。

## 接下来 1～3 个阶段

1. 在 iPhone/Android 按原 PR #4 验收清单完成真机测试，并核实带登录态的 CAS/WebView 登出；Mac 已验收。
2. 根据设备结果修复必要问题。
3. 单独处理 Material3 alpha/compileSdk 豁免与其他未决的产品支持问题。

## 相关文件

- 安全复查报告：`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-21.md`（本轮）、`docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-17.md`（上轮）。
- Liquid 计划与发布：`docs/migration/m17-apple-liquid-glass-shell-plan.md`、`docs/releases/v1.7.8-Liquid.md`、`docs/releases/v1.7.9.md`。

## 维护规则

- 每轮开始先确认 `git branch --show-current`、工作区状态和本文件；事实以当前源码、命令输出和当前截图为准。
- 已完成历史只进 `history_full.md`，实时文件只保留当前接续需要的结论；过期的“已落地/已实机/当前方案”必须删除或改成历史语气。
- 事实与计划分开写，明确验证范围；不记录账号、密码、Cookie、令牌、验证码或其他敏感会话内容。
