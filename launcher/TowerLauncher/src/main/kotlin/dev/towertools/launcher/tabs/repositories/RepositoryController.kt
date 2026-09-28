package dev.towertools.launcher.tabs.repositories

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Instant
import java.util.Properties
import java.util.UUID

data class RepositoryScan(
    val directoryCount: Int,
    val repositoryNames: List<String>,
    val scannedAt: Instant,
)

data class RepositoryLocation(
    val id: String,
    val path: Path,
    val scan: RepositoryScan? = null,
)

data class DiscoveredRepository(
    val locationId: String,
    val name: String,
    val path: Path,
)

fun discoveredRepositories(locations: List<RepositoryLocation>): List<DiscoveredRepository> =
    locations.flatMap { location ->
        location.scan?.repositoryNames.orEmpty().map { name ->
            DiscoveredRepository(location.id, name, location.path.resolve(name))
        }
    }.sortedWith(compareBy({ it.name.lowercase() }, { it.path.toString().lowercase() }))

class RepositoryRegistry(
    internal val file: Path = RepositoryPaths.dataDirectory.resolve("locations.properties"),
) {
    fun load(): List<RepositoryLocation> {
        if (!Files.exists(file)) return emptyList()
        val properties = Properties()
        Files.newInputStream(file).use(properties::load)
        require(properties.getProperty("format") == "1") { "仓库配置格式无效" }
        val count = properties.getProperty("count")?.toIntOrNull()?.takeIf { it >= 0 }
            ?: error("仓库配置中的路径数量无效")
        return (0 until count).map { index ->
            val prefix = "location.$index."
            val id = properties.getProperty(prefix + "id")?.takeIf(String::isNotBlank)
                ?: error("路径 $index 缺少 ID")
            val path = Paths.get(properties.getProperty(prefix + "path")
                ?: error("路径 $index 缺少目录"))
            require(path.isAbsolute) { "路径 $index 不是完整路径" }
            val scannedAt = properties.getProperty(prefix + "scannedAt")
            val scan = if (scannedAt == null) {
                null
            } else {
                val directoryCount = properties.getProperty(prefix + "directoryCount")
                    ?.toIntOrNull()?.takeIf { it >= 0 }
                    ?: error("路径 $index 的子目录数量无效")
                val repositoryCount = properties.getProperty(prefix + "repositoryCount")
                    ?.toIntOrNull()?.takeIf { it in 0..directoryCount }
                    ?: error("路径 $index 的仓库数量无效")
                val names = (0 until repositoryCount).map { repositoryIndex ->
                    properties.getProperty(prefix + "repository.$repositoryIndex")
                        ?.takeIf(String::isNotBlank)
                        ?: error("路径 $index 的仓库列表不完整")
                }
                RepositoryScan(directoryCount, names, Instant.parse(scannedAt))
            }
            RepositoryLocation(id, path, scan)
        }
    }

    fun save(locations: List<RepositoryLocation>) {
        val directory = file.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "locations-", ".tmp")
        try {
            val properties = Properties()
            properties.setProperty("format", "1")
            properties.setProperty("count", locations.size.toString())
            locations.forEachIndexed { index, location ->
                val prefix = "location.$index."
                properties.setProperty(prefix + "id", location.id)
                properties.setProperty(prefix + "path", location.path.toString())
                location.scan?.let { scan ->
                    properties.setProperty(prefix + "scannedAt", scan.scannedAt.toString())
                    properties.setProperty(prefix + "directoryCount", scan.directoryCount.toString())
                    properties.setProperty(prefix + "repositoryCount", scan.repositoryNames.size.toString())
                    scan.repositoryNames.forEachIndexed { repositoryIndex, name ->
                        properties.setProperty(prefix + "repository.$repositoryIndex", name)
                    }
                }
            }
            Files.newOutputStream(temporary).use { properties.store(it, "Repository tab locations") }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

class RepositoryController(
    private val registry: RepositoryRegistry = RepositoryRegistry(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val appearanceStore: RepositoryAppearanceStore = RepositoryAppearanceStore(registry.file.resolveSibling("appearance.properties")),
) {
    fun locations(): List<RepositoryLocation> = registry.load()

    fun appearance(): RepositoryAppearanceSettings = appearanceStore.load()

    fun updateAppearance(path: Path, category: String, order: Int, favorite: Boolean? = null): RepositoryAppearanceSettings =
        appearanceStore.updateRepository(path, category, order, favorite)

    fun updateCategoryOrder(order: List<String>): RepositoryAppearanceSettings = appearanceStore.updateCategoryOrder(order)

    fun add(path: Path): List<RepositoryLocation> {
        val normalized = path.toAbsolutePath().normalize()
        require(Files.isDirectory(normalized)) { "找不到目录：$normalized" }
        val current = registry.load()
        require(current.none { it.path.toString().equals(normalized.toString(), ignoreCase = true) }) {
            "这个路径已经添加"
        }
        return (current + RepositoryLocation(UUID.randomUUID().toString(), normalized))
            .also(registry::save)
    }

    fun remove(id: String): List<RepositoryLocation> {
        val current = registry.load()
        require(current.any { it.id == id }) { "路径已不存在，请重新打开仓库页" }
        return current.filterNot { it.id == id }.also(registry::save)
    }

    fun refresh(id: String): List<RepositoryLocation> {
        val current = registry.load()
        val location = current.firstOrNull { it.id == id }
            ?: error("路径已不存在，请重新打开仓库页")
        val scan = scan(location.path)
        return current.map { if (it.id == id) it.copy(scan = scan) else it }
            .also(registry::save)
    }

    private fun scan(path: Path): RepositoryScan {
        require(Files.isDirectory(path)) { "找不到目录：$path" }
        var directoryCount = 0
        val repositoryNames = mutableListOf<String>()
        Files.newDirectoryStream(path).use { children ->
            for (child in children) {
                if (!Files.isDirectory(child)) continue
                directoryCount++
                val gitMarker = child.resolve(".git")
                if (Files.isDirectory(gitMarker)) {
                    repositoryNames += child.fileName.toString()
                }
            }
        }
        repositoryNames.sortWith(String.CASE_INSENSITIVE_ORDER)
        return RepositoryScan(directoryCount, repositoryNames, clock.instant())
    }
}
