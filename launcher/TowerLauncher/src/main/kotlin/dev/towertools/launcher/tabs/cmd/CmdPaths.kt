package dev.towertools.launcher.tabs.cmd

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object CmdPaths {
    private const val tabKey = "cmd"

    val dataDirectory: Path by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")

        localAppData.resolve("Alice-tower").resolve("TowerLauncher").resolve("tabs").resolve(tabKey)
            .also(Files::createDirectories)
    }
}
