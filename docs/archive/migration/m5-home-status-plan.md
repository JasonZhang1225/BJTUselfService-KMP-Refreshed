# M5 首页状态切片结果

> 基线：`v1.7.0@419313d` 及官方 Android App 的真实行为盘点  
> 实现范围：首页三张状态卡、缓存与刷新、邮箱跳转、校园卡外部应用边界、校园网默认浏览器边界
> 当前结论：共享实现与三端构建、自动化测试已通过；登录后首页真实数据、iOS 完美校园小程序落地页与校园网默认浏览器跳转均已验收

## 1. 与 Android 1.7.0 对齐的行为

首页状态继续请求 MIS 的 `https://mis.bjtu.edu.cn/osys_ajax_wrap/`，读取：

- `newmail_count`：新邮件数量；操作进入现有“邮箱”切片。
- `ecard_yuer`：校园卡余额；操作只确认并打开完美校园官方应用页面，不代填金额、不发起支付。
- `net_fee`：校园网余额；操作只用系统默认浏览器打开学校续费网页，登录、金额和付款仍由用户在浏览器中完成。

金额按服务端展示字符串保存，不在客户端擅自改精度或货币符号。低校园卡余额和校园网余额为零只产生提示，不自动触发外部动作。

## 2. 共享数据与状态

- `HomeStatusJsonParser` 对三个必需字段做严格 JSON 解析；缺字段、类型不符或非对象响应均作为格式失败。
- `HomeStatusRemoteDataSource` 只允许精确 MIS HTTPS 主机；CAS 重定向及 `401/403` 映射为会话失效，不把登录页误当状态数据。
- `CacheStoreHomeStatusLocalDataSource` 使用账号隔离的 `home_status_v1` 元数据缓存。长度前缀编码允许展示字符串包含分隔符；损坏缓存被安全拒绝。
- `DefaultHomeStatusRepository` 先恢复缓存再刷新；网络、会话或解析失败时保留旧快照，并向界面明确说明正在显示上次状态。
- `HomeScreenModel` 防止首次初始化重复加载，刷新失败不会清空已显示数据。

首页现为登录后默认入口。iPhone 使用纵向状态卡和应用壳顶部刷新；宽窗口使用横向三卡、页面标题与独立刷新按钮。邮箱操作复用既有受限 Cookie 网页切片，不另建旁路。

## 3. 平台动作边界

### 校园卡

- iOS 与 Android 按用户于 2026-08-01 指定的微信小程序跳转链接打开完美校园：`https://wxaurl.cn/RLEw5IMZRKl`；两端均交给系统默认浏览器，确认框明确说明这是外部小程序链接，App 不代填金额或发起支付。
- macOS 不跳转外部商店或网页；确认框显示同一链接生成的高对比度二维码，并提示使用手机微信扫描。
- 三端均先显示确认框，文案明确第三方应用负责充值，本应用不处理金额或支付。

当前没有可靠、公开且已验证的完美校园私有 URL Scheme，因此不猜测私有 scheme；移动端使用用户明确提供的公开小程序跳转链接，macOS 只显示该链接的二维码，也不伪装成本 App 已完成充值。

### 校园网

- 用户于 2026-08-01 明确要求三端直接在浏览器打开公开续费链接 `https://weixin.bjtu.edu.cn/pay/wap/network/recharge.html`，不要系统分享或剪贴板。
- iOS、Android 与 macOS 均复用平台 URL handler 打开系统默认浏览器；打开前显示本地确认框，明确后续登录、金额和付款都在浏览器完成，本应用不代填、不支付。
- 原 iOS `UIActivityViewController`、Android `ACTION_SEND`、macOS 剪贴板及未通过运行验收的 JNA `NSSharingServicePicker` 尝试均已删除，不保留死代码或误导文案。

## 4. 测试与构建证据

本切片原有 15 项测试，本轮再新增 3 项平台路由测试：

- 领域规则 2 项。
- JSON 解析 3 项。
- 远端协议 3 项。
- Repository 3 项。
- ScreenModel 2 项。
- Desktop 真实缓存重开/损坏处理 2 项。
- iOS/Android 浏览器链接与 macOS 二维码路由 3 项，另锁定二维码矩阵结构 1 项。

全量门禁结果：

- `:shared:desktopTest`：191 项，0 失败，0 错误，0 跳过。
- `:androidApp:assembleDebug`：通过。
- `:shared:linkDebugFrameworkIosSimulatorArm64`：通过。
- `:desktopApp:createDistributable`：通过。
- Xcode iPhone 17 Pro / iOS 26.5 Simulator build：通过；只有既有 ICU deployment warning。
- macOS `.app`：`codesign --verify --deep --strict` 通过，bundle id 为 `team.bjtuss.bjtuselfservice.desktop`，主可执行文件为 arm64。
- 2026-08-01 当前外部动作调整后的最新门禁：Desktop 237 项、iOS Simulator 222 项均零失败；Android Debug/Release、iOS framework 和 macOS 自包含应用全部构建成功。Xcode Simulator 宿主沿用上一轮成功证据，本次共享 framework 已重新链接。

## 5. Computer Use 与视觉验证

为避免在 CAS 风控期继续提交验证码，验证阶段临时注入了不含真实账号信息的三卡状态，完成后立即删除，并重新构建正式认证入口。验证结果：

- iPhone 三张卡均完整显示，校园卡确认框没有按钮截断或支付误导。
- 历史 iOS Share Sheet 与 macOS 剪贴板证据已被新的直接浏览器需求取代，不再作为当前产物能力。
- 最新 iPhone 17 Pro Simulator 登录态已确认卡片文案改为“在浏览器中打开续费网页 / 前往续费网页”；确认后 Safari 地址为学校 `weixin.bjtu.edu.cn`，没有出现分享面板或剪贴板反馈，返回 App 后登录态保留。
- iOS 完美校园确认后进入 `wxaurl.cn` 的“完美校园·小程序”落地页并显示“前往微信打开”；用户确认该落地页正确，安装微信时会继续唤起小程序。Simulator 未安装微信，因此运行证据只到落地页，不宣称本次已在微信内打开。
- macOS 二维码弹层与 Android 默认浏览器仍待各自独立运行复验；源码路由和三端构建不能替代运行证据。
- 临时预览标记与示例值源码扫描为 0；正式 iOS 与 macOS 产物重启后均回到真实登录入口。

这些证据只证明布局与平台动作边界，不冒充真实 MIS 会话或真实余额验收。

## 6. 剩余验收

真实会话已补齐三个字段的解析/缓存/手动刷新和 iOS 邮箱 SSO。当前只保留：

1. macOS 完美校园二维码弹层、Android 完美校园默认浏览器跳转，以及 macOS/Android 校园网默认浏览器跳转的独立运行复验；均已有源码与构建证据，iOS 已真实通过。
2. 会话自然失效时旧快照保留和重新登录提示。

这些边界不影响当前代码和构建结论，但不能在运行复验前把浏览器跳转写成三端实机通过。
