# BJTUselfService-KMP 只读安全复查报告（1.8.0）

- **审计日期**：2026-09-29
- **审计对象版本**：`1.8.0`（Build 21；`iosApp/iosApp/Info.plist` `CFBundleShortVersionString`；HEAD `413a47a`）
- **审计性质**：对照 [2026-09-17](BJTU-KMP-Security-Audit-GLM-2026-09-17.md) 与 [2026-09-21](BJTU-KMP-Security-Audit-GLM-2026-09-21.md)（含 09-23 P1/P2 实施记录）的**重新扫描**。上一轮中危项声称已修，本轮核对其是否回退，并覆盖 09-21 之后合入的 Liquid / 1.7.9 / 1.8.0 改动。
- **审计方式**：纯只读（源码 + git 跟踪核查 + OSV.dev 按精确 Maven 坐标查询）。本轮**未**修改业务代码；本文件是审计产出。
- **审计范围**：`multiplatform/`（活跃 KMP：Android / iOS / macOS / Windows）。冻结根 `app/` 只确认历史高危仍在、且不被当前发布流水线打出。

---

## 总体结论

**上一轮 P1/P2 安全修复全部保持，未发现 KMP 工程新增高危项，未发现证书校验回退。**

上一轮中危（WebView 登出清理、桌面缓存加密、macOS 全量清理、iOS 重装清 Keychain、CAS 注销）在源码层均仍在。剩余问题分成三类：

1. **上轮已修、本轮核过的保持项**（见第五节对照表）。
2. **上轮已标明、仍待设备验收的残余**（iPhone Keychain / 带登录态 CAS+WebView / Android 真机）。
3. **本轮新增或重新定性的中低危**（源码审计时点）：Android WebView 的进程级 Cookie 罐不会在冷启动/重新登录时重置；安全 CI 的 push 触发仍绑在已删除的 `Liquid` 分支；发布守卫测试与 `release.yml` 条件漂移。这三项已在 `audit/1.8.0-2026-09-29` 修复，见文末实施记录。

KMP 工程仍无 TrustManager / hostnameVerifier / trustAll。明文 HTTP 仍锁在用户授权的两个学校 origin + 教室人数第三方 origin。凭据四平台均为系统级加密，无明文密码落盘回退。

---

## 〇、已知例外声明（学校系统必需，不计入高危）

| 端点 | 用途 | 配置位置 | 残余固有风险 |
|---|---|---|---|
| `http://123.121.147.7:88`（路径锁 `/ve/`、`/oauth/`） | 智慧教学平台 API / 第三方登录握手 | `SmartPlatformEndpoint.kt:126-130`、`network_security_config.xml:6-10`、`Info.plist:37-41` | 中低：明文链路上 sessionid / OAuth ticket 可被同网段嗅探 |
| `http://123.121.147.7:1936`（路径锁 `/kk/rp/`） | 教学日历 PDF | 同上 | 同上 |
| `http://yaya.csoci.com:2333/api/classnum/` | 空教室人数估计（第三方；https 不可达） | `ClassroomRemoteDataSource.kt:24-26` | 低：无学校凭据；独立 transport，不携带登录 Cookie（`AuthenticatedSessionFactory.kt:191-194`） |

防护实现核对（保持完好）：

- `SmartPlatformEndpoint.kt:134-152` origin / 主机 / 端口 / 路径前缀逐字段白名单，拦截 `%2f/%2e/../%25/%00` 逃逸；`:185-208` 降级重定向最多 10 跳，明文跳仅限精确 apiOrigin，HTTPS 跳仅限 `cas.bjtu.edu.cn` / `mis.bjtu.edu.cn`。
- `ClassroomRemoteDataSource.kt:75-76` 最终 URL 必须留在精确 origin，出界即失败。
- Android `base-config cleartextTrafficPermitted="false"`，只例外上述两域；iOS ATS 同步这两域，`verify-apple-release-metadata.sh:52-53` 禁止 `NSAllowsArbitraryLoads`。
- 未发现统计、埋点、图片 CDN 等其他明文传输。学校主站均为 https。应用内网页容器强制 `https://`（`WebPageModels.kt:42`）。

---

## 一、高危

无发现（KMP 工程）。

