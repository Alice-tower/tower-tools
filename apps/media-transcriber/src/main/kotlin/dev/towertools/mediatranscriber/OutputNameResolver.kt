package dev.towertools.mediatranscriber

import java.nio.file.Files
import java.nio.file.Path

object OutputNameResolver {
    fun baseName(input: Path): String {
        val name = input.fileName.toString()
        val lastDot = name.lastIndexOf('.')
        return if (lastDot > 0) name.substring(0, lastDot) else name
    }

    fun resolveName(inputFileName: String, extension: String, existingNames: Set<String>): String {
        val cleanExtension = extension.removePrefix(".")
        val base = baseName(Path.of(inputFileName))
        val occupied = existingNames.mapTo(HashSet()) { it.lowercase() }
        var candidate = "$base.$cleanExtension"
        var suffix = 2
        while (candidate.lowercase() in occupied) {
            candidate = "$base ($suffix).$cleanExtension"
            suffix++
        }
        return candidate
    }

    fun resolve(outputDirectory: Path, input: Path, extension: String): Path {
        val names = Files.list(outputDirectory).use { files -> files.map { it.fileName.toString() }.toList().toSet() }
        return outputDirectory.resolve(resolveName(input.fileName.toString(), extension, names))
    }
}
