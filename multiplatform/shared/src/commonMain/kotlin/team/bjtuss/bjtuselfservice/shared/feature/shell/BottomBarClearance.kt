package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 底栏占位是宿主报告的完整 frame 高度（含底部安全区）。
 * iOS 滚动视口保持全出血；这份净空只加到滚动内容末尾，不能缩小视口。
 * 窄屏根页停在底栏上方 8.dp；push 页没有底栏，只让开系统安全区。
 * 宽屏使用侧栏，不预留手机底栏空间。
 */
internal fun destinationBottomClearance(
    expanded: Boolean,
    hasBottomBar: Boolean,
    barInset: Dp,
    systemInset: Dp,
): Dp = when {
    expanded -> 0.dp
    hasBottomBar -> {
        // 首帧宿主尚未测量时，先让开默认栏高，避免短暂重叠。
        val completeBarInset = if (barInset > 0.dp) barInset else 80.dp + maxOf(systemInset, 0.dp)
        maxOf(completeBarInset, systemInset, 0.dp) + 8.dp
    }
    else -> maxOf(systemInset, 0.dp)
}

/** 滚动内容末尾的透明净空，不绘制背景，不创建占位条。 */
val LocalBottomBarClearance = staticCompositionLocalOf { 0.dp }
