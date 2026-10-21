package team.bjtuss.bjtuselfservice.windows

import java.awt.Font
import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon

/**
 * 系统托盘图标与右键菜单。直接用 AWT 并给菜单指定中文字体：AWT 原生菜单默认的逻辑字体
 * 在精简运行时里找不到中文字形，Compose `Tray` 的菜单会显示成方框。
 */
internal class WindowsTray(
    icon: Image,
    tooltip: String,
    onOpen: () -> Unit,
    onExit: () -> Unit,
) : AutoCloseable {
    private val trayIcon: TrayIcon

    init {
        val font = menuFont()
        val popup = PopupMenu().apply { this.font = font }
        popup.add(MenuItem("打开交大自由行").apply {
            this.font = font
            addActionListener { onOpen() }
        })
        popup.addSeparator()
        popup.add(MenuItem("退出（停止远程查询）").apply {
            this.font = font
            addActionListener { onExit() }
        })
        trayIcon = TrayIcon(icon, tooltip, popup).apply {
            isImageAutoSize = true
            // 双击（或键盘选择）托盘图标打开窗口。
            addActionListener { onOpen() }
        }
        SystemTray.getSystemTray().add(trayIcon)
    }

    fun setTooltip(text: String) {
        if (trayIcon.toolTip != text) trayIcon.toolTip = text
    }

    override fun close() {
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
