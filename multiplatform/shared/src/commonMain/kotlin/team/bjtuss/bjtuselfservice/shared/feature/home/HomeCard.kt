package team.bjtuss.bjtuselfservice.shared.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import team.bjtuss.bjtuselfservice.shared.PlatformFamily
import team.bjtuss.bjtuselfservice.shared.currentPlatform

/**
 * 首页卡片容器。
 *
 * iOS 上按苹果「分组列表」的样式来：不投影、不描边，靠一档比页面更亮的填充分层把卡片抬出来
 * （本主题里 `background=#F4F5F9` 是页面灰、`surface=#F9F9FC` 是卡片白，深色下同样是 surface 更亮）。
 * 圆角走 `shapes.large`。投影在 M17 玻璃壳里会和系统材质打架——卡片浮到玻璃 TabBar 底下时，
 * 阴影会被玻璃再折射一次，看起来像脏了一层。
 *
 * 其它平台继续用 Material3 `ElevatedCard`：Android 端已发布，本次 iOS 迁移不顺带改它的外观。
 */
@Composable
internal fun HomeCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (currentPlatform().family == PlatformFamily.IOS) {
        Surface(
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(content = content)
        }
    } else {
        ElevatedCard(modifier = modifier, content = content)
    }
}
