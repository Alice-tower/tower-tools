package dev.towertools.launcher.tabs.tools

import kotlinx.serialization.Serializable
import java.nio.file.Path

@Serializable
data class ToolCatalog(
    val schemaVersion: Int = 1,
    val tools: List<CatalogTool> = emptyList(),
)

@Serializable
data class CatalogTool(
    val id: String,
    val projectName: String,
    val displayName: String,
    val description: String,
    val version: String,
    val executablePath: String,
    val defaultCategory: String = "未分类",
    val defaultOrder: Int = 0,
)

@Serializable
data class UserSettings(
    val tools: Map<String, ToolOverride> = emptyMap(),
    val categoryOrder: List<String> = emptyList(),
)

@Serializable
data class ToolOverride(
    val category: String? = null,
    val order: Int? = null,
    val favorite: Boolean = false,
)

data class LauncherTool(
    val id: String,
    val projectName: String,
    val displayName: String,
    val description: String,
    val version: String,
    val executable: Path,
    val category: String,
    val order: Int,
    val favorite: Boolean = false,
)
