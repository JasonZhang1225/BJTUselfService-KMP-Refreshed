# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-10。
> 当前阶段：`1.8.3-Beta-ci` / Build 28 已推 debug 标签；CI 打包进行中。
> 当前分支：`main`。
> KMP 应用版本：`1.8.3-Beta-ci` / Build 28。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 正式 Latest 仍是 `v1.8.2-KMP`。

## 已完成

- 版本改为 `1.8.3-Beta-ci`（Build 28）。大写 `Beta` 字典序低于 `KMP`、高于 `Alpha`，正式 `1.8.3-KMP` 可被应用内更新检出。
- 本构建包含教务限流修复与邮箱附件下载/预览（`4dffbab`）。
- 已推 `mine/main` 与标签 `debug-1.8.3-Beta-ci`（`01cfac2`）。CI run `38026283379`。不发 GitHub Release。

## 当前注意事项

- 全小写 `1.8.3-alpha-ci` / `1.8.3-beta-ci` 字典序高于 `1.8.3-KMP`，应用内收不到正式版。本包用大写 `Beta` 才能升到 `1.8.3-KMP`。
- 小米「跟随应用内设置」覆盖安装清不掉。装新包后卸载重装，或在「使用高刷新率的应用」里打开交大自由行 KMP。
- 本机 APFS 大小写不敏感，本地删了 `debug-1.8.3-beta-ci` 标签文件才写出 `debug-1.8.3-Beta-ci`；GitHub 上两个标签可以并存。
- 未推 `origin`。

## 当前痛点

- 附件预览/保存尚未在 Android 真机和 macOS 窗口中实际点验；iOS 保存面板未点验；Windows 目标在 macOS 上只部分编译。
- Windows CI 安装器中文名：工作流已改为 `-Duser.language=zh -Duser.country=CN` 让 jpackage 用代码页 936 的中文 .wxl，替代 ASCII 兜底；未提交、未跑 CI 验证。
- `HomeworkFileGateway` 已被邮箱复用，后续可改名为通用文件网关。

## 接下来

1. 等 CI 四端产物：Android APK、Windows MSI、macOS DMG、iOS unsigned IPA。
2. 实机点验：Android 打开方式、macOS 默认应用打开；首登成绩/课表同步复测。
3. 需要正式发布时再定 `v1.8.3-KMP` 标签、说明和四端包。
