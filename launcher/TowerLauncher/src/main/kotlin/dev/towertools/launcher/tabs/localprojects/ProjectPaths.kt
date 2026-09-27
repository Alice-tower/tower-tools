package dev.towertools.launcher.tabs.localprojects

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Keep the retired application's data location so existing projects remain available. */
object ProjectPaths {
    const val legacyApplicationId = "dev.towertools.researchlibrarylauncher"

    val dataDirectory: Path by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")

        localAppData.resolve("Alice-tower").resolve("TowerTools").resolve(legacyApplicationId)
            .also(Files::createDirectories)
    }
}
