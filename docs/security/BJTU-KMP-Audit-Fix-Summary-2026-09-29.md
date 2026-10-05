# 1.8.0 安全 / 代码味道复查：修了什么

- **日期**：2026-09-29
- **对象**：KMP 1.8.0（HEAD `413a47a`，Build 21）
- **分支**：`audit/1.8.0-2026-09-29`（从 `main` 检出）
- **状态**：源码修改与相关自动化测试/平台编译已完成；本分支修复提交并推送到个人远端。**所有设备相关行为仍待真机检验**，代码测试和编译不代表真机验收。

完整条目与对照表见：

- [安全复查](BJTU-KMP-Security-Audit-2026-09-29.md)
- [代码味道复查](../refactor/BJTU-KMP-CodeShit-Audit-2026-09-29.md)

本文只讲「查到了什么、改了什么、没改什么」。

---

## 本轮设备验收边界

本分支代码改动都还待对应设备/系统实机检验，包括 Android WebView 换账号后重新打开、iPhone Keychain 卸载重装与 CAS/WebView 登出、iOS/macOS 课程日历从全学期改为窄周段后的精确替换，以及相关界面/会话行为。此前已有的独立 Mac 全量清理验收只覆盖那项既有场景，不代表本分支其他改动已真机验证。

源码测试或编译通过不等同真机验收；本轮未写入用户真实系统日历。课程日历实测应使用独立测试日历，并确认手工事件与考试安排保留。

---

## 一句话

上一轮 P1/P2（WebView 登出清理、桌面缓存 AES-GCM、CAS 注销、iOS 重装清 Keychain、ONNX、Wrapper SHA-256）都还在，KMP **没有证书校验回退、没有新增高危**。

本轮真正动手的，是漏审的中低危：Android Cookie 罐跨账号残留、CI 还在看已删的 `Liquid`、发布守卫测试和真实 workflow 对不上、HTTP 客户端先自动跟随再检查白名单（会把自定义 `sessionid` 头带到外域）、WebView 导航只看 host、iOS Cookie 漏 `Secure`。

代码味道方面：成绩页拆包保持（3811 → 1213 行）；新热点是 `AuthenticatedAppShell.kt` 2000 行。本轮**故意不拆壳**。

---

## 查完之后的结论

| 类别 | 结论 |
|---|---|
| 高危（KMP） | 无 |
| TLS / trustAll | 三引擎都是默认工厂；明文只锁在已授权的智慧教学两 origin + 教室人数第三方 origin |
| 凭据 | 四平台仍走 Keystore / Keychain / DPAPI；无明文密码落盘回退 |
| 依赖（OSV.dev，当日） | OkHttp 5.3.2、Ktor 3.5.1、ONNX 1.30.0、SQLDelight 2.3.2 等精确坐标 **0 条公开漏洞** |
| 冻结根 `app/` | 历史 trustAll + DataStore 明文密码仍在；按仓库纪律不改，也不打进当前 KMP 包 |

学校当前握手链路上，HTTPS→HTTP 降级会被 Ktor 停住，所以「自动跟随把会话头带出学校域」是**条件风险**，不是网上已经抓到的外泄。修的是：白名单必须在发出下一跳之前生效，不能事后看 `finalUrl`。

---

## 本轮修掉的项

### M1 · Android WebView：先清空 Cookie 罐，再注入本页会话

**问题**：Android `CookieManager` 是进程级持久罐。每次打开邮箱/网页只 `setCookie`，不先清。登出路径会清，但崩溃、划掉、冷启动后换账号时，上一账号没被覆盖的域 Cookie 可能跟进新 WebView。iOS 已用非持久 `WKWebsiteDataStore`，桌面不内嵌 WebView。

**改法**：`removeAllCookies` 的回调里再 `setCookie` → `flush` → `loadUrl`。不能同步清完立刻写——异步回调会把刚注入的 Cookie 清掉。

**文件**：[`SchoolWebView.android.kt`](../../multiplatform/shared/src/androidMain/kotlin/team/bjtuss/bjtuselfservice/shared/webview/SchoolWebView.android.kt)

**未验**：真机换账号再开邮箱。

---

### M2 · 安全 CI 改看 `main`

**问题**：`kmp-security-check.yml` 的 `on.push.branches` 仍是已删除的 `Liquid`。直接推 `main` 不会跑 Android 编译门禁和 macOS 安全测试；走 PR 的 path filter 还有效。

**改法**：`Liquid` → `main`。测试断言工作流里不能再出现 `branches: [Liquid]`。

