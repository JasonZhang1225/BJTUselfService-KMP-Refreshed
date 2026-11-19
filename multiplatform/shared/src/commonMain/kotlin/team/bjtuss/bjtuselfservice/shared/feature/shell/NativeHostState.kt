package team.bjtuss.bjtuselfservice.shared.feature.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Geometry reported by one platform presentation host, never by an account.
 * The Activity/navigation container owns this object and passes it to its Compose pages.
 * It contains no session, native controller, navigation callbacks or data-retention policy.
 */
@Stable
class NativeHostState {
    /** Full tab-bar frame, including its bottom safe area, in points/dp. */
    var glassTabBarBottomInsetDp: Float by mutableStateOf(0f)
    /** UIKit already constrains the body's horizontal safe rectangle. */
    var systemManagedContentBounds: Boolean by mutableStateOf(false)
    /** System bottom safe area; Android carries the first-frame measurement in its route Intent. */
    var systemBottomInsetDp: Float by mutableStateOf(0f)
    /** Native navigation bar's bottom edge, including the status bar, in points/dp. */
    var glassTopBarInsetDp: Float by mutableStateOf(0f)
}
