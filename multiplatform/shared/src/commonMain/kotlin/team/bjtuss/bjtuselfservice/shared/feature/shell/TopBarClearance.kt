package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
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
 * 镜像底部的 [LocalBottomBarClearance]；不可纵向滚动的全览表格不要消费它，整块停在栏下。
 */
val LocalTopBarClearance = staticCompositionLocalOf { 0.dp }

/**
 * 页面把自己的真实滚动偏移（像素）报给壳层，壳层据此折算玻璃浓度。
 *
 * 之前用嵌套滚动手势累加估算偏移：上滚封顶、下滚扣减，与列表真实位置漂移
 * （fling、跳转、回顶都对不上），表现为玻璃早退——内容还在栏后，玻璃已经没了。
 * 直接读列表状态不会漂：首项之后一律视为完全盖住（宿主侧钳制为 1）。
 */
val LocalReportTopScroll = staticCompositionLocalOf<(Float) -> Unit> { {} }

/** LazyColumn 页面调用：把首项索引/偏移换算成像素偏移上报。 */
@Composable
fun ReportTopScrollListState(listState: LazyListState) {
    val report = LocalReportTopScroll.current
    LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) {
                Float.MAX_VALUE
            } else {
                listState.firstVisibleItemScrollOffset.toFloat()
            }
        }.collect { report(it) }
    }
}

/** 整页 verticalScroll 页面调用：直接上报 ScrollState 偏移。 */
@Composable
fun ReportTopScrollState(scrollState: ScrollState) {
    val report = LocalReportTopScroll.current
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value.toFloat() }.collect { report(it) }
    }
}
