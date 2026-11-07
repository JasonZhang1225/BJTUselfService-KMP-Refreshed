# M6 macOS 窗口生命周期结果

> 范围：关闭窗口、Dock/系统重新激活、应用退出与数据库清理  
> 结论：实现、自动化测试、三端构建和 Computer Use 真实生命周期验收通过

## 1. 行为定义

macOS 的应用进程、窗口和账号会话现在是三个不同生命周期：

- 点击窗口左上角关闭按钮：只隐藏窗口，不退出进程，不销毁 Compose 树，不清除表单、内存 Cookie、账号或缓存。
- 从 Dock 或系统重新激活应用：重新显示并聚焦原来的窗口，继续使用同一个 Compose 树和内存状态。
- 使用系统 App 菜单“退出”或 `⌘Q`：真正结束应用；`application` 返回后才关闭 SQLDelight/JDBC 数据库。
- 退出账号仍只由应用内“退出并清除登录信息”触发，继续负责内存会话、安全凭据和当前账号缓存清理；关闭窗口不会复用这条危险路径。

## 2. 实现方式

`DesktopWindowLifecycle` 只持有当前原生窗口的窄接口：

- `hide()` 隐藏窗口但不移除 Compose `Window`。
- `showAndFocus()` 重新显示、置前并请求焦点。
- `attach/detach` 防止窗口销毁后收到过期系统事件。

桌面宿主通过 JDK `java.awt.desktop.AppReopenedListener` 接收 macOS 的重新打开事件。官方 Java API明确说明，这个事件用于“应用没有打开窗口时再次显示窗口”的场景。实现没有通过条件分支销毁 `Window`，因此 `LoginRoute`、ScreenModel、Repository 和 Ktor 会话仍留在原 Compose 树中。

缓存数据库不再在 `onCloseRequest` 中关闭。`main()` 使用 `try/finally`，只有 Compose application 真正结束时才调用 `CacheStore.close()`，避免隐藏窗口后仍运行的页面访问已关闭数据库。

## 3. 自动化证据

新增 3 项 Desktop 生命周期测试：

1. 关闭会隐藏已挂载窗口，并保留同一个窗口持有的未保存状态。
2. 重新打开会显示并聚焦同一个窗口。
3. 已 detach 的旧窗口不会收到后续重新打开事件。

全量门禁结果：

- Desktop：196 项测试，0 失败，0 错误，0 跳过。
- Android debug APK：通过。
- iOS Simulator arm64 framework：通过。
- macOS distributable：通过；严格签名校验成功，主程序为 arm64。
- Xcode iPhone 17 Pro / iOS 26.5 Simulator host build：通过。
- 真正退出后 SQLite：`quick_check=ok`，`user_version=2`。

## 4. Computer Use 真实验收

在最新自包含 macOS `.app` 中执行：

1. 在登录页勾选“在此设备上安全保存登录信息”，只作为无敏感、未持久化的内存状态标记。
2. 点击原生关闭按钮。
3. `list_apps` 确认 bundle id `team.bjtuss.bjtuselfservice.desktop` 仍为 `isRunning=true`。
4. 重新激活同一 bundle；窗口重新出现。
5. “登录成功后将保存到系统安全存储。”说明仍存在，证明勾选状态和同一个 Compose 树没有被销毁。
6. 取消测试勾选，恢复初始界面。
7. 使用 `⌘Q`；等待退出完成后确认 `isRunning=false`，随后数据库完整性检查通过。

该验证没有读取凭据、没有获取或提交 CAPTCHA、没有登录、没有写入安全存储。

## 5. 边界

- 当前是单窗口应用；若后续加入独立详情窗口，需要把“最后一个业务窗口关闭”与“应用退出”继续分开建模。
- 系统强制终止和崩溃不能保证执行 `finally`；SQLite 自身恢复仍是异常终止保障，本切片只承诺正常退出路径。
- 登录后真实 MIS/AA 会话跨关窗保留仍需在 CAS 风控解除后补一次端到端证据；当前源代码和无敏感状态验证证明 Compose/transport 不会因关窗主动销毁，但不冒充真实服务器会话成功。
