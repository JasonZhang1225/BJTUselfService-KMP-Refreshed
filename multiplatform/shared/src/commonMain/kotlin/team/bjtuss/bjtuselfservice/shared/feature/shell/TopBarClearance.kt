package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 系统原生导航栏压在内容之上时，页面滚动容器需要为它预留的**顶部留白**。
 *
 * 只在 M17 玻璃壳下、且页面显式 opt-in（`DestinationPage(scrollUnderTopBar = true)`）时为正值。
 * 这份留白必须加在滚动内容上（`contentPadding.top`），而不是加在页面布局上：加在布局上时
 * 列表视口止于导航栏底边，栏后只剩一片纯色，原生玻璃就没有东西可折射；只有让列表从屏幕顶
 * 开始画、首项靠顶边距让开，内容才会穿进栏后，得到系统设置 App 那种玻璃。
 *
 * 不可纵向滚动的全览表格不要消费它，整块停在栏下。底部边界统一由壳层处理。
 */
val LocalTopBarClearance = staticCompositionLocalOf { 0.dp }

/**
 * 页面通过统一滚动容器上报逻辑偏移与实际回弹位移，壳层据此折算玻璃浓度。
 *
 * 之前用嵌套滚动手势累加估算偏移：上滚封顶、下滚扣减，与列表真实位置漂移
 * （fling、跳转、回顶都对不上），表现为玻璃早退——内容还在栏后，玻璃已经没了。
 * 短列表的逻辑偏移可能为 0，仍需测量回弹后的内容位置；首项之后视为完全盖住。
 */
val LocalReportTopScroll = staticCompositionLocalOf<(Float) -> Unit> { {} }

/** Include real elastic placement: a short iOS page can move without logical list scrolling. */
internal fun resolveVisualTopScrollOffset(
    logicalOffset: Float,
    viewportTop: Float?,
    contentTop: Float?,
): Float {
    val elasticOffset = if (viewportTop != null && contentTop != null) viewportTop - contentTop else 0f
    return (logicalOffset + elasticOffset).coerceAtLeast(0f)
}
