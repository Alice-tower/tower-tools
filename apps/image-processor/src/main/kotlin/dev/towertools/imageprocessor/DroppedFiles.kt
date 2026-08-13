package dev.towertools.imageprocessor

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

object DroppedFiles {
    fun parse(values: List<String>): List<Path> = values.mapNotNull { value ->
        runCatching {
            if (value.startsWith("file:", ignoreCase = true)) {
                Paths.get(URI(value))
            } else {
                Paths.get(value)
            }
        }.getOrNull()
    }
}
