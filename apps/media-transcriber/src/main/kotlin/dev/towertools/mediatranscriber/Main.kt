package dev.towertools.mediatranscriber

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.WString
import com.sun.jna.platform.win32.Shell32
import java.awt.EventQueue
import java.awt.Dimension
import java.awt.Frame

fun main() {
    runCatching {
        val result = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(AppMetadata.id))
        check(result.toInt() == 0) { "Windows AppUserModelID setup failed: $result" }
    }.onFailure { AppLog.logger.warning(it.message) }

    val singleInstance = runCatching { SingleInstance.acquire(AppMetadata.id) }
        .onFailure { AppLog.logger.severe("Unable to initialize single-instance control: ${it.message}") }
        .getOrNull() ?: return

    application {
        val windowState = rememberWindowState(width = 1180.dp, height = 720.dp)
        val controller = remember { MediaTranscriberController() }
        var confirmExit by remember { mutableStateOf(false) }
        fun requestExit() { if (controller.state.isBusy) confirmExit = true else exitApplication() }

        Window(onCloseRequest = ::requestExit, state = windowState, title = AppMetadata.displayName, icon = painterResource("app-icon.png")) {
            DisposableEffect(window) {
                window.minimumSize = Dimension(760, 540)
                singleInstance.onActivate {
                    EventQueue.invokeLater { window.extendedState = Frame.NORMAL; window.isVisible = true; window.toFront(); window.requestFocus() }
                }
                onDispose { controller.close(); singleInstance.close() }
            }
            MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
                Surface(modifier = Modifier) { MediaTranscriberApp(controller, window) }
                if (confirmExit) AlertDialog(
                    onDismissRequest = { confirmExit = false },
                    title = { Text("取消任务并退出？") },
                    text = { Text("当前任务会被取消，临时文件将被清理。") },
                    confirmButton = { Button(onClick = { confirmExit = false; controller.cancel { exitApplication() } }) { Text("取消任务并退出") } },
                    dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续任务") } },
                )
            }
        }
    }
}
