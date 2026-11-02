package dev.shinglem.netbird.ui.app

import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.MouseInfo
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JWindow
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/** 托盘菜单的一项：只支持普通条目与分隔线，托盘是系统级入口，不需要勾选与子菜单。 */
sealed interface TrayMenuItem {
    data class Action(
        val text: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit,
    ) : TrayMenuItem

    data object Separator : TrayMenuItem
}

/**
 * 托盘图标 + 右键菜单。
 *
 * **不用** `androidx.compose.ui.window.Tray`：它的菜单是 AWT `PopupMenu`（原生 Win32 菜单），
 * 菜单文字交给 Win32 前按默认字符集转 ANSI，中文整片变成方框；`MenuItem.setFont` 对原生菜单无效。
 *
 * 这里改用 Swing `JPopupMenu`：Java2D 渲染、走 LAF 字体，中文正常；边缘翻转、失焦与 Esc 关闭
 * 都是 Swing 自带的。上游问题见 CMP-1936（仍 Open）。
 */
class DesktopTray(
    private val icon: Image,
    private val tooltip: String,
    private val onActivate: () -> Unit,
    private val menu: () -> List<TrayMenuItem>,
) {
    private var trayIcon: TrayIcon? = null

    fun install() {
        if (trayIcon != null) return
        if (!SystemTray.isSupported()) {
            System.err.println("当前平台不支持托盘图标")
            return
        }
        val iconToAdd = TrayIcon(icon).apply {
            isImageAutoSize = true
            toolTip = tooltip
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (event.isPopupTrigger) showMenu() else if (event.clickCount >= 2) onActivate()
                }
            })
        }
        try {
            SystemTray.getSystemTray().add(iconToAdd)
        } catch (failure: Exception) {
            // 托盘区满或被策略禁用：不该因此让整个应用起不来。
            System.err.println("无法添加托盘图标：$failure")
            return
        }
        iconToAdd.addActionListener { onActivate() }
        trayIcon = iconToAdd
    }

    fun dispose() {
        trayIcon?.let { iconToRemove -> runCatching { SystemTray.getSystemTray().remove(iconToRemove) } }
        trayIcon = null
    }

    private fun showMenu() {
        val entries = menu()
        if (entries.isEmpty()) return
        val location = MouseInfo.getPointerInfo()?.location ?: return

        val popup = JPopupMenu()
        entries.forEach { entry ->
            when (entry) {
                is TrayMenuItem.Action -> popup.add(
                    JMenuItem(entry.text).apply {
                        isEnabled = entry.enabled
                        addActionListener { entry.onClick() }
                    },
                )

                TrayMenuItem.Separator -> popup.addSeparator()
            }
        }

        // JPopupMenu.show 要求 invoker 已经显示，而托盘常驻时主窗口可能是隐藏的：
        // 用一个 1×1 的透明窗口顶着，菜单一关就销毁。
        //
        // 位置按「菜单底边贴住鼠标」算再夹进 availableBounds：托盘在屏幕底部，
        // 向下展开会被任务栏切掉最后一项。
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val size = popup.preferredSize
        val x = location.x.coerceIn(bounds.x, (bounds.x + bounds.width - size.width).coerceAtLeast(bounds.x))
        val y = (location.y - size.height).coerceAtLeast(bounds.y)
        val invoker = JWindow().apply {
            setBounds(x, y, 1, 1)
            isAlwaysOnTop = true
            runCatching { opacity = 0f }
        }
        popup.addPopupMenuListener(
            object : PopupMenuListener {
                override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
                override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) = invoker.dispose()
                override fun popupMenuCanceled(event: PopupMenuEvent) = invoker.dispose()
            },
        )
        invoker.isVisible = true
        popup.show(invoker, 0, 0)
    }
}
