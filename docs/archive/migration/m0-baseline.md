# M0 基线与工具链记录

> 日期：2026-07-29 至 2026-07-30  
> 状态：主体完成；官方 v1.7.0 已登录并完成一级页面与核心二级流程盘点，未执行会改变外部或重要本地状态的动作

## 1. 基线身份

### 发布与功能基线

- Release：`v1.7.0`
- Release 状态：正式发布，非草稿、非预发布
- Release 目标提交：`419313d2382379e8ab109e9ae6668d223bc4ebdf`
- 本地隔离源码：`.artifacts/baseline-v1.7.0/source/`
- 本地官方 APK：`.artifacts/baseline-v1.7.0/BJTUSelfService-1.7.0_arm64-v8a.apk`
- APK SHA-256：`c48b4ddb8f2fdbbb30b546e9f67d34a12fdf4041861dc27b9318c70d428dad3f`
- APK 大小：`156515923` bytes

APK 清单核验结果：

- applicationId：`team.bjtuss.bjtuselfservice`
- versionName：`v1.7.0`
- versionCode：`8`
- minSdk：`28`
- targetSdk / compileSdk：`34`
- 应用名称：`交大自由行NEO`
- ABI 发布资产：`arm64-v8a`

源码压缩包由 GitHub `v1.7.0` 标签下载。该标签是 annotated tag，下载目录前缀包含标签对象短 SHA `552a108`；远端 Release 元数据和 compare 结果确认其发布目标提交为 `419313d`，且 `419313d` 是 `9d8da18` 的后代，领先 9 个提交、没有分叉。

### 本地迁移工作区

- 当前分支：`ZJG`
- 当前 HEAD / 分支创建点：`9d8da18`
- 当前工作区存在用户已有的未提交工具链和编译修复，不能把本地构建结果描述为纯 `9d8da18`。
- KMP 工作不得 merge、rebase、cherry-pick 或覆盖这些改动。
- 发布行为参考以官方 1.7.0 APK为准；源码参考以隔离目录为准；新实现只进入后续 `multiplatform/` 工程。

## 2. macOS 与 Apple 工具链

- Mac 架构：Apple Silicon `arm64`
- macOS：27.0（Build `26A5388g`）
- Xcode 路径：`/Applications/Xcode.app/Contents/Developer`
- Xcode：26.6（Build `17F113`）
- 另有 `/Applications/Xcode-beta.app`：27.0 beta 3（Build `25183.54.10`），不用于 KMP 稳定基线。
- iPhoneOS SDK：26.5
- iPhoneSimulator SDK：26.5
- macOS SDK：26.5
- 可用模拟器 Runtime：iOS 26.2
- 已发现 iPhone 17 系列、iPhone 16e、iPad Pro/Air/mini 等模拟器。

在受限环境内直接调用 `simctl` 会因无法连接 CoreSimulatorService 失败；获准在受限环境外只读检查后，模拟器 Runtime 安装/注册完成，并成功列出设备。这属于环境权限差异，不是 KMP 项目错误。

Computer Use 已打开 Xcode 欢迎页，未出现协议或签名阻塞。由于两个 Xcode 使用相同 bundle identifier，Computer Use 聚焦到了 27.0 beta 3；命令行 `xcode-select` 明确指向稳定版 26.6。M1 构建必须显式使用稳定路径，不能混用 beta。Kotlin 2.4.10 官方兼容表目前列到 Xcode 26.4，因此 26.6 需要用最小 iOS framework/simulator build 实测，不能提前宣称兼容。

## 3. Java、Gradle 与 Android 工具链

- 系统默认 JDK：Temurin 25.0.1 arm64
- 另有 JDK：Amazon Corretto 11.0.29 arm64
- Android Studio 自带 JBR：21.0.10 arm64
- 本项目 Gradle：8.9
- 当前 Android SDK platforms：`android-36`、`android-36.1`
- 当前 Android Build Tools：36.0.0
- Android AVD：`Pixel_10_Pro_XL`
- 实际运行设备：Pixel 10 Pro XL API 37.1，`emulator-5554`

