package dev.towertools.launcher.tabs.tools

import dev.towertools.launcher.AppPaths
import java.awt.Desktop
import java.io.File
import java.nio.file.Files

object ToolActions {
    fun launch(tool: LauncherTool) {
        require(Files.isRegularFile(tool.executable)) { "找不到可执行文件：${tool.executable}" }
        processBuilder(tool).start()
    }

    internal fun processBuilder(tool: LauncherTool): ProcessBuilder = ProcessBuilder(tool.executable.toString())
        .directory(tool.executable.parent.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)

    fun openDirectory(tool: LauncherTool) {
        require(Files.isDirectory(tool.executable.parent)) { "找不到工具目录：${tool.executable.parent}" }
        Desktop.getDesktop().open(tool.executable.parent.toFile())
    }

    fun openLauncherConfig() {
        Desktop.getDesktop().open(AppPaths.dataDirectory.toFile())
    }

    fun openToolsConfig() {
        val directory = AppPaths.dataDirectory.parent.resolve("TowerTools")
        Files.createDirectories(directory)
        Desktop.getDesktop().open(directory.toFile())
    }
}
