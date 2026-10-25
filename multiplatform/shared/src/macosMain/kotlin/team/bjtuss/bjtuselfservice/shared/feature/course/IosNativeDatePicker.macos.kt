package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate

/** macOS 不走 iOS 的 UIDatePicker 分支，保留空实现占位。 */
@Composable
internal actual fun IosNativeDatePicker(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier,
) = Unit
