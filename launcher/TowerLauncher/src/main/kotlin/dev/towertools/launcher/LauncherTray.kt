package dev.towertools.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import java.awt.Image
import java.awt.Point
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.imageio.ImageIO

@Composable
internal fun LauncherTray(
    onShow: () -> Unit,
    onExit: () -> Unit,
    readAnchor: () -> TrayMenuAnchor? = ::readTrayMenuAnchor,
) {
    val latestOnShow = rememberUpdatedState(onShow)
    val latestOnExit = rememberUpdatedState(onExit)
    val latestReadAnchor = rememberUpdatedState(readAnchor)
    var menuAnchor by remember { mutableStateOf<TrayMenuAnchor?>(null) }

    DisposableEffect(Unit) {
        val tray = SystemTray.getSystemTray()
        val image = ImageIO.read(requireNotNull(LauncherTrayResource::class.java.getResource("/app-icon.png")))
        val size = tray.trayIconSize
        // Leave popupMenu unset: the Windows AWT menu's native glyph rendering
        // differs from the renderer already used by the launcher.
        val icon = TrayIcon(
            image.getScaledInstance(size.width, size.height, Image.SCALE_SMOOTH),
            AppMetadata.displayName,
        ).apply {
            isImageAutoSize = true
            addActionListener {
                menuAnchor = null
                latestOnShow.value.invoke()
            }
            addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (event.isPopupTrigger || event.button == MouseEvent.BUTTON3) {
                        // Windows tray events use physical pixels; MouseInfo supplies AWT coordinates and the target monitor.
                        menuAnchor = latestReadAnchor.value.invoke()?.let {
                            TrayMenuAnchor(Point(it.position), it.configuration)
                        }
                    }
                }
            })
        }
        tray.add(icon)
        onDispose { tray.remove(icon) }
    }

    menuAnchor?.let { anchor ->
        TrayMenuWindow(
            anchor = anchor,
            onShow = { menuAnchor = null; latestOnShow.value.invoke() },
            onExit = { menuAnchor = null; latestOnExit.value.invoke() },
            onDismiss = { if (menuAnchor === anchor) menuAnchor = null },
        )
    }
}

private object LauncherTrayResource
