package dev.towertools.mediatranscriber

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

object DroppedFiles {
    fun parse(files: List<String>): List<Path> = files.asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { value ->
            runCatching {
                if (value.startsWith("file:", ignoreCase = true)) Paths.get(URI(value)) else Paths.get(value)
            }.getOrNull()
        }
        .map { it.toAbsolutePath().normalize() }
        .distinctBy { it.toString().lowercase() }
        .toList()
}
