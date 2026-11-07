# 平台原生导航迁移计划

## 目标

把页面层级动画从共享 Compose `NavDisplay` 移交给平台导航容器，同时保留 KMP 的登录、网络、缓存、ScreenModel 与页面内容。迁移后：

- Android 底部五个一级入口仍在根 Activity 内即时切换；“更多”子页与教室楼宇详情由系统 Activity 栈负责，使用系统 cross-activity / predictive-back 动画。
- iOS 底部五个一级入口由并列 tab 语义承载，不播放左右 push；二级、三级页面由 Swift `UINavigationController` push/pop，系统边缘返回可取消、可跟手。
- macOS 侧栏选择保持桌面式即时切换；需要堆栈层级的详情交给 SwiftUI/AppKit 导航容器。当前 JVM Compose Desktop 包保留到新的 Swift macOS 宿主可运行。

## 强制语义

| 操作 | Android | iOS | macOS |
| --- | --- | --- | --- |
| 首页 / 课程表 / 成绩 / 作业 / 更多 | 同一 Activity 即时切换 | 原生 tab 即时切换 | 侧栏选择即时切换 |
| 更多 → 考试/课件/教室/邮箱/下载/设置 | 新 Activity | `UINavigationController.pushViewController` | 内容区原生导航层级 |
| 教室 → 楼宇详情 | 再压入 Activity | 再 push UIViewController | 再压入详情 |
| 返回 | 系统 Activity back stack | 系统 pop 与 leading-edge gesture | 系统 back/侧栏选择 |
| Reduce Motion | 由系统动画倍率处理 | UIKit/SwiftUI 自动遵循系统设置 | AppKit/SwiftUI 自动遵循系统设置 |

## 架构切分

### 共享层

1. 新增 `AuthenticatedSession`，集中保存登录后的 profile、各 ScreenModel、偏好、文件网关、刷新/退出能力。
2. `LoginRoute` 仍唯一拥有登录状态与 Repository 创建；登录后发布 `AuthenticatedSession`，退出时撤销。
3. 把当前 `AuthenticatedAppShell` 内的目的地渲染器提取为可复用的 `AuthenticatedDestination`。
4. 根 shell 只管理一级 tab 和“更多”根；平台原生导航可用时，不再把二级页压入共享 `NavDisplay`。
5. 平台宿主不可用、宽屏或测试环境继续走共享回退路径，确保每步可回退。

### Android

- 新增应用进程内 `AndroidAuthenticatedSessionRegistry`，只保存当前根 Activity 发布的会话引用，不落盘、不跨进程。
- 新增 `NativeDetailActivity`，通过受控 route ID 渲染 `AuthenticatedDestination`。
- `MainActivity` 提供原生导航回调；只对二/三级页面调用 `startActivity()`。
- Detail Activity 不拦截系统返回；Android 15+ 由系统提供 cross-activity predictive back。根 Activity 继续负责登录和底栏。
- 进程重建后若 registry 不存在，Detail Activity 立即关闭回根页面，不伪造会话。

### iOS

- Swift 新增 `NativeNavigationController`，根控制器仍是 Kotlin `ComposeUIViewController`。
- Kotlin 登录成功后把 `AuthenticatedSession` 回调给 Swift 宿主。
- 点击二/三级入口时，Swift 创建对应 Kotlin `AuthenticatedDestinationViewController` 并执行系统 push。
- Compose 二级页的返回按钮调用 Swift pop；系统交互返回直接由 `interactivePopGestureRecognizer` 管理。
- Swift 导航栏暂时隐藏，保留现有 Compose 标题栏；后续逐页迁成 Swift 时可直接替换 destination controller，不改变导航所有权。

### macOS

- 当前 `desktopApp` 是 JVM/Skiko 应用，不能被 SwiftUI 直接当作 KMP framework 嵌入。
- 新增 `macosArm64` Kotlin/Native framework 与 `macosApp` Swift 宿主前，旧 desktopApp 保持可运行。
- 先迁共享业务和会话导出，再以 SwiftUI `NavigationSplitView`/`NavigationStack` 承载页面；不使用手机式全屏滑动模拟桌面导航。

## 文件范围

- 共享：`App.kt`、`LoginScreen.kt`、`feature/grade/GradeScreen.kt`，新增 session/native-navigation 文件。
- Android：`MainActivity.kt`、`AndroidManifest.xml`，新增 `NativeDetailActivity.kt` 与 session registry。
- iOS：`MainViewController.kt`、`ContentView.swift`，必要时更新 Xcode project 文件。
- macOS：新增独立 `macosApp/` 与 `macosMain` actual；旧 `desktopApp/` 只做兼容适配，不删除。

## 验证

1. Android：一级 tab 无左右动画；更多→课件为系统 Activity 动画；返回手势显示前一 Activity；进程重建安全回根。
2. iOS：一级 tab 无左右动画；更多→课件由 UIKit push；按钮返回和 leading-edge 手势 pop；取消手势仍停留详情。
3. macOS：侧栏切换无手机动画；详情层级由 SwiftUI/AppKit 管理；关闭窗口不清会话。
4. 命令：Android debug、iOS Simulator Xcode build、desktopTest；macOS Swift 宿主建立后补 `xcodebuild`。

