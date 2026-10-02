package dev.towertools.launcher.tabs.cmd

import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CmdAppearanceTest {
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun renameUpdatesEveryMemberIncludingDefaultAndHiddenProjects() {
        val root = createTempDirectory("cmd-category-rename-")
        try {
            val file = root.resolve("appearance.properties")
            val store = CmdAppearanceStore(file)
            val projects = listOf("default", "custom", "other").map {
                LocalProject(it, it, root.resolve("$it.cmd"))
            }
            store.updateProject("custom", "未分类", -2, true)
            store.updateProject("hidden", "未分类", 8, true)
            store.updateProject("other", "其他", 4)
            store.updateCategoryOrder(listOf("其他", "未分类", "历史分类"))

            store.renameCategory("未分类", " 新名称 ", projects)
            val reopened = CmdAppearanceStore(file).load()
            assertEquals(listOf("其他", "新名称", "历史分类"), reopened.categoryOrder)
            assertEquals(CmdProjectAppearance("新名称"), reopened.projects["default"])
            assertEquals(CmdProjectAppearance("新名称", -2, true), reopened.projects["custom"])
            assertEquals(CmdProjectAppearance("新名称", 8, true), reopened.projects["hidden"])
            assertEquals(CmdProjectAppearance("其他", 4), reopened.projects["other"])
            assertEquals(listOf("新名称", "新名称", "其他"),
                displayCmdProjects(projects, reopened).map { it.category })

            val original = Files.readString(file)
            assertFailsWith<IllegalArgumentException> { store.renameCategory("新名称", "其他", projects) }
            assertFailsWith<IllegalArgumentException> { store.renameCategory("新名称", "历史分类", projects) }
            assertFailsWith<IllegalArgumentException> { store.renameCategory("新名称", " ", projects) }
            store.renameCategory("新名称", " 新名称 ", projects)
            assertEquals(original, Files.readString(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun renameKeepsUnsavedCategoryOrderInsteadOfSortingByNewName() {
        val root = createTempDirectory("cmd-category-rename-order-")
        try {
            val store = CmdAppearanceStore(root.resolve("appearance.properties"))
            val projects = listOf("first", "second").map { LocalProject(it, it, root.resolve("$it.cmd")) }
            store.updateProject("first", "A", 0)
            store.updateProject("second", "B", 0)
            assertEquals(listOf("Z", "B"), store.renameCategory("A", "Z", projects).categoryOrder)
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun projectAppearanceSurvivesEditingAndReopening() {
        val root = createTempDirectory("cmd-appearance-")
        try {
            val registry = ProjectRegistry(root.resolve("projects.properties"))
            val controller = ProjectController(registry)
            val firstScript = root.resolve("first.cmd")
            val secondScript = root.resolve("second.cmd")
            Files.writeString(firstScript, "@echo off")
            Files.writeString(secondScript, "@echo off")
            val first = controller.saveProject(null, "第一个", firstScript, null).single()
            val second = controller.saveProject(null, "第二个", secondScript, null).last()
            controller.updateAppearance(first.id, "工作", 9)
            controller.updateAppearance(second.id, "工作", -2, true)
            controller.updateCategoryOrder(listOf("工作"))
            controller.saveProject(first.id, "改名后", firstScript, 8080)

            val reopened = ProjectController(registry)
            val entries = displayCmdProjects(reopened.projects(), reopened.appearance())
            assertEquals(listOf(second.id, first.id), entries.map { it.project.id })
            assertEquals(listOf(true, false), entries.map { it.favorite })
            assertEquals("改名后", entries.last().project.name)
            assertEquals(listOf("工作"), orderedCmdCategories(entries, reopened.appearance().categoryOrder))
            assertEquals(CmdFilter.Favorites, CmdTabState(reopened).selectedFilter.value)
            assertEquals(CmdFilter.All, validCmdFilter(CmdFilter.Favorites, entries.filterNot { it.favorite }))
            reopened.deleteProject(first.id)
            assertEquals(setOf(second.id), reopened.appearance().projects.keys)
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun invalidAppearanceIsNotOverwritten() {
        val root = createTempDirectory("cmd-appearance-invalid-")
        try {
            val file = root.resolve("appearance.properties")
            val invalid = "format=1\nprojectCount=broken\ncategoryCount=0\n"
            Files.writeString(file, invalid)
            val store = CmdAppearanceStore(file)
            assertFailsWith<IllegalStateException> { store.updateProject("id", "工作", 3) }
            assertFailsWith<IllegalStateException> { store.updateCategoryOrder(listOf("工作")) }
            assertFailsWith<IllegalStateException> { store.renameCategory("旧分类", "新分类", emptyList()) }
            assertEquals(invalid, Files.readString(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun categoryOrderKeepsSavedOrderAndPlacesUncategorizedLast() {
        val projects = listOf("未分类", "工作", "个人").map { category ->
            DisplayCmdProject(LocalProject(category, category, java.nio.file.Path.of("C:/scripts/$category.cmd")),
                category, 0, false)
        }
        assertEquals(listOf("个人", "工作", "未分类"),
            orderedCmdCategories(projects, listOf("已删除", "个人", "个人")))
        assertEquals(listOf("工作", "个人", "未分类"),
            moveCmdCategory(listOf("个人", "工作", "未分类"), "工作", "个人"))
    }
}
