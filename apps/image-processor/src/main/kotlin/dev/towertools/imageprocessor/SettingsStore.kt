package dev.towertools.imageprocessor

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class AppSettings(val outputDirectory: String = "")

object SettingsStore {
    private val settingsPath: Path get() = AppPaths.dataDirectory.resolve("settings.json")

    fun load(path: Path = settingsPath): AppSettings = runCatching {
        if (!Files.isRegularFile(path)) return AppSettings()
        val text = Files.readString(path, StandardCharsets.UTF_8)
        val match = Regex("\"outputDirectory\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").find(text)
            ?: return AppSettings()
        AppSettings(unescapeJson(match.groupValues[1]))
    }.onFailure {
        AppLog.logger.warning("Unable to read settings: ${it.message}")
    }.getOrDefault(AppSettings())

    fun save(settings: AppSettings, path: Path = settingsPath) {
        val json = """{
  "schemaVersion": 1,
  "outputDirectory": "${escapeJson(settings.outputDirectory)}"
}
"""
        path.parent?.let(Files::createDirectories)
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(temporary, json, StandardCharsets.UTF_8)
        try {
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun escapeJson(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }

    private fun unescapeJson(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                index++
                append(
                    when (value[index]) {
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> value[index]
                    },
                )
            } else {
                append(character)
            }
            index++
        }
    }
}
