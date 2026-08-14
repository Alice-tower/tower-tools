package dev.towertools.mediatranscriber

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AppPaths {
    val dataDirectory: Path by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")

        localAppData.resolve("Alice-tower").resolve("TowerTools").resolve(AppMetadata.id)
            .also(Files::createDirectories)
    }

    val logDirectory: Path by lazy {
        dataDirectory.resolve("logs").also(Files::createDirectories)
    }

    val tempDirectory: Path by lazy {
        dataDirectory.resolve("temp").also(Files::createDirectories)
    }

    val settingsFile: Path by lazy { dataDirectory.resolve("settings.json") }
}
