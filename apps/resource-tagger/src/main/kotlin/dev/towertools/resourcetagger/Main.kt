package dev.towertools.resourcetagger

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.Frame

fun main() {
    val singleInstance = runCatching { SingleInstance.acquire(AppMetadata.id) }
        .onFailure { AppLog.logger.severe("Unable to initialize single-instance control: ${it.message}") }
        .getOrNull()
        ?: return

    application {
        val state = rememberWindowState(width = 1320.dp, height = 820.dp)

        Window(onCloseRequest = ::exitApplication, state = state, title = AppMetadata.displayName) {
            DisposableEffect(window) {
                window.minimumSize = java.awt.Dimension(1120, 680)
                singleInstance.onActivate {
                    EventQueue.invokeLater {
                        window.extendedState = Frame.NORMAL
                        window.isVisible = true
                        window.toFront()
                        window.requestFocus()
                    }
                }
                onDispose(singleInstance::close)
            }

            MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LibraryApp(window)
                }
            }
        }
    }
}
