package dev.towertools.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicReference

@Composable
internal fun ApplicationScope.LauncherWindow(
    registerActivation: (() -> Unit) -> Unit,
    state: WindowState = rememberWindowState(width = 960.dp, height = 680.dp),
    content: @Composable FrameWindowScope.() -> Unit,
) {
    var visible by remember { mutableStateOf(true) }
    val windowReference = remember { AtomicReference<ComposeWindow?>(null) }
    val icon = painterResource("app-icon.png")

    fun showWindow() {
        EventQueue.invokeLater {
            visible = true
            state.isMinimized = false
            windowReference.get()?.apply {
                isVisible = true
                toFront()
                requestFocus()
            }
        }
    }

    LauncherTray(onShow = ::showWindow, onExit = ::exitApplication)

    // Keep the same window composition so tab state and in-progress work survive hiding.
    Window(
        onCloseRequest = { visible = false },
        state = state,
        visible = visible,
        title = AppMetadata.displayName,
        icon = icon,
    ) {
        DisposableEffect(window) {
            windowReference.set(window)
            registerActivation(::showWindow)
            onDispose {
                registerActivation {}
                windowReference.set(null)
            }
        }
        content()
    }
}
