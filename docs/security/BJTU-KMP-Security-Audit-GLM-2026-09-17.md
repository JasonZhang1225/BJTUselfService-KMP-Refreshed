# BJTUselfService-KMP-Refreshed 只读安全审计报告

- **审计日期**：2026-09-17
- **审计方式**：纯只读（源码阅读 + git 状态核查 + 公开 CVE 数据库检索），未修改、创建或删除任何项目文件
- **审计范围**：仓库内两套工程
  - `multiplatform/`（**活跃** KMP 工程：Android / iOS / macOS / Windows）
  - `app/`（**已冻结** 的旧 Android 工程，v1.7.0 参考，CLAUDE.md 声明不再修改发布）

---

## 〇、已知例外声明（按审计要求标注，不计入高危）

学校旧系统必需的 HTTP 明文端点，KMP 工程以**最小白名单**方式放行：

| 端点 | 用途 | 配置位置 |
|---|---|---|
| `http://123.121.147.7:88` | 智慧教学平台旧端点（含登录会话） | `multiplatform/androidApp/src/main/res/xml/network_security_config.xml`、`multiplatform/iosApp/iosApp/Info.plist`（ATS 例外）、`SmartPlatformEndpoint.kt:126-130` |
| `http://123.121.147.7:1936` | 教学日历 | 同上 |
| `http://yaya.csoci.com:2333` | 教室人数估计 | 同上（旧工程 `ApiConstant.java:4-5`） |

**实现评价（好）**：Android 侧 `base-config cleartextTrafficPermitted="false"` + 仅两域例外（注释注明用户 2026-08-04 授权）；iOS ATS 同步只例外这两个域；HTTPS→HTTP 降级跳转逐跳白名单跟随、降级绝不扩散到白名单外（`SmartPlatformEndpoint.kt:105-117, 176-208`）。

**固有风险提示**（例外本身不可避免）：这些明文链路上的智慧平台 `sessionid` Cookie 同网段攻击者可截获；学校端点升级 HTTPS 后应收窄白名单。

---

## 一、高危（2 项，均位于已冻结的旧工程 `app/`）

### H1. 旧工程完全禁用 TLS 证书校验，全部学校 HTTPS 请求可被中间人攻击

- **位置**：`app/src/main/java/team/bjtuss/bjtuselfservice/StudentAccountManager.java:31-46`（`X509TrustManager` 空实现）及 `86-103` 行（`sslSocketFactory` 注入 + `hostnameVerifier (hostname, session) -> true`）
- **影响**：该 client 承载 MIS 登录（**提交学号+密码**）、CAS 单点登录、成绩、课表、考试等全部学校 HTTPS 请求。证书校验被完全绕过后，处于网络中间位置的攻击者（如恶意 Wi-Fi）可解密并窃取统一身份认证密码。
- **状态说明**：此工程已冻结不再发布；但基于它的历史发布版（v1.7.0 及更早 APK）仍带此缺陷。
- **修复建议**（仅建议）：
  1. KMP 工程三平台引擎均为默认证书校验，无 trustAll 代码（已验证 `KtorEngine.android.kt` / `.ios.kt` / `.desktop.kt`），**新工程无需改动**；
  2. 在 README 中声明旧版 APK 的 MITM 风险，引导用户迁移 KMP 版；
  3. 若旧工程将来解冻，删除 trustAll/hostnameVerifier 代码。

### H2. 旧工程密码明文落盘 + 允许 Android 云备份

- **位置**：
  - `app/src/main/java/team/bjtuss/bjtuselfservice/repository/DataStoreRepository.kt:20-21, 44-49` —— `stringPreferencesKey("password")` 明文写入 Preferences DataStore；
  - `app/src/main/AndroidManifest.xml:18`（`android:allowBackup="true"`）、`:27`（`android:usesCleartextTraffic="true"`）；
  - `app/src/main/res/xml/backup_rules.xml` 与 `data_extraction_rules.xml` 均为**未配置的空模板**（全部数据默认可备份）。
