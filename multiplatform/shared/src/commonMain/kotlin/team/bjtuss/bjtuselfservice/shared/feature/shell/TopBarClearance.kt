package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 系统原生导航栏压在内容之上时，页面滚动容器需要为它预留的**顶部留白**。
 *
 * 只在 M17 玻璃壳下、且页面显式 opt-in（`DestinationPage(scrollUnderTopBar = true)`）时为正值。
 * 这份留白必须加在滚动内容上（`contentPadding.top`），而不是加在页面布局上：加在布局上时
 * 列表视口止于导航栏底边，栏后只剩一片纯色，原生 blur 就没有东西可采样（之前无效果的根因）；
 * 只有让列表从屏幕顶开始画、首项靠顶边距让开，内容才会穿进栏后，得到系统设置 App 那种 blur。
 *
 * 镜像底部的 [LocalBottomBarClearance]；不可纵向滚动的全览表格不要消费它，整块停在栏下。
 */
val LocalTopBarClearance = staticCompositionLocalOf { 0.dp }
