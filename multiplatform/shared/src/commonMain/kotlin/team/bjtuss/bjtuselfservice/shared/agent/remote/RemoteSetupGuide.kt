package team.bjtuss.bjtuselfservice.shared.agent.remote

/** 指向已有远端分支中的协议文档，不依赖尚未推送的本地搭建笔记。 */
const val REMOTE_SETUP_DOCUMENT_URL = "https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/blob/agent-remote/docs/agent/agent-remote-plan.md"

val REMOTE_SETUP_PROMPT = """
请使用 Sites 插件，根据交大自由行的 Agent Remote 文档：
$REMOTE_SETUP_DOCUMENT_URL
以及同一仓库的 multiplatform/remote-site-template 模板，为我创建只有我自己可访问的 Site 架构插件，用于远程读取电脑上传的课表、考试、作业和实验缓存快照。

请创建并发布私人 Site，使用持久数据库保存设备、快照与任务；普通查询直接读取云端快照，只有我明确要求最新数据时才让电脑执行刷新。保留逐来源同步时间、失败状态和任务进度，不要上传学校系统密码，不要将 Site 公开。

请复用 Site 自动生成的插件，提供 get_status、query_snapshot、request_refresh_all、request_live_query、request_citel_problem、get_request_result 六个工具和配套查询 Skill；确认插件可在 ChatGPT 的 Chat Work 和 Codex 模式中调用。

完成后请给我 Site 地址，生成一个一次性配对码，并引导我完成电脑端安全连接配置。不要让我手工复制平台内部访问凭据。若私人 Site 的电脑访问尚未验证，请明确说明并先解决，不要只提供一个配对码就声称链路已通。
""".trimIndent()
