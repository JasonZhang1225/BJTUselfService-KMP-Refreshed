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

/**
 * 窗口底部系统安全区。玻璃壳下由宿主从窗口安全区写入；Compose inset 有值时取较大者。
 * 只给「内容必须停在底栏上沿 / 二级页安全区」的页面消费，不改变其它页的滚动净空。
 */
val LocalSystemBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * 悬浮底栏上沿到屏幕底的距离，再叠上系统安全区。
 *
 * [barInset] 是底栏自身占位（可为 0，表示这条页面没有底栏）。[systemInset] 是 Home Indicator
 * 一类系统安全区。两者都在时相加，避免只留一个写死高度；底栏隐藏时只留系统安全区。
 */
internal fun stackedFloatingBottomInset(barInset: Dp, systemInset: Dp): Dp {
    val bar = if (barInset.value > 0f) barInset else 0.dp
    val system = if (systemInset.value > 0f) systemInset else 0.dp
    return bar + system
}
