package dev.towertools.researchlibrarylauncher

import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.util.logging.Level

data class OperationResult(val message: String, val isError: Boolean = false) {
    companion object {
        fun info(message: String) = OperationResult(message)
        fun error(message: String) = OperationResult(message, isError = true)
    }
}

class ResearchLibraryController(
    private val settings: LauncherSettings = LauncherSettings(),
) {
    var scriptPath: Path = settings.loadScriptPath()
        private set

    fun launchLibrary(): OperationResult {
        if (isServiceRunning()) {
            return openBrowser("资料库已经在运行，已在浏览器中打开。")
        }
        if (!Files.isRegularFile(scriptPath)) {
            return OperationResult.error("找不到启动脚本，请点击“重新选择启动脚本”。")
        }

        return runCatching {
            val workingDirectory = requireNotNull(scriptPath.parent) { "启动脚本缺少父目录" }
            val command = buildLaunchCommand(scriptPath)
            ProcessBuilder("cmd.exe", "/d", "/s", "/c", command)
                .directory(workingDirectory.toFile())
                .start()
            AppLog.logger.info("Started research library with script: $scriptPath")
            OperationResult.info("启动命令已发送。首次运行可能需要安装依赖，请查看弹出的命令窗口。")
        }.getOrElse { error ->
            AppLog.logger.log(Level.SEVERE, "Unable to start research library", error)
            OperationResult.error("无法启动资料库：${error.message ?: "未知错误"}")
        }
    }

    fun openBrowser(): OperationResult = openBrowser("已在浏览器中打开资料库。")

    fun openProjectDirectory(): OperationResult {
        val directory = scriptPath.parent
        if (directory == null || !Files.isDirectory(directory)) {
            return OperationResult.error("找不到研究资料库目录，请重新选择启动脚本。")
        }
        return runCatching {
            requireDesktopAction(Desktop.Action.OPEN)
            Desktop.getDesktop().open(directory.toFile())
            OperationResult.info("已打开研究资料库目录。")
        }.getOrElse { desktopError("打开项目目录", it) }
    }

    fun updateScriptPath(path: Path): OperationResult {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized) || !normalized.fileName.toString().endsWith(".cmd", true)) {
            return OperationResult.error("请选择一个有效的 .cmd 启动脚本。")
        }
        return runCatching {
            settings.saveScriptPath(normalized)
            scriptPath = normalized
            AppLog.logger.info("Updated research library script path: $scriptPath")
            OperationResult.info("启动脚本已更新。")
        }.getOrElse { error ->
            AppLog.logger.log(Level.WARNING, "Unable to save script path", error)
            OperationResult.error("无法保存启动脚本设置：${error.message ?: "未知错误"}")
        }
    }

    private fun openBrowser(successMessage: String): OperationResult = runCatching {
        requireDesktopAction(Desktop.Action.BROWSE)
        Desktop.getDesktop().browse(LIBRARY_URI)
        OperationResult.info(successMessage)
    }.getOrElse { desktopError("打开浏览器", it) }

    private fun desktopError(action: String, error: Throwable): OperationResult {
        AppLog.logger.log(Level.WARNING, "Unable to $action", error)
        return OperationResult.error("无法$action：${error.message ?: "系统不支持此操作"}")
    }

    private fun requireDesktopAction(action: Desktop.Action) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)) {
            "当前系统不支持此操作"
        }
    }

    companion object {
        val DEFAULT_SCRIPT_PATH: Path = Paths.get(
            "C:\\All\\Dev\\Repo\\research-reference-collection\\start.cmd",
        )
        val LIBRARY_URI: URI = URI("http://127.0.0.1:4173")

        internal fun buildLaunchCommand(scriptPath: Path): String {
            val directory = requireNotNull(scriptPath.parent)
            return "start \"Research Library\" /D ${quoteForCmd(directory)} cmd.exe /d /c call ${quoteForCmd(scriptPath)}"
        }

        private fun quoteForCmd(value: Path): String = "\"${value.toString().replace("\"", "\"\"")}\""

        private fun isServiceRunning(): Boolean = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 4173), 250)
            }
            true
        }.getOrDefault(false)
    }
}

class LauncherSettings(
    private val settingsFile: Path = AppPaths.dataDirectory.resolve("settings.properties"),
) {
    fun loadScriptPath(): Path {
        if (!Files.isRegularFile(settingsFile)) return ResearchLibraryController.DEFAULT_SCRIPT_PATH
        return runCatching {
            Files.newInputStream(settingsFile).use { input ->
                Properties().apply { load(input) }.getProperty(SCRIPT_PATH_KEY)
            }?.takeIf(String::isNotBlank)?.let(Paths::get)
                ?: ResearchLibraryController.DEFAULT_SCRIPT_PATH
        }.onFailure {
            AppLog.logger.log(Level.WARNING, "Unable to read launcher settings", it)
        }.getOrDefault(ResearchLibraryController.DEFAULT_SCRIPT_PATH)
    }

    fun saveScriptPath(path: Path) {
        Files.createDirectories(settingsFile.parent)
        val properties = Properties().apply { setProperty(SCRIPT_PATH_KEY, path.toString()) }
        Files.newOutputStream(settingsFile).use { output ->
            properties.store(output, "Research Library Launcher")
        }
    }

    private companion object {
        const val SCRIPT_PATH_KEY = "scriptPath"
    }
}