**文件**：[`.github/workflows/kmp-security-check.yml`](../../.github/workflows/kmp-security-check.yml)

---

### M3 · 发布守卫测试对齐真实 `release.yml`

**问题**：测试还在要求 `!contains(github.ref_name, 'Liquid')`，workflow 实际已经是 `github.ref_name == 'v1.7.0'`（只打冻结 Android 那一个标签）。失败的供应链回归测试等于没守住。

**改法**：断言改成精确的 `v1.7.0` 条件。不为了变绿把 Liquid 字符串填回去。

**文件**：[`SecurityReleaseConfigTest.kt`](../../multiplatform/shared/src/desktopTest/kotlin/team/bjtuss/bjtuselfservice/shared/packaging/SecurityReleaseConfigTest.kt)

---

### M4 · 握手 / 教室人数：关掉自动跟随，白名单看到每一跳

**问题**：Ktor 会话客户端默认 `followRedirects=true`。作业、课件、课表把 `execute()` 传进 `followSmartHandshakeRedirects`。helper 本身逐跳校验，但同协议 302 和 HTTP→HTTPS 会在 helper 看到 3xx 之前被跟掉，`maxHops` 和 origin 白名单只约束最终 URL。教室人数明文 GET 同理：先跟随再看 `finalUrl`，中间人可以 302 到任意主机。教室请求不带学校 Cookie，但白名单承诺被破坏。

**改法**：

- 握手调用点一律 `executeWithoutRedirects`
- 教室 3xx 直接失败，不发第二跳
- helper 注释写明：调用方必须传入 no-follow execute

**文件**：

- [`SmartPlatformEndpoint.kt`](../../multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/data/homework/SmartPlatformEndpoint.kt)
- [`HomeworkRemoteDataSource.kt`](../../multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/data/homework/HomeworkRemoteDataSource.kt) / [`CoursewareRemoteDataSource.kt`](../../multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/data/courseware/CoursewareRemoteDataSource.kt) / [`CourseScheduleRemoteDataSource.kt`](../../multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/data/course/CourseScheduleRemoteDataSource.kt)
- [`ClassroomRemoteDataSource.kt`](../../multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/data/classroom/ClassroomRemoteDataSource.kt)

**测试**：出界 Location 零后续请求、第 11 跳停住、同协议 HTTPS 一跳可见、教室 302 不发第二跳。课表周查询仍走普通 `execute`（不带自定义 `sessionid` 头）。

---

### M5 · 作业 / 课件业务请求：`sessionid` 头不得跟随到外域

**问题**：比握手更具体。`smartRequest` 在请求上加自定义头 `sessionid`（不是 `Authorization`）。Ktor `HttpRedirect` 跨 authority 只剥 `Authorization`。平台若 302 到 HTTPS 外域，这个头会跟过去；事后检查 `finalUrl` 撤不回已经发出的第二跳。

**改法**：`smartRequest` 改 `executeWithoutRedirects`；3xx 视为会话失效，清本地会话状态，不发第二跳。

**测试**：假 302 到 `evil.example` 时，请求列表不含该主机。

**未在网上观测**：学校当前作业/课件接口是否真的发跨域 302。

---

### L12 · 应用内网页导航必须是 https

**问题**：`SchoolWebDomainPolicy.isSchoolHost` 以前只看 host，会放行 `http://mis.bjtu.edu.cn` 或 `file://mis.bjtu.edu.cn/...`。`WebPageRequest` 构造时已经要求 `https://`，但 WebView 后续导航走的是 host 检查。

**改法**：先解析 URL，协议不是 https 直接拒绝，再对 host 做学校白名单。

**文件**：[`WebPageModels.kt`](../../multiplatform/shared/src/commonMain/kotlin/team/bjtuss/bjtuselfservice/shared/webview/WebPageModels.kt)

---

### L17 · iOS WebView Cookie 补 `Secure`

**问题**：共享模型默认 `secure=true`，Android 会写 `; Secure`；iOS 创建 `NSHTTPCookie` 时只设了 name/value/domain/path。ATS 挡明文学校域，实际外泄未上设备确认。

**改法**：`cookie.secure` 为真时写入 `NSHTTPCookieSecure = TRUE`。

**文件**：[`SchoolWebView.ios.kt`](../../multiplatform/shared/src/iosMain/kotlin/team/bjtuss/bjtuselfservice/shared/webview/SchoolWebView.ios.kt)

---

## 代码味道：查了，本轮不拆

