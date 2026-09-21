package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.datetime.LocalDate

@Composable
internal actual fun IosNativeDatePicker(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier,
) = Unit
