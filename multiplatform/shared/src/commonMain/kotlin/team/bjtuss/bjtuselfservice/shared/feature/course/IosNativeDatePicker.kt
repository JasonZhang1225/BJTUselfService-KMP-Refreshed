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

/**
 * iOS 表单里的紧凑日期按钮（`UIDatePickerStyleCompact`）：显示所选日期，点按由系统弹出日历。
 * 宽高按系统控件的固有尺寸；早于 [minimumDate] 的日期不可选。其它平台不会进入这条分支。
 */
@Composable
internal expect fun IosCompactDatePicker(
    selectedDate: LocalDate,
    minimumDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
)
