package dev.towertools.launcher

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.application
import com.sun.jna.WString
import com.sun.jna.platform.win32.Shell32
import dev.towertools.launcher.tabs.cmd.CmdTab
import dev.towertools.launcher.tabs.cmd.CmdTabState
import dev.towertools.launcher.tabs.cmd.ProjectController
import dev.towertools.launcher.tabs.repositories.RepositoryController
import dev.towertools.launcher.tabs.repositories.RepositoryTab
import dev.towertools.launcher.tabs.repositories.RepositoryTabState
import dev.towertools.launcher.tabs.tools.CatalogRepository
import dev.towertools.launcher.tabs.tools.ToolsTab
import dev.towertools.launcher.tabs.tools.ToolsTabState

fun main() {
    runCatching {
        val result = Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(WString(AppMetadata.id))
        check(result.toInt() == 0) { "Windows AppUserModelID setup failed: $result" }
    }.onFailure { AppLog.logger.warning(it.message) }

    val singleInstance = runCatching { SingleInstance.acquire(AppMetadata.id) }
        .onFailure { AppLog.logger.severe("Unable to initialize single-instance control: ${it.message}") }
        .getOrNull()
        ?: return

    val repository = CatalogRepository.default()

    application {
        DisposableEffect(singleInstance) {
            onDispose(singleInstance::close)
        }
        LauncherWindow(registerActivation = singleInstance::onActivate) {
            MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
                val toolsState = remember { ToolsTabState(repository) }
                val projectController = remember { ProjectController() }
                val projectsState = remember { CmdTabState(projectController) }
                val repositoryController = remember { RepositoryController() }
                val repositoryState = remember { RepositoryTabState(repositoryController) }
                DisposableEffect(repositoryState) {
                    onDispose(repositoryState::close)
                }
                LauncherApp(
                    listOf(
                        LauncherTab(TabId.TOOLS) { ToolsTab(repository, toolsState) },
                        LauncherTab(TabId.CMD) {
                            CmdTab(projectController, projectsState, window)
                        },
                        LauncherTab(TabId.REPOSITORIES) {
                            RepositoryTab(repositoryController, repositoryState, window)
                        },
                    ),
                )
            }
        }
    }
}
