# Gitee Release 镜像与验收

GitHub 是发行事实来源。`kmp-package.yml` 的 `release` 任务成功后，通过 `needs: release` 调用独立的 `.github/workflows/gitee-release-mirror.yml`，同步当前标签、Release 标题/说明、pre-release 属性和 APK / IPA / DMG / MSI。自动调用传 `best_effort: true`，镜像任务允许失败，不改变 GitHub Release 或原打包成功状态；镜像失败单独写 warning 与 Job Summary。debug / alpha / beta 仍只打包。不会创建新的正式版本。

## 触发方式

使用 [GitHub 官方支持的 reusable workflow](https://docs.github.com/en/actions/how-tos/reuse-automations/reuse-workflows)：自动调用与手动重试共用一份实现。不直接监听 `release: published`，因为现有发布任务使用 `GITHUB_TOKEN`，它创建的 Release 通常[不会触发新的事件工作流](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)。显式依赖发布任务可以确保四端附件和说明已经发布成功，也不需要额外 GitHub PAT 或 Actions 写权限。

手动入口通常只镜像**已经存在且已发布**的 GitHub Release（仅下文固定测试标签允许认证读取草稿）；脚本先读 GitHub，再做四端附件/容量检查，失败前不推 Gitee 标签或创建发行版。不会把仅有 Actions 产物的 debug/alpha/beta 当正式发行版。

## 凭据配置

1. 所有者在 [Gitee 私人令牌](https://gitee.com/profile/personal_access_tokens) 创建令牌，选择 **projects（仓库权限）**，账号需有 `zjg1225/BJTUselfService-KMP-Refreshed` 写入权限。API 没有单独的 Release 写权限 scope；建议使用仅能访问该镜像库的专用账号，限制令牌影响范围。
2. 在 [GitHub Actions Secrets](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/settings/secrets/actions) 新建仓库 secret **GITEE_TOKEN**。不要填写进 YAML、聊天、普通仓库变量或提交。如果使用专用账号，在 Actions Variables 设置 `GITEE_USERNAME` 为该账号登录名（默认 `zjg1225`）；令牌仍必须放 Secrets。GitHub 读取已发布 Release 使用任务自带 `github.token`，不需要新增 GitHub PAT。
3. 令牌只通过环境变量传给镜像脚本。标签以 `GIT_ASKPASS` 输入密码，只推当前标签，不强推、不覆盖 Gitee main。已有冲突标签会停止同步。

没有配置 Token 时镜像任务明确报错；GitHub 发布继续成功。可在 Actions → **KMP package** → **Run workflow** 选择 main，将 mirror_tag 填为已有 GitHub Release 标签，只调用独立镜像工作流，跳过四端打包和 GitHub 发版；mirror_tag 留空才正常打包。手动运行失败会正常标红。若 GitHub 已显示 **Gitee Release mirror** 独立入口，也可直接填 tag 重试；本次该新入口返回404，因此保留已验证的 KMP package 重试入口。按文件名续传并重新验证全部附件；同名但内容不同的旧附件会报错，交由所有者处理，不自动覆盖或删除。

## API 与容量调查（2026-10-22）

- 权威 API 契约：[Gitee Swagger](https://gitee.com/api/v5/swagger)，机器可读 `/api/v5/doc_json`，本次响应版本 5.4.93。公开库 `GET /repos/{owner}/{repo}/releases` 无需令牌，已实测本项目 HTTP 200、空列表。
- 创建/更新：`POST /repos/{owner}/{repo}/releases`、`PATCH /repos/{owner}/{repo}/releases/{id}`；创建必须提供 `target_commitish`。附件：`GET/POST /repos/{owner}/{repo}/releases/{release_id}/attach_files`，上传为 `multipart/form-data`，字段 `file`。
- 本项目 [创建发行版页面](https://gitee.com/zjg1225/BJTUselfService-KMP-Refreshed/releases/new) 当前显示：单附件 ≤100M（GVP 200M），仓库全部附件 ≤1G（推荐项目 5G、GVP 20G），包括普通仓库附件和发行版附件。不能把 Git 仓库/LFS 配额当附件配额。
- 脚本按更保守的十进制 100,000,000 字节检查单文件，Release 附件合计预算 900,000,000 字节，预留普通附件空间；普通附件占用无法由 Release 列表推断，服务端仍可能拒绝。任一超限会停止同步，不能标记完整；不拆分安装包冒充可直接安装的文件。
- 下载使用[官方文档中的稳定路由](https://blog.gitee.com/2022/08/18/update/)：`/releases/download/{tag}/{filename}`。不保存 CDN 临时签名 URL。
- 匿名实测：无 Cookie、无 Authorization、无 access_token 请求 `ld/J2Cache` 的 `2.0-dev1/j2cache-core-2.0.0-dev.jar`，跟随重定向获得 56,492 字节、ZIP/JAR 魔数有效。只证明该公开附件当前可匿名下载，不保证所有库/未来策略。
- 最初调查时本项目无 Release。随后按所有者授权通过浏览器创建 `v2.0.0-gitee-test` 临时预发行版，目前没有附件/完成标记；它不是 Actions 自动同步的成功证据。首次实际镜像必须逐个匿名下载四端安装包，比对 GitHub 原文件的字节数与 SHA-256；登录 HTML、错误内容、同名错误包均失败。全部通过后才追加 `<!-- bjtu-mirror-ready:v1 -->`，供 App 排除未同步完的版本。

## 保留、失败恢复与回退

默认永久保留，不实现 DELETE 请求。每次预检输出 Release 附件占用和最旧版本的人工清理候选（至少最近五条不列入候选）；候选只是提示，不代表已获删除授权。重要发行版、紧急修复和用户仍在使用的包由所有者审查。接近预算时先考虑增加容量/外部对象存储；删除须另行确认具体 tag 和附件，GitHub 历史包应继续保留。

镜像失败后修复 Token、容量、网络或标签冲突，再用上文手动入口输入相同 tag。首次同步若上传一半失败，Release 可见但没有完成标记，App 忽略；原 GitHub Release 可继续下载。回退只需移除 `kmp-package.yml` 中的 `gitee-mirror` 调用，不影响原四端打包和 GitHub 发布。

验证：`python3 -m unittest discover -s scripts/tests -v`（16项通过）。2026-10-23 Actions 39824529964 已通过本项目四端上传及匿名SHA-256校验，证明已配置仓库级Token可访问、推标签、发布附件；App实际仍为1.8.6-KMP/Build44。实测大包跨境上传曾超过300秒，附件上传预算延长至1200秒、镜像任务60分钟；元数据请求仍300秒。

## 所有者授权的整链路测试（2026-10-23）

仅 `v1.0.0-gitee-test` 标签先在草稿中上传四端附件，齐备后短暂发布为预发行版，并立即由 GitHub CLI 将 Release 改回草稿（失败时退出trap重试隐藏）。它使用实际 1.8.6-KMP / Build44 的四端包，不修改 App 版本号。镜像仅允许这个固定测试标签在草稿状态下读取；按标签API只返回已发布Release，测试草稿改从认证Release列表精确匹配。单独测试任务需要contents:write才可见草稿，普通镜像仍contents:read。使用认证的 GitHub Asset API 取包，跨主机重定向时去掉 Authorization/Cookie，不把仓库令牌传给 CDN。其他草稿仍拒绝。

确认 Gitee 初次同名镜像完整、匿名哈希通过后，手动运行 **KMP package**：mirror_tag 填 `v1.0.0-gitee-test`，勾选 `promote_test`（或独立入口填同名tag）。该模式仅允许把这个已完整测试镜像改为 Gitee `v2.0.0`，不改变 GitHub tag、不覆盖其他 Gitee Release、不删除附件；新 tag 的四端匿名哈希再次通过后才恢复完成标记。说明包含 `<!-- bjtu-mirror-test:v1 -->`，App 据此避免测试用2.0.0污染普通1.x版本的最高版本记录。


实际验收：Gitee v1.0.0-gitee-test 完整同步后，[39826073012](https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/actions/runs/39826073012) 将同一个Release id1231100改为[v2.0.0](https://gitee.com/zjg1225/BJTUselfService-KMP-Refreshed/releases/tag/v2.0.0)，新标签四端匿名哈希全部通过。本机第二次独立无凭据下载也全部匹配原包。桌面无账号验证入口注入GitHub不可达，自动模式5215ms回退真实Gitee并显示更新；指定Gitee306ms成功；弹窗所选DMG直链匿名下载哈希一致。验证入口拦截系统URL处理器来保存验证文件，因此系统浏览器交接与各端真机安装仍待复测。未自动删除任何发行版。
