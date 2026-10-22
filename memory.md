# BJTUselfService 实时工作记忆

> 最后更新：2026-10-22。
> 当前分支：`main`。PR #40 图书馆、#41 灵动岛/实时活动/手表、#42 作业搜索与教室当前节次均已合入（合并提交 `64c24ab`、`6f5c36f`、`8189baf`），细节见 `history_full.md`。远端另保留 `claude/宣传图制作`、`feat/chatgpt-agent-remote`（AI Agent，开发中）。
> KMP 源码版本：`1.8.6-Alpha` / Build 41（尚未发版）；最新 GitHub Release 为 `v1.8.5-EmergencyFix2`（2026-10-20，Latest）。iOS 最低系统 18.0。
> 2026-10-22 仓库清理：冻结原 Android 工程（`app/` 与根 Gradle 工程、`release.yml`/`debug.yml`）与验证码 torch 模型（含 Windows `BJTUCaptcha.pt`、DJL）已移除，存档于标签 `archive/legacy-android-and-captcha-model`（`8189baf`）；Windows 验证码改为手动输入。只保留 `origin` 远端；GitHub 上仍是 `HFDLYS/BJTUselfService` 的 fork，由所有者在 Settings 解除。
> 认证现状（2026-10-20）：MIS 走统一身份认证 sso.bjtu.edu.cn（SM2 加密密码、默认免验证码）；物理在线用独立账号密码；智慧教学仍卡在学校的旧 CAS 跳转。
> 已完成工作的细节见 `history_full.md` 末尾日志。

## 进行中

- AI Agent（`feat/chatgpt-agent-remote`）：开发中，约七八成；合入后再清理 main 里经紧急修复带进来的半成品 AI 代码。
- 已合入功能的待真机验证（不阻塞）：
  - 灵动岛/实时活动：收起态最终版截图（等实际上课时）；开课时“即将上课”切“正在上课”；两个实时活动并存时的最小态小圆环；Live 菜单读屏自定义操作。
  - 图书馆：登录流程在 Copilot 修复后改过，需真机重新登录一次；iOS 横屏布局（浮动续借胶囊、顶栏高度）；Android 真机；续借只在早期版本实测过。
  - 作业搜索：iOS 系统搜索按钮展开/取消动画、搜索中进详情再返回；Android 返回键收起搜索；上课时段教室当前节次光晕与空闲/占用标签（浅/深色）。
  - Windows：移除 torch 模型后，遇到验证码时手动输入路径未在 Windows 真机走过。

## 当前痛点

- 智慧教学（课程平台作业/课件，MIS module 28）仍被学校跳到已下线的 `cas.bjtu.edu.cn/o/authorize`，浏览器同样 404，属学校侧问题；待学校改到 SSO 后再适配 `SmartPlatformEndpoint` 握手白名单。
- 同步卡顿：Beta3 已到可接受范围。剩余长帧为同步详情 sheet 弹出（约 50ms）、进入作业页（约 49ms）和原生滚动带来的导航栏布局；`checkSession` 改后台后未复采；壳层随任一状态流整体重组（约 0.4 秒/次同步），彻底解决需拆分壳层。
- 小组件：PR #8 的 ad-hoc 签名后，侧载包的小组件共享容器是否可读尚未在真机确认；周视图、中号两栏、脚注只经 CI 编译，未在模拟器浅/深色下查看，小屏 iPhone 周视图可能偏挤；“正在上/还剩 N 分钟”未在上课时段实机观察。
- 首页物理在线/CITEL 日程项进入二级详情只在代码层实现，模拟器只验证了演示实验。
- “保存二维码并扫一扫”未真机验证（Android MediaStore 保存/去重、扫码后进入小程序/缴费页）；iOS 每次保存会多一张；Android 二维码弹窗未在平板上看。
- HyperOS 小窗↔全屏来回切换未实测（adb 无法切换窗口模式）。
- 课件 15 分钟大文件超时未用 >30 秒真实下载验证；iOS/Android“未开放下载”标记只验证编译。
- 在 Chrome 登录智慧教学会顶掉 App 会话，App 自动恢复登录失败一次（需手动刷新），未排查。
- macOS 附件默认应用打开、iOS 保存面板未点验；`HomeworkFileGateway` 已被邮箱复用，可改名为通用文件网关。

## 已知限制（非缺陷，排查时先看这里）

- 教务系统 `aa.bjtu.edu.cn` 会临时拒绝 443 连接（2026-10-20 晚间约 1.5 小时，MIS 同时可达）；PR #33 起 App 照常进入主界面，仅成绩/课表/考试同步失败并保留缓存。复现可在 `KtorSchoolHttpTransport.executeOn` 临时对 aa 抛异常，测完撤销。
- 校历公众号文章不能由第三方 App 直接在微信内打开（invalid source），走系统浏览器。
- 小米“跟随应用内设置”覆盖安装清不掉：装新包后卸载重装，或在“使用高刷新率的应用”里打开交大自由行 KMP。
- 本地 Gradle 偶发被 Maven Central 429 限流，此时测试与 iOS 编译交给 PR CI。

## 迁移期遗留（长期，无排期）

- iOS Keychain 合法签名往返、正式签名发布（无 Apple Developer Team）。
- 物理在线真实上传、Windows 触摸屏；邮箱真实发送/删除/移动/附件上传；真实作业上传、退出账号往返（等学校侧条件）。

## 接下来

- 后续开发统一从最新 `main` 建分支，PR 以 `main` 为目标。

1. 在真机已有版本（SideStore 保留扩展）上验证小组件共享容器与各尺寸显示。
2. 华为 HBN-AL10（HarmonyOS 4.2）装含 PR #32/#33 的新包：确认课表小组件尺寸（logcat `CourseWidget` 标签）与教务不可达时不再闪退。
3. 真机过一遍“进行中”列出的待验证项。
4. 下次发版时核对版本号清单（见 `AGENTS.md`）、发布说明与测试矩阵；版本与发版由用户决定。
