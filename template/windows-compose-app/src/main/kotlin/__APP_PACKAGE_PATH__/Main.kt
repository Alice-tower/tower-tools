package __APP_PACKAGE__

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        val state = rememberWindowState(width = 920.dp, height = 640.dp)

        Window(onCloseRequest = ::exitApplication, state = state, title = AppMetadata.displayName, icon = painterResource("app-icon.png")) {
            DisposableEffect(window) {
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
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(AppMetadata.displayName, style = MaterialTheme.typography.h4)
                        Text(AppMetadata.description, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}
