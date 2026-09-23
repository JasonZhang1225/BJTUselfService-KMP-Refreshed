# BJTUselfService-KMP 只读安全复查报告（版本更新验证轮）

- **审计日期**：2026-09-21
- **审计对象版本**：`1.7.8-Liquid`（iOS `Info.plist:22` 确认）
- **审计性质**：上一轮审查（[2026-09-17 安全审计](BJTU-KMP-Security-Audit-GLM-2026-09-17.md) + 屎山审查）修复完成、版本更新后的**重新扫描验证**
- **审计方式**：纯只读（4 个并行探查代理覆盖网络层 / 本地存储 / 登出清理与卸载残留 / 依赖版本 + 联网 CVE 检索），未修改、创建或删除任何项目文件
- **审计范围**：`multiplatform/`（活跃 KMP 工程：Android / iOS / macOS / Windows）；根 `app/` 冻结工程按惯例跳过

---

## 总体结论

**上一轮修复全部保持有效，未发现回归，未发现新增高危项。**

验证通过项：

- 凭据存储三平台均为系统级加密（Android Keystore AES/GCM、Apple Keychain `AfterFirstUnlockThisDeviceOnly`、Windows DPAPI），无明文落盘回退
- 证书校验零旁路：全仓无 TrustManager / hostnameVerifier / SSL 自定义代码，三引擎（OkHttp / Darwin / CIO）默认校验
- 明文流量收口在两个精确 origin，Android network_security_config、iOS ATS 例外、代码级 origin 白名单三层一致，发布脚本 `verify-apple-release-metadata.sh:50-54` 强制断言
- 登出清理链路完整（内存 Cookie → 凭据 vault → 按账号逐表删库 + metadata → 内存状态），清除失败有用户可见提示
- 日志全面脱敏（请求级脱敏表 + 模型 `toString` redacted，全仓仅 4 个 `AppLog` 调用点，逐一核对通过）
- 密钥嵌入：`MisSecret.md` / `.artifacts/` 仅被桌面 live 探测测试运行期读取（需 `BJTU_LIVE_PROBE=1`），不进产物
- 依赖供应链入口仅 google / mavenCentral / gradlePluginPortal，iOS 零三方依赖

剩余待修风险集中在 **WebView Cookie 残留** 与 **桌面端明文缓存** 两点。

---

## 〇、已知例外声明（学校系统必需，不计入高危）

| 端点 | 用途 | 配置位置 | 残余固有风险 |
|---|---|---|---|
| `http://123.121.147.7:88`（路径锁 `/ve/`、`/oauth/`） | 智慧教学平台 API / 第三方登录握手 | `SmartPlatformEndpoint.kt:128-129`、`network_security_config.xml:6-10`、`Info.plist:33-48` | 中低：明文链路上 sessionid / OAuth ticket 可被同网段嗅探（例外的固有代价） |
| `http://123.121.147.7:1936`（路径锁 `/kk/rp/`） | 教学日历 PDF | 同上 | 同上 |
| `http://yaya.csoci.com:2333/api/classnum/` | 空教室人数估计（第三方，https 实测不可达） | `ClassroomRemoteDataSource.kt:24` | 低：无凭据明文，独立无 Cookie transport（`AuthenticatedSessionFactory.kt:179`） |

防护实现核对（保持完好）：

- `SmartPlatformEndpoint.kt:134-143` origin 协议/主机/端口/路径前缀逐字段白名单；`:145-152` 拦截 `%2f/%2e/../%25/%00` 逃逸；`:185-208` 降级重定向手动逐跳跟随，明文跳仅限精确 apiOrigin、HTTPS 跳仅限 `cas/mis.bjtu.edu.cn`，最多 10 跳
- `ClassroomRemoteDataSource.kt:96-102` 精确 authority 锁定（显式端口防后缀域伪造），重定向出 origin 即失败
- 未发现统计、埋点、图片 CDN 等其他明文传输；学校主站（cas/aa/mis/bksycenter/phyvlab/mail）均为 https 基线

---

## 一、高危

无发现。

---

## 二、中危（4 项）

### M1. WebView 持久 Cookie 存储在登出时不清理

