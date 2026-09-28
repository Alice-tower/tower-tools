package dev.towertools.launcher.tabs.cmd

import dev.towertools.launcher.AppLog
import java.awt.Desktop
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.logging.Level

data class LocalProject(
    val id: String,
    val name: String,
    val scriptPath: Path,
    val webPort: Int? = null,
)

data class OperationResult(val message: String, val isError: Boolean = false) {
    companion object {
        fun info(message: String) = OperationResult(message)
        fun error(message: String) = OperationResult(message, isError = true)
    }
}

enum class PortStatus { IN_USE, AVAILABLE, UNKNOWN }

class ProjectRegistry(
    internal val projectsFile: Path = CmdPaths.dataDirectory.resolve("projects.properties"),
) {
    fun load(): List<LocalProject> {
        if (!Files.exists(projectsFile)) return emptyList()
        val properties = Properties()
        Files.newInputStream(projectsFile).use(properties::load)
        val count = properties.getProperty("count")?.toIntOrNull()
            ?.takeIf { it >= 0 } ?: error("项目配置中的数量无效")
        return (0 until count).map { index ->
            val prefix = "project.$index."
            val portText = properties.getProperty(prefix + "webPort").orEmpty()
            LocalProject(
                id = properties.getProperty(prefix + "id") ?: error("项目 $index 缺少 ID"),
                name = properties.getProperty(prefix + "name") ?: error("项目 $index 缺少名称"),
                scriptPath = Paths.get(properties.getProperty(prefix + "scriptPath")
                    ?: error("项目 $index 缺少脚本路径")),
                webPort = if (portText.isEmpty()) null else (
                    portText.toIntOrNull()?.takeIf { it in 1..65535 }
                        ?: error("项目 $index 的端口无效")
                ),
            )
        }
    }

    fun save(projects: List<LocalProject>) {
        val directory = projectsFile.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "projects-", ".tmp")
        try {
            val properties = Properties()
            properties.setProperty("count", projects.size.toString())
            projects.forEachIndexed { index, project ->
                val prefix = "project.$index."
                properties.setProperty(prefix + "id", project.id)
                properties.setProperty(prefix + "name", project.name)
                properties.setProperty(prefix + "scriptPath", project.scriptPath.toString())
                properties.setProperty(prefix + "webPort", project.webPort?.toString().orEmpty())
            }
            Files.newOutputStream(temporary).use { properties.store(it, "Local project launcher") }
            Files.move(temporary, projectsFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

class ProjectController(registry: ProjectRegistry? = null, appearanceStore: CmdAppearanceStore? = null) {
    private val registry: ProjectRegistry by lazy { registry ?: ProjectRegistry() }
    private val appearanceStore: CmdAppearanceStore by lazy {
        appearanceStore ?: CmdAppearanceStore(this.registry.projectsFile.resolveSibling("appearance.properties"))
    }

    fun projects(): List<LocalProject> = registry.load()

    fun appearance(): CmdAppearanceSettings = appearanceStore.load()

    fun updateAppearance(id: String, category: String, order: Int, favorite: Boolean? = null): CmdAppearanceSettings =
        appearanceStore.updateProject(id, category, order, favorite)

    fun updateCategoryOrder(order: List<String>): CmdAppearanceSettings = appearanceStore.updateCategoryOrder(order)

    fun saveProject(existingId: String?, name: String, scriptPath: Path, webPort: Int?): List<LocalProject> {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty()) { "请输入项目名称" }
        require(scriptPath.isAbsolute) { "请输入 .cmd 文件的完整路径" }
        val normalizedPath = scriptPath.toAbsolutePath().normalize()
        require(normalizedPath.fileName.toString().endsWith(".cmd", ignoreCase = true)) { "请选择 .cmd 启动脚本" }
        require(Files.isRegularFile(normalizedPath)) { "找不到启动脚本：$normalizedPath" }
        require(webPort == null || webPort in 1..65535) { "端口必须在 1 到 65535 之间" }

        val current = registry.load()
        val project = LocalProject(existingId ?: UUID.randomUUID().toString(), normalizedName, normalizedPath, webPort)
        val updated = if (existingId == null) {
            current + project
        } else {
            require(current.any { it.id == existingId }) { "项目已不存在，请刷新列表" }
            current.map { if (it.id == existingId) project else it }
        }
        registry.save(updated)
        return updated
    }

    fun deleteProject(id: String): List<LocalProject> {
        val current = registry.load()
        require(current.any { it.id == id }) { "项目已不存在，请刷新列表" }
        val updated = current.filterNot { it.id == id }
        registry.save(updated)
        runCatching { appearanceStore.removeProject(id) }
            .onFailure { AppLog.logger.log(Level.WARNING, "Unable to remove deleted project appearance: $id", it) }
        return updated
    }

    fun launch(project: LocalProject): OperationResult {
        if (!Files.isRegularFile(project.scriptPath)) return OperationResult.error("找不到启动脚本：${project.scriptPath}")
        return runCatching {
            val directory = requireNotNull(project.scriptPath.parent) { "启动脚本缺少父目录" }
            ProcessBuilder("cmd.exe", "/d", "/s", "/c", buildLaunchCommand(project.scriptPath))
                .directory(directory.toFile())
                .start()
            AppLog.logger.info("Started local project: ${project.scriptPath}")
            OperationResult.info("启动命令已发送，请查看弹出的命令窗口。")
        }.getOrElse { error ->
            AppLog.logger.log(Level.SEVERE, "Unable to launch local project", error)
            OperationResult.error("启动失败：${error.message ?: "未知错误"}")
        }
    }

    fun openBrowser(project: LocalProject): OperationResult {
        val port = project.webPort ?: return OperationResult.error("此项目没有登记 Web 端口")
        return desktopAction("打开浏览器") {
            requireDesktopAction(Desktop.Action.BROWSE)
            Desktop.getDesktop().browse(URI("http://127.0.0.1:$port/"))
        }
    }

    fun openProjectDirectory(project: LocalProject): OperationResult {
        val directory = project.scriptPath.parent
        if (directory == null || !Files.isDirectory(directory)) return OperationResult.error("找不到项目目录")
        return desktopAction("打开项目目录") {
            requireDesktopAction(Desktop.Action.OPEN)
            Desktop.getDesktop().open(directory.toFile())
        }
    }

    private fun desktopAction(action: String, operation: () -> Unit): OperationResult = runCatching {
        operation()
        OperationResult.info("已$action。")
    }.getOrElse {
        AppLog.logger.log(Level.WARNING, "Unable to $action", it)
        OperationResult.error("无法$action：${it.message ?: "系统不支持此操作"}")
    }

    private fun requireDesktopAction(action: Desktop.Action) {
        check(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action)) {
            "当前系统不支持此操作"
        }
    }

    companion object {
        internal fun buildLaunchCommand(scriptPath: Path): String {
            val directory = requireNotNull(scriptPath.parent)
            return "start \"Local Project\" /D ${quoteForCmd(directory)} cmd.exe /d /c call ${quoteForCmd(scriptPath)}"
        }

        private fun quoteForCmd(value: Path): String = "\"${value.toString().replace("\"", "\"\"")}\""

        fun portStatus(port: Int): PortStatus = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
            }
            PortStatus.IN_USE
        } catch (_: ConnectException) {
            PortStatus.AVAILABLE
        } catch (_: SocketTimeoutException) {
            PortStatus.UNKNOWN
        } catch (_: Exception) {
            PortStatus.UNKNOWN
        }
    }
}
