package dev.towertools.launcher

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object AppPaths {
    val dataDirectory: Path by lazy {
        val localAppData = System.getenv("LOCALAPPDATA")
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?: Paths.get(System.getProperty("user.home"), "AppData", "Local")

        localAppData.resolve("Alice-tower").resolve("TowerLauncher")
            .also(Files::createDirectories)
    }

    val logDirectory: Path by lazy {
        dataDirectory.resolve("logs").also(Files::createDirectories)
    }

    val userSettingsFile: Path by lazy { dataDirectory.resolve("user-settings.json") }

    val outputsRoot: Path by lazy {
        System.getProperty("tower.tools.outputs")
            ?.takeIf(String::isNotBlank)
            ?.let(Paths::get)
            ?.toAbsolutePath()
            ?.normalize()
            ?: packagedOutputsRoot()
            ?: developmentOutputsRoot()
    }

    private fun packagedOutputsRoot(): Path? {
        val appPath = System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank) ?: return null
        val launcherDirectory = Paths.get(appPath).toAbsolutePath().normalize().parent ?: return null
        return if (launcherDirectory.fileName.toString().equals("launcher", ignoreCase = true)) {
            launcherDirectory.parent
        } else {
            null
        }
    }

    private fun developmentOutputsRoot(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath().normalize()
        repeat(8) {
            val current = candidate ?: return@repeat
            val directCatalog = current.resolve("catalog").resolve("tools.json")
            if (Files.exists(directCatalog) && current.fileName.toString().equals("outputs", ignoreCase = true)) {
                return current
            }
            val repositoryOutputs = current.resolve("outputs")
            if (Files.exists(repositoryOutputs.resolve("catalog").resolve("tools.json"))) {
                return repositoryOutputs
            }
            candidate = current.parent
        }
        return Paths.get("").toAbsolutePath().normalize().resolve("outputs")
    }
}
