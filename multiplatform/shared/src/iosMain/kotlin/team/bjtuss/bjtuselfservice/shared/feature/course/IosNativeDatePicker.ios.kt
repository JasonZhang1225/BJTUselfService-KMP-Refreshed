package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.datetime.LocalDate
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIControlEventValueChanged
import platform.UIKit.UIDatePicker
import platform.UIKit.UIDatePickerMode
import platform.UIKit.UIDatePickerStyle
import platform.UIKit.UIView
import platform.darwin.NSObject

private const val UNIX_REFERENCE_SECONDS = 978_307_200.0

@OptIn(ExperimentalComposeUiApi::class, ExperimentalForeignApi::class)
@Composable
internal actual fun IosNativeDatePicker(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val latestOnDateSelected = rememberUpdatedState(onDateSelected)
    UIKitView(
        modifier = modifier.fillMaxWidth().height(360.dp),
        factory = {
            NativeDatePickerView { date -> latestOnDateSelected.value(date) }
        },
        update = { view -> view.updateDate(selectedDate) },
        onRelease = NativeDatePickerView::dispose,
    )
}

@OptIn(ExperimentalForeignApi::class)
private class NativeDatePickerView(
    private val onDateSelected: (LocalDate) -> Unit,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val picker = UIDatePicker()
    private val target = DatePickerTarget { pickerDate ->
        onDateSelected(localDateFromDate(pickerDate))
    }

    init {
        picker.datePickerMode = UIDatePickerMode.UIDatePickerModeDate
        picker.preferredDatePickerStyle = UIDatePickerStyle.UIDatePickerStyleInline
        picker.addTarget(
            target = target,
            action = NSSelectorFromString("valueChanged:"),
            forControlEvents = UIControlEventValueChanged,
        )
        addSubview(picker)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        picker.setFrame(bounds)
    }

    fun updateDate(date: LocalDate) {
        val targetDate = dateToNSDate(date)
        if (
            kotlin.math.abs(
                picker.date.timeIntervalSinceReferenceDate - targetDate.timeIntervalSinceReferenceDate,
            ) > 0.5
        ) {
            picker.setDate(targetDate, animated = false)
        }
    }

    fun dispose() {
        picker.removeTarget(
            target = target,
            action = NSSelectorFromString("valueChanged:"),
            forControlEvents = UIControlEventValueChanged,
        )
    }

    private fun dateToNSDate(date: LocalDate): NSDate = NSDate(
        timeIntervalSinceReferenceDate =
            date.toEpochDays() * 86_400.0 + 12.0 * 3_600.0 - UNIX_REFERENCE_SECONDS,
    )

    private fun localDateFromDate(date: NSDate): LocalDate {
        val epochDay = kotlin.math.floor(
            (date.timeIntervalSinceReferenceDate + UNIX_REFERENCE_SECONDS + 8.0 * 3_600.0) /
                86_400.0,
        ).toInt()
        return LocalDate.fromEpochDays(epochDay)
    }
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private class DatePickerTarget(
    private val onChanged: (NSDate) -> Unit,
) : NSObject() {
    @ObjCAction
    fun valueChanged(sender: UIDatePicker) {
        onChanged(sender.date)
    }
}
