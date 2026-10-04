package dev.towertools.towerreader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.towertools.towerreader.library.LibraryRepository
import dev.towertools.towerreader.ui.LibraryScreen
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.EventQueue
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LibraryUiTest {
    @get:Rule val rule=createComposeRule()
    @get:Rule val folder=TemporaryFolder()
    private fun <T> edt(action:()->T):T {
        val task=FutureTask<T> { action() }
        EventQueue.invokeAndWait(task)
        return task.get(10,TimeUnit.SECONDS)
    }
    private fun close(controller:ReaderController) {
        val done=CountDownLatch(1)
        edt { controller.close { done.countDown() } }
        assertTrue(done.await(20,TimeUnit.SECONDS))
    }
    @Test fun searchClassificationAndRightClickDeletionAreOperableThroughLibraryUi() {
        val root=folder.root.toPath()
        val source=Files.writeString(root.resolve("quiet-original.txt"),"窗前的风，和一段安静的文字。")
        val travel=Files.writeString(root.resolve("远方的灯火.txt"),"旅行日记，山川与城市。")
        val notes=Files.writeString(root.resolve("日常的诗意.txt"),"日常小事，也值得阅读。")
        val data=root.resolve("data")
        var quietId=""
        val managed=LibraryRepository(data).use { repo ->
            assertEquals(3,repo.importBooks(listOf(source,travel,notes)).added)
            val quiet=repo.listBooks().single { it.sourceName=="quiet-original.txt" }
            quietId=quiet.id
            repo.updateBook(quiet.id,"窗前的风","文学")
            repo.markOpened(quiet.id); repo.saveProgress(quiet.id,"flow:0:15",0.42)
            val trip=repo.listBooks().single { it.sourceName=="远方的灯火.txt" }
            repo.updateBook(trip.id,trip.title,"旅行")
            repo.bookPath(quiet)
        }
        val controller=edt { ReaderController(data) }
        try {
            rule.setContent {
                MaterialTheme(colors=readerColors(controller.settings.theme)) {
                    Surface {
                        Column(Modifier.size(1180.dp,820.dp)) {
                            ThemeSelector(controller)
                            LibraryScreen(controller,{})
                        }
                    }
                }
            }
            rule.waitUntil(15000) { controller.ready || controller.error!=null }
            rule.waitForIdle()
            assertNull(edt { controller.error })
            rule.onNodeWithText("3 本书").assertExists()
            fun screenshot(name:String) {
                rule.waitForIdle()
                val file=java.io.File("build/test-previews/$name").apply { parentFile.mkdirs() }
                javax.imageio.ImageIO.write(rule.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),"png",file)
            }
            screenshot("library.png")
            rule.onNodeWithText("深色", substring=false).performClick()
            rule.onNodeWithText("✓ 深色", substring=false).assertExists()
            assertEquals("dark", edt { controller.settings.theme })
            screenshot("library-dark.png")
            rule.onNodeWithText("暖纸", substring=false).performClick()
            rule.onNodeWithText("✓ 暖纸", substring=false).assertExists()
            assertEquals("paper", edt { controller.settings.theme })
            rule.onNodeWithText("已读 42%",useUnmergedTree=true).assertExists()
            rule.onAllNodesWithText("尚未阅读",useUnmergedTree=true).assertCountEquals(2)
            val search=rule.onNode(hasSetTextAction())
            search.performTextReplacement("quiet-original.txt")
            rule.onNodeWithText("1 本书").assertExists()
            rule.onNodeWithTag("book-card-$quietId").assertExists()
            rule.onNodeWithText("远方的灯火").assertDoesNotExist()
            search.performTextReplacement("窗前")
            rule.onNodeWithText("1 本书").assertExists()
            rule.onNodeWithText("编辑").assertDoesNotExist()
            rule.onNodeWithTag("book-card-$quietId").performMouseInput { click(button=MouseButton.Secondary) }
            rule.onNodeWithText("编辑").performClick()
            rule.onNode(hasSetTextAction() and hasText("书名")).performTextReplacement("窗前的风与月")
            rule.onNodeWithText("选择已有分类 ▾").assertDoesNotExist()
            rule.onNodeWithContentDescription("选择分类").performClick()
            rule.onNodeWithTag("book-category-option-旅行").performClick()
            rule.onNodeWithTag("book-category-input").assertTextContains("旅行")
            rule.onNodeWithContentDescription("选择分类").performClick()
            rule.onNodeWithTag("book-category-none").performClick()
            rule.onNodeWithTag("book-category-input").assertTextEquals("分类（可输入新分类）", "未分类")
            screenshot("book-category-combobox.png")
            rule.onNodeWithTag("book-category-input").performTextReplacement("随笔")
            rule.onNodeWithText("保存").performClick()
            rule.waitUntil(10000) { controller.books.any { it.title=="窗前的风与月" && it.category=="随笔" } }
            rule.waitForIdle()
            search.performTextReplacement("")
            rule.onNodeWithTag("library-filter-随笔").performClick()
            rule.onNodeWithText("1 本书").assertExists()
            rule.onNodeWithText("远方的灯火").assertDoesNotExist()
            rule.onNodeWithTag("book-card-$quietId").performMouseInput { click(button=MouseButton.Secondary) }
            rule.onNodeWithText("移出").performClick()
            rule.onNodeWithText("取消").performClick()
            rule.onNodeWithText("1 本书").assertExists()
            assertTrue(Files.exists(managed))
            rule.onNodeWithTag("book-card-$quietId").performMouseInput { click(button=MouseButton.Secondary) }
            rule.onNodeWithText("移出").performClick()
            rule.onNodeWithText("移出书库").performClick()
            rule.waitUntil(10000) { controller.books.size==2 }
            rule.waitForIdle()
            rule.onNodeWithText("0 本书").assertExists()
            assertFalse(Files.exists(managed))
            assertEquals("窗前的风，和一段安静的文字。",Files.readString(source))
            assertTrue(Files.exists(travel)); assertTrue(Files.exists(notes))
            rule.onNodeWithTag("library-filter-全部书籍").performClick()
            rule.onNodeWithText("2 本书").assertExists()
            assertNull(edt { controller.error })
            LibraryRepository(data).use { repo ->
                assertEquals(2,repo.listBooks().size)
                assertTrue(repo.listBooks().none { it.sourceName=="quiet-original.txt" })
            }
        } finally { close(controller) }
    }
}
