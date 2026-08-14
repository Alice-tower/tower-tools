package dev.towertools.mediatranscriber

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ExecutableResolver {
    fun resolve(name: String, configuredDirectory: String, pathValue: String? = System.getenv("PATH")): Path? {
        if (configuredDirectory.isNotBlank()) {
            val configured = Paths.get(configuredDirectory).resolve(name)
            if (Files.isRegularFile(configured)) return configured.toAbsolutePath()
        }
        return pathValue.orEmpty().split(';').asSequence()
            .filter(String::isNotBlank)
            .map { Paths.get(it.trim().removeSurrounding("\"")).resolve(name) }
            .firstOrNull(Files::isRegularFile)
            ?.toAbsolutePath()
    }
}
