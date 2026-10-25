package team.bjtuss.bjtuselfservice.shared.agent

/**
 * macOS 原生端首版不提供本机 AI Agent 接入：
 * 现有实现依赖 JVM 进程内的可执行文件路径与脚本写入，原生宿主下要重新设计分发方式。
 * 返回 null 时设置页隐藏该入口，不影响其余功能。
 */
actual fun platformAgentIntegration(): AgentIntegration? = null