- **影响**：明文 MIS 密码（与统一身份认证同源）可经 Google 云备份 / 设备迁移 / adb 备份提取；root 设备直接可读。
- **修复建议**（仅建议）：KMP 工程已改为系统级加密存储（见下文），**新工程无需改动**；旧工程如需再发布，最低限度应将 `allowBackup` 改为 `false`。

---

## 二、中危（5 项）

### M1. 仓库根目录 `MisSecret.md` 含真实学号 + 明文密码（本地明文凭据文件）

- **位置**：`MisSecret.md`（内容为真实学号与 MIS 密码）
- **关键验证结果**：该文件**已被 `.gitignore:19`（`/MisSecret.md`）忽略**，`git ls-files` 确认未被跟踪，`git log --all -- MisSecret.md` 确认**从未进入任何提交**——凭据未泄露到仓库/远端。
- **残余风险**：本地明文文件对本机所有进程可读；该密码长期用于 AI 代理自动登录流程，暴露面较大。
- **修复建议**：改用环境变量或系统凭据管理器向自动化流程供数；**建议轮换该 MIS 密码**。

### M2. Apple 端（iOS/macOS）Keychain 条目卸载残留，无重装清除机制

- **位置**：
  - `multiplatform/shared/src/iosMain/.../security/IosKeychainCredentialVault.kt`（保存时设置 `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`，查询/删除走同一 query）；
  - `multiplatform/shared/src/desktopMain/.../security/MacOsKeychainCredentialVault.kt`（**未设置** `kSecAttrAccessible`，见 L3）。
- **影响**：iOS/macOS 卸载应用后 Keychain 条目**默认保留在设备**；`ThisDeviceOnly` 只阻止备份迁移，不阻止卸载残留。iOS 重装同 bundle ID 应用可直接读回原凭据。当前无任何"检测重装→主动清除"逻辑。
- **修复建议**：首次启动时检查哨兵标记（如 NSUserDefaults/UserDefaults 中的标志位——它随卸载清除而 Keychain 不会）；标记缺失即判定为重装，执行 `SecItemDelete` 后再要求重新登录。

### M3. 桌面端（macOS/Windows）本地数据目录卸载残留

- **位置**：
  - `multiplatform/shared/src/desktopMain/.../cache/DesktopCacheStore.kt:10-14` —— 明文缓存数据库 `~/Library/Application Support/BJTUselfServiceKMP/bjtuselfservice_cache.db`（成绩、课表、考试、作业，含教师姓名、`user_id` 等）；
  - `multiplatform/windowsApp/src/windowsMain/.../WindowsAccountSecurityStore.kt:35-37` —— DPAPI 密文凭据存 `java.util.prefs`（Windows 落 `HKCU\Software\JavaSoft\Prefs\team\bjtuss\...` 注册表）。
- **影响**：删除应用本体（.app / exe 目录）不会清除上述目录/注册表键——学业数据与凭据密文残留。DPAPI 密文虽仅同 Windows 用户可解，仍是凭据残留。
- **修复建议**：设置页提供"清除本地数据"入口；README 提供卸载后手动清理路径；Windows 端可考虑迁移到 Credential Manager（`CredWrite/CredRead`）替代 prefs 注册表。

### M4. iOS 明文缓存数据库未排除 iCloud/iTunes 备份

- **位置**：`multiplatform/shared/src/iosMain/.../cache/IosCacheStore.kt:13-27`（Application Support 目录，未设置 `NSURLIsExcludedFromBackupKey`）
- **影响**：含个人学业数据的明文 SQLite 会随 iCloud/iTunes 备份上传云端。
- **修复建议**：对数据库文件（及 `-wal`/`-shm`）设置 `isExcludedFromBackup`；或在隐私说明中明示。

### M5. Android release 签名密钥使用硬编码弱口令默认值

- **位置**：`multiplatform/androidApp/build.gradle.kts:62-64` —— `storePassword`/`keyPassword` 默认 `"android"`、alias 默认 `"androiddebugkey"`（环境变量缺省时回退）。
- **影响**：签名密钥文件（`~/.android/bjtu-kmp-upload.keystore`）+ 公开可读的默认口令 = 任何拿到 keystore 的人可签出可覆盖安装的"更新"（配合应用内更新引导构成伪更新攻击链）。
- **修复建议**：CI/本地构建强制注入强口令、移除默认值回退（缺环境变量即 fail）；keystore 不入仓库（当前已满足）。

