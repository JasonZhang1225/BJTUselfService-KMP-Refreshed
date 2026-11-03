package team.bjtuss.bjtuselfservice.shared.feature.assignment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.UIKit.UIAction
import platform.UIKit.UIButton
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIEvent
import platform.UIKit.UIView
import team.bjtuss.bjtuselfservice.shared.BJTUConfigureScrollToTopButton

@OptIn(ExperimentalForeignApi::class, ExperimentalComposeUiApi::class)
@Composable
internal actual fun AssignmentScrollToTopButton(modifier: Modifier, onClick: () -> Unit) {
    val currentOnClick by rememberUpdatedState(onClick)
    UIKitView(
        modifier = modifier,
        factory = {
            val button = UIButton(frame = CGRectMake(2.0, 2.0, 44.0, 44.0))
            BJTUConfigureScrollToTopButton(button)
            button.addAction(
                UIAction.actionWithTitle("", image = null, identifier = null) { _ -> currentOnClick() },
                forControlEvents = UIControlEventTouchUpInside,
            )
            // Preserve the 44pt glass circle and the existing 48pt touch target.
            object : UIView(frame = CGRectMake(0.0, 0.0, 48.0, 48.0)) {
                override fun hitTest(point: CValue<CGPoint>, withEvent: UIEvent?): UIView? =
                    if (super.hitTest(point, withEvent) != null) button else null
            }.apply { addSubview(button) }
        },
        properties = UIKitInteropProperties(
            interactionMode = UIKitInteropInteractionMode.NonCooperative,
            placedAsOverlay = true,
            isNativeAccessibilityEnabled = true,
        ),
    )
}
