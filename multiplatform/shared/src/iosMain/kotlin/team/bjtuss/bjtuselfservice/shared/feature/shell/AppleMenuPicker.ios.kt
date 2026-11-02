package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIColor
import platform.UIKit.UIMenu
import platform.UIKit.UIMenuElementState
import platform.UIKit.UIMenuOptionsSingleSelection

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun AppleMenuAnchor(
    options: List<AppleMenuOption>,
    selectedKey: String?,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier,
) {
    // UIAction 的回调在菜单构建时捕获；经 rememberUpdatedState 读最新回调，避免选项不变时重建菜单。
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnPressedChange by rememberUpdatedState(onPressedChange)
    // 点按全被原生按钮吃掉，Compose 卡片的 onClick 不触发，expanded 不参与 iOS 呈现。
    UIKitView(
        factory = { AppleMenuButton(onPressedChange = { currentOnPressedChange(it) }) },
        modifier = modifier,
        update = { view ->
            view.enabled = options.isNotEmpty()
            view.menu = buildSelectionMenu(options, selectedKey) { currentOnSelect(it) }
        },
        onRelease = { view -> view.onPressedChange = {} },
        properties = UIKitInteropProperties(
            // 协同模式会先扣住触摸约 150ms 判断是否滚动，快速点按弹不出菜单（见实时活动按钮）。
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            // 卡片由 Compose 画，原生按钮透明叠在上面只负责弹菜单。
            placedAsOverlay = true,
            isNativeAccessibilityEnabled = false,
        ),
    )
}

internal actual val appleMenuAnchorHandlesTap: Boolean = true

/** 透明的原生按钮：系统 UIMenu 必须挂在真正的 UIButton 上；按下状态回传给 Compose 卡片。 */
@OptIn(ExperimentalForeignApi::class)
private class AppleMenuButton(
    var onPressedChange: (Boolean) -> Unit,
) : UIButton(frame = CGRectMake(0.0, 0.0, 44.0, 44.0)) {
    init {
        showsMenuAsPrimaryAction = true
        backgroundColor = UIColor.clearColor
    }

    override fun setHighlighted(highlighted: Boolean) {
        super.setHighlighted(highlighted)
        onPressedChange(highlighted)
    }
}

/** 单选菜单：当前项打勾（系统画在左侧），副标题显示在选项下方；选项多时系统让菜单内上下滑动。 */
private fun buildSelectionMenu(
    options: List<AppleMenuOption>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
): UIMenu {
    val actions = options.map { option ->
        UIAction.actionWithTitle(
            title = option.title,
            image = null,
            identifier = null,
        ) { _ -> onSelect(option.key) }.also { action ->
            action.subtitle = option.subtitle
            if (option.key == selectedKey) action.state = UIMenuElementState.UIMenuElementStateOn
        }
    }
    return UIMenu.menuWithTitle(
        title = "",
        image = null,
        identifier = null,
        options = UIMenuOptionsSingleSelection,
        children = actions,
    )
}