---

## 三、低危（5 项）

| # | 发现 | 位置 | 说明与建议 |
|---|---|---|---|
| L1 | `pytorch_android 2.1.0` 受 CVE-2024-31583（mobile interpreter UAF，CVSS 7.8，有公开 PoC）与 CVE-2024-31584（flatbuffer OOB read）影响 | `multiplatform/gradle/libs.versions.toml:13`、`app/build.gradle.kts:81` | 触发前提是**加载不可信 FlatBuffer 模型**；本项目模型为 APK 内置资产（`app/src/main/assets/model.pt`），攻击面受限，实际风险低。建议后续升级。Windows 端 DJL 用 pytorch-native 2.5.1（>2.2.0，不受影响） |
| L2 | 旧工程 `jsoup 1.14.3` 受 CVE-2022-36033（<1.15.3，cleaner XSS，需非默认 `preserveRelativeLinks`）影响 | `app/build.gradle.kts:82` | 项目仅用 jsoup 解析学校 HTML、不使用 cleaner 输出，实际风险低。建议升级至 ≥1.15.3（当前最新 1.18.x） |
| L3 | macOS Keychain 保存时未设置 `kSecAttrAccessible`（iOS 版设置了 `AfterFirstUnlockThisDeviceOnly`，两平台实现不一致） | `MacOsKeychainCredentialVault.kt:70-77` | 建议补齐与 iOS 一致的 accessibility 属性 |
| L4 | debug 变体导出 `SecuritySmokeActivity`（`exported="true"`） | `multiplatform/androidApp/src/debug/AndroidManifest.xml:4-6` | 仅存在于 debug 构建，不随 release 分发；建议保持 debug-only |
| L5 | 旧工程申请 `MANAGE_EXTERNAL_STORAGE` 等"所有文件访问"权限 | `app/src/main/AndroidManifest.xml:9-13` | 冻结工程；KMP 版 manifest 仅 `INTERNET` + `ACCESS_NETWORK_STATE`，**已收敛** |

---

## 四、五个审计专项结论

### 1. 网络层安全（KMP 工程）—— ✅ 良好

- 三平台 Ktor 引擎（Android=OkHttp / iOS=Darwin / Desktop=CIO）均为默认配置，**无任何自定义 TrustManager、hostnameVerifier 或证书绕过代码**（全库 grep 验证）；
- 明文传输按白名单最小放行（见"已知例外"），与 iOS ATS 例外域一一对应；
- HTTPS→HTTP 降级重定向逐跳白名单跟随（`SmartPlatformEndpoint.followSmartHandshakeRedirects`，最多 10 跳，明文跳仅限精确 apiOrigin，HTTPS 跳仅限 `cas.bjtu.edu.cn`/`mis.bjtu.edu.cn`）；
- 错误日志已脱敏：URL 去 query、不记录 Cookie/文件名/正文（`KtorSchoolHttpTransport.kt:150-160`）；
- 应用更新检查走 `https://api.github.com/.../releases`（HTTPS 官方 API）。

### 2. 缓存与本地存储（KMP 工程）—— ✅ 设计良好，两点例外

- **凭据四平台均为系统级加密**：Android = AndroidKeystore AES-256-GCM（`setRandomizedEncryptionRequired(true)`，密文+IV 存 MODE_PRIVATE SharedPreferences）；iOS/macOS = Keychain；Windows = DPAPI（附加熵）。未发现任何明文密码落盘路径；
- 缓存 SQLite 为明文，但仅学业数据、按 `account_scope`（学号）分区，且 `CacheStore.kt:43` 明文注释禁止写入"密码、Cookie、CSRF、CAPTCHA 或可复用会话"（PhyVlab 缓存经核验确不含 sessionid，`PhyVlabCacheCodec.kt:207-209` 硬编码 `canSubmit=false`）；
- Android 数据库位于应用私有目录（`databases/`，MODE_PRIVATE）；
- 例外：iOS 备份上云（M4）、桌面卸载残留（M3）、本地凭据文件（M1）。

