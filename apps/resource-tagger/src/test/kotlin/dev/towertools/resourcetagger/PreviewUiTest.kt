package dev.towertools.resourcetagger

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PreviewUiTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    @Test fun `changing preview target discards late content and closing a tab cancels its request`() {
        val host = PreviewHost("lifecycle", folder.root.toPath())
        var target by mutableStateOf("old")
        var visible by mutableStateOf(true)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val closingStarted = CountDownLatch(1)
        val closingCancelled = CountDownLatch(1)
        try {
            rule.setContent {
                MaterialTheme {
                    if (visible) {
                        val requested = target
                        when (val result = rememberPreview(host, requested) {
                            if (requested == "old") {
                                started.countDown()
                                while (true) try { release.await(); break } catch (_: InterruptedException) { }
                                finished.countDown()
                            }
                            if (requested == "closing") {
                                closingStarted.countDown()
                                try { CountDownLatch(1).await() }
                                catch (e: InterruptedException) { closingCancelled.countDown(); throw e }
                            }
                            requested
                        }) {
                            PreviewLoad.Loading -> Text("loading")
                            is PreviewLoad.Ready -> Text(result.value)
                            is PreviewLoad.Failed -> Text(result.message)
                        }
                    }
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            rule.runOnIdle { target = "new" }
            rule.waitUntil(10000) { rule.onAllNodesWithText("new").fetchSemanticsNodes().isNotEmpty() }
            release.countDown(); assertTrue(finished.await(10, TimeUnit.SECONDS))
            rule.waitForIdle(); rule.onNodeWithText("new").assertExists(); rule.onNodeWithText("old").assertDoesNotExist()
            rule.runOnIdle { target = "closing" }; rule.waitForIdle()
            assertTrue(closingStarted.await(10, TimeUnit.SECONDS))
            rule.runOnIdle { visible = false }; rule.waitForIdle()
            assertTrue(closingCancelled.await(10, TimeUnit.SECONDS))
            rule.onNodeWithText("new").assertDoesNotExist()
        } finally { release.countDown(); host.close() }
    }

    @Test fun `host layouts tabs and actions retain resource management and selection across pages`() {
        val rootPath = folder.newFolder("preview").toPath()
        Files.writeString(rootPath.resolve("entry-0000"), "preview content")
        val database = folder.root.toPath().resolve("library.sqlite")
        val fs = object : ResourceFileSystem {
            override fun inspect(path: java.nio.file.Path) = Kind.File
            override fun scan(path: java.nio.file.Path) = (0 until 450).map { Found("entry-%04d".format(it), Kind.File) }
        }
        Library(Database(database), fs).use { lib -> lib.scan(lib.saveRoot(null, rootPath.toString(), "Root")); lib.createTag("test-tag") }
        val loads = AtomicInteger()
        val plugin = object : PreviewPlugin {
            override val id = "test-grid"
            override val title = "测试网格"
            override fun tabs(target: PreviewTarget) = listOf(PreviewTab("test-tab", "测试预览") { current, host ->
                when (val result = rememberPreview(host, current) { loads.incrementAndGet(); Files.readString(host.files.resolve(current)) }) {
                    PreviewLoad.Loading -> Text("测试预览加载中")
                    is PreviewLoad.Ready -> Text(result.value)
                    is PreviewLoad.Failed -> Text(result.message)
                }
            })
            override fun actions(target: PreviewTarget) = listOf(PreviewAction("fail", "测试失败动作") { _, _ -> error("测试预览失败") })
        }
        val tabsOnly = object : PreviewPlugin {
            override val id = "tabs-only"
            override val title = "仅详情扩展"
        }
        val controller = LibraryController(database)
        rule.setContent { MaterialTheme { Surface { LibraryApp(null, controller, PreviewRegistry(listOf(StandardPreviewPlugin, plugin, tabsOnly))) } } }
        fun idle() { rule.waitForIdle(); rule.waitUntil(10000) { controller.busy == null && !controller.loading }; rule.waitForIdle() }
        fun choose(current: String, next: String) {
            rule.onNodeWithText("预览插件：$current").performClick()
            rule.onNodeWithText(next, substring = false).performClick(); idle()
        }
        idle()
        rule.onNodeWithText("搜索资源名称或相对路径").performTextReplacement("entry"); idle()
        rule.onNodeWithContentDescription("选择资源 entry-0000").performClick(); idle()
        val selected = controller.selected
        val focused = controller.focusData.resources.single().id
        rule.onNodeWithText("下一页").performClick(); idle()
        choose("标准", "测试网格")
        rule.onNodeWithText("网格", substring = false).performClick()
        rule.onNodeWithTag("resource-grid").assertExists()
        assertEquals(450, controller.total); assertEquals(200, controller.offset)
        assertEquals(selected, controller.selected); assertEquals(focused, controller.focusData.resources.single().id)
        assertEquals(0, loads.get())
        rule.onNodeWithText("测试预览", substring = false).performClick()
        rule.waitUntil(10000) { loads.get() > 0 }; rule.waitForIdle()
        rule.waitUntil(10000) { rule.onAllNodesWithText("preview content").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("资源信息").performClick()
        rule.onNodeWithText("编辑标签", substring = false).assertExists()
        rule.onNodeWithText("上一页").performClick(); idle()
        rule.onNodeWithText("▤ entry-0000").performMouseInput { click(button = MouseButton.Secondary) }
        rule.onNodeWithText("测试失败动作").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("测试预览失败").fetchSemanticsNodes().isNotEmpty() }
        assertNull(controller.error); assertEquals(selected, controller.selected)
        rule.onNodeWithContentDescription("全选结果").performClick(); idle()
        rule.onNodeWithText("批量标签").performClick(); idle()
        rule.onNodeWithText("添加", substring = false).performClick(); idle()
        assertEquals(450, controller.editCounts.values.single())
        rule.onNodeWithText("完成", substring = false).performClick()
        choose("测试网格", "仅详情扩展")
        rule.onNodeWithTag("resource-grid").assertExists()
        rule.onNodeWithText("▤ entry-0000").assertExists()
        assertEquals(450, controller.selected.size)
        choose("仅详情扩展", "标准")
        rule.onNodeWithText("画廊", substring = false).performClick(); idle()
        rule.onNodeWithTag("resource-gallery").assertExists()
        val screenshot = java.io.File("build/verification/gallery-ui.png").apply { parentFile.mkdirs() }
        javax.imageio.ImageIO.write(rule.onRoot().captureToImage().toAwtImage(), "png", screenshot)
        assertEquals(450, controller.selected.size)
        assertEquals(focused, controller.focusData.resources.single().id)
        rule.onNodeWithText("列表", substring = false).performClick(); idle()
        rule.onNodeWithTag("resource-list").assertExists()
        rule.onNodeWithText("搜索资源名称或相对路径").assertTextContains("entry")
        assertEquals("preview content", Files.readString(rootPath.resolve("entry-0000")))
    }
}
