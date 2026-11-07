# M4 账户安全与持久化进度

> 状态：主体实现完成，签名运行验收待补  
> 实现范围：仅 `multiplatform/`；冻结 Android 根工程未修改

## 当前结论

M4 的敏感凭据安全存储、记住登录信息生命周期和普通缓存数据库已经形成三平台实现。普通缓存能够跨进程重开，数据库 `v1 → v2` 升级和损坏后单次重建均有真实文件测试；退出登录会清除当前账号的系统安全存储、内存会话和账号范围缓存，同时保留不含账号数据的全局设置。

“重启后普通缓存可恢复、退出登录清理敏感状态、数据库升级测试通过”三项功能验收已经满足。M4 暂不标记完整完成，只因为未签名 iOS Simulator 缺少合法 Keychain entitlement；获得 Apple Development 签名身份后仍需补一次 iOS Keychain 保存、恢复和清除的真实运行往返。

共享 UI 的行为统一为：

1. 默认不保存账号密码。
2. 只有 MIS 与教务系统都登录成功后，才按用户选项写入安全存储。
3. 取消“在此设备上安全保存登录信息”会立即清除已保存凭据和记住标记。
4. “退出并清除登录信息”会结束内存会话、清空表单，并清除安全存储和普通偏好。
5. 安全存储读取失败或载荷损坏时恢复为手动登录，不把异常内容、账号或密码显示到界面和日志。

## 分层实现

| 层 | 实现 | 当前验证 |
|---|---|---|
| `commonMain` | 版本化二进制凭据载荷、长度/UTF-8 防护、`AccountSecurityCoordinator` | 单元测试通过 |
| 普通缓存 | SQLDelight 2.3.2；共享 schema、查询、迁移和账户范围策略 | 5 个数据库测试及三平台运行通过 |
| Android | Android Keystore AES/GCM；私有 SharedPreferences 只保存 IV 和密文；记住标记单独保存 | debug 合成凭据烟测 `SECURITY_SMOKE_PASS` |
| iOS | Security.framework `SecItemAdd` / `SecItemCopyMatching` / `SecItemDelete`；`AfterFirstUnlockThisDeviceOnly` | Simulator arm64 与真机 arm64 编译通过；未签名 Simulator 运行受 entitlement 阻塞 |
| macOS | 通过 JNA 调用 Security.framework 和 CoreFoundation；普通标记使用 Java Preferences | 合成凭据真实 Keychain 往返测试通过 |

Android 的 `SecuritySmokeActivity` 只位于 `src/debug`。最终 APK 清单核验结果：debug 包包含该 Activity，release 包只包含正式 `MainActivity`，不会把安全烟测入口带入发布构建。

## 普通缓存数据库

原 Android Room `v1` 的成绩、课程、考试和作业四张表已做只读审计。新 KMP 工程最终采用 SQLDelight 2.3.2，而不是把 Android Room 实体直接带入共享层；SQLDelight 提供 Kotlin/Native SQLite driver，并支持把版本迁移写成 `.sqm` 文件。参考：[Native SQLite driver](https://sqldelight.github.io/sqldelight/native_sqlite/)、[SQLDelight migrations](https://sqldelight.github.io/sqldelight/2.0.2/jvm_sqlite/migrations/)。

当前 schema 版本为 2，共七张表：成绩、课程、考试、作业、成绩自选、账号范围元数据和全局设置。前六类账号数据均由 `account_scope` 隔离；密码、Cookie、CSRF、CAPTCHA 和可复用会话禁止进入该数据库。`1.sqm` 会给旧四表补 `account_scope`，保留旧行并在账号成功登录后认领，随后增加成绩自选、元数据和设置表。

平台 driver 与位置：

- Android：`AndroidSqliteDriver`，应用私有数据库 `bjtuselfservice_cache.db`。
- iOS：`NativeSqliteDriver`，由 driver 放入 App 沙盒 `Application Support/databases/`。
- macOS：JDBC SQLite，位于 `~/Library/Application Support/BJTUselfServiceKMP/`；自包含运行时显式包含 `java.sql`。

打开数据库时会立即执行轻量探测；创建、迁移或读取失败时，关闭 driver、删除数据库及 WAL/SHM，仅重建一次，再次失败则保留明确错误而不循环重试。共享 UI 只显示“本地缓存损坏，已安全重建”，不会暴露数据库内容。

## iOS Keychain 的已知边界

当前 Xcode Simulator target 使用 `CODE_SIGNING_ALLOWED=NO`，因此没有可供 Keychain 校验的应用身份。运行安全烟测时，原生字典参数问题修复后，Security.framework 稳定返回：

```text
errSecMissingEntitlement (-34018)
```

这代表当前未签名测试宿主缺少所需 entitlement，不是凭据编解码失败。Apple 对 `-34018` 的排查建议也是先确认签名 entitlements；Keychain access group 必须与签名身份匹配。参考：[Apple Developer Forums: Resolving the -34018 Keychain Error](https://developer.apple.com/forums/thread/114456)、[Sharing access to Keychain items](https://developer.apple.com/documentation/security/sharing-access-to-keychain-items-among-a-collection-of-apps)。

曾做过一次本地 ad-hoc entitlement 试验，Simulator 拒绝启动。该试验的项目设置和临时 entitlement 文件已经完整回退。后续验收必须使用合法 Apple Development 签名或真机配置，不伪造团队标识，也不把“编译通过”写成“Keychain 运行通过”。

## 构建与测试证据

- Desktop：43 个测试通过，其中新增 5 个 SQLDelight 测试，覆盖全部实体往返与账号隔离、关闭后重开、真实 `v1 → v2` 迁移、损坏文件重建和设置容错；原有安全协调器、凭据载荷及真实 macOS Keychain 测试继续通过。
- Android：`:androidApp:assembleDebug :androidApp:assembleRelease` 成功。
- iOS 共享代码：`:shared:compileKotlinIosSimulatorArm64 :shared:compileKotlinIosArm64` 成功。
- iOS Xcode：明确指定 iPhone 17 Pro / iOS 26.5 / arm64 后，未签名 Debug App `BUILD SUCCEEDED`；选择“任意模拟器”会错误尝试未配置的 `x86_64`，不能作为推荐命令。
- macOS：使用带 `jpackage` 的 Temurin JDK 25 执行 `:desktopApp:createDistributable` 成功；成品运行时已包含 `java.sql`。Android Studio JBR 不含 `jpackage`，只适合 Android 构建。
- 冻结边界聚合 SHA-256 仍为 `a2b8313387fa6902cb8297554a2724c63dfddcfdd800f5f536374e44bbe1c73f`。

Android KMP App 冷启动成功并在应用私有目录创建数据库。iPhone 17 Pro Simulator 首次启动、终止、再次启动均正常，数据库实际落在 App 沙盒 `Application Support/databases/`。macOS 自包含 `.app` 也已通过 Computer Use 完成首次启动、关闭和二次启动；关闭后的只读 SQLite 检查为 `quick_check=ok`、`user_version=2`。三端视觉核验均没有填写真实账号，也没有触发 CAPTCHA。

## 下一步

1. 获得合法 Apple 签名身份后，在签名 Simulator 或真机执行 iOS Keychain 保存、恢复、清除的完整往返验收。
2. 进入 M5 的第一个纵向切片，把成绩 Repository、缓存读写、同步状态和共享页面接到当前基础设施上。
3. 在业务页面形成后，用同一行为清单继续做 Android、iOS 和 macOS 的视觉/重启回归。
