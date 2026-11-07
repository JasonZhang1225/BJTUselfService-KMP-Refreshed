# 1.8.1 分支集成与回归检查

日期：2026-10-03。应用版本：1.8.1；Android/iOS/macOS 构建号：22。

## 检查对象

- `main@c82617c`：首页课表/校历并行同步、公开校历超时处理、iOS 原生玻璃与回顶透明，版本与发布守卫测试更新。
- `audit@4c7e7dd`：保留安全修复提交 `030fae3`，合入上述 main。两次合并父提交均保留，旧分支名称清理不会丢失审计历史。
- 本地分支只有 `main`、`audit`，对应个人远端 `mine/main`、`mine/audit`。上游 `origin` 仍是原作者仓库引用。

## 自动化结果

| 检查 | 结果 | 证据 |
|---|---|---|
| main 共享/JVM 全量回归 | 561 项，0 失败 | `/tmp/bjtu-181-main-kmp-checks.log` 的 shared desktopTest；后续正确范围检查 `/tmp/bjtu-181-main-final-checks.log` 成功 |
| audit 共享/JVM 全量回归 | 576 项，0 失败/跳过 | `:shared:desktopTest` |
| audit iOS 模拟器测试 | 537 项，0 失败/跳过 | `:shared:iosSimulatorArm64Test` |
| Windows Kotlin 编译 | 成功 | `:windowsApp:compileKotlinWindows` |
| Android arm64 APK | 构建成功，v2 签名通过 | `:androidApp:assembleDebug`、apksigner；原共享证书 SHA-256 `5d0dabc3fdba94df03fa0e8c7d7a719a74d5ab2ac2330f17c235e0a88466c773` |
| macOS DMG | 构建、镜像校验、包内严格签名验证通过 | `:desktopApp:packageDmg`、hdiutil verify、codesign --verify --deep --strict |
| iOS unsigned IPA | Release 真机构建、未签名验证、arm64/iPhoneOS 元数据与全包 CRC 验证通过 | `/tmp/bjtu-181-audit-ipa-build.log`、codesign、lipo、vtool、Zip CRC |

合并后门禁命令：

```sh
cd multiplatform
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew \
  :shared:desktopTest :shared:iosSimulatorArm64Test :windowsApp:compileKotlinWindows \
  --max-workers=1 --no-parallel --console=plain
```

合并后门禁日志：`/tmp/bjtu-181-audit-checks.log`。三端包来自 audit 的已检查代码。

## 集成后的重点检查

- 校历与课程快照并行到达时，未校准的远端周数仍不能覆盖校历结果；手动选周不被后到的异步结果覆盖。慢请求、取消后重试、教学周空档由课程模型回归测试覆盖。
- 公开校历使用独立请求旁路及 6 秒超时。自身超时返回失败结果，外层取消保持向上传递，不再把接口超时当成取消整个登录后同步。
- 原生模糊保持 alpha=1，直接遮罩施加在材质视图本体。0 进度时隐藏，1 进度时完整显示；布局重算同样尊重进度，避免回顶后残留材质。
- 主分支物理在线月份测试原来依赖系统月份，9 月数据在 10 月被正常过滤后会使旧断言失败。现用注入的固定月份时间验证课程切换与首页全课程议程；默认应用时间源保持系统时钟。
- 审计安全契约保留：Android Cookie 清理回调先于会话注入；智慧教学握手的下一跳白名单在发送请求前检查；教室重定向拒绝；WebView 强制 https 导航；iOS Cookie Secure 属性保留。
- Apple 课程日历精确范围替换保留：按学期窗口和 App marker 识别旧课程系列，清理旧系列后重建新范围；手工事件与考试不参与课程清理。
- CI 已从过时的 Liquid 分支迁移到 `main` / `audit`。冻结根 Android 的 v1.7.0 发布守卫由测试校验。

以上是此次合并后的定向代码审阅与回归检查。没有重新执行依赖漏洞数据库扫描，不沿用旧报告日期的“0 条漏洞”结论冒充本次扫描结果。

## 验证边界

- iOS 原生 Keychain 用例在系统返回 errSecNotAvailable 时会提前返回；报告计数为 0 skipped 不能自动证明真实 Keychain 往返。合法签名真机仍需验收。
- Windows 原生测试在 Mac 上尝试运行时无法加载 Crypt32；模型原生运行也不通过。本轮 Windows 证据是编译，Windows 原生行为须由 Windows 主机验证。
- 首页浅深色、中间滚动与回顶的视觉证据来自上一轮模拟器 fixture/原生探针；完整 iPhone 导航壳仍待实机。
- Android WebView 换账号、iPhone 卸载重装/CAS 登出、Apple 日历范围替换的真实设备验收尚未完成。日历验收应使用独立测试日历，保留用户手工事件和考试。
- 本轮打包未覆盖用户安装的 Mac 应用，未操作用户已有系统日历，也未创建新版本标签或 GitHub Release。

## 远端检查与产物

- main 的源码检查已通过：[run 37039046269](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/37039046269)，对应 c82617c。
- audit 的源码检查已通过：[run 37046769022](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/37046769022)，对应 4c7e7dd。
- 最后的记录提交只更新本报告和 memory.md，三端包的应用源码与最终 audit 一致；包来源源码提交为 4c7e7dd。
- APK 继续使用既有共享证书，versionCode=22，arm64-v8a。IPA 无签名/provisioning/PlugIns，最低 iOS 16.0。DMG 包内 App 严格签名校验通过，arm64 / 最低 macOS 12.0，本地 ad-hoc 签名，未公证。

| 文件（Downloads） | 字节数 | SHA-256 |
|---|---:|---|
| BJTUSelfService-KMP-1.8.1-audit-arm64-v8a.apk | 99278240 | `c21c4744f9d7b32c94b1ab70cc16d8475ac89ab09d775b4cda5aea8b13a04884` |
| BJTUselfServiceKMP-1.8.1-audit.dmg | 120922656 | `29928a6b0f32e9593284708253c2c4f3c0f129fadd8055533e6e53347d59ed16` |
| BJTUSelfService-KMP-1.8.1-audit-iOS-unsigned.ipa | 41891012 | `7478c3817838a47aa107631a8c17bd04acf8d7e8479bab3c55cf25d79c4c936a` |

独立机器校验资料：Downloads 下的 BJTUselfServiceKMP-1.8.1-audit-SHA256SUMS.txt 和 BJTUselfServiceKMP-1.8.1-audit-manifest.json。
