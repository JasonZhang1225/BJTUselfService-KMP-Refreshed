package team.bjtuss.bjtuselfservice.shared.agent.remote

/** 模板所在的仓库路径；合入 main 前联调可临时改成开发分支。 */
const val REMOTE_TEMPLATE_URL = "https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/tree/main/multiplatform/remote-site-template"

/** 设置页“复制提示词”的内容：发给 ChatGPT（Work 模式），由它用 Sites 搭建私人 Site 与插件。 */
val REMOTE_SETUP_PROMPT = """
请使用 Sites 插件，按交大自由行的远程模板为我搭建只有我自己能访问的私人 Site 和插件：
$REMOTE_TEMPLATE_URL
（先读模板的 README.md；查询约定见仓库 docs/agent/agent-query-contract.md。）

要求：
1. 原样使用模板 0.3.0 的 src、migrations 与 plugin/skills/bjtuss-remote/SKILL.md，用持久数据库保存设备、快照与任务；不要改写查询逻辑和日期换算。
2. 发布为私人 Site，不要公开；不要让我上传学校系统密码。
3. 复用 Site 自动生成的插件，提供模板定义的 11 个工具：get_status、get_homework、get_calendar、get_exams、get_grades、get_mail、request_live_query、request_refresh_all、request_citel_problem、request_mail_message、get_request_result，并附上模板里的查询 Skill；确认在 ChatGPT 的 Chat、Work 和 Codex 模式里都能调用。
4. 如果我已经有旧版 Site，请在原 Site 上升级到 0.3.0 并重新发布插件，不要另建第二个。

完成后告诉我 Site 地址，生成一个一次性配对码，并引导我完成电脑端的安全连接配置，不要让我手工复制平台内部访问凭据。若电脑访问私人 Site 尚未验证，请明确说明并先解决，不要只给一个配对码就说已经连通。
""".trimIndent()