### 3. 依赖版本漏洞

| 依赖 | 版本 | 结论 |
|---|---|---|
| io.ktor | 3.5.1 | ✅ 高于 CVE-2025-29904（3.1.1 修复）与 CVE-2026-68762（3.4.1 修复）的修复版本 |
| sqldelight | 2.3.2 | ✅ 无已知 CVE |
| kotlinx-serialization / coroutines | 1.8.1 / 1.10.2 | ✅ 无已知 CVE（注：网络流传的"coroutines 严重漏洞 CVE-2024-XXXXX"文章无 CVE 编号、无 JetBrains 官方通告，判定为不可信内容，未采信） |
| jna | 5.17.0 | ✅ 无已知 CVE |
| pytorch_android | 2.1.0 | ⚠️ CVE-2024-31583 / CVE-2024-31584（见 L1，实际风险低） |
| okhttp（旧工程） | 4.12.0 | ✅ 高于 CVE-2023-3635 修复版（4.11.0） |
| jsoup（旧工程） | 1.14.3 | ⚠️ CVE-2022-36033（见 L2，实际风险低） |
| gson（旧工程） | 2.11.0 | ✅ 高于 CVE-2022-25647 修复版（2.8.9） |
| JitPack 三方库 | compose-markdown 0.5.6 / ucrop 2.2.8 等 | ⚠️ 无已知 CVE 但无维护审计（供应链观察项） |

### 4. 卸载残留（逐平台）

| 平台 | 数据目录 | 凭据 | 结论 |
|---|---|---|---|
| Android（KMP） | 随卸载删除（私有目录） | AndroidKeystore 密钥随卸载销毁，SharedPreferences 密文同删；`allowBackup="false"` 无云备份 | ✅ 干净 |
| iOS | Application Support 随卸载删除 | **Keychain 条目残留**（M2）；数据库进 iCloud 备份（M4） | ⚠️ |
| macOS | `~/Library/Application Support/BJTUselfServiceKMP/` **残留**（M3） | Keychain 条目残留（M2） | ⚠️ |
| Windows | 注册表 Prefs 键**残留**（DPAPI 密文，M3） | 同左 | ⚠️ |
| 旧 Android 工程 | `allowBackup=true` + 空备份规则 → 明文密码进云备份（H2） | — | ⚠️ |

### 5. 登出 / 数据清理机制（KMP 工程）—— ✅ 完整且失败可观察

登出链路（`LoginScreen.kt:444-471`）：
1. `protocol.logout()` → 服务端登出 + `transport.clearSession()` 重建 HttpClient 与 Cookie jar（内存会话清除）；
2. `securityCoordinator.clear()` → 各平台 vault 清除（Android SharedPreferences `.clear()`、iOS/macOS `SecItemDelete`、Windows prefs remove）+ 记住密码标记复位；
3. `cacheStore.clearAccount(accountScope)` → **单事务删除 7 张账号表**（成绩/课表/考试/作业/成绩自选/课程性质/元数据，`CacheStore.kt:334-344`）；
4. 任一环节失败时向用户明示（storageMessage 提示"安全存储/缓存清除失败"）——失败不静默。

保留项评估：`app_setting` 表仅 UI 偏好（主题/自动同步开关，已核验全部 key），无敏感内容；AndroidKeystore 密钥别名保留但无对应密文，不构成残留。

旧工程对照：`AppStateManager.clearAllData()`（`app/src/main/.../statemanager/AppStateManager.kt:227-245`）清理 Room 四表 + 凭据，但凭据是**置空字符串而非删除 key**、清理异步执行且无失败反馈——不彻底，属冻结工程遗留。

---

## 五、优先修复顺序建议（仅建议，未改动任何代码）

