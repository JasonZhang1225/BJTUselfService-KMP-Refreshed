# BJTUselfService KMP 实时工作记忆

> 最后更新：2026-10-09。
> 当前阶段：上游 `v1.7.1` 已合入冻结 Android 工程。
> 当前分支：`main`。
> KMP 应用版本：`1.8.3-beta-ci` / Build 26。
> 冻结原 Android：`v1.7.1` / versionCode 9（对照 `origin/main@7d94f74`）。
> 正式 Latest 仍是 `v1.8.2-KMP`。

## 已完成

- 已合并上游两提交：`c5418d2` 作业上传改 `homeworkUpload.shtml?noteId=`，`7d94f74` 版本 `v1.7.1`。
- 冻结工程作业上传与版本已对齐上游；本机 `compileSdk/targetSdk 36`、阿里云镜像与 versionName 空安全保留。
- KMP 学生上传端点此前已是 `homeworkUpload.shtml`，本轮未改 `multiplatform/`。

## 当前注意事项

- README.md 仍有未提交的本地编辑，未纳入本次合并。
- `debug-*` / alpha / beta 标签只上传 Actions 产物，不发 GitHub Release。
- 未推 `origin`，未做正式发布。

## 接下来

1. 等 `debug-1.8.3-beta-ci` 的 KMP package 和 security checks 跑完。
2. 需要正式发布时再定 `1.8.3-KMP` 版本号、说明和四端包。

