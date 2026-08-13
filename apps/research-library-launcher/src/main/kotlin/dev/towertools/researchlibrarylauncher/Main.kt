package dev.towertools.researchlibrarylauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths

fun main() {
    val singleInstance = runCatching { SingleInstance.acquire(AppMetadata.id) }
        .onFailure { AppLog.logger.severe("Unable to initialize single-instance control: ${it.message}") }
        .getOrNull()
        ?: return

    val controller = ResearchLibraryController()

    application {
        val state = rememberWindowState(width = 760.dp, height = 480.dp)

        Window(onCloseRequest = ::exitApplication, state = state, title = AppMetadata.displayName) {
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

            var scriptPath by remember { mutableStateOf(controller.scriptPath) }
            var status by remember { mutableStateOf(OperationResult.info("正在检查研究资料库……")) }
            var working by remember { mutableStateOf(false) }

            suspend fun runOperation(operation: () -> OperationResult) {
                working = true
                status = withContext(Dispatchers.IO) { operation() }
                working = false
            }

            LaunchedEffect(Unit) {
                runOperation(controller::launchLibrary)
            }

            MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Text(AppMetadata.displayName, style = MaterialTheme.typography.h4)
                        Text(
                            "打开本工具时会自动启动本地资料库。运行期间请保留弹出的命令窗口，按 Ctrl+C 可停止服务。",
                            style = MaterialTheme.typography.body1,
                        )

                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (status.isError) {
                                MaterialTheme.colors.error.copy(alpha = 0.12f)
                            } else {
                                MaterialTheme.colors.primary.copy(alpha = 0.10f)
                            },
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Text(status.message, modifier = Modifier.padding(16.dp))
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("启动脚本", style = MaterialTheme.typography.subtitle2)
                            Text(scriptPath.toString(), style = MaterialTheme.typography.body2)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                enabled = !working,
                                onClick = { status = controller.launchLibrary() },
                            ) {
                                Text(if (working) "处理中……" else "启动资料库")
                            }
                            OutlinedButton(onClick = { status = controller.openBrowser() }) {
                                Text("打开浏览器")
                            }
                            OutlinedButton(onClick = { status = controller.openProjectDirectory() }) {
                                Text("打开项目目录")
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                chooseCommandFile(window, scriptPath)?.let { selected ->
                                    status = controller.updateScriptPath(selected)
                                    scriptPath = controller.scriptPath
                                }
                            },
                        ) {
                            Text("重新选择启动脚本")
                        }
                    }
                }
            }
        }
    }
}

private fun chooseCommandFile(owner: Frame, currentPath: Path): Path? {
    val dialog = FileDialog(owner, "选择研究资料库启动脚本", FileDialog.LOAD).apply {
        directory = currentPath.parent?.toString()
        file = "*.cmd"
        isVisible = true
    }
    val selectedFile = dialog.file ?: return null
    return Paths.get(dialog.directory, selectedFile)
}