1. **轮换 `MisSecret.md` 中的 MIS 密码**，并改用环境变量/系统凭据管理器（M1，成本最低收益最高）；
2. iOS/macOS 增加"重装检测→Keychain 清除"哨兵逻辑（M2）；
3. iOS 缓存数据库设置 `isExcludedFromBackup`（M4）；
4. Android 签名口令去掉默认回退、CI 强制注入（M5）；
5. 桌面端提供"清除本地数据"设置项与卸载指引（M3）；
6. 计划升级 `pytorch_android` 与（如解冻）`jsoup`（L1/L2）；
7. 旧工程问题（H1/H2）以文档声明 + 引导迁移 KMP 版处理，不动冻结代码。

---

*审计方法留痕：Read/Grep/Glob 全库扫描；`git check-ignore -v`、`git ls-files --error-unmatch`、`git log --all -- MisSecret.md` 验证凭据文件未入库；CVE 依据 NVD / GitHub Advisory / Snyk / JetBrains 官方安全页（检索时间 2026-09-17）。本报告为只读审计，未对任何项目文件做出修改。*

---

## 六、安全修复里程碑进度（2026-09-18）

> 以下为本仓库基于上节审计结论的修复记录。总体要求：现有用户**直接覆盖升级**（不卸载重装）即可获得修复；修复不改变 Android 签名，保证可覆盖安装。
> 暂缓项：H1、H2（冻结旧工程，仅文档声明并引导迁移 KMP 版）、M1（本地明文凭据文件，建议轮换密码）。L1–L5 未在本轮处理。

### 修复状态总览

| 编号 | 问题 | 状态 | 修复日期 |
|---|---|---|---|
| M2 | iOS/macOS Keychain 卸载残留 | ✅ 已修复（iOS 路径；macOS 由 M3 全量清理兜底） | 2026-09-18 |
| M3 | 桌面端本地数据卸载残留 | ✅ 已修复（应用内全量清理 + README 指引；Windows MSI 卸载清理为既有能力） | 2026-09-18 |
| M4 | iOS 明文缓存库进 iCloud/iTunes 备份 | ✅ 已修复 | 2026-09-18 |
| M5 | Android release 签名弱口令默认值 | ✅ 已修复并验证（commit 584d26c 去掉默认回退 + 本地/CI 同一密钥） | 2026-09-18 |
| H1/H2/M1 | 见上 | ⏸ 暂缓（按用户指示） | — |

### M2：iOS/macOS Keychain 卸载残留

- **实现方式（与审计建议的独立哨兵不同，原因见下）**：`AccountSecurityCoordinator.restore()` 中，当"记住密码"标记为否时主动调用 `vault.clear()`。
- **原理**：iOS 卸载会清除 NSUserDefaults（`remember_credentials` 标记）但保留 Keychain 条目。重装后标记复位为否、Keychain 仍有密文 → 启动时 `restore()` 直接清除残留。
- **为什么不用独立哨兵**：新哨兵在升级场景（旧版本从未写入哨兵）会被误判为重装，强制存量用户重新登录；复用已有标记则升级用户（标记为真）凭据不受影响，**覆盖升级无需重新登录**。
- **macOS 说明**：删除 `.app` 不清除 Preferences plist，无法可靠检测"卸载"，改由 M3 的应用内全量清理兜底。
- **修改文件**：`multiplatform/shared/src/commonMain/.../security/AccountSecurityStore.kt`
- **测试**：`AccountSecurityCoordinatorTest.unrememberedStatePurgesResidualVaultContent`（commonTest，桌面/iOS 目标通过）。

### M3：桌面端本地数据卸载残留

- **应用内全量清理**：设置页「本地数据与会话」卡片新增「清除全部本地数据」（仅 macOS/Windows 桌面显示），确认后清除：
  1. 系统安全存储中的登录信息（macOS Keychain / Windows DPAPI 注册表，含"记住密码"标记）；
  2. 所有账号的离线缓存与应用设置（`CacheStore.clearAll()`，单事务清 8 张表含 `app_setting`）。
