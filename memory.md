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

## 接下来

1. 等 CI `37906214993` 四端产物；平板用本地 APK 对照。
2. 需要正式发布时再定 `v1.8.3-KMP` 标签、说明和四端包。
