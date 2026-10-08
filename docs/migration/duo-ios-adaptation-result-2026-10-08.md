# iOS Duo 与横屏适配实施记录

日期：2026-10-08。分支：`codex/Duo`，基线为远端 `mine/codex/Duo` 的 `73186ed`。已阅读 Windows 的 `duo-landscape-adaptation-research-2026-10-08.md` 和 `duo-bugfix-result-2026-10-08.md`。

## 实现

- iOS 27+ 的 iPhone 使用真正的 `UITabBarController` / `UITab`，各入口保留独立的 `UINavigationController`；系统决定竖向动作栏与 tab sidebar，sidebar 使用 tile 布局。iOS 26 的原有玻璃壳和 iPad 共享壳保留。
- 新系统的正文由 `SafeAreaComposeHost` 将 Compose 子控制器约束到 UIKit 当前 `safeAreaLayoutGuide`。背景全出血；前景同时避开导航栏、侧栏、摄像头与 Home Indicator，不假设导航栏永远位于顶部。
- 新系统不再安装自绘顶部玻璃层和居中标题；普通图标动作改为标准 `UIBarButtonItem`，溢出菜单保留动作文字。共享层不重复预留顶部 / 底部栏高度。
- 已由原生宿主持有的页面，旋转、合上或展开后继续使用原生子路由，避免 UIKit 与 Compose 分别拥有半份返回栈。共享宽屏布局不会重复绘制导航侧栏。
- 旧壳 / 登录和原生目的地的 iOS 正文消费左右安全区；原生横向导航栏的顶部占位使用实测栏底高度。
- 两种周课表网格都使用有限滚动视口和明确内容高度。七行最低 56 dp，随字号放大；短视口纵向滚动，横向翻周保持独立。普通 iPhone 的低于 300 dp 视口还让工具区随正文一起滚动，避免固定工具区挤掉网格。
- 同步详情有明确的纵向滚动容器。普通短信息 sheet 的 wrapper 提供滚动；课程 / 成绩详情声明自己拥有滚动，避免嵌套无限高度。移除 sheet 重复的固定 44 点顶部留白，原生 sheet 正文消费左右安全区。

## 验证环境与隔离

Xcode 27.1（27A9275），iOS Simulator SDK 27.1。Duo 使用现有 iOS 27.1 模拟器，普通 iPhone 17 Pro 使用现有 iOS 27.0 模拟器。

验证应用标识为 `team.bjtuss.bjtuselfservice.kmp.layoutprobe`，与已登录旧版不同。`--layout-smoke` 使用虚构资料、独立数据库、无凭据存储和拒绝联网的 transport，再交给生产导航容器与生产页面。没有安装覆盖旧版、清理其缓存 / Keychain，或调用其登录流程。

`--sheet-smoke` 使用生产同步详情组件和 19 条合成状态，末项为“滚动验证末尾”，用于验证真实溢出后的滚动，而非仅检查弹窗能打开。

## 已完成检查

- iOS arm64 模拟器 Debug 编译通过，最低部署版本仍为 iOS 16。
- `NativeShellBridgeTest`、`DestinationBottomClearanceTest`、`CourseScheduleScreenModelTest`：58 项，失败 0，错误 0。
- `git diff --check` 通过。
- Duo 展开：只有一套系统 sidebar；课程表正文与左右系统栏分开，实际滑到第七节 21:00。
- Duo 外屏横屏：课程网格保留可读行高，实际下滑到第七节。
- 应用 → 设置：系统侧边栏显示返回箭头；合上后仍保留设置页，点击返回回到应用目录。
- 课程详情：半屏原生弹窗正文、关闭和末项均可见。
- 首页同步详情：外屏横屏时正常显示全部七个真实模块状态与关闭入口。
- 普通 iPhone 17 Pro（iOS 27.0）：灵动岛在左右两侧的横屏均避开正文，工具区可随正文滚走；两侧均实际滑到第七节 21:00，旋回竖屏恢复完整七行。
- 普通 iPhone 17 Pro：横屏同步长弹窗实际滚到第 19 条“滚动验证末尾”，原生标题和关闭入口仍固定可见。

截图和构建日志位于 `.artifacts/duo-ios-20261008/`、`.artifacts/duo-build-final.log`，不提交图片。

## 限制

iOS 27.0 的冷启动和安装最初耗时较长，最终启动成功并完成独立离线壳验证。旧系统 / iPad 实机以及部分折叠 reserved regions 的完整矩阵尚未完成；不把 Duo 截图写成这些设备已经实测通过。

本轮解决基础导航、安全区和短视口滚动。折叠中部的双面板 / division 布局属于调研文档中的后续增强，未引入。
