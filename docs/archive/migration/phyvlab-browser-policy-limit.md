# 物理在线浏览器验证限制记录

> 记录日期：2026-08-27（Asia/Shanghai）
> 目的：把本轮通过 Codex 浏览器验证 `phyvlab.bjtu.edu.cn` 时遇到的环境限制交给后续复核者。

## 结论

浏览器标签可以被发现，且用户在验证期间已重新登录物理在线；但是一旦尝试读取页面内容，浏览器控制层会因为 **管理员策略校验不可用** 拒绝访问。该拒绝独立于账号登录状态，也没有通过重复登录、重新连接浏览器或调整 Codex 访问权限而解除。

本轮没有绕过策略，也没有执行任何外部写操作。

## 可复现步骤（均为只读）

1. 连接 Codex 的 Edge 扩展会话，使用当前用户标签；随后按用户要求重试。
2. 用户明确改用 Codex 内置浏览器后，连接 `iab` 会话。
3. 通过浏览器的用户标签列表发现并接管当前标签：
   - 标题：`课程： 大学物理I_(2026春)`
   - URL：`https://phyvlab.bjtu.edu.cn/course/view.php?id=72`
4. 只执行了标签发现、接管和页面 DOM 可见结构读取：
   - `user.openTabs()`
   - `user.claimTab(...)`
   - `playwright.domSnapshot()`
5. 第 4 步的 DOM 读取每次都返回下述拒绝；未继续点击或导航。

## 原始拒绝信息

```text
Browser Use could not complete this action because a browser security check was unavailable.
Reason: The admin-enforced policy could not be verified, so access was not granted.
Browser use cannot access https://phyvlab.bjtu.edu.cn because the admin-enforced policy could not be verified.
Please try again later. This failure may be temporary.
The agent may retry after the issue is resolved, but must not bypass browser security controls or use an indirect workaround.
```

## 已尝试的状态变化

- 用户确认物理在线账号已重新登录后重试：仍被拒绝。
- 用户调整 Codex 状态并授予完全访问权限后重试：仍被拒绝。
- 从 Edge 扩展会话切换到 Codex 内置浏览器：仍被拒绝。
- 重新建立浏览器连接、重新发现标签并按精确标签信息接管：仍被拒绝。

这些动作只改变了连接/权限上下文，没有读取或修改浏览器 Cookie、Local Storage、密码、令牌或会话存储。

## 未执行的操作

- 没有输入用户名、密码、验证码或 OAuth 参数。
- 没有点击课程、作业、提交、上传、保存、删除或确认按钮。
- 没有上传本地文件、提交作业、下载文件或改变物理在线数据。
- 没有使用 Computer Use、独立 Playwright、脚本注入或其他间接方式绕过浏览器策略。

## 给后续复核者的建议

请在另一套获管理员策略允许的浏览器控制链路中，先读取同一课程 URL 的 DOM，再核对作业详情页的提交表单、批改成绩和文件上传控件。复核时应继续把“读取页面”与“上传/提交”分开；真实上传或提交必须由用户在明确确认后执行。不要复制或导出本机浏览器的 Cookie、密码或令牌来绕过该限制。

## Chrome DevTools 复核（2026-08-27）

> 控制链路：本机 Chrome DevTools MCP。用户自行完成 CAS 登录后，代理只读 DOM / 同源 GET。未点击提交/保存、未上传文件、未读取 Cookie、密码、令牌或 `sesskey` 值。

### 策略限制本身

Chrome DevTools 可以读取 `https://phyvlab.bjtu.edu.cn/course/view.php?id=72`（标题 `课程： 大学物理I_(2026春)`）以及作业详情 HTML。Codex Edge 扩展 / 内置浏览器上的“管理员策略校验不可用”**没有**出现在这条链路上。该限制是控制层策略问题，不是站点登录失败。

### 课程页 ↔ `parsePhyVlabActivities`

登录后课程页与解析器锚点一致：

| 选择器 | 现场 |
| --- | --- |
| `li.activity.modtype_assign` | 13 个 |
| `a.aalink[href*='/mod/assign/view.php']` + `.instancename` | 13/13 |
| `[data-region='activity-dates']` 标签 `打开` / `到期日` | 13/13 |
| 完成按钮 `button[data-action='toggle-manual-completion']` | 有，`data-toggletype` 前缀 `manual` |
| 日期正文 | 中文 `yyyy年M月d日` **同时带英文星期**，与日期清理逻辑对应 |

