package dev.towertools.launcher

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

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
        val settings = readUserSettings()

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
        val current = readUserSettings()
        val updated = current.copy(tools = current.tools + (toolId to ToolOverride(category, order)))
        Files.createDirectories(userSettingsFile.parent)
        Files.writeString(
            userSettingsFile,
            json.encodeToString(updated) + System.lineSeparator(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
    }

    private fun readCatalog(): ToolCatalog {
        if (!Files.exists(catalogFile)) return ToolCatalog()
        return runCatching { json.decodeFromString<ToolCatalog>(Files.readString(catalogFile)) }
            .onFailure { AppLog.logger.warning("Unable to read tool catalog: ${it.message}") }
            .getOrDefault(ToolCatalog())
    }

    private fun readUserSettings(): UserSettings {
        if (!Files.exists(userSettingsFile)) return UserSettings()
        return runCatching { json.decodeFromString<UserSettings>(Files.readString(userSettingsFile)) }
            .onFailure { AppLog.logger.warning("Unable to read launcher settings: ${it.message}") }
            .getOrDefault(UserSettings())
    }

    companion object {
        fun default(): CatalogRepository = CatalogRepository(
            outputsRoot = AppPaths.outputsRoot,
            catalogFile = AppPaths.outputsRoot.resolve("catalog").resolve("tools.json"),
            userSettingsFile = AppPaths.userSettingsFile,
        )
    }
}
