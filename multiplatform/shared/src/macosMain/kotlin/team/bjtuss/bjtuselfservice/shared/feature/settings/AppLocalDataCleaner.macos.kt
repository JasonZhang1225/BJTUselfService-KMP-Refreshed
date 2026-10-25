package team.bjtuss.bjtuselfservice.shared.feature.settings

/** macOS 原生端首版不提供本机数据清理入口；退出登录仍会清理凭据与缓存。 */
actual fun platformLocalDataCleaner(): AppLocalDataCleaner? = null
