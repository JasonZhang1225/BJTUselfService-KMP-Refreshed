# M3 登录协议阶段记录

> 更新时间：2026-07-30  
> 状态：完成；共享协议和三端登录 UI 已建立，KMP Android 与 iOS Simulator 已完成真实 MIS/AA 登录

## 已完成

- 对照 `v1.7.0@419313d` 的 `MisDataManager.java`、`StudentAccountManager.java` 与 `AppStateManager.kt` 梳理登录链路。
- 建立不含 Android、OkHttp、`CompletableFuture` 和全局单例的共享登录模型与顺序状态机。
- 建立共享 HTTP request/response/transport 边界及 Ktor 实现：
  - Android：OkHttp engine
  - iOS：Darwin engine
  - desktop/macOS：CIO engine
- 使用 Ktor 内存 Cookie storage 保持同一次会话，并通过重建客户端清除会话。
- 使用 Ksoup 解析 CAS 隐藏字段、MIS 学生资料和 AA 重定向表单。
- 将 CAPTCHA 明确建模为“图片挑战”：共享层只获取图片与一次性字段，答案由上层在用户动作时授权后提供。
- 为凭据、验证码字段、Cookie、响应正文和 URL 查询参数建立 `toString()` 脱敏回归测试。
- 建立共享 Compose 登录 UI：紧凑窗口单列，宽窗口说明区与表单并列；密码显隐、按钮禁用、加载、错误、验证码刷新和手动答案状态均有明确反馈。
- 使用 Computer Use 在 Android、iOS 和 macOS 实际打开首屏；iOS 与 Android 都通过真实学校 CAS 获取并显示验证码图片。
- 真实验证发现 CAS POST 有时已建立 MIS Cookie，但最终响应仍停留在 CAS 地址；协议现在会主动探测 MIS home，再决定是否失败，避免必须二次点击恢复会话。
- MIS 与 AA 首页判断严格匹配主机/路径，同时忽略无害的末尾斜杠、查询参数和 fragment 差异。
- Apple Design/视觉验收发现 iPhone 紧凑高度下主登录按钮不易到达；紧凑头部、卡片间距和表单密度已调整，按钮在 iPhone 17 Pro 视口内直接可见。
- KMP iOS 26.5 Simulator 和 Pixel 10 Pro XL Android 17 均已在一次 CAPTCHA 提交后显示“登录成功”，且没有 AA 教务连接失败。

## v1.7.0 登录链路

1. GET `https://mis.bjtu.edu.cn/auth/sso/?next=/`。
2. 已有会话时最终落到 MIS home；否则最终落到 CAS login。
3. 从 CAS HTML 读取 CSRF 与 CAPTCHA ID，再取得 CAPTCHA 图片。
4. POST CSRF、CAPTCHA ID/答案、用户名和密码；若最终响应地址仍不明确，则使用同一 Cookie 会话主动探测 MIS home，再解析学生资料。
5. GET MIS module 10，从 `form#redirect` 读取 AA 跳转地址；最终 URL 为 AA notice 页面时视为教务联接成功。

当前共享实现保留以上可观察协议，但不保留旧实现的三项风险：

- 不信任所有 TLS 证书。
- 不关闭 hostname verification。
- 不把用户名、密码、Cookie、验证码或完整响应写入日志字符串。

## 依赖基线

- Ktor Client `3.5.1`
- Ksoup `0.2.6`

只引入 Ksoup core；网页获取仍由统一 Ktor transport 完成，避免 HTML 库另建一套 Cookie 会话。

## 验证

最终共享回归命令：

```bash
./gradlew \
  :shared:desktopTest \
  :shared:compileKotlinIosSimulatorArm64 \
  --no-daemon --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 8s`。Desktop 35 个测试全部通过，0 skipped、0 failures、0 errors；iOS Simulator arm64 共享目标编译成功。协议测试覆盖 CAS 响应地址不明确但 MIS Cookie 已建立的真实分支。

`:androidApp:assembleDebug` 结果 `BUILD SUCCESSFUL in 1m 5s`。最新 Xcode iPhone 17 Pro/iOS 26.5 Simulator 构建成功并安装运行；仅保留 ICU 对最低 iOS deployment target 的既有链接警告。

## 视觉与真实挑战证据

- `m3-evidence/android-login.jpg`：Android 17 紧凑窗口首屏。
- `m3-evidence/ios-login.jpg`：iOS 26.5 紧凑窗口首屏。
- `m3-evidence/macos-login.jpg`：macOS 27 宽窗口首屏。
- `m3-evidence/ios-captcha-cropped.jpg`：只保留验证码图片与刷新按钮的裁剪证据，不包含账号或密码。

真实挑战验证已覆盖 SSO/CAS 重定向、Cookie 会话、HTML 隐藏字段解析、图片下载、跨平台图片解码、CAPTCHA 提交、MIS profile 解析和 AA module 10 联动。iOS 与 Android 最终都以非敏感状态布尔值确认成功；没有保存成功页或任何包含真实账号、姓名、密码、Cookie 的证据图。使用过的凭据、临时脚本和完整临时截图均已清除。

视觉验收发现 Android KMP 首版冷启动首帧约 23 秒。把 Ktor/OkHttp client 改为点击“获取验证码”后惰性创建后，ActivityManager 冷启动复测为 `TotalTime: 13065 ms`、`WaitTime: 16158 ms`。改动有效但仍明显偏慢；剩余 Compose/Kotlin/依赖冷加载需要后续启动性能专项，不能写成已经达标。

## 后续边界 / 不得夸大

- 没有实现安全凭据持久化；Keychain/Keystore 属于 M4。
- macOS 尚未做真实账号登录；M3 证明共享 CIO 引擎可编译和登录 UI 可运行，但真实桌面会话要在 Apple 端逐页验收继续验证。
- 当前“基础数据获取”只覆盖 MIS profile 与 AA 会话联动；成绩、课表、考试、作业等业务数据属于后续纵向切片。
- fixture 只证明当前解析规则，不能证明学校网页没有变化。
- Android 冷启动虽从约 23 秒降到本次 `TotalTime: 3508 ms` 的一次测量，但此前稳定复测仍有约 10 秒结果，不能仅凭单次数据宣称性能问题已解决。

下一步进入 M4，先完成 Apple Keychain 与 Android Keystore 的可读写/清除验证，再把凭据保存做成明确的用户选择；Android 冷启动优化作为独立性能项保留。