冻结根 `app/` 的 H1（trustAll + hostnameVerifier）与 H2（明文凭据 DataStore + `allowBackup=true`）**仍在** `StudentAccountManager.java:31-46, 100-101` 与旧 Manifest。该工程按仓库纪律冻结；当前 `release.yml:13` 只在 `v1.7.0` 或手动 dispatch 时构建旧 APK，KMP 标签走 `kmp-package.yml`。不作为本轮 KMP 新债。

---

## 二、中危

### M1. Android WebView 使用进程级持久 Cookie 罐，仅在登出时清空

- **位置**：
  - `shared/src/androidMain/.../webview/SchoolWebView.android.kt:31-38` — 每次创建 WebView 都把会话 Cookie `setCookie` 进全局 `CookieManager`，**注入前不清罐**；
  - 同文件 `:67-75` — `clearSchoolWebViewData()` 才 `removeAllCookies` + `flush`，该函数只挂在登出/放弃验证码路径（`LoginScreen.kt:491`）。
- **与上轮 M1 的关系**：上轮是「登出不清理」。登出清理**仍在**，不是回归。本项是同一机制的另一面：Android Cookie 罐跨 WebView 生命周期、跨进程冷启动仍然存在；iOS 已改 `WKWebsiteDataStore.nonPersistentDataStore()`（`SchoolWebView.ios.kt:74`），桌面端不内嵌 WebView（`SchoolWebView.desktop.kt:66-67`）。
- **影响**：
  1. 登录态内关闭邮箱/网页后，学校 Cookie 仍留在应用私有 Cookie 数据库，直到用户走登出。
  2. 若进程被杀死而**未走登出**（崩溃、系统回收、用户划掉），下次冷启动若换账号登录，旧账号残留 Cookie 名不会被新的 `setCookie` 全部覆盖，可能把上一账号的部分域 Cookie 带进新 WebView。
- **修复建议**：WebView `factory` 在 `setCookie` 之前先 `removeAllCookies`（或至少按学校域删除）；应用启动 / 登录成功时也清一次 Cookie 罐，不要只依赖登出。

### M2. 专用安全编译工作流的 push 触发仍绑在已删除的 `Liquid` 分支

- **位置**：`.github/workflows/kmp-security-check.yml:5`（`on.push.branches: [Liquid]`）
- **影响**：`Liquid` 本地与远端分支已删除，日常集成在 `main`。向 `main` 的直接 push **不会**跑该工作流的 Android 编译门禁与 macOS 安全测试。`pull_request` 路径过滤仍有效，所以走 PR 的改动还会跑；直接推 `mine/main` 则不会。
- **修复建议**：把 push 分支改为 `main`（或 `main` + 仍存在的开发分支），保留 path filter。

### M3. 发布守卫测试与真实 `release.yml` 条件漂移

- **位置**：
  - 测试：`shared/src/desktopTest/.../packaging/SecurityReleaseConfigTest.kt:55-56` 断言 `release.yml` 含 `!contains(github.ref_name, 'Liquid')`；
  - 实现：`.github/workflows/release.yml:13` 实际是 `github.ref_name == 'v1.7.0'`（只打冻结 Android 的那一个标签）。
- **影响**：安全意图没有回退——旧工程更不容易被新标签打出。但该测试在当前 HEAD 会失败（`memory.md` 已记为全量桌面测试里唯一失败项）。失败的供应链回归测试等于没守住。
- **修复建议**：把断言改成当前的 `v1.7.0` 精确条件（并继续要求 wrapper-validation）。不要为了让测试变绿而把旧的 Liquid 字符串填回去。

### M4. 智慧平台握手用会自动跟随重定向的 client，白名单看不到中间跳

- **性质**：上轮漏审，不是 1.8.0 新引入的代码。
- **位置**：
  - `KtorSchoolHttpTransport.kt:38-39, 178-183` 常规 `client` 默认 `followRedirects=true`，`rawClient` 才关掉；
  - `followSmartHandshakeRedirects`（`SmartPlatformEndpoint.kt:185-205`）本身逐跳校验，但作业 / 课件 / 课表调用点传入 `execute()` / `executeSoft()`；
  - 注释写「Ktor 会在 HTTPS→HTTP 降级处停住」，对**同协议** 302 和 HTTP→HTTPS 并不成立。
