# BJTUselfService KMP 迁移工作记忆

> 最后更新：2026-09-18
> 当前分支：`main`；显示/打包版本 **`1.7.6-KMP`**。正式提交 `ac10ba1`、CI 修复 `eb42f1e` 已推送并打正式 tag `v1.7.6-KMP`（run 35185935353 五 job 全绿，正式 Release 已发布）。签名加固 commit `584d26c` 与 2026-09-18 安全修复改动**仍在本地工作区未提交**。
> 阶段状态：**1.7.6-KMP 已正式发布；2026-09-18 完成安全审计修复里程碑（M2/M3/M4 修复 + M5 验证，代码未提交），审计与进度见 `docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-17.md`。** 邮箱主体能力保持自 `v1.7.5-KMP`，真实邮箱写操作、Apple 真机签名等仍按已知限制保留。
> 分支创建点：`9d8da18`；上游对照基线：`v1.7.0@419313d`；KMP 自身基线：**`v1.7.3-KMP-B` (`a342615`)**；当前正式版本 **`1.7.6-KMP`**；上一发布 `v1.7.5-KMP` 保留。
> 完整历史与已归档的验收细节：见 `history_full.md`（按里程碑归档，只读）
> 本文件是实时工作记忆，不是只追加日志：任务开始读、结束改，只保留当前接续工作需要的状态。

## 1. 本阶段已做到（≤10 行）

- **2026-09-18 安全审计修复里程碑（本轮，工作区未提交）**：GLM 只读审计归档为 `docs/security/BJTU-KMP-Security-Audit-GLM-2026-09-17.md` 并附修复进度。按用户指示 H1/H2/M1 暂缓；M2/M3/M4 修复、M5 验证通过；总体要求「覆盖升级即获修复、不改签名」全部满足。细节已归档 `history_full.md`「安全审计与修复里程碑」。
- **M2 Keychain 重装残留**：`restore()` 在「记住密码」标记为否时主动 `vault.clear()`（iOS 卸载清 NSUserDefaults 残留 Keychain，重装即清）；不用新哨兵避免升级用户被误判强制重登。macOS 由 M3 兜底。
- **M3 桌面卸载残留**：设置页新增「清除全部本地数据」（仅 macOS/Windows 显示）：清凭据 + `CacheStore.clearAll()` 含 `app_setting`；README 新增卸载清理指引；Windows MSI 卸载清理为既有能力。
- **M4 iOS 备份排除**：`createIosCacheStore()` 每次启动对 db/-wal/-shm 设 `NSURLIsExcludedFromBackupKey`。
- **M5 签名验证**：本地强口令 + keystore 别名实为 `androiddebugkey`（证书 `5D0DABC3…C773` 与已发布 APK 一致）+ `signingReport` 通过；CI Secrets 还原同一 keystore。覆盖升级链路成立。
- **本轮验证**：安全/设置测试组、`iosSimulatorArm64Test` 全量、Android debug 编译、`signingReport` 均通过；`PackagingCiAsciiConfigTest` 2 例为既有失败（与本轮无关）。
- **2026-09-16/17 作业提交协议、首页周卡片、1.7.6 正式发布**：细节已归档 `history_full.md`（1.7.4～1.7.6 段）。

## 2. 当前痛点（≤8 条）

- **安全修复未提交、未实机验证**：iOS 卸载重装清 Keychain、iCloud 备份排除、桌面「清除全部本地数据」UI 均未实机验收；改动等用户决定提交/发版节奏（下一版需 bump versionCode）。
- **`PackagingCiAsciiConfigTest` 既有失败（2 例）**：1.7.6 打包收口遗留（CI ASCII vs 本机 UTF-8 安装器元数据断言与当前工作流不一致），与本轮无关，待单独修复。
- **普通作业列表「提交人数 0/63」口径待确认**：详情正确，列表与网页端 93/99 不一致，需核对 `submitCount` 字段来源。
- **Windows MSI 卸载清凭据待复测**：装带卸载清理的 MSI 后再卸，确认 AppData 缓存与注册表凭据被删（与 M3 相关，下一版安装包顺带复测）。
- **iOS 真机签名/连接**：Bundle ID 无匹配 provisioning profile；实体 iPhone `devicectl` unavailable；签名安装与 Keychain 往返未取得证据。
- **M13/M15 保留边界**：物理在线真实上传未执行；邮箱真实发送/删除/移动/附件写操作待单独切片；验证码发布级准确率待扩样。
- **构建环境**：compose 1.12.0-beta03 要求 compileSdk 37；JBR 无 jlink/jpackage 需完整 JDK；arm64-only APK 不能装 x86_64 模拟器。

## 3. 接下来 1～3 个阶段

1. **安全修复收口**：用户确认后提交（含 `docs/security/`、README、memory 文档）；下一版 bump 版本号后实机验证 iOS 卸载重装清除、桌面全量清理按钮，并顺带复测 Windows MSI 卸载清理。
2. **正式包安装回归**：从 `v1.7.6-KMP` Release 覆盖安装四端产物检查（与新安全修复版本衔接）。
3. **M15 邮箱读写验收与扩展**：真实发送、删除、移动、附件下载仍待单独切片。

## 维护规则

- 每次开始目标模式任务时先读本文件；结束前必须再次更新。
- 已完成事项压缩为一行保留在“本阶段已做到”；里程碑真正完成时，把细节归档进 `history_full.md` 并从本文件删除。
- 痛点解除即删除或改写，不保留已经失效的阻塞描述。
- 近期计划只保留接下来 1～3 个可执行阶段；远期内容留在 `goal.md`。
- 事实、命令结果和验证边界要具体；不能把计划写成已完成。
- 分支、基线、最新 Release 或工作区状态发生变化时，更新文件顶部摘要。
- 不在本文件写入账号、密码、Cookie、令牌、真实验证码会话或其他敏感信息。
