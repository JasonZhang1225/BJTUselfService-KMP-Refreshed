# Gitee Release 镜像与验收

GitHub 是发行事实来源。`kmp-package.yml` 的 `release` 任务成功后，独立的 `gitee-mirror` 任务同步当前标签、Release 标题/说明、pre-release 属性和 APK / IPA / DMG / MSI。镜像任务 `continue-on-error`，失败不改变 GitHub Release；失败详情保留在独立任务日志。debug / alpha / beta 仍只打包。不会创建新的正式版本。

## 凭据配置

1. 所有者在 [Gitee 私人令牌](https://gitee.com/profile/personal_access_tokens) 创建令牌，选择 **projects（仓库权限）**，账号需有 `zjg1225/BJTUselfService-KMP-Refreshed` 写入权限。API 没有单独的 Release 写权限 scope；建议使用仅能访问该镜像库的专用账号，限制令牌影响范围。
2. 在 [GitHub Actions Secrets](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/settings/secrets/actions) 新建仓库 secret **GITEE_TOKEN**。不要填写进 YAML、聊天、普通仓库变量或提交。如果使用专用账号，在 Actions Variables 设置 `GITEE_USERNAME` 为该账号登录名（默认 `zjg1225`）；令牌仍必须放 Secrets。GitHub 读取已发布 Release 使用任务自带 `github.token`，不需要新增 GitHub PAT。
3. 令牌只通过环境变量传给镜像脚本。标签以 `GIT_ASKPASS` 输入密码，只推当前标签，不强推、不覆盖 Gitee main。已有冲突标签会停止同步。

没有配置 Token 时镜像任务明确报错；GitHub 发布继续成功。可在 Actions 中重跑失败任务，按文件名续传并重新验证全部附件；同名但内容不同的旧附件会报错，交由所有者处理，不自动覆盖或删除。

## API 与容量调查（2026-10-22）

- 权威 API 契约：[Gitee Swagger](https://gitee.com/api/v5/swagger)，机器可读 `/api/v5/doc_json`，本次响应版本 5.4.93。公开库 `GET /repos/{owner}/{repo}/releases` 无需令牌，已实测本项目 HTTP 200、空列表。
- 创建/更新：`POST /repos/{owner}/{repo}/releases`、`PATCH /repos/{owner}/{repo}/releases/{id}`；创建必须提供 `target_commitish`。附件：`GET/POST /repos/{owner}/{repo}/releases/{release_id}/attach_files`，上传为 `multipart/form-data`，字段 `file`。
- 本项目 [创建发行版页面](https://gitee.com/zjg1225/BJTUselfService-KMP-Refreshed/releases/new) 当前显示：单附件 ≤100M（GVP 200M），仓库全部附件 ≤1G（推荐项目 5G、GVP 20G），包括普通仓库附件和发行版附件。不能把 Git 仓库/LFS 配额当附件配额。
- 脚本按更保守的十进制 100,000,000 字节检查单文件，Release 附件合计预算 900,000,000 字节，预留普通附件空间；普通附件占用无法由 Release 列表推断，服务端仍可能拒绝。任一超限会停止同步，不能标记完整；不拆分安装包冒充可直接安装的文件。
- 下载使用[官方文档中的稳定路由](https://blog.gitee.com/2022/08/18/update/)：`/releases/download/{tag}/{filename}`。不保存 CDN 临时签名 URL。
- 匿名实测：无 Cookie、无 Authorization、无 access_token 请求 `ld/J2Cache` 的 `2.0-dev1/j2cache-core-2.0.0-dev.jar`，跟随重定向获得 56,492 字节、ZIP/JAR 魔数有效。只证明该公开附件当前可匿名下载，不保证所有库/未来策略。
- 本项目尚无 Release，因此没有发布或上传测试文件。首次镜像必须逐个匿名下载四端安装包，比对 GitHub 原文件的字节数与 SHA-256；登录 HTML、错误内容、同名错误包均失败。全部通过后才追加 `<!-- bjtu-mirror-ready:v1 -->`，供 App 排除未同步完的版本。

## 保留、失败恢复与回退

默认永久保留，不实现 DELETE 请求。每次预检输出 Release 附件占用和最旧版本的人工清理候选（至少最近五条不列入候选）；候选只是提示，不代表已获删除授权。重要发行版、紧急修复和用户仍在使用的包由所有者审查。接近预算时先考虑增加容量/外部对象存储；删除须另行确认具体 tag 和附件，GitHub 历史包应继续保留。

镜像失败后修复 Token、容量、网络或标签冲突，再重跑 `gitee-mirror`。首次同步若上传一半失败，Release 可见但没有完成标记，App 忽略；原 GitHub Release 可继续下载。回退只需移除/禁用 `gitee-mirror` job，不影响原四端打包和 GitHub 发布。

验证：`python3 -m unittest discover -s scripts/tests -v`。真实写 API、项目四端匿名校验、GitHub Actions 端网络与令牌权限，必须等所有者配置 Secret 后由实际发布/重跑验收；本地测试不代表这些已通过。
