package dev.towertools.launcher

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
    fun malformedSettingsAreNotOverwrittenByAnEdit() {
        val root = createTempDirectory("tower-launcher-corrupt-test")
        try {
            val settings = root.resolve("user-settings.json")
            val original = "{\"tools\": {broken"
            Files.writeString(settings, original)
            val repository = CatalogRepository(root, root.resolve("catalog.json"), settings)

            assertFailsWith<IllegalStateException> {
                repository.updateOverride("dev.towertools.sample", "网络工具", 10)
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
