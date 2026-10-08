package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import kotlinx.datetime.LocalDate
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
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
    var pickerHeight by remember { mutableStateOf(420.dp) }
    UIKitView(
        modifier = modifier.fillMaxWidth().height(pickerHeight),
        factory = {
            NativeDatePickerView(
                onDateSelected = { date -> latestOnDateSelected.value(date) },
                onHeightChanged = { height -> pickerHeight = height.toFloat().dp },
            )
        },
        update = { view -> view.updateDate(selectedDate) },
        onRelease = NativeDatePickerView::dispose,
    )
}

@OptIn(ExperimentalForeignApi::class)
private class NativeDatePickerView(
    private val onDateSelected: (LocalDate) -> Unit,
    private val onHeightChanged: (Double) -> Unit,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private var reportedHeight = 0.0
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
        val width = bounds.useContents { size.width }
        val height = picker.sizeThatFits(CGSizeMake(width, 10000.0)).useContents { this.height }
            .coerceAtLeast(320.0)
        picker.setFrame(CGRectMake(0.0, 0.0, width, height))
        if (kotlin.math.abs(reportedHeight - height) > 0.5) {
            reportedHeight = height
            onHeightChanged(height)
        }
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