- **位置**：
  - `shared/src/androidMain/.../webview/SchoolWebView.android.kt:26-36` — 会话 Cookie 经全局 `CookieManager.setCookie()` 写入 WebView 持久数据库，`domStorageEnabled=true`；全仓无 `removeAllCookies` 调用
  - `shared/src/iosMain/.../webview/SchoolWebView.ios.kt:68-94` — 默认**持久** `WKWebsiteDataStore`；`MainViewController.kt:142-146` 预热的 WKWebView 同样持久
- **影响**：登出后邮箱 MIS 会话 Cookie 残留磁盘至自然过期；同安装期内换账号仍可读到上一账号部分域的 Cookie
- **修复建议**：登出 / 会话失效时调用 `CookieManager.removeAllCookies(null)`（Android，可加 `removeAllCookies` 后 `flush()`）；iOS 用 `WKWebsiteDataStore.removeData(of:for:)` 或改用 `nonPersistent()` data store

### M2. iOS 卸载后 Keychain 凭据残留（系统行为）

- **位置**：`shared/src/appleMain/.../security/AppleKeychainCredentialVault.kt:59-92`（accessibility 已是 `AfterFirstUnlockThisDeviceOnly`，不随备份迁移，此项正确）
- **影响**：勾选「记住密码」且未登出即卸载，学号+密码密文留在钥匙串，重装可被 `SecItemCopyMatching` 读回
- **修复建议**：代码侧无法兜底卸载；可在设置页文案明确告知用户，或下次启动检测重装态并询问是否清除

### M3. macOS 桌面版无卸载清理

- **位置**：`shared/src/desktopMain/.../cache/DesktopCacheStore.kt:10-19`（`~/Library/Application Support/BJTUselfServiceKMP/`）+ macOS Keychain 条目 + `java.util.prefs`（`~/Library/Preferences`）
- **影响**：DMG 拖拽安装无卸载器，删除 .app 后个人学业数据库与凭据全残留
- **修复建议**：提供卸载脚本 / 说明文档；应用内「清除全部本地数据」补上删除 db 文件、prefs 节点与 Keychain 条目本身（当前只清表行和标记，见 `AccountSecurityStore.kt:103-109`）

### M4. 桌面 / Windows 明文 SQLite 缓存含成绩、作业正文、姓名学号

- **位置**：
  - `DesktopCacheStore.kt:19-27`（`~/Library/Application Support/BJTUselfServiceKMP/bjtuselfservice_cache.db`，明文、无备份排除、无加密）
  - `windowsApp/.../WindowsCacheStore.kt:21-29`（`%LOCALAPPDATA%\BJTUselfServiceKMP\bjtuselfservice_cache.db`，明文）
  - 写入点 `CacheStore.kt:266-284`（明文缓存姓名、学号、身份、院系）
- **影响**：同用户 / 管理员可直接读取。Android / Apple 端同为明文但有私有目录 + `allowBackup=false` / `NSURLIsExcludedFromBackupKey`（`AppleCacheStore.kt:56-60`，良好实践）缓解，定为低-中
- **修复建议**：桌面端缓存入库前用平台凭据派生密钥加密（macOS Keychain 存对称密钥、Windows DPAPI），或至少在发布文档中明示存储位置与明文性质

---

## 三、低危（10 项）

| # | 位置 | 内容 | 修复建议 |
|---|---|---|---|
| L1 | `CacheStore.kt:347-358` | `clearAll()` 不 VACUUM、不删 db 文件，已删行可被磁盘取证恢复 | 清库后执行 `VACUUM` 或删除重建 db 文件 |
| L2 | `SchoolLoginProtocol.kt:200` | 登出仅本地清 Cookie，服务端 CAS 会话存活至自然过期 | 登出时请求一次学校侧登出端点（若可用） |
| L3 | `AppLog.desktop.kt:5` | 桌面端日志默认开启（内容已脱敏，但与 Android release 默认关不对齐） | 分发构建默认关闭 |
| L4 | `AuthenticatedAppShell.kt:1608-1634` | phyvlab 外链 http→https 用非前缀 `String.replace`，query 内嵌 `http://` 文本也会被改写 | 改为 `removePrefix("http://")` 前缀替换 |
| L5 | `androidApp/src/debug/AndroidManifest.xml:5` | debug-only `SecuritySmokeActivity` exported（仅 fixture 冒烟，无数据泄露面） | 可加 signature 权限或仅内部触发 |
| L6 | `gradle/wrapper/gradle-wrapper.properties` | 未固定 `distributionSha256Sum` | 补上哈希（供应链加固） |
| L7 | Ktor 传递依赖 | OkHttp 本体版本未显式锁定（应为 ≥4.12.0，不受 CVE-2023-3635 影响，但未验证） | 显式声明或跑 `./gradlew :shared:dependencies` 核实 |
| L8 | `libs.versions.toml` — `pytorch_android` 2.1.0 | 2023 年旧版本，与 2026 工具链脱节；仅本地推理攻击面有限 | 计划升级 |
| L9 | `WindowsAccountSecurityStore.kt:77-78` | DPAPI 附加熵为编译期常量（安全边界在用户派生密钥，影响有限） | 可选加固：引入外部熵源 |
| L10 | `Config.xcconfig` — iOS deployment target 15.0 | iOS 15 已停止安全更新 | 视产品策略提到 16+ |

