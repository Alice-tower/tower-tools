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
