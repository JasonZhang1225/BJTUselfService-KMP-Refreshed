package team.bjtuss.bjtuselfservice.shared.agent.remote

import team.bjtuss.bjtuselfservice.shared.update.AppUpdateChecker

/** 安装文案与 App 发布版本共用来源，协议能力最低版本仍独立保留。 */
const val REMOTE_TEMPLATE_RELEASE = AppUpdateChecker.CURRENT_VERSION

/** 搭建入口跟随 main 分支中已合入的模板与安装文档。 */
const val REMOTE_TEMPLATE_URL = "https://github.com/JasonZhang1225/BJTUselfService-KMP-Refreshed/tree/main/multiplatform/remote-site-template"

/** 设置页“复制提示词”的内容：发给 ChatGPT（Work 模式），由它用 Sites 搭建或增量更新私人 Site 与插件。 */
val REMOTE_SETUP_PROMPT = """
请用 Sites 和 Plugin Creator，按以下模板为我创建或增量更新一份独立的私人 Site 与查询插件：
$REMOTE_TEMPLATE_URL
先读取模板内 SETUP.md（含「已有 Site 的增量更新」说明），再按其中步骤完成；查询约定也在模板内 agent-query-contract.md。

请直接完成准备、构建、发布和插件配置，最后统一交付一次，搭建或升级中途不要让我反复登录、取码或配对：
1. 使用模板 $REMOTE_TEMPLATE_RELEASE 的服务代码、DB 绑定与增量迁移（migrations/ 与 drizzle/），不重写查询和日期换算。Site 只允许我访问，不保存学校密码。
2. 我没有提供旧 Site 时新建一份；若明确提供已有 Site，则保留其身份（project_id、已有拥有者）、已有数据、设备绑定与 canonical 插件，按 SETUP.md 增量更新代码、先查 schema_migration 再只按顺序补跑尚未执行的增量迁移（如从 1.8.6-KMP 升至 2.0.0-KMP 只补跑 0003_attachments.sql）并更新同插件，不重建或清空库表（已配对设备继续有效，无需重配）。创建、发布响应不明确时先核实原操作，不重复创建。
3. 发布前确认 owner-private，按 SETUP.md 配置首次可信用户身份固定和本站设备访问机密（已有 Site 升级时保留已有有效配置，不无故轮换机密）。使用插件工具完成配置，不要求我提前登录 Site 取用户 ID，也不操作我的本机系统存储。
4. 复用 Sites 自动生成的 canonical 私人插件，通过 Plugin Creator 保留 App 绑定并附加或更新模板查询 Skill。核对模板的 13 个工具；需要安装时只在最终交付时展示一次安装/连接入口，不另建插件，不执行额外 MCP 登录。
5. 开发侧完成可自动执行的检查（包括健康检查版本、工具列表）。会话尚未加载工具或设备尚未配对时，明确区分状态，不要求我重建、重装或反复授权。

最终只给我 Site 链接、同一私人插件的安装/连接入口和这套操作说明：打开 Site 并登录 → 点击“生成配对信息” → 在新版交大自由行 App 的“Agent 和 ChatGPT 插件”中只粘贴完整配对长码（已含地址，无需另填） → 选择上云范围并确认配对（若为旧 Site 增量更新，原有已配对设备保持有效，无需重配）。
不要替我生成、领取或在聊天展示配对码、配对信息和访问凭据；我登录 Site 后自行生成。只在手机 ChatGPT 查询电脑已有数据时，无需再配对手机 App。若有真实平台阻塞，报告具体失败步骤和原因，不让我用重复登录试错。
""".trimIndent()