| 项 | 状态 |
|---|---|
| `GradeScreen` 万物文件 | **保持已修**：3811 → 1213 行，包内只剩成绩 |
| `AuthenticatedAppShell.kt` | **新热点**：2000 行，主函数约 1786 行。不是错包，是拆出来之后继续长 |
| `SectionDestination` | 约 534 行局部 `when`，仍捕获全部 feature model |
| 课表 / 邮箱 / 首页 / 作业 Screen | 仍在 1400–1700 行；结构已分化，不为拆而拆 |
| 桌面日历 `jsonEscape` 重复 | 半小时可去重，本轮没动 |
| 真实 `TODO`/`FIXME` | 0（命中的 TODO 是邮箱「待办邮件」文件夹名） |

给上游看工程时：数据层、测试纪律、安全边界比巨型 UI 文件更有说服力。壳层要在 Issue 里诚实写上，路线是「先拆布局骨架、后拆路由表」，不要假装已经整洁。

---

## 改了哪些文件

工作区相对 `413a47a`，**未提交**：

| 路径 | 对应项 |
|---|---|
| `.github/workflows/kmp-security-check.yml` | M2 |
| `SchoolWebView.android.kt` | M1 |
| `SchoolWebView.ios.kt` | L17 |
| `WebPageModels.kt` + `WebPageModelsTest.kt` | L12 |
| `SmartPlatformEndpoint.kt` + Test | M4 |
| `HomeworkRemoteDataSource.kt` + Test | M4 / M5 |
| `CoursewareRemoteDataSource.kt` + Test | M4 / M5 |
| `CourseScheduleRemoteDataSource.kt` + Test | M4 |
| `ClassroomRemoteDataSource.kt` + Test | M4 |
| `SecurityReleaseConfigTest.kt` | M2 / M3 / M1 顺序 / 握手 no-follow 守卫 |
| `docs/security/BJTU-KMP-Security-Audit-2026-09-29.md` | 只读报告 + 实施记录 |
| `docs/refactor/BJTU-KMP-CodeShit-Audit-2026-09-29.md` | 味道报告 |
| `memory.md` | 工作记忆 |

冻结根 `app/` **零改动**。

---

## 验证过什么

在 `multiplatform/` 下跑过（均 BUILD SUCCESSFUL）：

- `:shared:desktopTest --tests '*SecurityReleaseConfigTest'`
- `:shared:desktopTest --tests '*SmartPlatformEndpointTest'`
- `:shared:desktopTest --tests '*HomeworkRemoteDataSourceTest'`
- `:shared:desktopTest --tests '*CoursewareRemoteDataSourceTest'`
- `:shared:desktopTest --tests '*CourseScheduleRemoteDataSourceTest'`
- `:shared:desktopTest --tests '*ClassroomRemoteDataSourceTest'`
- `:shared:desktopTest --tests '*WebPageModelsTest'`
- `:shared:compileAndroidMain`

**没跑**：全量 `:shared:desktopTest`、iOS 模拟器/真机、Android 真机换账号开邮箱、网上真实 302 链。

---

## 明确没做的

源码层留下、不假装已修：

- L1 移动端学业缓存明文 SQLite（桌面已 AES-GCM；有意识的平台差异）
- L13 iOS 缓存 WAL 若本轮才出现、下次冷启动前可能进备份
- L14 全量清理一半失败时内存会话仍在
- L15 Android debug smoke 用生产 Keystore alias
- L16 Windows DPAPI 输出缓冲未 `LocalFree`
- L18 `executeWithoutRedirects` 接口仍默认回落到 `execute()`（生产 Ktor 已覆盖；新假客户端必须显式 override）
- 成绩单 URL 路径段模板、教室安排旧 APK 里的静态 token（冻结工程，KMP 未复制）
- `AuthenticatedAppShell` 拆分、桌面 `jsonEscape` 去重
- git commit / tag / push / 上游 Issue / PR

设备验收仍缺（不阻塞源码，但不能写成「已验收」）：

- iPhone 卸载重装后 Keychain 是否清空
- 带登录态的 CAS + WebView 登出
- Android 真机换账号后再开邮箱
- M5 在学校真实 302 链上是否会被打到

---

## 建议的下一步（等你点头）

1. **提交本分支**（或继续改）。提交前给你看完整信息和文件列表，不擅自 commit。
2. 对照上游 `HFDLYS/BJTUselfService` 写差异 / 优势，起草 Issue，先给你看再发。
3. 有设备时补上面那几条验收。
