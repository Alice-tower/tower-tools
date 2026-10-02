package dev.towertools.launcher.tabs.tools

import dev.towertools.launcher.AppLog
import dev.towertools.launcher.AppPaths
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
                favorite = override?.favorite ?: false,
            )
        }.sortedWith(compareBy<LauncherTool> { it.order }.thenBy { it.displayName })
    }

    fun updateOverride(toolId: String, category: String, order: Int, favorite: Boolean? = null) {
        val current = try {
            readUserSettings()
        } catch (failure: Exception) {
            throw IllegalStateException("无法读取现有分类和排序，已取消保存以保护原文件：$userSettingsFile", failure)
        }
        val updated = current.copy(
            tools = current.tools + (toolId to ToolOverride(category, order, favorite ?: current.tools[toolId]?.favorite ?: false)),
        )
        writeSettingsAtomically(
            userSettingsFile,
            json.encodeToString(updated) + System.lineSeparator(),
        )
    }

    fun loadCategoryOrderResult(): Result<List<String>> = runCatching { readUserSettings().categoryOrder }
        .onFailure { AppLog.logger.warning("Unable to read launcher category order: ${it.message}") }

    fun loadCategoryOrder(): List<String> = loadCategoryOrderResult().getOrDefault(emptyList())

    fun updateCategoryOrder(visibleOrder: List<String>) {
        val current = try {
            readUserSettings()
        } catch (failure: Exception) {
            throw IllegalStateException("无法读取现有分类排序，已取消保存以保护原文件：$userSettingsFile", failure)
        }
        val ordered = visibleOrder.distinct() + current.categoryOrder.filterNot { it in visibleOrder }.distinct()
        val updated = current.copy(categoryOrder = ordered)
        writeSettingsAtomically(
            userSettingsFile,
            json.encodeToString(updated) + System.lineSeparator(),
        )
    }

    fun renameCategory(oldName: String, newName: String) {
        val current = try {
            readUserSettings()
        } catch (failure: Exception) {
            throw IllegalStateException("无法读取现有分类设置，已取消保存以保护原文件：$userSettingsFile", failure)
        }
        // Use actual defaults; a catalog read failure must not save a partial rename.
        val catalog = json.decodeFromString<ToolCatalog>(Files.readString(catalogFile))
        val effectiveCategories = catalog.tools.associate { tool ->
            tool.id to (current.tools[tool.id]?.category ?: tool.defaultCategory)
        }
        val name = newName.trim()
        val available = effectiveCategories.values + current.tools.values.mapNotNull { it.category } + current.categoryOrder
        require(name.isNotEmpty()) { "分类名称不能为空" }
        require(oldName in available) { "该分类已不存在，请刷新后重试" }
        require(name == oldName || name !in available) { "该分类名称已存在" }
        if (name == oldName) return
        val overrides = current.tools.toMutableMap()
        current.tools.forEach { (id, appearance) ->
            if (appearance.category == oldName) overrides[id] = appearance.copy(category = name)
        }
        effectiveCategories.forEach { (id, category) ->
            if (category == oldName) overrides[id] = (current.tools[id] ?: ToolOverride()).copy(category = name)
        }
        val visible = effectiveCategories.values.distinct()
        val order = current.categoryOrder.distinct() +
            visible.filterNot { it in current.categoryOrder }
                .sortedWith(compareBy<String> { it == "未分类" }.thenBy { it })
        val updated = current.copy(tools = overrides, categoryOrder = order.map { if (it == oldName) name else it })
        writeSettingsAtomically(userSettingsFile, json.encodeToString(updated) + System.lineSeparator())
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