---

## 四、依赖版本核实结果

版本来源：`multiplatform/gradle/libs.versions.toml`（唯一版本来源，无硬编码）。

核心依赖：Gradle 9.3.1 / Kotlin 2.4.10 / Compose Multiplatform 1.12.0-beta03（Material3 1.12.0-alpha03）/ AGP 9.1.1 / Ktor 3.5.1 / SQLDelight 2.3.2 / kotlinx-serialization 1.8.1 / kotlinx-coroutines 1.10.2 / kotlinx-datetime 0.8.0 / KSoup 0.2.6 / JNA 5.17.0 / Navigation3 1.1.1 / activity-compose 1.13.0 / pytorch_android 2.1.0 / DJL 0.33.0 + pytorch-native-cpu 2.5.1。

- 联网检索未发现针对 Ktor 3.5.1 / SQLDelight 2.3.2 的有效 CVE 通告
- 多数依赖版本较新（超出知识库可靠覆盖范围），建议后续用 OSV.dev 按精确版本批量核实一次
- iOS 零第三方依赖（无 Podfile / SwiftPM，仅自产 BJTUShared 静态框架 + 系统 sqlite3）
- 无 local-maven / mavenLocal，`settings.gradle.kts` 开启 `FAIL_ON_PROJECT_REPOS`
- 工程妥协项：`gradle.properties` 因 Material3 alpha 要求 compileSdk 37 开启了 `android.experimental.disableCompileSdkChecks` 豁免，正式版发布后应移除
- 供应链建议：自产验证码模型 `.pt` / `.mlpackage` 制品做 SHA-256 留档（`tools/captcha/` 脚本侧已有 validation_manifest 思路）

---

## 五、与上轮（2026-09-17）的差异对照

| 上轮发现 | 本轮状态 |
|---|---|
| H1 / H2（冻结旧工程 trustAll + 明文密码） | 冻结工程本轮未扫（按审计要求跳过），历史结论不变 |
| 上轮中危：凭据明文存储、证书校验、明文收口 | **已修复且保持**，本轮验证通过 |
| `MisSecret.md` 本地明文凭据 | 仍在（仓库外已 gitignore），未被嵌入产物，维持上轮建议（轮换密码） |
| WebView Cookie 残留 | 上轮已指出，**本轮仍未修**（M1，优先级最高） |
| 桌面明文缓存 / macOS 卸载残留 | 上轮已指出，**本轮仍未修**（M3、M4） |
| 新增发现 | L4（phyvlab 链接 replace 瑕疵）、L6（Gradle wrapper 哈希）、L7（OkHttp 版本未锁定）、依赖版本清单整体更新（Kotlin 2.4.10 / Compose 1.12.0-beta03 / Ktor 3.5.1 等） |

---

## 六、建议修复优先级

1. **M1** WebView Cookie 清理（登出与账号切换路径，改动小收益大）
2. **M4** 桌面 / Windows 缓存加密或文档披露
3. **M3** macOS 卸载残留（脚本 + 全量清除补删文件）
4. **M2** iOS Keychain 残留（文案 / 重装检测，产品决策）
5. 低危项按 L1→L10 顺次处理，L6/L7 属一次性供应链加固

---

## 七、P1–P2 修复实施记录（2026-09-23）

