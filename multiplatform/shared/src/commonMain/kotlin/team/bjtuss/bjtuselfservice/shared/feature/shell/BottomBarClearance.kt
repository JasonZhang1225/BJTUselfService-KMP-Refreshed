package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 系统玻璃 TabBar 压在内容之上时，页面滚动容器需要为它预留的**尾部留白**。
 *
 * 只在 M17 玻璃壳下为正值。这份留白必须加在滚动内容上（`contentPadding` / 滚动内容尾部），
 * 而不是加在页面布局上：加在布局上时列表视口止于玻璃条上沿，玻璃背后只剩一片纯色，
 * 液态玻璃就没有东西可折射；只有让列表画到物理底边、末项靠尾部留白让开，
 * 卡片才会从玻璃条底下穿过，得到系统 App 那种通透感。
 */
val LocalBottomBarClearance = staticCompositionLocalOf { 0.dp }
