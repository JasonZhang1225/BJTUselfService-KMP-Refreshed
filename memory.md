# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-09。
> 当前阶段：`1.8.3-Alpha-ci` / Build 27 已推 debug 标签；本地 APK 已打好，CI 打包进行中。
> 当前分支：`main`。
> KMP 应用版本：`1.8.3-Alpha-ci` / Build 27。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 正式 Latest 仍是 `v1.8.2-KMP`。

## 已完成

- 版本改为 `1.8.3-Alpha-ci`（Build 27）。大写 `Alpha` 字典序低于 `KMP`，正式 `1.8.3-KMP` 可被应用内更新检出。
- HyperOS 刷新率加固已进该构建：退出登录不无故拉起 Chromium；每帧清 Compose ARR 投票。
- 已推 `mine/main` 与标签 `debug-1.8.3-Alpha-ci`（`4c76bd2`）。CI run `37906214993`。不发 GitHub Release。
- 本地 APK：`~/Downloads/BJTUSelfService-KMP-1.8.3-Alpha-ci-hyperos-20261009-arm64-v8a.apk`（versionCode 27，arm64-v8a）。

## 当前注意事项

- 全小写 `1.8.3-alpha-ci` / `1.8.3-beta-ci` 字典序高于 `1.8.3-KMP`，应用内收不到正式版。本包用大写 `Alpha` 才能升到 `1.8.3-KMP`。
- 小米「跟随应用内设置」覆盖安装清不掉。装新包后卸载重装，或在「使用高刷新率的应用」里打开交大自由行 KMP。
- 本机 APFS 大小写不敏感，本地删了 `debug-1.8.3-alpha-ci` 标签文件才写出 `debug-1.8.3-Alpha-ci`；GitHub 上两个标签可以并存。
- 未推 `origin`。

## 本轮（2026-10-10，未提交）

- 教务限流修复：新增 `HostRequestPolicy`/`HostRequestGate`，aa 串行、起始间隔 400ms、GET 遇 503 按 1/2/4 秒退避；CAS 串行；智慧教学（`123.121.147.7`、`bksycenter`）放宽到 5 并发，其他主机 2。成绩自动同步改为模型内 3 次 NETWORK 重试。实网探测：课表连拉 0/3→3/3，8 并发 4/8→8/8；作业同步 49 个请求全 200。
- 根因：aa 短窗口限流（约 3 秒内超 8 个请求返回 503），`99a3593` 放开并发后首登无缓存时成绩/课表全部失败。
- 邮箱附件：修复解析（真实字段 `filename`/`estimateSize`，此前所有真实附件被丢弃）；新增下载（`mbox-data?mode=download`）、行内“保存”、点按预览：图片/纯文本在应用内 `AppleSheet` 预览，其余交系统预览（iOS QuickLook、macOS 默认应用、Android FileProvider 打开方式）。单附件上限 50MB。实网下载 5 个附件字节数与签名正确。上传按用户决定不做。
- 验证：desktopTest 776 全过；iOS sim/arm64、desktopApp、androidApp debug 编译通过；附件区渲染（浅/深/大字/无网关/文本预览）已检查。

- iOS 附件预览修复（`0d20311` 之后，未提交）：Kotlin/Native 看不到 NSURL 对 QLPreviewItem 的分类遵循，`as QLPreviewItemProtocol` 抛 TypeCastException 导致崩溃；改为自实现预览项。预览目录改用 `bjtu-preview-` 前缀并在每次预览前清理残留。iPhone 17 Pro 模拟器实测 docx、xlsx 均正常渲染，关闭后临时目录删除；模拟器首次冷启动 QuickLook 渲染 Office 文档需数十秒。

## 当前痛点

- 附件预览/保存尚未在 Android 真机和 macOS 窗口中实际点验；iOS 保存面板未点验；Windows 目标在 macOS 上只部分编译。
- `HomeworkFileGateway` 已被邮箱复用，后续可改名为通用文件网关。

## 接下来

1. 实机点验：Android 打开方式（FileProvider 授权）、macOS 默认应用打开；首登成绩/课表同步复测。
2. 通过后提交并打新的 debug 包。
3. 需要正式发布时再定 `v1.8.3-KMP` 标签、说明和四端包。