- **影响**：MIS module 28 若先 302 到同协议的非白名单主机，再跳回学校域，helper 只看到最终 URL。`maxHops` 与 `acceptsHandshakeUrl` 约束不了已经发出的中间请求。学校当前链路由降级停住，所以这是条件风险，不是已观测到的外泄。
- **修复建议**：握手每跳使用 `executeWithoutRedirects`；module 28 的第一跳同样关掉跟随。教室人数评估同理：明文 GET 不得自动跟随，3xx 直接失败。用假 transport 覆盖：出界 Location 零后续请求、第 11 跳停住、同协议 HTTPS 一跳一跳可见、教室 302 不发第二跳。

同一自动跟随还影响 `ClassroomRemoteDataSource`：原先 `transport.execute` 后才看 `finalUrl`。无学校凭据，但明文 yaya 可被中间人 302 到任意主机，白名单承诺被破坏。已与握手一并改走 `executeWithoutRedirects`，3xx 即失败。

### M5. 智慧平台业务请求带着自定义 sessionid 头走自动跟随

- **性质**：上轮漏审。握手已改 no-follow 之后，作业/课件 `smartRequest` 仍走 `transport.execute()`。
- **位置**：`HomeworkRemoteDataSource.kt` 的 `smartRequest`、`CoursewareRemoteDataSource.kt` 的 `smartRequest`；头字段名是 `sessionid`，不是 `Authorization`。
- **机制**：Ktor `HttpRedirect` 跨 authority 只剥 `Authorization`。平台 302 到 HTTPS 外域时，自定义头会跟过去。`finalUrl` 白名单在跟随之后才检查，撤不回已经发出的第二跳。
- **影响**：明文智慧平台或被劫持的 302 可能把教学会话标识送到攻击者主机。学校当前链是否真的发跨域 302 未在网上观测。
- **修复**：`smartRequest` 改 `executeWithoutRedirects`，3xx 视为会话失效，不发第二跳。测试：假 302 到外域时请求列表不含该主机。

---

## 三、低危

