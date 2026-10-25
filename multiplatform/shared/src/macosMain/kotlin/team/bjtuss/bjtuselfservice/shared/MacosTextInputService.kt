package team.bjtuss.bjtuselfservice.shared

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.SetSelectionCommand
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize

/**
 * 宿主窗口信息。Compose 的 `WindowInfoImpl` 在 compose-ui 内部是 internal，
 * 这里按公开接口自己实现一份：侧栏展开、缩放与跨屏拖动时由宿主更新尺寸。
 */
internal class MacosWindowInfo : WindowInfo {
    override var isWindowFocused: Boolean = true
    override var containerSize: IntSize = IntSize.Zero
    override var containerDpSize: DpSize = DpSize.Zero

    override val keyboardModifiers: PointerKeyboardModifiers
        get() = PointerKeyboardModifiers()
}

/**
 * macOS 端的文本输入服务。
 *
 * Compose Multiplatform 1.12 的 macOS 目标里 `MacosTextInputService` 仍是空实现
 * （上游 TODO CMP-9858）：它只记住当前输入会话，从不把编辑命令回传给 Compose，
 * 所以 Compose 文本框在 macOS 原生端收不到键入内容。
 *
 * 这里补上真正需要的部分：把宿主 NSView 收到的按键转成 `EditCommand`
 * 回传给当前输入会话。覆盖登录框、搜索框这类 ASCII 输入；
 * 不含输入法组字（中文候选）与系统自动更正，这是上游 macOS 文本输入尚未实现的部分。
 */
internal class MacosTextInputService : PlatformTextInputService {

    private class Session(
        var value: TextFieldValue,
        val onEditCommand: (List<EditCommand>) -> Unit,
        val onImeActionPerformed: (ImeAction) -> Unit,
        val imeAction: ImeAction,
    )

    private var session: Session? = null

    override fun startInput(
        value: TextFieldValue,
        imeOptions: ImeOptions,
        onEditCommand: (List<EditCommand>) -> Unit,
        onImeActionPerformed: (ImeAction) -> Unit,
    ) {
        session = Session(value, onEditCommand, onImeActionPerformed, imeOptions.imeAction)
    }

    override fun stopInput() {
        session = null
    }

    override fun showSoftwareKeyboard() = Unit

    override fun hideSoftwareKeyboard() = Unit

    override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {
        session?.value = newValue
    }

    /** 当前是否有输入会话；有则说明焦点在某个 Compose 文本框里。 */
    val hasActiveSession: Boolean
        get() = session != null

    /**
     * 在光标处插入文本。宿主在 [keyDown] 里拿到可打印字符后调用。
     */
    fun insertText(text: String) {
        val current = session ?: return
        val selection = current.value.selection
        val start = selection.min
        val end = selection.max
        val updated = current.value.text.replaceRange(start, end, text)
        val newCursor = start + text.length
        current.onEditCommand(
            listOf(
                SetSelectionCommand(start, end),
                CommitTextCommand(text, 1),
                SetSelectionCommand(newCursor, newCursor),
            )
        )
    }

    /** 退格：删除光标前一个字符。 */
    fun deleteBackward() {
        val current = session ?: return
        val selection = current.value.selection
        if (selection.collapsed) {
            val start = selection.min
            if (start == 0) return
            current.onEditCommand(
                listOf(
                    SetSelectionCommand(start - 1, start),
                    CommitTextCommand("", 1),
                    SetSelectionCommand(start - 1, start - 1),
                )
            )
        } else {
            val start = selection.min
            val end = selection.max
            current.onEditCommand(
                listOf(
                    SetSelectionCommand(start, end),
                    CommitTextCommand("", 1),
                    SetSelectionCommand(start, start),
                )
            )
        }
    }

    /** 回车：交给 Compose 的 IME 动作处理。 */
    fun performImeAction() {
        val current = session ?: return
        current.onImeActionPerformed(current.imeAction)
    }
}
