# BJTUselfService 实时工作记忆

> **当前状态（2026-11-07）**
> - **当前分支**：`main`（与 `origin/main` 同步，最新合并 PR #97 `23309e47`、PR #99 `d7df0f1b`）
> - **源码版本**：`2.0.0-Alpha` / Build 45（最新正式 Release：`v1.8.6-KMP` / Build 44）
> - **远端活跃分支**：`main`、`feat/mac-swift`（macOS 原生 SwiftUI 探索）；原 `feat/homework-submit-cli-drop`、`feat/agent-assignment-detail` 已合入并删除。
> - **已完成事项归档**：2.0 开发期已合入 `main` 的全部功能（课程回放、macOS/Windows 硬件合成、作业忽略与筛选拆分、全应用设备时区、离线更新日志、`DESIGN.md` / `AppleAlert` / `AppleMenuPickerCard`、物理实验 IP 备用路径、上传文件拖放、本机 CLI 交作业、统一作业详情与远程增量同步等，PR #68–#99）均已移入 `history_full.md`。

---

## 进行中 / 待处理：物理在线相关检查

### 1. 物理在线登录恢复（2026-11-06 CDP 取证）

- **问题一（网页独立账号登录新增点选验证码）**：
  - 学校物理在线独立登录页新增阿里云图形点选验证，需先 `POST /login/captcha_verify.php` 通过验证后再提交 Moodle 登录表单。
  - 用户已确认手动点选后可以正常登录，但每次新建立会话均需点选；App 现有独立登录协议只提交账号、密码和隐藏字段，无法在无界面下完成图形点选。
- **问题二（统一身份认证 OAuth2 回跳与账户信息拉取失败）**：
  - 统一认证走 `cas.bjtu.edu.cn/o/authorize/`。首轮与第二轮（2026-11-06）Chrome CDP 实测发现两层问题：
    1. **回调参数拆分**：原始回调带授权码时，`state` 内嵌的 `sesskey` 被拆到了外层查询参数；仅在拆出字段与本次原始 `state` 一致时恢复原编码，同一 `MoodleSession` 下可通过原会话校验并将授权码转发为 `oauth2code`。
    2. **服务端账户信息缺失（阻塞项）**：通过回调会话校验后，`/admin/oauth2callback.php` 303 到 `/auth/oauth2/login.php`，后者 303 回登录页，中文提示「缺少账户信息，可能是配置有误」，英文明确显示 `No user information was returned`（对应 Moodle 上游 `loginerror_nouserinfo`，不同于字段不全的 `loginerror_userincomplete`）；三次复测（中文登录页、英文登录页、从“我的课程”触发）一致复现，访问 `/my/courses.php` 同样 303 回登录页。Moodle 服务端到学校身份提供方的 userinfo 请求及后台映射配置在客户端不可见、无法从客户端修复。
- **下一步方向**：
  - 放弃当前坏掉的学校 OAuth2 回跳路径，改为验证在 App 内打开学校物理在线原网页、由用户手动完成一次阿里云点选登录，随后提取并接回该站点的 `MoodleSession` 会话供后续作业与详情同步复用。

### 2. 物理在线作业详情与老师附件真实页面校验（PR #98/#99 后续）

- PR #98/#99 新增了从物理在线作业说明 HTML 中解析老师附件（`pluginfile.php` 链接与内嵌图片）、草稿/最终提交状态判定（`phyVlabAssignmentDetailHasSubmission`）以及单批详情同步最多恢复 1 次会话的保护。
- 此前受上述登录问题阻塞，老师附件解析仅通过脱敏 fixture 测试验证；待登录恢复后，需用真实学校页面核对 `pluginfile.php` 附件解析、重定向白名单下载与缓存补全。

---

## 已知限制与日常维护备忘

- **私人远程 Site 升级**：PR #99 合入后，如需启用按设备增量快照（`PATCH /api/v1/device/snapshot`）与作业附件分块上云，需重新部署 `multiplatform/remote-site-template` 并执行 D1 迁移 `0003_attachments.sql`（旧模板自动回退全量上传、不传附件）。
- **校园网与学校端点特性**：
  - 物理实验、演示实验、物理在线需校园网；物理实验主域名 `http://wlsy.bjtu.edu.cn/` 失败或 6 秒超时会自动回退 `http://59.64.5.228/`（见 `docs/security/physics-lab-ip-backup-2026-11-02.md`）。
  - 智慧教学（课程平台作业/课件）直连 module 28 仍被跳往旧 CAS，当前通过本科服务平台 `jwcZhjx` 自动回退建会话。
  - 教务系统 `aa.bjtu.edu.cn` 偶发拒绝 443 连接时，App 正常进主界面并保留成绩/课表/考试离线缓存。
  - 会话过期且学校恰好要求验证码时，后台静默恢复会停下交给用户，需重登或重开 App 手动输入验证码。
- **测试环境已知差异**：Windows 本机跑全量 `:shared:desktopTest` 时，`DesktopAgentIntegrationTest`（3 项）与 `MacUninstallCleanerTest`（2 项）因 Unix 脚本/符号链接权限与 macOS 路径假设失败，在 macOS 与 CI 上全绿。

---

## 接下来

1. 跟进物理在线网页手动点选登录接回 `MoodleSession` 会话，并在真实页面验证物理在线作业详情与老师附件解析。
2. 重新部署私人远程 Site 模板并执行迁移 `0003_attachments.sql`，推进 2.0.0 发版前验收。
