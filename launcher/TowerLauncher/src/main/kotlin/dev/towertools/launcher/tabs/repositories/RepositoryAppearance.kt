package dev.towertools.launcher.tabs.repositories

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties

private const val UNCATEGORIZED = "未分类"

data class RepositoryAppearance(
    val category: String = UNCATEGORIZED,
    val order: Int = 0,
    val favorite: Boolean = false,
)

data class RepositoryAppearanceSettings(
    val repositories: Map<String, RepositoryAppearance> = emptyMap(),
    val categoryOrder: List<String> = emptyList(),
)

data class DisplayRepository(
    val repository: DiscoveredRepository,
    val category: String,
    val order: Int,
    val favorite: Boolean,
) {
    val key: String get() = repositoryKey(repository.path)
}

internal fun repositoryKey(path: Path): String =
    path.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)

internal fun displayRepositories(
    repositories: List<DiscoveredRepository>,
    settings: RepositoryAppearanceSettings,
): List<DisplayRepository> = repositories.map { repository ->
    val appearance = settings.repositories[repositoryKey(repository.path)] ?: RepositoryAppearance()
    DisplayRepository(repository, appearance.category, appearance.order, appearance.favorite)
}.sortedWith(compareBy<DisplayRepository> { it.order }
    .thenBy { it.repository.name.lowercase(Locale.ROOT) }
    .thenBy { it.repository.name }
    .thenBy { it.key })

internal fun orderedRepositoryCategories(repositories: List<DisplayRepository>, savedOrder: List<String>): List<String> {
    val available = repositories.map { it.category }.distinct()
    val saved = savedOrder.distinct().filter { it in available }
    val remaining = available.filterNot { it in saved }
        .sortedWith(compareBy<String> { it == UNCATEGORIZED }.thenBy { it })
    return saved + remaining
}

internal fun moveRepositoryCategory(categories: List<String>, moving: String, target: String): List<String> {
    val from = categories.indexOf(moving)
    val to = categories.indexOf(target)
    if (from < 0 || to < 0 || from == to) return categories
    return categories.toMutableList().apply {
        removeAt(from)
        add(to, moving)
    }
}

/** This file belongs only to the repository tab; scan locations remain in locations.properties. */
class RepositoryAppearanceStore(
    private val file: Path = RepositoryPaths.dataDirectory.resolve("appearance.properties"),
) {
    fun load(): RepositoryAppearanceSettings {
        if (!Files.exists(file)) return RepositoryAppearanceSettings()
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        require(properties.getProperty("format") == "1") { "仓库外观配置格式无效" }
        val repositoryCount = properties.nonNegativeInt("repositoryCount")
        val categoryCount = properties.nonNegativeInt("categoryCount")
        val repositories = (0 until repositoryCount).associate { index ->
            val prefix = "repository.$index."
            val key = properties.getProperty(prefix + "path")?.takeIf(String::isNotBlank)
                ?: error("仓库 $index 缺少路径")
            val category = properties.getProperty(prefix + "category")?.takeIf(String::isNotBlank)
                ?: error("仓库 $index 缺少分类")
            val order = properties.getProperty(prefix + "order")?.toIntOrNull()
                ?: error("仓库 $index 的排序无效")
            val favorite = when (properties.getProperty(prefix + "favorite")) {
                "true" -> true
                "false" -> false
                else -> error("仓库 $index 的收藏状态无效")
            }
            key to RepositoryAppearance(category, order, favorite)
        }
        require(repositories.size == repositoryCount) { "仓库外观配置包含重复路径" }
        val order = (0 until categoryCount).map { index ->
            properties.getProperty("category.$index")?.takeIf(String::isNotBlank)
                ?: error("分类顺序 $index 无效")
        }
        require(order.distinct().size == categoryCount) { "分类顺序包含重复分类" }
        return RepositoryAppearanceSettings(repositories, order)
    }

    fun updateRepository(path: Path, category: String, order: Int, favorite: Boolean? = null): RepositoryAppearanceSettings {
        val current = loadSafelyForUpdate()
        val key = repositoryKey(path)
        val updated = current.copy(repositories = current.repositories +
            (key to RepositoryAppearance(category.trim().ifEmpty { UNCATEGORIZED }, order,
                favorite ?: current.repositories[key]?.favorite ?: false)))
        save(updated)
        return updated
    }

    fun updateCategoryOrder(visibleOrder: List<String>): RepositoryAppearanceSettings {
        val current = loadSafelyForUpdate()
        val order = visibleOrder.distinct() + current.categoryOrder.filterNot { it in visibleOrder }.distinct()
        val updated = current.copy(categoryOrder = order)
        save(updated)
        return updated
    }

    private fun loadSafelyForUpdate(): RepositoryAppearanceSettings = try {
        load()
    } catch (failure: Exception) {
        throw IllegalStateException("无法读取现有仓库分类设置，已取消保存以保护原文件：$file", failure)
    }

    private fun save(settings: RepositoryAppearanceSettings) {
        val directory = file.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "appearance-", ".tmp")
        try {
            val properties = Properties()
            properties.setProperty("format", "1")
            properties.setProperty("repositoryCount", settings.repositories.size.toString())
            properties.setProperty("categoryCount", settings.categoryOrder.size.toString())
            settings.repositories.entries.forEachIndexed { index, (key, appearance) ->
                val prefix = "repository.$index."
                properties.setProperty(prefix + "path", key)
                properties.setProperty(prefix + "category", appearance.category)
                properties.setProperty(prefix + "order", appearance.order.toString())
                properties.setProperty(prefix + "favorite", appearance.favorite.toString())
            }
            settings.categoryOrder.forEachIndexed { index, category ->
                properties.setProperty("category.$index", category)
            }
            Files.newOutputStream(temporary).use { properties.store(it, "Repository tab appearance") }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun Properties.nonNegativeInt(key: String): Int =
        getProperty(key)?.toIntOrNull()?.takeIf { it >= 0 } ?: error("仓库外观配置中的 $key 无效")
}