- **Windows 卸载级清理（既有能力，2026-08-16 已实现）**：MSI 卸载的 deferred CustomAction `CleanupUserData` 删除 `%LOCALAPPDATA%\BJTUselfServiceKMP` 与 `HKCU\Software\JavaSoft\Prefs\team\bjtuss\bjtuselfservice`；升级安装（`UPGRADINGPRODUCTCODE`）不触发，不影响覆盖升级。
- **README 指引**：新增「卸载与本地数据清理」一节，列出 macOS 手动清理路径（Application Support、Keychain 条目、Preferences 偏好文件）。
- **修改文件**：`SettingsScreen.kt`、`SettingsScreenModel.kt`、`LoginScreen.kt`、`README.md`
- **测试**：`SettingsScreenModelTest` 新增全量清除成功/失败两用例。

### M4：iOS 缓存数据库排除备份

- **实现**：`createIosCacheStore()` 每次启动对已存在的 `bjtuselfservice_cache.db` 及 `-wal`/`-shm`（两个候选路径）设置 `NSURLIsExcludedFromBackupKey`，尽力而为、不阻断启动。
- **修改文件**：`multiplatform/shared/src/iosMain/.../cache/IosCacheStore.kt`
- **验证**：`:shared:compileKotlinIosSimulatorArm64`、`:shared:iosSimulatorArm64Test` 通过。实机/模拟器上备份行为未实测（无 iCloud 备份验证环境）。

### M5：Android 签名（覆盖升级链路验证）

- **代码侧（commit 584d26c，2026-09-18 前完成）**：`androidApp/build.gradle.kts` 移除弱口令默认回退，`storePassword`/`keyAlias`/`keyPassword` 必须来自环境变量或 `~/.gradle/gradle.properties`，缺失即构建失败；keystore 不入仓库。
- **本地验证（2026-09-18）**：
  - `~/.gradle/gradle.properties` 含三项凭据，store/key 口令均非弱默认值 `android`；
  - `~/.android/bjtu-kmp-upload.keystore` 存在，唯一条目别名 `androiddebugkey`（沿用原版密钥别名），证书 SHA-256 `5D:0D:AB:C3:…:C7:73` 与已发布 Release APK 同一证书；
  - `./gradlew :androidApp:signingReport` debug/release 均解析成功 → 本地打包签名不变，可覆盖安装升级。
- **CI 验证**：`.github/workflows/kmp-package.yml` 从 GitHub Secrets 还原同一 keystore（`BJTU_ANDROID_KEYSTORE_BASE64`）并注入三项口令 → 与本地同一签名身份。此前 `v1.7.6-KMP` Release（run 35185935353）已用同一密钥出包。
- **结论**：满足"本地与 CI 均能打出**不改变签名**、可直接覆盖升级安装的版本"的要求。

### 回归验证记录（2026-09-18）

- `:shared:desktopTest --tests AccountSecurityCoordinatorTest --tests SettingsScreenModelTest`：通过。
- `:shared:iosSimulatorArm64Test` 全量：通过。
- `:androidApp:compileDebugKotlin`：通过。
- `:shared:desktopTest` 全量存在 1 个**与本轮无关的既有失败**：`PackagingCiAsciiConfigTest`（打包 CI ASCII/UTF-8 元数据断言，stash 本轮改动后复现同一失败，属 1.7.6 打包收口遗留，未处理）。
- iOS/macOS 桌面端 UI（清除按钮、确认弹窗、反馈条）与 iOS Keychain 重装清除**未做实机验证**（需要卸载重装与真实 Keychain 操作），待后续真机验收。

### 后续待办

- [ ] 实机验证：iOS 卸载重装后 Keychain 残留自动清除；iCloud 备份不含缓存库。
- [ ] 实机验证：macOS/Windows 设置页「清除全部本地数据」端到端效果。
- [ ] Windows MSI 卸载清理（既有 `CleanupUserData`）随下一版安装包复测（memory.md 既有痛点）。
- [ ] 处理 `PackagingCiAsciiConfigTest` 既有失败（与本里程碑无关）。
- [ ] M1：轮换 MIS 密码并改用环境变量/凭据管理器（暂缓，按用户指示）。
