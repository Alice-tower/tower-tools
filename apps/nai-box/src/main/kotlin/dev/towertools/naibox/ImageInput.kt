package dev.towertools.naibox

import java.io.File
import java.net.URI
import java.nio.file.Paths

internal object ImageInput {
    private val extensions = setOf("png", "jpg", "jpeg", "jpe", "jfif", "webp", "gif", "bmp", "avif", "heif", "heic")

    fun firstImage(files: List<File>): File? = files.firstOrNull { file ->
        file.isFile && file.extension.lowercase() in extensions
    }

    fun droppedFiles(values: List<String>): List<File> = values.mapNotNull { value ->
        runCatching { if (value.startsWith("file:", true)) Paths.get(URI(value)).toFile() else File(value) }.getOrNull()
    }
}
