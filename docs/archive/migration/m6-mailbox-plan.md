# M6 校内邮箱与网页会话桥接

日期：2026-07-30  
基线：官方 `v1.7.0@419313d` 的邮箱页面与 M0 真实行为盘点  
入口：`https://mis.bjtu.edu.cn/module/module/26/`

## 基线与平台差异

- 原 Android App 把 OkHttp 中对 MIS URL 生效的 Cookie 同步到 WebView，再打开 MIS module 26；真实邮箱页面是桌面式多栏布局。
- iOS 使用 WKWebView 内嵌学校页面；非学校域名链接分流到系统浏览器。
- macOS 首版不引入 JCEF。页面交给系统浏览器，App Cookie 不注入外部进程；若浏览器没有学校会话，用户需要在学校页面重新登录。此限制必须明示，不能把 Cookie 拼进 URL 或写入磁盘换取“免登录”。

## 实现

- `SchoolHttpTransport.sessionCookiesFor(url)` 只导出内存 Cookie storage 中对目标 URL 生效的 Cookie；`SchoolSessionCookie.toString()` 永远隐藏值，退出登录会替换整个 Cookie storage。
- `MailboxScreenModel` 只请求 MIS module 26 对应 Cookie，并把 Domain 收窄为精确 `mis.bjtu.edu.cn`；空会话或读取异常进入可重试失败态。
- 学校网页白名单加入实际邮箱导航主机 `mail.bjtu.edu.cn`；Cookie 注入仍只允许与初始页面完全相同的明确白名单 host，拒绝 `.bjtu.edu.cn` 之类过宽父域。
- host 解析改用 Ktor `Url`，避免手写切割在端口、userinfo 等边界下误判。
- iOS 等全部 Cookie 异步写入 WKWebsiteDataStore 后才加载初始请求，消除“偶尔先发请求、后有 Cookie”的竞态。
- 登录后应用壳新增“邮箱”入口；macOS 侧栏入口区域可独立滚动，账号卡和退出按钮保持固定，不因九个入口超出窗口而不可达。
- Settings 的 GitHub 动作改用 Compose `LocalUriHandler`，修复 Android actual 原先无操作的问题。

## 测试与构建

- 新增 5 项测试：邮箱请求精确 Cookie 收窄、空会话、Cookie storage 异常、过宽父域拒绝、邮箱 host 白名单。
- 全量 `:shared:desktopTest`：**176 项，零失败、零跳过**。
- `:androidApp:assembleDebug`、`:shared:linkDebugFrameworkIosSimulatorArm64`、`:desktopApp:createDistributable` 同次构建成功。
- Xcode 26.6 / iPhone 17 Pro iOS 26.5 arm64 未签名宿主构建成功；最新正式 App 安装启动后登录首屏完整。

## 真实结果与边界

- 2026-08-01 iOS 有效 MIS 会话已进入内嵌邮箱，用户肉眼确认页面显示正常且可用；这补齐了 MIS → WKWebView 邮箱 SSO 的实际证据。
- 本轮没有点击邮箱中的外部链接，因此非学校域名分流仍只由白名单测试覆盖，不能写成实机通过。
- 邮箱网页本身的手机布局属于学校站点能力；首版只保证安全容器和导航，不注入页面脚本改造第三方 DOM。
- macOS 系统浏览器中的既有学校会话状态不可由 App 控制，也不宣称免登录。