| # | 位置 | 内容 | 修复建议 |
|---|---|---|---|
| L1 | `AndroidCacheStore.kt`、`AppleCacheStore.kt` | 移动端学业缓存 SQLite 仍为明文。Android 在应用私有目录且 `allowBackup="false"`；iOS/macOS 原生库每次启动对 db/wal/shm 设 `NSURLIsExcludedFromBackupKey`（`AppleCacheStore.kt:56-60`）。桌面端已 AES-GCM，移动端是有意识的平台差异。 | 保持披露；若要对齐桌面，再单独立项做 SQLCipher/字段加密，不要顺手改。 |
| L2 | `WindowsAccountSecurityStore.kt:77-79` | DPAPI 附加熵仍为编译期常量。安全边界仍是 Windows 用户派生密钥。上轮 L9，用户已接受不表面化替换。 | 维持；不要把随机熵和密文放进同一 prefs 节点。 |
| L3 | `gradle.properties:12` | `android.experimental.disableCompileSdkChecks=true`，因为 Material3 alpha 元数据要 compileSdk 37。 | 正式依赖稳定后去掉豁免。 |
| L4 | `windowsApp` + DJL PyTorch native 2.5.1 | Windows 验证码仍走本地 PyTorch；模型是仓库内资源。OSV 对 `pytorch-native-cpu:2.5.1` / `pytorch-engine:0.33.0` 无命中。Android 已迁 ONNX 1.30.0。 | 与 Android 对齐到 ONNX 可作为后续切片；非本轮门禁。 |
| L5 | 仓库根 `MisSecret.md` | 仍被 `.gitignore:19` 忽略，`git ls-files` 未跟踪，`git log --all -- MisSecret.md` 为空。本机明文文件对同机进程可读。 | 维持上轮建议：自动化改走系统凭据；密码轮换由用户处理。 |
| L6 | `SchoolWebView.android.kt:29` | `javaScriptEnabled=true`，无 `addJavascriptInterface`。学校页 XSS 不能调 Kotlin，但能在 WebView 里跑脚本。 | 保持不下桥；不要为学校页关 JS（登录页依赖它）。 |
| L7 | 卸载残留（产品边界） | DMG 拖走 `.app` 不会清 Keychain / Application Support；Windows 删目录不清 DPAPI prefs。应用内「清除全部本地数据」会 `clearAll`+VACUUM+`purge` 并退出（`CacheStore.kt:398-423`、`AccountSecurityStore.kt:101-108`、`AuthenticatedSessionFactory.kt:215-221`）。README 仍需手动路径。 | 文案已说清（`SettingsScreen.kt:95-98`）。不作为代码缺陷重开。 |
| L8 | iOS Keychain 重装 | 代码：`remember_credentials` 缺失即 `vault.clear()`（`AccountSecurityStore.kt:45-55`）。CI 原生 Keychain 仍可能 `errSecNotAvailable`。 | 真机卸载重装验收，不改逻辑。 |
| L9 | `desktopApp/.../DesktopSystemCalendarGateway.kt:103` | 日历桥接手写 `jsonEscape`，与 `util/JsonEscape.kt` 重复。不是密钥问题。 | 去重，放到代码味道报告。 |
| L10 | 冻结 `app/` 教室安排 WebView | `ApiConstant.CLASSROOM_VIEW_URL` 路径含固定 hex 段，`DetectionScreen` POST 带静态 token。均打进历史 APK。KMP **未复制**该弹层（`Classroom.kt` 标明未实现）。若第三方把 token 当授权，可从旧 APK 提取。 | 冻结工程不改。KMP 继续不要接这个 URL。 |
| L12 | Android/iOS WebView 导航 | `isSchoolHost` 曾只看 host。现已强制 https。 | 策略改为必须 https。 |
| L17 | iOS `SchoolWebView.ios.kt` Cookie 属性 | 注入时只设 name/value/domain/path，漏 `Secure`。共享模型默认 `secure=true`，Android 会写 `; Secure`。iOS ATS 挡明文学校域，实际外泄未上设备确认。 | 创建 `NSHTTPCookie` 时传 `NSHTTPCookieSecure`。 |
| L13 | `AppleCacheStore.kt:56-71` | 启动时只给当时已存在的 db/wal/shm 设排除备份。WAL 若在本次启动后才出现，到下次冷启动前可能进备份。代码注释已承认尽力而为。 | 对缓存目录做排除，或打开库后再扫一遍旁路文件。 |
| L14 | `AuthenticatedSessionFactory.kt:215-221` | 全量清理要求缓存与凭据都成功才 `onPurgeLogout`。一半失败时界面报失败，内存会话仍在，后台同步可能写回。 | 一开始就停同步并退出会话，清理结果单独汇报。 |
| L15 | Android debug `SecuritySmokeActivity.kt:23-29` | 用**生产** Keystore alias 先 `clear()` 再写测试凭据。仅 debug + signature 权限。在有用户数据的 debug 安装上跑会清掉真凭据。iOS smoke 已用隔离服务名。 | 改隔离 alias / prefs，对齐 iOS。 |
| L16 | Windows `DataBlob.free()` | `CryptUnprotectData` 的输出 `pbData` 由 Crypt32 分配；`free()` 只清 JNA `Memory`，未 `LocalFree` 该缓冲。同用户进程内存转储可能看到明文。附加熵常量仍按 L2 保留。 | `finally` 里清零并 `LocalFree` DPAPI 输出。 |
| L18 | `SchoolHttpTransport.executeWithoutRedirects` 默认回落到 `execute()` | 生产 Ktor 已覆盖。新 transport 若忘了 override，握手和 `smartRequest` 的 no-follow 假设会失效。测试假客户端现在多显式 override。 | 可改成接口无默认实现，或加测试断言生产 transport 的 rawClient `followRedirects=false`。本轮不改接口，避免牵动所有假客户端。 |

未再列为缺陷、但需知情：

- 桌面日志默认关（`AppLog.desktop.kt:6-7`，需 `-Dbjtu.debug.logging=true`）；Android 由 `BuildConfig.DEBUG` 打开（`MainActivity.kt:22`）；iOS 由 `Platform.isDebugBinary`（`AppLog.ios.kt:9`）。
- Live 课表探针默认跳过，需 `BJTU_LIVE_PROBE=1`。
- `SecuritySmokeActivity` 仍 `exported=true`，但要求 signature 权限；iOS smoke 仅 `#if DEBUG` 且参数 `--security-smoke`，用隔离 Keychain 服务名。
- 签名口令无弱默认值，缺环境变量即 fail（`androidApp/build.gradle.kts:60-69`）。
- 下载文件名经 `safeExportFileName` / `safeExportPathSegment` 去掉路径语义（`CoursewareDirectoryGateway.kt:8-40`）。
- 普通登出 `clearAccount()` 只 DELETE、不做 VACUUM：这是「退出登录」与「清除全部本地数据」的产品分界，后者才 checkpoint/VACUUM。不单独立项。
- 桌面全量清理故意保留缓存加密密钥（本身不含账号）；README 已写拖拽卸载后的手动删除。与 09-26 验收一致。

