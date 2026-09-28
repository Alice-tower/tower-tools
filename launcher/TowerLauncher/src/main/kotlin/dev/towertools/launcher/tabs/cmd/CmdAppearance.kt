package dev.towertools.launcher.tabs.cmd

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

private const val UNCATEGORIZED = "未分类"

data class CmdProjectAppearance(
    val category: String = UNCATEGORIZED,
    val order: Int = 0,
    val favorite: Boolean = false,
)

data class CmdAppearanceSettings(
    val projects: Map<String, CmdProjectAppearance> = emptyMap(),
    val categoryOrder: List<String> = emptyList(),
)

data class DisplayCmdProject(
    val project: LocalProject,
    val category: String,
    val order: Int,
    val favorite: Boolean,
)

internal fun displayCmdProjects(
    projects: List<LocalProject>,
    settings: CmdAppearanceSettings,
): List<DisplayCmdProject> = projects.map { project ->
    val appearance = settings.projects[project.id] ?: CmdProjectAppearance()
    DisplayCmdProject(project, appearance.category, appearance.order, appearance.favorite)
}.sortedWith(compareBy<DisplayCmdProject> { it.order }.thenBy { it.project.name }.thenBy { it.project.id })

internal fun orderedCmdCategories(projects: List<DisplayCmdProject>, savedOrder: List<String>): List<String> {
    val available = projects.map { it.category }.distinct()
    val saved = savedOrder.distinct().filter { it in available }
    val remaining = available.filterNot { it in saved }
        .sortedWith(compareBy<String> { it == UNCATEGORIZED }.thenBy { it })
    return saved + remaining
}

internal fun moveCmdCategory(categories: List<String>, moving: String, target: String): List<String> {
    val from = categories.indexOf(moving)
    val to = categories.indexOf(target)
    if (from < 0 || to < 0 || from == to) return categories
    return categories.toMutableList().apply {
        removeAt(from)
        add(to, moving)
    }
}

/** CMD presentation is independent of the project registry and the other tabs. */
class CmdAppearanceStore(
    private val file: Path = CmdPaths.dataDirectory.resolve("appearance.properties"),
) {
    fun load(): CmdAppearanceSettings {
        if (!Files.exists(file)) return CmdAppearanceSettings()
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        require(properties.getProperty("format") == "1") { "CMD 外观配置格式无效" }
        val projectCount = properties.nonNegativeInt("projectCount")
        val categoryCount = properties.nonNegativeInt("categoryCount")
        val projects = (0 until projectCount).associate { index ->
            val prefix = "project.$index."
            val id = properties.getProperty(prefix + "id")?.takeIf(String::isNotBlank)
                ?: error("项目 $index 缺少 ID")
            val category = properties.getProperty(prefix + "category")?.takeIf(String::isNotBlank)
                ?: error("项目 $index 缺少分类")
            val order = properties.getProperty(prefix + "order")?.toIntOrNull()
                ?: error("项目 $index 的排序无效")
            val favorite = when (properties.getProperty(prefix + "favorite")) {
                "true" -> true
                "false" -> false
                else -> error("项目 $index 的收藏状态无效")
            }
            id to CmdProjectAppearance(category, order, favorite)
        }
        require(projects.size == projectCount) { "CMD 外观配置包含重复项目" }
        val order = (0 until categoryCount).map { index ->
            properties.getProperty("category.$index")?.takeIf(String::isNotBlank)
                ?: error("分类顺序 $index 无效")
        }
        require(order.distinct().size == categoryCount) { "分类顺序包含重复分类" }
        return CmdAppearanceSettings(projects, order)
    }

    fun updateProject(id: String, category: String, order: Int, favorite: Boolean? = null): CmdAppearanceSettings {
        val current = loadSafelyForUpdate()
        val updated = current.copy(projects = current.projects +
            (id to CmdProjectAppearance(category.trim().ifEmpty { UNCATEGORIZED }, order,
                favorite ?: current.projects[id]?.favorite ?: false)))
        save(updated)
        return updated
    }

    fun removeProject(id: String) {
        val current = loadSafelyForUpdate()
        if (id in current.projects) save(current.copy(projects = current.projects - id))
    }

    fun updateCategoryOrder(visibleOrder: List<String>): CmdAppearanceSettings {
        val current = loadSafelyForUpdate()
        val order = visibleOrder.distinct() + current.categoryOrder.filterNot { it in visibleOrder }.distinct()
        val updated = current.copy(categoryOrder = order)
        save(updated)
        return updated
    }

    private fun loadSafelyForUpdate(): CmdAppearanceSettings = try {
        load()
    } catch (failure: Exception) {
        throw IllegalStateException("无法读取现有 CMD 分类设置，已取消保存以保护原文件：$file", failure)
    }

    private fun save(settings: CmdAppearanceSettings) {
        val directory = file.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "appearance-", ".tmp")
        try {
            val properties = Properties()
            properties.setProperty("format", "1")
            properties.setProperty("projectCount", settings.projects.size.toString())
            properties.setProperty("categoryCount", settings.categoryOrder.size.toString())
            settings.projects.entries.forEachIndexed { index, (id, appearance) ->
                val prefix = "project.$index."
                properties.setProperty(prefix + "id", id)
                properties.setProperty(prefix + "category", appearance.category)
                properties.setProperty(prefix + "order", appearance.order.toString())
                properties.setProperty(prefix + "favorite", appearance.favorite.toString())
            }
            settings.categoryOrder.forEachIndexed { index, category ->
                properties.setProperty("category.$index", category)
            }
            Files.newOutputStream(temporary).use { properties.store(it, "CMD tab appearance") }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun Properties.nonNegativeInt(key: String): Int =
        getProperty(key)?.toIntOrNull()?.takeIf { it >= 0 } ?: error("CMD 外观配置中的 $key 无效")
}
