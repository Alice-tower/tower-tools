package dev.towertools.launcher

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogRepositoryTest {
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