---

## 四、依赖与供应链

版本来源：`multiplatform/gradle/libs.versions.toml`（唯一目录）。

| 组件 | 版本 |
|---|---|
| Gradle | 9.3.1（`distributionSha256Sum=b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`） |
| Kotlin | 2.4.10 |
| Compose Multiplatform | 1.12.0-beta03（Material3 1.12.0-alpha03） |
| AGP | 9.1.1 |
| Ktor | 3.5.1 |
| OkHttp | 5.3.2（显式锁定，非仅传递） |
| SQLDelight | 2.3.2 |
| ONNX Runtime Android | 1.30.0 |
| DJL / PyTorch native（仅 Windows） | 0.33.0 / 2.5.1 |
| KSoup | 0.2.6 |
| JNA | 5.17.0 |
| kotlinx-coroutines / serialization / datetime | 1.10.2 / 1.8.1 / 0.8.0 |

2026-09-29 OSV.dev 对上表 Maven 坐标 + 精确版本查询，**均 0 条漏洞**。这不等于「永远没有」，只表示审计当日公开数据库无匹配。CVE-2023-3635 影响 OkHttp 4.9.2–4.11.0 的 `Cookie.parse`，与 5.3.2 无关。Android 已无 `pytorch_android 2.1.0`（上轮 L8 / CVE-2024-31583 不再适用于 KMP Android）。

仓库策略：

- `settings.gradle.kts`：`google()` / `mavenCentral()` / `gradlePluginPortal()`，`FAIL_ON_PROJECT_REPOS`，无 `mavenLocal`。
- iOS 无 Podfile / SwiftPM 三方，仅自产框架 + 系统 sqlite3 / WebKit / Security。
- Wrapper JAR 校验出现在 `kmp-package.yml`、`release.yml`、`debug.yml`、`kmp-security-check.yml`。
- 验证码 ONNX SHA-256 由 `SecurityReleaseConfigTest.androidCaptchaUsesPinnedOnnxArtifactAndRecordedChecksum` 对照 `tools/captcha/validation_manifest.json`。
- `MisSecret.md`、`.artifacts/` 不进 git。跟踪的模型文件是应用内置验证码资产（`BJTUCaptcha.onnx` / `.mlpackage` / Windows `.pt`），不是账号数据。

---

## 五、与 2026-09-21 / P1–P2 实施记录的对照

| 上轮项 | 本轮状态 | 证据 |
|---|---|---|
| 证书校验 / trustAll（KMP） | **保持** | 全 `multiplatform` 无 TrustManager / hostnameVerifier；三引擎 `KtorEngine.*.kt` 均为默认工厂 |
| 明文白名单 | **保持** | 见第〇节 |
| M1 WebView 登出清理 | **登出路径保持**；Android 会话期内持久罐见本轮 M1 | Android `removeAllCookies`+`flush`；iOS `nonPersistent` + 退出时清 default store；放弃验证码走同一 `logout` |
| M2 iOS 重装 Keychain | **代码保持，设备未验收** | `AccountSecurityCoordinator.restore()` 在标记缺失时 `vault.clear()`；无「缺标记当升级」分支 |
| M3 macOS 全量清理 | **代码保持**；Mac 安装验收已在 09-26 做过 | `clearAll` WAL TRUNCATE + VACUUM；`purge()` 删凭据和偏好键；成功后 `onPurgeLogout` |
| M4 桌面明文缓存 | **保持加密** | `JvmAesCacheValueProtector` AES-256-GCM；升级删旧库；macOS 密钥在 Keychain，Windows 密钥 DPAPI |
| L1 VACUUM | **已修保持** | `CacheStore.kt:411-423` |
| L2 CAS 登出 | **已修保持** | `SchoolLoginProtocol.kt:16, 206-215` 请求 `https://cas.bjtu.edu.cn/auth/logout/`，无论成败都 `clearSession()`；失败对用户可见 |
| L3 桌面日志默认开 | **已修保持** | 默认关 |
| L4 phyvlab `replace` | **已修保持** | `upgradePhyVlabUrlToHttps` 只改前缀（`AuthenticatedAppShell.kt:1999-2000`） |
| L5 SecuritySmoke exported | **已修保持** | signature 权限 |
| L6 Wrapper SHA-256 | **已修保持** | `gradle-wrapper.properties` |
| L7 OkHttp 未锁定 | **已修保持** | toml `okhttp = "5.3.2"` |
| L8 pytorch_android 2.1.0 | **Android 已迁 ONNX** | Windows 仍 DJL+PT，见 L4 |
| L9 DPAPI 熵 | **按用户决定保留** | 见 L2 |
| L10 iOS 15 | **已提到 16.0** | `Config.xcconfig`；测试 `iosDeploymentTargetIsAtLeast16` |
| 签名默认口令 `"android"` | **已修保持** | `requiredSigningSecret`，无默认值 |
| iOS 备份排除 | **已修保持** | `NSURLIsExcludedFromBackupKey` |
| Android `allowBackup` | **保持 false** | `androidApp/.../AndroidManifest.xml:6` |

