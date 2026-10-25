package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.runtime.Composable

/** macOS 没有完美校园的公开 URL Scheme，只保留扫码与复制链接。 */
@Composable
internal actual fun rememberCampusAppLauncher(): (() -> Boolean)? = null
