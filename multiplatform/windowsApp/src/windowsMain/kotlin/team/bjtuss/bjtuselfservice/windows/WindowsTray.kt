package team.bjtuss.bjtuselfservice.windows

import java.awt.Font
import java.awt.Dimension
import java.awt.Image
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.MenuSelectionManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * AWT 只负责托盘图标，菜单交给 Swing 的 Unicode 绘制与字体回退。
 * Windows 的 AWT 原生菜单即使 canDisplay 中文、补齐编码模块并指定字体，仍可能画成方框。
 */
internal class WindowsTray(
    icon: Image,
    tooltip: String,
    onOpen: () -> Unit,
    onExit: () -> Unit,
) : AutoCloseable {
    private val trayIcon: TrayIcon
    // 托盘不是 Component；用一个不进任务栏的临时 owner 接收菜单焦点和键盘事件。
    private val menuOwner = JDialog().apply {
        isUndecorated = true
        isAlwaysOnTop = true
        setSize(1, 1)
    }
    private val popup = JPopupMenu()

    init {
        val font = menuFont()
        popup.font = font
        popup.add(JMenuItem("打开交大自由行").apply {
            this.font = font
            addActionListener { onOpen() }
        })
        popup.addSeparator()
        popup.add(JMenuItem("退出（停止远程查询）").apply {
            this.font = font
            addActionListener { onExit() }
        })
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) { menuOwner.isVisible = false }
            override fun popupMenuCanceled(event: PopupMenuEvent) { menuOwner.isVisible = false }
        })
        trayIcon = TrayIcon(icon, tooltip).apply {
            isImageAutoSize = true
            // 双击（或键盘选择）托盘图标打开窗口。
            addActionListener { onOpen() }
            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) = showMenu(event)
                override fun mouseReleased(event: MouseEvent) = showMenu(event)
            })
        }
        SystemTray.getSystemTray().add(trayIcon)
    }

    private fun showMenu(event: MouseEvent) {
        if (!event.isPopupTrigger || popup.isVisible) return
        // Windows TrayIcon 的事件仍用物理像素；Swing 窗口位置用缩放后的用户坐标。
        // MouseInfo 已按鼠标所在显示器转换坐标，不能把 event.x/y 直接传给 Swing。
        val pointer = MouseInfo.getPointerInfo() ?: return
        val configuration = pointer.device.defaultConfiguration
        val bounds = configuration.bounds
        val insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration)
        val workArea = Rectangle(
            bounds.x + insets.left, bounds.y + insets.top,
            bounds.width - insets.left - insets.right,
            bounds.height - insets.top - insets.bottom,
        )
        menuOwner.location = trayMenuLocation(pointer.location, popup.preferredSize, workArea)
        menuOwner.isVisible = true
        popup.show(menuOwner.contentPane, 0, 0)
        MenuSelectionManager.defaultManager().selectedPath = arrayOf(popup)
    }

    fun setTooltip(text: String) {
        if (trayIcon.toolTip != text) trayIcon.toolTip = text
    }

    override fun close() {
        popup.isVisible = false
        menuOwner.dispose()
        runCatching { SystemTray.getSystemTray().remove(trayIcon) }
    }

    private companion object {
        /** Windows 10/11 自带的界面字体；缺失时退回宋体，再退回逻辑字体。 */
        fun menuFont(): Font {
            val available = runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
            }.getOrDefault(emptySet())
            val family = listOf("Microsoft YaHei UI", "Microsoft YaHei", "SimSun").firstOrNull { it in available } ?: Font.DIALOG
            return Font(family, Font.PLAIN, 12)
        }
    }
}

/** 菜单贴近鼠标，完整落在当前显示器工作区内（排除任务栏，保留负坐标）。 */
internal fun trayMenuLocation(pointer: Point, menuSize: Dimension, workArea: Rectangle): Point = Point(
    pointer.x.coerceIn(workArea.x, (workArea.x + workArea.width - menuSize.width).coerceAtLeast(workArea.x)),
    pointer.y.coerceIn(workArea.y, (workArea.y + workArea.height - menuSize.height).coerceAtLeast(workArea.y)),
)