基线构建使用 Android Studio JBR 21，而不是系统默认 JDK 25：

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --stacktrace
```

结果：`BUILD SUCCESSFUL in 9s`，50 个任务中 14 个执行、36 个 up-to-date。

已记录警告：

- Kapt 暂不支持 Kotlin 2.0+ language version，回退到 1.9。
- 使用了与 Gradle 9.0 不兼容的 deprecated Gradle features。

这些警告不阻塞当前 Android 基线，但 KMP 骨架必须重新核对 Kotlin、Compose Multiplatform、Gradle 和 AGP 兼容矩阵，不能复制当前组合后假定三端可用。

## 4. Computer Use 与真实 App 观察

已通过 Computer Use 观察 Android Studio 和运行设备：

- Android Studio 打开的是 `BJTUselfService`，分支显示为 `ZJG`。
- IDE Build 窗口显示 Android 构建完成。
- Running Devices 中运行 Pixel 10 Pro XL API 37.1。
- 官方 1.7.0 APK已安装到模拟器，系统包信息复核为 versionCode 8 / versionName v1.7.0。

首次启动出现 Android 16 KB page-size 兼容性提示，点名以下原生库的 LOAD segment 未按 16 KB 对齐：

- `libdatastore_shared_counter.so`
- `libc++_shared.so`
- `libpytorch_jni.so`
- `libfbjni.so`
- `libpytorch_jni_lite.so`

该提示没有被设置为“以后不再显示”。它不等于业务失败，但说明当前 PyTorch/FBJNI 原生依赖在新 Android 环境存在兼容性风险；KMP Apple 模型层必须与 Android 原生模型层隔离。

关闭提示后观察并完成了真实登录流程：

- 未登录主页仍在背景显示，并被半透明遮罩降低层级。
- 中央登录弹窗包含应用标题、账号输入、密码输入和登录按钮。
- 底部导航为紧凑的“首页 / 应用 / 设置”三项。
- MIS 凭据按用户在动作发生时的明确授权从 `MisSecret.md` 直接填入本机表单；凭据没有输出、进入截图、日志或文档。
- 第一次坐标操作发生在 Android Studio 主窗口，登录框实际为空且按钮禁用，未发起登录或 CAPTCHA 请求；重新填入后只提交一次。
- 提交后先出现“正在登录...”，随后指示消失并保持“首页 / 应用 / 设置”可用，证明 MIS 登录和首次状态切换成功。

登录后使用 Computer Use 保持 Android Studio `Running Devices` 窗口在前台，并用模拟器只读控制补足嵌入画面坐标精度。实际打开并观察了：

- 首页：邮件、校园卡、校园网三个状态卡，日历、作业截止提醒、数据变动区域和底部导航。
- 邮箱：Cookie/SSO 能直接进入校内邮箱；网页仍是桌面式多栏布局，在手机 WebView 中没有响应式适配。由于 WebView 页面文字不进入 Android 可访问性树，邮箱只记录“容器和 SSO 已验证”，不保留网页内容截图。
- 校园卡：本地确认弹层包含“校园卡充值 / 打开应用 / 取消”；只打开后取消，没有启动完美校园或充值。
- 校园网：本地确认弹层包含“校园网续费 / 分享至微信 / 取消”；只打开后取消，没有分享或续费。
- 应用页：真实显示成绩、课程表、考试安排、作业、课件、教室人数评估、其他功能七个入口。
- 成绩：显示汇总卡、学期筛选、排序和成绩卡片；“自选课程计算”能切换为带复选框的选择模式，并恢复为普通模式；成绩详情弹层可以打开。
- 课程表：显示标题、七列周视图、节次行和更多菜单；菜单真实包含“切换学期 / 选择周数”，周数弹层当前列出 14 个周按钮。没有改变学期或选择其他周。
- 考试安排：显示学期筛选及包含地点、时间、座位/说明信息的考试卡片列表。
- 作业：显示学期/状态筛选、排序、作业卡片和“上传作业 / 下载作业”动作；卡片详情真实包含“作业内容 / 附件 / 下载 / 关闭”。没有上传、下载或提交。
- 课件：显示课程层级、展开箭头、下载和日历动作；第一门课程可以展开为资源文件列表。没有下载文件。
- 教室人数评估：真实显示教学楼列表；进入教学楼后显示有效期、排序和教室人数/一周占用指示。点击教室时本轮没有出现 WebView 安排弹层，因此“教室安排展示修复”仍不能写成实机通过。
- 其他功能：真实显示校历下载，以及带中英文切换的成绩单下载；没有下载文件。“作业提醒订阅”没有出现在页面。
- 设置：真实显示用户、检查更新、清除本地数据、GitHub、主题、动态配色、自定义背景、四项自动同步、更新提示和退出账号。主题菜单已打开但未改变；清除数据确认弹层已取消；检查更新弹层已关闭；开关、壁纸、退出均未操作。

视觉观察：

- 1.7.0 使用浅色 Material 3，大圆角卡片与蓝紫主色；应用页是两列大卡片网格，图标采用彩色渐变。
- 首页和设置使用固定手机纵向信息流；课程表使用横向七列网格；成绩、考试和作业使用宽卡片列表。
- 底部导航是三个独立的浮动方形/圆角块，选中项使用更深的容器色。
- 设置和主页的多数信息层级清楚，但邮箱桌面网页被直接缩进移动端 WebView；Apple 端不能照搬这一布局。
- 脱敏图保存在 `docs/migration/visual-baseline/`。所有可访问文字统一覆盖；WebView 内容和弹层背景若无法由语义树可靠遮挡，则整页或背景区域直接覆盖，不保留可辨识内容。

## 5. M0 结论与未执行边界

- M0 的构建、工具链、登录、一级页面、核心二级页面和脱敏视觉基线主体已完成。
- 没有执行上传/下载、充值、分享、发送邮件、清除数据、退出账号、切换设置、选择成绩课程等有副作用动作。
- Android 周视图小组件没有添加到模拟器桌面；目前只有 APK/源码声明和应用内课程表行为证据，不能写成小组件实机通过。
- 教室列表可用，但本轮点击教室没有显示 WebView 安排弹层；该项在功能矩阵保留“部分验证”。
- 作业同名同步修复需要具有同名样本的同步前后对比，本次账号数据不能直接证明；留作脱敏 fixture 和后续回归测试。
- 壁纸/玻璃态需要选择本地图片并改变设置，本次为避免修改用户状态未执行；只确认入口和默认视觉。
- Xcode IDE、SDK 和模拟器已核对；仍不登录开发者账号、不创建证书或接受协议。
- `docs/migration/m1-kmp-skeleton-plan.md` 已给出完整 M1 文件计划、版本、验证和回退方式；进入 M1 前等待用户确认该计划。
