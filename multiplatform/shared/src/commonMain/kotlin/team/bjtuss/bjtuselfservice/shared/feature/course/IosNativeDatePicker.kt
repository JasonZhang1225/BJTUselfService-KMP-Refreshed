package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate

/** iOS 使用 UIKit UIDatePicker；其它平台不会进入这条分支。 */
@Composable
internal expect fun IosNativeDatePicker(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
)
