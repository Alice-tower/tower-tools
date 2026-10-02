package dev.towertools.launcher.tabs.tools

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import java.io.IOException

class CatalogRepositoryTest {
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun renameUpdatesDefaultsAndOverridesInOneSaveAndPreservesPreferences() {
        val root = createTempDirectory("tower-category-rename-")
        try {
            val catalog = root.resolve("catalog.json")
            val settings = root.resolve("user-settings.json")
            Files.writeString(catalog, catalogJson("1.0.0"))
            val repository = CatalogRepository(root, catalog, settings)
            repository.updateOverride("dev.towertools.hidden", "未分类", -8, true)
            repository.updateOverride("dev.towertools.other", "其他", 12, true)
            repository.updateCategoryOrder(listOf("其他", "未分类", "历史分类"))

            repository.renameCategory("未分类", "  我的工具  ")
            val reopened = CatalogRepository(root, catalog, settings)
            assertEquals("我的工具", reopened.load().single().category)
            assertEquals(0, reopened.load().single().order)
            assertEquals(listOf("其他", "我的工具", "历史分类"), reopened.loadCategoryOrder())
            val persisted = kotlinx.serialization.json.Json.decodeFromString<UserSettings>(Files.readString(settings))
            assertEquals(ToolOverride("我的工具", -8, true), persisted.tools["dev.towertools.hidden"])
            assertEquals(ToolOverride("其他", 12, true), persisted.tools["dev.towertools.other"])
            // A default order stays a default, even after the catalog changes.
            Files.writeString(catalog, catalogJson("1.1.0").replace("\"defaultOrder\": 0", "\"defaultOrder\": 20"))
            assertEquals(20, reopened.load().single().order)

            val original = Files.readString(settings)
            assertFailsWith<IllegalArgumentException> { repository.renameCategory("我的工具", "其他") }
            assertFailsWith<IllegalArgumentException> { repository.renameCategory("我的工具", "历史分类") }
            assertFailsWith<IllegalArgumentException> { repository.renameCategory("我的工具", "  ") }
            repository.renameCategory("我的工具", " 我的工具 ")
            assertEquals(original, Files.readString(settings))
            Files.writeString(catalog, "{broken")
            assertFailsWith<Exception> { repository.renameCategory("我的工具", "新的名称") }
            assertEquals(original, Files.readString(settings))
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun renamePreservesVisiblePositionBeforeCategoryOrderHasBeenSaved() {
        val root = createTempDirectory("tower-category-rename-order-")
        try {
            val catalog = root.resolve("catalog.json")
            Files.writeString(catalog, catalogJson("1.0.0").replace("未分类", "A"))
            val repository = CatalogRepository(root, catalog, root.resolve("settings.json"))
            repository.renameCategory("A", "Z")
            assertEquals(listOf("Z"), repository.loadCategoryOrder())
            assertEquals("Z", repository.load().single().category)
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun malformedSettingsAreNotOverwrittenByAnEdit() {
        val root = createTempDirectory("tower-launcher-corrupt-test")
        try {
            val settings = root.resolve("user-settings.json")
            val original = "{\"tools\": {broken"
            Files.writeString(settings, original)
            val repository = CatalogRepository(root, root.resolve("catalog.json"), settings)

            assertEquals(true, repository.loadCategoryOrderResult().isFailure)
            assertFailsWith<IllegalStateException> { repository.renameCategory("旧分类", "新分类") }
            assertFailsWith<IllegalStateException> {
                repository.updateOverride("dev.towertools.sample", "网络工具", 10)
            }
            assertFailsWith<IllegalStateException> {
                repository.updateCategoryOrder(listOf("网络工具", "文本工具"))
            }
            assertEquals(original, Files.readString(settings))
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun failedWritePreservesOriginalSettingsAndRemovesTemporaryFile() {
        val root = createTempDirectory("tower-launcher-write-failure-test")
        try {
            val settings = root.resolve("user-settings.json")
            val original = "{\"tools\":{}}"
            Files.writeString(settings, original)

            assertFailsWith<IOException> {
                writeSettingsAtomically(settings, "new settings") { path, _ ->
                    Files.writeString(path, "partial")
                    throw IOException("simulated write failure")
                }
            }
            assertEquals(original, Files.readString(settings))
            Files.list(root).use { assertEquals(listOf(settings), it.toList()) }
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun editingExistingSettingsPreservesOtherTools() {
        val root = createTempDirectory("tower-launcher-replace-test")
        try {
            val catalog = root.resolve("catalog.json")
            val settings = root.resolve("user-settings.json")
            Files.writeString(catalog, catalogJson("1.0.0"))
            val repository = CatalogRepository(root, catalog, settings)
            repository.updateOverride("dev.towertools.sample", "文本工具", 7)
            repository.updateOverride("dev.towertools.other", "网络工具", 10)
            assertEquals("文本工具", repository.load().single().category)
            assertEquals(7, repository.load().single().order)
            repository.updateOverride("dev.towertools.sample", "新分类", -2)
            val persisted = kotlinx.serialization.json.Json.decodeFromString<UserSettings>(Files.readString(settings))
            assertEquals(ToolOverride("网络工具", 10), persisted.tools["dev.towertools.other"])
            assertEquals(ToolOverride("新分类", -2), persisted.tools["dev.towertools.sample"])
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun userCategoryAndOrderSurviveCatalogRegeneration() {
        val root = createTempDirectory("tower-launcher-test")
        try {
            val catalog = root.resolve("catalog.json")
            val settings = root.resolve("user-settings.json")
            Files.writeString(catalog, catalogJson(version = "1.0.0"))
            Files.writeString(
                settings,
                """{"tools":{"dev.towertools.sample":{"category":"文本工具","order":7}}}""",
            )

            val repository = CatalogRepository(root, catalog, settings)
            assertEquals("文本工具", repository.load().single().category)
            assertEquals(7, repository.load().single().order)
            assertEquals(false, repository.load().single().favorite)

            Files.writeString(catalog, catalogJson(version = "1.1.0"))
            val updated = repository.load().single()
            assertEquals("1.1.0", updated.version)
            assertEquals("文本工具", updated.category)
            assertEquals(7, updated.order)
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun updateOverridePersistsCategoryAndOrder() {
        val root = createTempDirectory("tower-launcher-update-test")
        try {
            val catalog = root.resolve("catalog.json")
            val settings = root.resolve("user-settings.json")
            Files.writeString(catalog, catalogJson(version = "1.0.0"))
            val repository = CatalogRepository(root, catalog, settings)

            repository.updateOverride("dev.towertools.sample", "网络工具", -10)

            val updated = repository.load().single()
            assertEquals("网络工具", updated.category)
            assertEquals(-10, updated.order)
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun favoritePersistsAndControlsInitialFilter() {
        val root = createTempDirectory("tower-launcher-favorite-test")
        try {
            val catalog = root.resolve("catalog.json")
            val settings = root.resolve("user-settings.json")
            Files.writeString(catalog, catalogJson(version = "1.0.0"))
            val repository = CatalogRepository(root, catalog, settings)

            assertEquals(ToolFilter.All, defaultFilter(repository.load()))
            repository.updateOverride("dev.towertools.sample", "未分类", 0, favorite = true)
            assertEquals(true, repository.load().single().favorite)
            assertEquals(ToolFilter.Favorites, defaultFilter(repository.load()))

            Files.writeString(catalog, catalogJson(version = "1.1.0"))
            assertEquals(true, CatalogRepository(root, catalog, settings).load().single().favorite)
            repository.updateOverride("dev.towertools.sample", "网络工具", 1)
            assertEquals(true, repository.load().single().favorite)

            repository.updateOverride("dev.towertools.sample", "网络工具", 1, favorite = false)
            assertEquals(false, repository.load().single().favorite)
            assertEquals(ToolFilter.All, validFilter(ToolFilter.Favorites, repository.load()))
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun categoryOrderPersistsWithoutChangingToolOverrides() {
        val root = createTempDirectory("tower-launcher-category-order-test")
        try {
            val catalog = root.resolve("catalog.json")
            val settings = root.resolve("user-settings.json")
            Files.writeString(catalog, catalogJson("1.0.0"))
            val repository = CatalogRepository(root, catalog, settings)
            repository.updateOverride("dev.towertools.sample", "图片&影音", 7, favorite = true)

            repository.updateCategoryOrder(listOf("系统环境", "图片&影音", "本地管理"))
            assertEquals(listOf("系统环境", "图片&影音", "本地管理"), repository.loadCategoryOrder())
            assertEquals("图片&影音", repository.load().single().category)
            assertEquals(true, repository.load().single().favorite)

            repository.updateOverride("dev.towertools.sample", "本地管理", -1)
            Files.writeString(catalog, catalogJson("1.1.0"))
            val reopened = CatalogRepository(root, catalog, settings)
            assertEquals(listOf("系统环境", "图片&影音", "本地管理"), reopened.loadCategoryOrder())
            assertEquals(-1, reopened.load().single().order)
            assertEquals(true, reopened.load().single().favorite)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun catalogJson(version: String) = """
        {
          "schemaVersion": 1,
          "tools": [{
            "id": "dev.towertools.sample",
            "projectName": "SampleTool",
            "displayName": "示例工具",
            "description": "测试",
            "version": "$version",
            "executablePath": "tools/dev.towertools.sample/SampleTool.exe",
            "defaultCategory": "未分类",
            "defaultOrder": 0
          }]
        }
    """.trimIndent()
}
