# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-09。
> 当前阶段：版本改为 `1.8.3-Alpha-ci` / Build 27，准备 debug CI 打包与本地 APK 对照。
> 当前分支：`main`。
> KMP 应用版本：`1.8.3-Alpha-ci` / Build 27。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 正式 Latest 仍是 `v1.8.2-KMP`。

## 已完成

- HyperOS 刷新率加固：退出登录不再无故拉起 Chromium；每帧把 Compose ARR 投票清成无偏好。
- 版本改为 `1.8.3-Alpha-ci`。大写 `Alpha` 后缀字典序低于 `KMP`，正式 `1.8.3-KMP` 可被应用内更新检出；全小写 `alpha-ci` / `beta-ci` 会高于 `KMP`，收不到正式版推送。

## 当前注意事项

- `debug-*` / Alpha / alpha / beta 标签只上传 Actions 产物，不发 GitHub Release。`contains(..., 'alpha')` 不区分大小写，`debug-1.8.3-Alpha-ci` 不会进 Release。
- 小米「跟随应用内设置」会写进 PowerKeeper，覆盖安装清不掉。新包装上后需要卸载重装，或在「使用高刷新率的应用」里把交大自由行 KMP 打开。
- 未推 `origin`，未做正式发布。

## 接下来

1. 推 `debug-1.8.3-Alpha-ci` 打 CI 包，本地再打一份 APK 对照。
2. 平板装完用 dumpsys 看 `mActiveRenderFrameRate` 是否回到 120。
3. 需要正式发布时再定 `v1.8.3-KMP` 标签、说明和四端包。
