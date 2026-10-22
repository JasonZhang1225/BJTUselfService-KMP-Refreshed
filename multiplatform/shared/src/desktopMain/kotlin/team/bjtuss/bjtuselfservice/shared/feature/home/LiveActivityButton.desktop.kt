package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 实时活动（灵动岛）只有 iOS 有。 */
@Composable
internal actual fun LiveActivityButton(target: LiveActivityTarget, modifier: Modifier) = Unit

/** 桌面端没有实时活动。 */
@Composable
internal actual fun rememberLiveActivityPermissionIntro(): LiveActivityPermissionIntro? = null
