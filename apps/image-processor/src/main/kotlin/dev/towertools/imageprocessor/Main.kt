package dev.towertools.imageprocessor

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.WString
import com.sun.jna.platform.win32.Shell32
import java.awt.EventQueue
import java.awt.Frame

fun main() {
    runCatching {
        val result = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(AppMetadata.id))
        check(result.toInt() == 0) { "Windows AppUserModelID setup failed: $result" }
    }.onFailure { AppLog.logger.warning(it.message) }

    val singleInstance = runCatching { SingleInstance.acquire(AppMetadata.id) }
        .onFailure { AppLog.logger.severe("Unable to initialize single-instance control: ${it.message}") }
        .getOrNull()
        ?: return

    application {
        val state = rememberWindowState(width = 1280.dp, height = 860.dp)
        val controller = remember { ImageProcessorController() }

        Window(onCloseRequest = ::exitApplication, state = state, title = AppMetadata.displayName, icon = painterResource("app-icon.png")) {
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(1080, 700)
                singleInstance.onActivate {
                    EventQueue.invokeLater {
                        window.extendedState = Frame.NORMAL
                        window.isVisible = true
                        window.toFront()
                        window.requestFocus()
                    }
                }
                onDispose {
                    controller.close()
                    singleInstance.close()
                }
            }

            MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ImageProcessorApp(controller, window)
                }
            }
        }
    }
}
