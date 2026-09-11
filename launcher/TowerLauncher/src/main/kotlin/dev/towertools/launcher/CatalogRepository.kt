package dev.towertools.launcher

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption

class CatalogRepository(
    private val outputsRoot: Path,
    private val catalogFile: Path,
    private val userSettingsFile: Path,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun load(): List<LauncherTool> {
        val catalog = readCatalog()
        val settings = runCatching { readUserSettings() }
            .onFailure { AppLog.logger.warning("Unable to read launcher settings: ${it.message}") }
            .getOrDefault(UserSettings())

        return catalog.tools.map { tool ->
            val override = settings.tools[tool.id]
            LauncherTool(
                id = tool.id,
                projectName = tool.projectName,
                displayName = tool.displayName,
                description = tool.description,
                version = tool.version,
                executable = outputsRoot.resolve(tool.executablePath.replace('/', '\\')).normalize(),
                category = override?.category ?: tool.defaultCategory,
                order = override?.order ?: tool.defaultOrder,
            )
        }.sortedWith(compareBy<LauncherTool> { it.order }.thenBy { it.displayName })
    }

    fun updateOverride(toolId: String, category: String, order: Int) {
        val current = try {
            readUserSettings()
        } catch (failure: Exception) {
            throw IllegalStateException("无法读取现有分类和排序，已取消保存以保护原文件：$userSettingsFile", failure)
        }
        val updated = current.copy(tools = current.tools + (toolId to ToolOverride(category, order)))
        writeSettingsAtomically(
            userSettingsFile,
            json.encodeToString(updated) + System.lineSeparator(),
        )
    }

    private fun readCatalog(): ToolCatalog {
        if (!Files.exists(catalogFile)) return ToolCatalog()
        return runCatching { json.decodeFromString<ToolCatalog>(Files.readString(catalogFile)) }
            .onFailure { AppLog.logger.warning("Unable to read tool catalog: ${it.message}") }
            .getOrDefault(ToolCatalog())
    }

    private fun readUserSettings(): UserSettings {
        val contents = try {
            Files.readString(userSettingsFile)
        } catch (_: NoSuchFileException) {
            return UserSettings()
        }
        return json.decodeFromString<UserSettings>(contents)
    }

    companion object {
        fun default(): CatalogRepository = CatalogRepository(
            outputsRoot = AppPaths.outputsRoot,
            catalogFile = AppPaths.outputsRoot.resolve("catalog").resolve("tools.json"),
            userSettingsFile = AppPaths.userSettingsFile,
        )
    }
}

internal fun writeSettingsAtomically(
    target: Path,
    contents: String,
    write: (Path, String) -> Unit = { path, text ->
        Files.writeString(path, text, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
    },
) {
    val destination = target.toAbsolutePath()
    Files.createDirectories(destination.parent)
    val temporary = Files.createTempFile(destination.parent, ".user-settings-", ".tmp")
    try {
        write(temporary, contents)
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