`/my/courses.php` 现场没有 `data-region='course-content'` / `a.aalink.coursename` 卡片，只有课程链接；`parseLegacyPhyVlabCourses` 兜底路径仍然必要。

### 作业详情 ↔ `parsePhyVlabAssignmentPage`

同一课程 13 个作业的只读 GET 结构相同：`#intro` 有正文；`div.submissionstatustable` 有状态表；已评分的才有 `div.feedbacktable`；**没有** `gradingsummarytable`。

| 现场标签 | 解析器当前匹配 | 结果 |
| --- | --- | --- |
| `作业状态`（已提交请评分 / 尚未批改） | 已认 `提交状态` / `作业状态` / `Submission status` / `Assignment status`，再回退正文 `已提交`/`未提交` | 已补齐现场标签；`尚未批改` 等现场值会保留并显示 |
| `评分状态` | 认 `评分状态` / `批改状态` | 命中 |
| `最后修改` | 认 `最后修改` | 命中 |
| `成绩`（在 feedback 表，形如 `N/N`） | 认 `成绩`，并有数字成绩正则 | 已评分作业命中；fixture 写在 `gradingsummarytable`，现场不在该表 |
| `教师评语` | 认 `教师评语` / `Feedback comments` | 现场 feedback 表是 `成绩` / `评分于` / `评分人`，本轮未见独立“教师评语”行 |
| 已提交文件 | `.submissionstatustable a[href*='/pluginfile.php']` | 命中。`.submissionstatussubmitted .files a` 未命中（YUI 文件树，无 `.files` 包装） |

已截止且已提交的作业：详情页没有“编辑提交”链接，也没有 `.filemanager`。`GET .../mod/assign/view.php?id=…&action=editsubmission` 返回站点错误页（`error/nopermission`，正文含“此处不可发布作业，不可提交作业”）。解析器把 `canSubmit=false` 是符合该课程现状的。

### 提交表单 / 文件上传控件

上传控件不在已提交作业的详情页上。另一门仍可添加提交的课程里：

- 详情页 CTA 是无 `href` 的 `添加作业` 按钮，与 `PhyVlabRemoteDataSource`“详情页没有 filemanager 就只读 GET `action=editsubmission`”一致。
- 编辑页 HTTP 200，表单 `action=/mod/assign/view.php`，隐藏域 `action=savesubmission`，有 `sesskey`，文件域名为 **`files_filemanager`**（fixture 里还有 `assignsubmission_file_filemanager`；代码按名称包含 `filemanager` 取值，两者都能接到）。
- 存在 `.filemanager` 节点和 `保存更改` 提交按钮，**没有**原生 `<input type="file">`（Moodle 草稿区 + `repository_ajax.php`）。
- 现场 `.filemanager` **没有** `data-itemid` / `data-contextid` / `data-clientid` / `data-repositoryid`。这些值在页面脚本的 filemanager JSON 里（`itemid`、`contextid`、`client_id`、`repositories`）。解析器现已只读提取这组初始化参数，并优先使用脚本值；若 `contextId`/`clientId` 缺失，详情不会暴露原生上传按钮，提交层也会拒绝带空上下文发送请求。草稿 `itemid` 仍优先从 `files_filemanager` 的 hidden value 读取；仓库 id 缺失时保留站点兼容的 `repo_id="4"` 兜底。

### 代码修复与验证（2026-08-27）

- `PhyVlabHtmlParser` 已补齐现场 `作业状态` 标签、filemanager 初始化脚本 JSON 的 `itemid/contextid/client_id/repositories` 提取，以及精确的反馈评论选择器；不再把 `.feedback` 外层整张成绩表误当成教师评语。
- `canSubmit` 与 `submitAssignment` 均要求草稿 id、context id、client id 齐全；缺字段时只保留网页备用入口。
- 新增现场反馈外层结构、JSON-only filemanager 上传往返和缺少上下文负例测试；物理在线解析/数据源定向测试通过。

### 仍未执行

- 没有真实上传、保存或改提交。
- 没有把 Cookie、`sesskey`、草稿 item、账号或成绩写入仓库或日志。
