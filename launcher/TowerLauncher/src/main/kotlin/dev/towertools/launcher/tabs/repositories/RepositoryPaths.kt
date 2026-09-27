package dev.towertools.launcher.tabs.repositories

import dev.towertools.launcher.AppPaths
import java.nio.file.Files
import java.nio.file.Path

object RepositoryPaths {
    private const val tabKey = "repositories"

    val dataDirectory: Path by lazy {
        AppPaths.dataDirectory.resolve("tabs").resolve(tabKey).also(Files::createDirectories)
    }
}
