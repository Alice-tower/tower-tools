package dev.towertools.mediatranscriber

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,
    val outputDirectory: String = "",
    val ffmpegDirectory: String = "",
    val whisperCliPath: String = "",
    val whisperModelPath: String = "",
    val includeTimestamps: Boolean = false,
)

class SettingsStore(
    private val file: Path = AppPaths.settingsFile,
    private val onWarning: (String) -> Unit = {},
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load(): AppSettings {
        if (!Files.isRegularFile(file)) return AppSettings()
        return runCatching { json.decodeFromString<AppSettings>(Files.readString(file)) }
            .getOrElse {
                onWarning("设置文件损坏，已恢复默认设置：${it.message}")
                AppSettings()
            }
    }

    fun save(settings: AppSettings) {
        Files.createDirectories(file.parent)
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(AppSettings.serializer(), settings))
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun temporaryFile(): Path = file.resolveSibling("${file.fileName}.tmp")
}