本节记录审计后的实际修复；上文保留审计时点的原始结论，避免覆盖历史证据。

### P1 修复

- **M1 WebView 残留：已修复。** Android 退出时等待清除 `CookieManager`、
  DOM Storage 并 flush；iOS 学校 WebView 与预热 WebView 改用
  `WKWebsiteDataStore.nonPersistent()`，退出时另行清理旧版本默认持久仓库。
  清理失败进入用户可见的退出反馈。
- **M4 桌面明文缓存：已修复。** macOS/Windows 的 SQLDelight 文本字段改用
  AES-256-GCM；普通值使用随机 nonce，账号范围、设置键和复合主键使用
  HMAC 派生 nonce 的确定性密文以保留等值查询；整数标识经密钥驱动的
  64 位 Feistel 置换，避免 `user_id` 等标识明文入库。macOS 密钥保存在 Keychain，
  Windows 密钥经 DPAPI 保护后保存。首次升级删除旧明文数据库并重建；安装标记
  使中途失败时下次启动仍会重新执行迁移。
- **M3 macOS 全量清理：已修复到应用能力边界。** `clearAll()` 删除全部表后执行
  WAL `TRUNCATE` checkpoint 与 `VACUUM`，避免仅逻辑删行；全量清理使用
  `AccountSecurityCoordinator.purge()` 删除凭据和“记住密码”偏好键本身。
  DMG 拖拽删除仍无法获得系统卸载回调，README 继续保留手动路径。
- **M2 iOS Keychain 重装残留：已修复。** 删除“缺失偏好标记时迁移旧凭据”的
  兼容分支；`remember_credentials=false` 或标记缺失均清除 Keychain，不再把
  卸载重装误判成旧版本升级。

### P2 修复

- 登出先请求 `https://cas.bjtu.edu.cn/auth/logout/` 使 CAS 服务端会话失效，
  无论请求成功与否都销毁本地 Cookie；服务端失败会显示给用户。
- Gradle 9.3.1 分发包固定官方 SHA-256；Wrapper JAR 重新生成并与官方 checksum
  匹配；所有执行 Gradle 的 GitHub Actions job 在构建前运行官方 Wrapper 校验。
- Android 从停止更新的 `pytorch_android 2.1.0` 迁移到 ONNX Runtime 1.30.0。
  新模型与确定性 PyTorch 基线的 logits 最大绝对误差约 `1.14e-5`，argmax 序列一致；
  模型 SHA-256 已写入 `tools/captcha/validation_manifest.json`。
- 显式固定 OkHttp `5.3.2`，不再仅依赖 Ktor 传递解析；桌面发行日志默认关闭，
  仅显式设置 `-Dbjtu.debug.logging=true` 时启用。
- 修正冻结旧 Android 发布流水线对 `v*-Liquid*` 标签的误匹配：Liquid 标签只走
  KMP 打包流水线，避免旧工程的历史 TLS/凭据缺陷被重新发布。

### 已执行验证

- `:shared:desktopTest` 在 macOS CI 全量 532 项通过，覆盖缓存原回归、AES-GCM 随机/确定性
  往返、篡改拒绝、各类学业缓存原始字节无测试个人明文、Keychain/偏好协调逻辑。
- `:windowsApp:compileKotlinWindows :windowsApp:windowsTest` 全量通过；独立测试节点
  验证缓存密钥经 DPAPI 加密持久化，且可跨实例读回。
- `:shared:compileAndroidMain :androidApp:compileDebugKotlin` 通过（仅编译，不签名打包）。
- ONNX checker + ONNX Runtime 对转换模型执行成功，argmax 与基线一致。
- PR #4 的 macOS 门禁（run `35800148828`）已完成 iOS Kotlin/Native 编译及
  macOS 全量测试。新增 iOS 模拟器测试验证真实 `NSUserDefaults` 标记消失后，
  协调器会清除保险库凭据。CI 中的 Kotlin/Native 测试可执行文件访问原生
  Keychain 返回 OSStatus `-25291`（`errSecNotAvailable`）；原生往返测试仅在
  Keychain 可用时执行，不能将该条件性测试视为原生 Keychain 已验收。
  真实 iOS 设备上的登出网站数据清理、卸载重装后 Keychain 清理仍需
  设备端到端验收，不能以编译或单元测试代替。
