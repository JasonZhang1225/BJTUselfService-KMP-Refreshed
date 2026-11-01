package team.bjtuss.bjtuselfservice.shared.feature.course

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.DpSize
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
import platform.UIKit.UILayoutFittingCompressedSize
import kotlinx.cinterop.readValue
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
                compact = false,
                onDateSelected = { date -> latestOnDateSelected.value(date) },
                onSizeChanged = { _, height -> pickerHeight = height.toFloat().dp },
            )
        },
        update = { view -> view.updateDate(selectedDate) },
        onRelease = NativeDatePickerView::dispose,
    )
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalForeignApi::class)
@Composable
internal actual fun IosCompactDatePicker(
    selectedDate: LocalDate,
    minimumDate: LocalDate?,
    onDateSelected: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    val latestOnDateSelected = rememberUpdatedState(onDateSelected)
    // 先给系统紧凑按钮的典型尺寸，布局后按 sizeThatFits 回报的固有尺寸收紧。
    var pickerSize by remember { mutableStateOf(DpSize(128.dp, 34.dp)) }
    UIKitView(
        modifier = modifier.size(pickerSize),
        factory = {
            NativeDatePickerView(
                compact = true,
                onDateSelected = { date -> latestOnDateSelected.value(date) },
                onSizeChanged = { width, height -> pickerSize = DpSize(width.toFloat().dp, height.toFloat().dp) },
            )
        },
        update = { view ->
            view.updateMinimumDate(minimumDate)
            view.updateDate(selectedDate)
        },
        onRelease = NativeDatePickerView::dispose,
    )
}

@OptIn(ExperimentalForeignApi::class)
private class NativeDatePickerView(
    private val compact: Boolean,
    private val onDateSelected: (LocalDate) -> Unit,
    private val onSizeChanged: (width: Double, height: Double) -> Unit,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private var reportedWidth = 0.0
    private var reportedHeight = 0.0
    private val picker = UIDatePicker()
    private val target = DatePickerTarget { pickerDate ->
        // 紧凑按钮的文字宽度随日期变化，重新量一次，否则系统会退回更短的数字格式。
        if (compact) setNeedsLayout()
        onDateSelected(localDateFromDate(pickerDate))
    }

    init {
        picker.datePickerMode = UIDatePickerMode.UIDatePickerModeDate
        picker.preferredDatePickerStyle =
            if (compact) UIDatePickerStyle.UIDatePickerStyleCompact else UIDatePickerStyle.UIDatePickerStyleInline
        picker.addTarget(
            target = target,
            action = NSSelectorFromString("valueChanged:"),
            forControlEvents = UIControlEventValueChanged,
        )
        addSubview(picker)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val boundsWidth = bounds.useContents { size.width }
        val width: Double
        val height: Double
        if (compact) {
            // 紧凑按钮按固有尺寸放在右侧，宽度随日期文字和动态字体变化。
            // sizeThatFits 会把提议的宽度原样返回，要用压缩尺寸才是按钮本身的固有大小。
            val fitted = picker.systemLayoutSizeFittingSize(UILayoutFittingCompressedSize.readValue())
            width = fitted.useContents { this.width }.coerceIn(60.0, 320.0)
            height = fitted.useContents { this.height }.coerceAtLeast(28.0)
            picker.setFrame(CGRectMake((boundsWidth - width).coerceAtLeast(0.0), 0.0, width, height))
        } else {
            width = boundsWidth
            height = picker.sizeThatFits(CGSizeMake(width, 10000.0)).useContents { this.height }
                .coerceAtLeast(320.0)
            picker.setFrame(CGRectMake(0.0, 0.0, width, height))
        }
        if (kotlin.math.abs(reportedHeight - height) > 0.5 || (compact && kotlin.math.abs(reportedWidth - width) > 0.5)) {
            reportedWidth = width
            reportedHeight = height
            onSizeChanged(width, height)
        }
    }

    fun updateMinimumDate(date: LocalDate?) {
        val target = date?.let(::dateToNSDate)
        val current = picker.minimumDate
        if (target == null && current == null) return
        if (target != null && current != null &&
            kotlin.math.abs(current.timeIntervalSinceReferenceDate - target.timeIntervalSinceReferenceDate) <= 0.5
        ) return
        picker.minimumDate = target
    }

    fun updateDate(date: LocalDate) {
        val targetDate = dateToNSDate(date)
        if (
            kotlin.math.abs(
                picker.date.timeIntervalSinceReferenceDate - targetDate.timeIntervalSinceReferenceDate,
            ) > 0.5
        ) {
            picker.setDate(targetDate, animated = false)
            if (compact) setNeedsLayout()
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
