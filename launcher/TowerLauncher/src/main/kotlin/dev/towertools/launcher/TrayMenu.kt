package dev.towertools.launcher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Dimension
import java.awt.GraphicsConfiguration
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window as AwtWindow
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.WindowConstants

internal const val TRAY_MENU_WINDOW_NAME = "tower-launcher-tray-menu"
internal const val TRAY_MENU_WIDTH = 148
internal const val TRAY_MENU_ROW_HEIGHT = 30
internal const val TRAY_MENU_HEIGHT = TRAY_MENU_ROW_HEIGHT * 2 + 9
private const val TRAY_MENU_GAP = 2

internal data class TrayMenuAnchor(val position: Point, val configuration: GraphicsConfiguration)

internal fun readTrayMenuAnchor(): TrayMenuAnchor? =
    MouseInfo.getPointerInfo()?.let { TrayMenuAnchor(it.location, it.device.defaultConfiguration) }

internal fun fitTrayMenuPosition(pointer: Point, size: Dimension, screenBounds: Rectangle): Point {
    val maxX = (screenBounds.x + screenBounds.width - size.width).coerceAtLeast(screenBounds.x)
    val maxY = (screenBounds.y + screenBounds.height - size.height).coerceAtLeast(screenBounds.y)
    val x = if (pointer.x + TRAY_MENU_GAP <= maxX) {
        pointer.x + TRAY_MENU_GAP
    } else {
        pointer.x - size.width - TRAY_MENU_GAP
    }
    val y = if (pointer.y + TRAY_MENU_GAP <= maxY) {
        pointer.y + TRAY_MENU_GAP
    } else {
        pointer.y - size.height - TRAY_MENU_GAP
    }
    return Point(x.coerceIn(screenBounds.x, maxX), y.coerceIn(screenBounds.y, maxY))
}

@Composable
internal fun TrayMenuWindow(anchor: TrayMenuAnchor, onShow: () -> Unit, onExit: () -> Unit, onDismiss: () -> Unit) {
    val latestOnDismiss = rememberUpdatedState(onDismiss)
    SwingWindow(
        create = {
            ComposeWindow(anchor.configuration).apply {
                name = TRAY_MENU_WINDOW_NAME
                isUndecorated = true
                type = AwtWindow.Type.POPUP
                isResizable = false
                isAlwaysOnTop = true
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                setSize(TRAY_MENU_WIDTH, TRAY_MENU_HEIGHT)
                location = fitTrayMenuPosition(anchor.position, size, anchor.configuration.bounds)
                addWindowFocusListener(object : WindowFocusListener {
                    override fun windowGainedFocus(event: WindowEvent) = Unit
                    override fun windowLostFocus(event: WindowEvent) = latestOnDismiss.value.invoke()
                })
            }
        },
        dispose = { it.dispose() },
        update = {
            val location = fitTrayMenuPosition(anchor.position, it.size, anchor.configuration.bounds)
            if (it.location != location) it.location = location
        },
        onPreviewKeyEvent = {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) {
                onDismiss()
                true
            } else false
        },
    ) {
        MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
            TrayMenuContent(onShow, onExit)
        }
    }
}

@Composable
internal fun TrayMenuContent(onShow: () -> Unit, onExit: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colors.surface,
        border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.16f)),
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            TrayMenuItem("显示启动器", onShow)
            Divider()
            TrayMenuItem("退出", onExit)
        }
    }
}

@Composable
private fun TrayMenuItem(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier.fillMaxWidth()
            .height(TRAY_MENU_ROW_HEIGHT.dp)
            .background(if (hovered || focused) MaterialTheme.colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, lineHeight = 18.sp)
    }
}
