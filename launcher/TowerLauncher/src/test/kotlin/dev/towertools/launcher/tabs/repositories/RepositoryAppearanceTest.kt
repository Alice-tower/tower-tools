package dev.towertools.launcher.tabs.repositories

import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RepositoryAppearanceTest {
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun preferencesSurviveRescanAndReaddedLocation() {
        val root = createTempDirectory("repository-appearance-")
        try {
            val path = Files.createDirectory(root.resolve("projects"))
            Files.createDirectory(Files.createDirectory(path.resolve("alpha")).resolve(".git"))
            Files.createDirectory(Files.createDirectory(path.resolve("beta")).resolve(".git"))
            val registry = RepositoryRegistry(root.resolve("locations.properties"))
            val controller = RepositoryController(registry)
            val first = controller.add(path)
            controller.refresh(first.single().id)
            controller.updateAppearance(path.resolve("beta"), "工作", -2, true)
            controller.updateAppearance(path.resolve("alpha"), "工作", 8)
            controller.updateCategoryOrder(listOf("工作"))
            controller.updateAppearance(path.resolve("beta"), "工作", -2, false)
            controller.updateAppearance(path.resolve("beta"), "工作", -2, true)

            val reopened = RepositoryController(registry)
            val entries = displayRepositories(discoveredRepositories(reopened.locations()), reopened.appearance())
            assertEquals(listOf("beta", "alpha"), entries.map { it.repository.name })
            assertEquals(listOf(true, false), entries.map { it.favorite })
            assertEquals(listOf("工作"), orderedRepositoryCategories(entries, reopened.appearance().categoryOrder))

            reopened.remove(first.single().id)
            val again = reopened.add(path).single()
            reopened.refresh(again.id)
            assertEquals(listOf("beta", "alpha"),
                displayRepositories(discoveredRepositories(reopened.locations()), reopened.appearance()).map { it.repository.name })
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun invalidAppearanceCannotBeOverwritten() {
        val root = createTempDirectory("repository-appearance-invalid-")
        try {
            val file = root.resolve("appearance.properties")
            val invalid = "format=1\nrepositoryCount=broken\ncategoryCount=0\n"
            Files.writeString(file, invalid)
            val store = RepositoryAppearanceStore(file)
            assertFailsWith<IllegalStateException> { store.updateRepository(root.resolve("repo"), "工作", 1) }
            assertFailsWith<IllegalStateException> { store.updateCategoryOrder(listOf("工作")) }
            assertEquals(invalid, Files.readString(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun categoryOrderKeepsSavedNamesAndUncategorizedLast() {
        val repositories = listOf("未分类", "工作", "个人").map { category ->
            DisplayRepository(DiscoveredRepository("location", category, java.nio.file.Path.of("C:/repos/$category")),
                category, 0, false)
        }
        assertEquals(listOf("个人", "工作", "未分类"),
            orderedRepositoryCategories(repositories, listOf("已删除", "个人", "个人")))
        assertEquals(listOf("工作", "个人", "未分类"),
            moveRepositoryCategory(listOf("个人", "工作", "未分类"), "工作", "个人"))
        assertTrue(repositoryKey(java.nio.file.Path.of("C:/Repos/Alpha")) ==
            repositoryKey(java.nio.file.Path.of("C:/repos/alpha")))
    }

    @Test
    fun defaultRepositoryOrderIgnoresNameCase() {
        val repositories = listOf("Zoo", "alpha").map { name ->
            DiscoveredRepository("location", name, java.nio.file.Path.of("C:/repos/$name"))
        }
        assertEquals(listOf("alpha", "Zoo"),
            displayRepositories(repositories, RepositoryAppearanceSettings()).map { it.repository.name })
    }
}
