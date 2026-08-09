package dev.towertools.launcher

import java.awt.Desktop
import java.nio.file.Files

object ToolActions {
    fun launch(tool: LauncherTool) {
        require(Files.isRegularFile(tool.executable)) { "找不到可执行文件：${tool.executable}" }
        ProcessBuilder(tool.executable.toString())
            .directory(tool.executable.parent.toFile())
            .start()
    }

    fun openDirectory(tool: LauncherTool) {
        require(Files.isDirectory(tool.executable.parent)) { "找不到工具目录：${tool.executable.parent}" }
        Desktop.getDesktop().open(tool.executable.parent.toFile())
    }

    fun openLogs(tool: LauncherTool) {
        val logDirectory = localAppData()
            .resolve("Alice-tower")
            .resolve("TowerTools")
            .resolve(tool.id)
            .resolve("logs")
        Files.createDirectories(logDirectory)
        Desktop.getDesktop().open(logDirectory.toFile())
    }

    private fun localAppData() = System.getenv("LOCALAPPDATA")
        ?.takeIf(String::isNotBlank)
        ?.let(java.nio.file.Paths::get)
        ?: java.nio.file.Paths.get(System.getProperty("user.home"), "AppData", "Local")
}
