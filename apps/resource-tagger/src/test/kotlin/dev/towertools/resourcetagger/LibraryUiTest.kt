package dev.towertools.resourcetagger

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryUiTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    @Test fun `GUI pagination retains selections and all results tagging spans unloaded pages`() {
        val path = folder.newFolder("paged").toPath()
        val database = folder.root.toPath().resolve("paged.sqlite")
        val fs = object : ResourceFileSystem {
            override fun inspect(path: java.nio.file.Path) = Kind.File
            override fun scan(path: java.nio.file.Path) = (0 until 450).map { Found("bucket-000001/" + "entry-%04d".format(it), Kind.File) }
        }
        Library(Database(database), fs).use { lib -> val root = lib.saveRoot(null, path.toString(), "Paged"); lib.scan(root); lib.createTag("跨页标签") }
        val controller = LibraryController(database)
        rule.setContent { MaterialTheme { Surface { LibraryApp(null, controller) } } }
        fun idle() { rule.waitForIdle(); rule.waitUntil(10000) { controller.busy == null && !controller.loading }; rule.waitForIdle() }
        idle()
        assertEquals(200, controller.data.resources.size); assertEquals(450, controller.total)
        rule.onNodeWithContentDescription("选择资源 entry-0000").performClick(); idle()
        val focused = controller.focusData.resources.single().id
        rule.onNodeWithText("下一页").performClick(); idle()
        assertEquals(200, controller.offset); assertEquals(setOf(focused), controller.selected)
        assertEquals(focused, controller.focusData.resources.single().id)
        rule.onNodeWithContentDescription("全选结果").performClick(); idle()
        assertEquals(450, controller.selected.size)
        rule.onNodeWithText("批量标签").performClick(); idle()
        rule.onNodeWithText("添加", substring = false).performClick(); idle()
        assertEquals(450, controller.editCounts.values.single())
        assertEquals(450, controller.data.tagCounts.values.single())
        rule.onNodeWithText("完成", substring = false).performClick()
        rule.onNodeWithText("下一页").performClick(); idle()
        assertEquals(50, controller.data.resources.size); assertEquals(450, controller.selected.size)
        rule.onNodeWithText("搜索资源名称或相对路径").performTextReplacement("missing")
        rule.onNodeWithText("搜索资源名称或相对路径").performTextReplacement("entry-0449"); idle()
        assertEquals(1, controller.total); assertEquals("entry-0449", controller.data.resources.single().name)
        assertEquals(0, controller.offset); assertTrue(controller.selected.isEmpty())
        assertEquals(null, controller.error)
    }
    @Test fun `GUI root scan review tag alias filter and ignore workflow`() {
        val path = folder.newFolder("资料 Root").toPath(); Files.createDirectory(path.resolve("bucket-000001"))
        Files.createDirectory(path.resolve("bucket-000001/目录 A")); Files.writeString(path.resolve("bucket-000001/文件 B.txt"), "B")
        val controller = LibraryController(folder.root.toPath().resolve("data/library.sqlite"))
        rule.setContent { MaterialTheme { Surface { LibraryApp(null, controller) } } }
        fun idle() { rule.waitForIdle(); rule.waitUntil(10000) { controller.busy == null && !controller.loading }; rule.waitForIdle() }
        idle()
        rule.onNodeWithText("添加 Root").performClick()
        rule.onNodeWithText("显示名称").performTextInput("我的资料")
        rule.onNodeWithText("目录路径").performTextInput(path.toString())
        rule.onNodeWithText("保存").performClick(); idle()
        rule.onNodeWithText("Root 管理").performClick()
        rule.onNodeWithText("扫描", substring = false).performClick(); idle()
        assertEquals(2, controller.data.resources.size)
        rule.onNodeWithText("资源", substring = false).performClick()
        rule.onNodeWithText("待处理 · 2").performClick(); idle()
        rule.onNodeWithText("▤ 文件 B.txt").performClick(); idle()
        rule.onNodeWithText("确认收录", substring = false).performClick(); idle()
        rule.onNodeWithText("全部资源 / 清空筛选").performClick(); idle()
        rule.onNodeWithText("▤ 文件 B.txt").performClick(); idle()
        rule.onNodeWithText("编辑标签", substring = false).performClick(); idle()
        rule.onNodeWithText("搜索标签或别名").performTextInput("资料")
        rule.onNodeWithText("创建 / 使用并添加").performClick(); idle()
        rule.onNodeWithText("完成", substring = false).performClick()
        assertEquals(1, controller.data.tags.size)
        assertEquals(1, controller.data.links.values.single().size)
        rule.onNodeWithText("标签管理").performClick()
        rule.onNodeWithText("添加别名").performClick()
        rule.onNodeWithText("别名", substring = false).performTextInput("documents")
        rule.onNodeWithText("保存").performClick(); idle()
        assertTrue(controller.data.tags.single().matches("documents"))
        rule.onNodeWithText("资源", substring = false).performClick()
        rule.onNodeWithText("标签 / 别名搜索").performTextInput("documents")
        rule.onNodeWithText("包含 +", substring = true).performScrollTo().performClick(); idle()
        rule.onNodeWithText("▤ 文件 B.txt").assertExists()
        rule.onNodeWithText("▣ 目录 A").assertDoesNotExist()
        val screenshot = java.io.File("build/verification/library-ui.png").apply { parentFile.mkdirs() }
        javax.imageio.ImageIO.write(rule.onRoot().captureToImage().toAwtImage(), "png", screenshot)
        rule.onNodeWithText("忽略", substring = false).performScrollTo().performClick(); idle()
        rule.onNodeWithText("▤ 文件 B.txt").assertDoesNotExist()
        assertTrue(controller.focusData.resources.any { it.name == "文件 B.txt" && it.status == Status.Ignored })
        assertTrue(Files.exists(path.resolve("bucket-000001/文件 B.txt")))
        assertEquals(null, controller.error)
    }

    @Test fun `GUI batch tags relocation confirmation and deletion boundaries`() {
        val path = folder.newFolder("资源").toPath(); Files.createDirectory(path.resolve("bucket-000001"))
        val old = Files.writeString(path.resolve("bucket-000001/old.txt"), "old")
        Files.writeString(path.resolve("bucket-000001/second.txt"), "second")
        val database = folder.root.toPath().resolve("data/library.sqlite")
        Library(Database(database)).use { lib -> val root = lib.saveRoot(null, path.toString(), "资源"); lib.scan(root); lib.createTag("主题") }
        val controller = LibraryController(database)
        rule.setContent { MaterialTheme { Surface { LibraryApp(null, controller) } } }
        fun idle() { rule.waitForIdle(); rule.waitUntil(10000) { controller.busy == null && !controller.loading }; rule.waitForIdle() }
        idle()
        rule.onNodeWithContentDescription("选择资源 old.txt").performClick()
        rule.onNodeWithContentDescription("选择资源 second.txt").performClick()
        rule.onNodeWithText("批量标签").assertIsEnabled().performClick(); idle()
        rule.onNodeWithText("添加", substring = false).performClick(); idle()
        rule.onNodeWithText("完成", substring = false).performClick()
        assertEquals(2, controller.data.links.size)
        val oldId = controller.data.resources.single { it.name == "old.txt" }.id
        val moved = Files.move(old, path.resolve("bucket-000001/renamed.txt"))
        rule.onNodeWithText("Root 管理").performClick(); rule.onNodeWithText("扫描", substring = false).performClick(); idle()
        rule.onNode(hasText("资源") and hasClickAction()).performClick(); rule.onNodeWithText("▤ old.txt").performClick(); idle()
        rule.onNodeWithText("重新定位", substring = false).performScrollTo().performClick()
        rule.onNodeWithText("目标完整路径").performTextInput(moved.toString())
        rule.onNodeWithText("保存").performClick(); idle()
        assertTrue(controller.error?.contains("勾选") == true)
        rule.onNode(isToggleable() and hasAnySibling(hasText("允许合并无标签、未处理的新发现记录"))).performClick()
        rule.onNodeWithText("保存").performClick(); idle()
        assertEquals(oldId, controller.data.resources.single { it.name == "renamed.txt" }.id)
        assertEquals(2, controller.data.resources.size)
        rule.onNodeWithText("标签管理").performClick(); rule.onNodeWithText("删除", substring = false).performClick()
        rule.onNodeWithText("取消", substring = false).performClick(); assertEquals(1, controller.data.tags.size)
        rule.onNodeWithText("删除", substring = false).performClick(); rule.onNodeWithText("确认", substring = false).performClick(); idle()
        assertTrue(controller.data.links.isEmpty()); assertEquals(2, controller.data.resources.size)
        rule.onNodeWithText("Root 管理").performClick(); rule.onNodeWithText("移除", substring = false).performClick()
        rule.onNodeWithText("确认", substring = false).performClick(); idle()
        assertTrue(controller.data.roots.isEmpty()); assertTrue(controller.data.resources.isEmpty())
        assertEquals("old", Files.readString(moved)); assertEquals("second", Files.readString(path.resolve("bucket-000001/second.txt")))
    }
}