09-17 冻结工程 H1/H2：仍在，不纳入 KMP 修复范围。

---

## 六、建议修复优先级（进入 audit 分支后）

1. **M3** 修正 `SecurityReleaseConfigTest` 对 `release.yml` 的断言。
2. **M2** 把 `kmp-security-check.yml` 的 push 分支改到 `main`。
3. **M1** Android WebView 等清空完成后再写入 Cookie。
4. **M5** 作业/课件 `smartRequest` 禁止自动跟随，避免 `sessionid` 头跟到外域。

设备验收（不阻塞源码修复）：iPhone 卸载重装 Keychain、带登录态的 CAS+WebView 登出、Android 真机验证码与登出。

---

## 七、audit 分支实施记录（2026-09-29）

只读扫描完成后检出 `audit/1.8.0-2026-09-29`，修了 M1–M5 / L12 / L17，并追加课程日历精确范围替换。代码、相关自动化测试与平台编译证据已纳入本分支提交；设备相关行为仍待真机验收。给人看的汇总：[BJTU-KMP-Audit-Fix-Summary-2026-09-29.md](BJTU-KMP-Audit-Fix-Summary-2026-09-29.md)。

| 项 | 改动 | 验证 |
|---|---|---|
| M3 | `SecurityReleaseConfigTest` 改为断言 `release.yml` 的 `github.ref_name == 'v1.7.0'` | `:shared:desktopTest --tests '*SecurityReleaseConfigTest'` 通过 |
| M2 | `kmp-security-check.yml` push 分支 `Liquid` → `main` | 同上 |
| M1 | Android WebView 先 `removeAllCookies`，回调里再 `setCookie` + `flush` + `loadUrl` | 源码顺序测试通过；`:shared:compileAndroidMain` 通过。真机换账号未验 |
| M5 | 作业/课件 `smartRequest` 改 no-follow，3xx 当会话失效；iOS Cookie 补 Secure | Homework/Courseware/CourseSchedule desktopTest 通过 |

未做：壳层拆分、jsonEscape 桌面重复、设备验收、提交/推送。

---

## 八、本轮未做 / 不能声称

- 未跑全量 `:shared:desktopTest`（跑了发布配置、握手白名单、作业/课件/课表数据源与日历专项测试）。
- 本分支所有设备相关行为仍待对应真机/系统环境检验：iPhone 卸载重装后的 Keychain 清理、带登录态的 CAS + WebView 登出、Android 真机换账号后再开邮箱，以及 Apple 课程日历从全学期改为窄周段后的精确替换。日历必须使用独立测试日历，不操作用户现有日历；此前 Mac 全量清理的单项验收不代表本分支其余行为已经实测。
- 学校真实 302 链尚未观测；测试证明白名单拒绝与 no-follow 逻辑，不证明学校线上实际响应。
- 未把真实账号、Cookie、验证码写入本报告或任何提交。
- OSV 零命中不能代替持续依赖扫描。
