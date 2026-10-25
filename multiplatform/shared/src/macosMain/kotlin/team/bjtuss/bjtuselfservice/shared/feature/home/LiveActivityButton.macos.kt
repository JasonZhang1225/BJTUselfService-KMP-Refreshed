package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** macOS 没有实时活动（Live Activity）机制，不占首页卡片位置。 */
@Composable
internal actual fun LiveActivityButton(target: LiveActivityTarget, modifier: Modifier) = Unit

/** macOS 不需要通知权限说明。 */
@Composable
internal actual fun rememberLiveActivityPermissionIntro(): LiveActivityPermissionIntro? = null
