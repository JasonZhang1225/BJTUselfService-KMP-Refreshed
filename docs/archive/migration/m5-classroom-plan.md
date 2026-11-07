# M5 教室人数评估切片

日期：2026-07-30  
基线：官方 `v1.7.0@419313d` 隔离源码与 M0 脱敏行为盘点  
修改边界：仅 `multiplatform/` 与迁移文档；冻结 Android 工程只读

## 行为基线

- 入口为“教室”，先选择 11 栋教学楼，再显示教室人数评估。
- 原 App 请求公开第三方接口 `http://yaya.csoci.com:2333/api/classnum/?building=<教学楼>`。
- 返回 JSON 的 `time` 是数据轮询窗口；`data` 行为 `[教室名, 使用率, 已用人数, 容量]`。
- 原 App 另有教室安排 WebView 弹层，但 M0 实机盘点时未稳定出现，本切片不把未验证行为冒充已完成。

## KMP 实现

- `domain/classroom/Classroom.kt`：11 栋教学楼、教室容量模型、名称/空位/容量筛选、名称/占用率/已用人数/容量排序。
- `data/classroom/ClassroomJsonParser.kt`：复用严格 JSON parser；字段缺失、类型错误返回结构化失败，不泄露正文。
- `data/classroom/ClassroomRemoteDataSource.kt`：中文教学楼名百分号编码；只允许精确 origin `http://yaya.csoci.com:2333`，重定向出界拒绝。
- `data/classroom/ClassroomRepository.kt`：网络/解析/安全通道失败映射。
- `feature/classroom/ClassroomScreenModel.kt`：教学楼会话内快照、失败保留旧数据、快速切楼不让旧请求覆盖新楼。
- `feature/classroom/ClassroomScreen.kt`：iPhone 使用教学楼列表→教室详情两级导航；macOS 使用左侧教学楼与右侧列表—详情；错误、加载、空结果与筛选状态就地反馈。
- 登录后应用壳新增第七个入口“教室”；第三方请求使用独立 transport，不与 MIS/AA Cookie 会话共用客户端。

## 明文 HTTP 与 Apple/Android 安全边界

真实探测确认：

- HTTP 接口返回 200。
- `https://yaya.csoci.com:2333` TLS 握手失败（服务器只支持过旧协议），没有可用 HTTPS 替代。

本实现没有添加 `NSAllowsArbitraryLoads`、ATS 例外或 Android cleartext 网络配置：

- iOS：平台能力明确返回不可用，界面显示“第三方教室接口只支持明文 HTTP，不满足当前系统安全要求”。
- Android target 36：同样不增加 cleartext 例外，确定显示安全通道不可用，而不是误报普通网络失败。
- macOS JVM/CIO：仅访问精确明文 origin，UI 明示“第三方明文接口，仅作参考”；所有重定向仍检查 origin。

若后续要在 iOS/Android 开放该接口，必须先获得用户对精确域名安全例外的明确决定；不得全局放宽网络安全策略。

## 测试与真实网络验收

- 新增 14 项 commonTest：真实结构/混合使用率/空列表/字段缺失/类型错误、中文 URL 编码、origin 逃逸、解析失败、筛选/排序、失败保留旧快照和非法教学楼。
- 全量 `:shared:desktopTest`：166 项，零失败零跳过。
- `:androidApp:assembleDebug`、`:shared:linkDebugFrameworkIosSimulatorArm64`、`:desktopApp:createDistributable`：`BUILD SUCCESSFUL`。
- 临时 desktop 测试（验收后已删除）直连“思源楼”：解析 **37** 间教室，数据窗口 `2026-07-30 21:17:17`—`21:17:58`，首项 `SY101`。

## 尚未证明

- 登录后第七入口的 iOS/macOS 真实视觉交互受 CAS 验证码风控阻塞；代码、测试和三端构建通过不替代逐页视觉验收。
- 教室安排 WebView 弹层仍是 v1.7.0 基线中的未验证边界。