## 回退

- 共享 `NavDisplay` 的回退导航在 Android/iOS 原生宿主稳定前保留；平台驱动为空时继续可用。
- 不删除旧 JVM macOS 应用，直到 Swift 宿主功能和分发验证完成。
- 不修改冻结根 Android 工程，不提交、不推送。

## 实施与验证记录（2026-08-05）

### 已实施

- 共享层：新增 `AuthenticatedSession`（集中登录后 profile、各 ScreenModel、偏好、文件网关、退出能力），`LoginRoute` 唯一发布与撤销会话；`AuthenticatedDestinationApp(routeId)` 渲染单个二级目的地；`isNativeDetailRoute` 白名单仅含 EXAMS/COURSEWARE/CLASSROOMS/CLASSROOM_DETAIL/MAILBOX/CALENDAR/REPORT_CARD_DOWNLOAD/SETTINGS。`nativeNavigationEnabled` 时紧凑端二级页不再压共享 `NavDisplay`（转场 None）；底栏五个一级 tab 只清栈即时切换，永不触发原生 push（底栏集合与 MoreGroupSections 除 MORE 外不相交，`navigateToSection` 已核对）。
- Android：新增 `NativeDetailActivity`（`enableOnBackInvokedCallback=true`，系统 cross-activity 与 predictive-back 动画，不拦截系统返回；`onStart/onStop` 观察会话，会话撤销即 finish）与进程内 `AndroidAuthenticatedSessionRegistry`（不落盘；registry 缺失时 detail 立即 finish 回根，不伪造会话）。`MainActivity` 发布会话、对二级路由 `startActivity`，`isFinishing` 时清空 registry，并预热 WebView 内核。
- iOS：Swift 新增 `NativeNavigationController`（隐藏导航栏，`UINavigationControllerDelegate` 暂无自定义方法）。登录态经 `onAuthenticatedSessionChanged` 传入、登出 `popToRootViewController(animated: false)`；二级入口由 `MainViewControllerKt.NativeDestinationViewController` 创建 Kotlin Compose 控制器并系统 push，`restorationIdentifier` 去重防连点；Compose 返回箭头接 `onCloseNativeRoute` → `popViewController(animated: true)`；leading-edge 返回由 UIKit `interactivePopGestureRecognizer` 提供。
- macOS：维持 JVM `desktopApp` 侧栏即时切换；SwiftUI 宿主未开始。

### 迁移中修复的两个 iOS 真实缺陷

1. **iOS 26 启动/转场闪退**：崩溃报告为 Compose iOS `AccessibilityElement.cachedProperties` 的 `EXC_BAD_ACCESS`（`KERN_INVALID_ADDRESS at 0x18`）——辅助功能客户端（含 Computer Use 自动化 AX 查询）在原生 push/pop 移除宿主控制器后继续查询已失效的 Compose 无障碍元素。查证 CMP 1.11.1 klib（strings）没有 `accessibilitySyncOptions`/`accessibilityEnabled` 公开 API，Kotlin/Native UIKit 绑定也不暴露 `accessibilityElementsHidden`；最终在 Swift 侧对所有 Compose 宿主视图设 `view.accessibilityElementsHidden = true`（与用户"跳过无障碍专项"的决定一致）。代价：AX 自动化读不到 App 内容，iOS 界面验证只能靠截图/目视。修复后模拟器运行无新崩溃报告。
2. **状态栏区域转场截开**：SwiftUI 宿主此前只对底边 `ignoresSafeArea`，导航控制器被约束在状态栏下方，push 页面盖不住状态栏、转场被截成两截。改为四边全屏 `.ignoresSafeArea(.all)`；同步移除 `CompactAppTopBar` 中"iOS 不加 `statusBarsPadding`"的 2026-08-04 特判（该特判建立在旧宿主假设上），紧凑登录页仅 iOS 补 `statusBarsPadding` 保持原居中布局；宿主底色与页面背景对齐防深色首帧闪白。Android/macOS 行为不变。

### 验证状态

- 通过：`:androidApp:assembleDebug`、`:shared:desktopTest`、iOS Simulator `xcodebuild` 构建/安装/启动（进程存活、无新崩溃报告）。
- 用户目视：iOS push 转场"基本没有问题了"（状态栏修复前）；状态栏覆盖修复与无障碍崩溃修复后的完整转场、底栏无动画切换、Android 系统 Activity 动画，均待用户目视终验。
- 工具边界：macOS 录屏隐私限制下 `simctl io screenshot` 与 Computer Use 截图读到黑帧（App 实际正常运行）；Computer Use 坐标无法可靠起始于设备屏幕边缘。iOS 转场与边缘手势的最终验收只能用户目视或真机录屏。
