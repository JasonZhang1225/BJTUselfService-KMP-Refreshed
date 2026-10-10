# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-10。
> 当前阶段：正式 `1.8.3-KMP` / Build 29 已推标签，CI 打包与 GitHub Release 进行中。
> 当前分支：`main`。
> KMP 应用版本：`1.8.3-KMP` / Build 29。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 正式 Latest 待 CI 完成后变为 `v1.8.3-KMP`。

## 已完成

- 版本改为 `1.8.3-KMP`（Build 29）。
- 已推 `mine/main` 与标签 `v1.8.3-KMP`（`7985a25`）。CI run `38033203351`。发布说明为仓库内 `docs/releases/v1.8.3-KMP.md`，本轮未再改正文。
- 本构建含教务限流与邮箱附件、安卓附件打开方式、完美校园微信跳转、登录失败弹窗文案。

## 当前注意事项

- 校历公众号文章不能由第三方 App 直接在微信内打开（invalid source），仍走系统浏览器。
- 小米「跟随应用内设置」覆盖安装清不掉。装新包后卸载重装，或在「使用高刷新率的应用」里打开交大自由行 KMP。
- 未推 `origin`。冻结 Android `release.yml` 对 `v1.8.3-KMP` 会 skip，属预期。

## 当前痛点

- macOS 附件默认应用打开、iOS 保存面板未点验。
- `HomeworkFileGateway` 已被邮箱复用，后续可改名为通用文件网关。

## 接下来

1. 等 CI 四端产物和 GitHub Release。
2. 需要热修时再开 `1.8.3-KMP-A` 或下一版本。
