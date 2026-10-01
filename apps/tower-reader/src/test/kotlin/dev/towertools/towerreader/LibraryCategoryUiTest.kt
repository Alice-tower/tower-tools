package dev.towertools.towerreader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.lightColors
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.towertools.towerreader.library.LibraryRepository
import dev.towertools.towerreader.ui.LibraryScreen
import dev.towertools.towerreader.ui.LibraryCategories
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.EventQueue
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LibraryCategoryUiTest {
    @get:Rule val rule=createComposeRule()
    @get:Rule val folder=TemporaryFolder()
    private fun <T> edt(action:()->T):T {
        val task=FutureTask<T> { action() }; EventQueue.invokeAndWait(task); return task.get(10,TimeUnit.SECONDS)
    }
    @Test fun rightClickRenameAndMouseHandleReorderPersistWithoutChangingSelection() {
        val data=folder.root.toPath().resolve("data")
        LibraryRepository(data).use { repo ->
            val files=(0..3).map { Files.writeString(folder.root.toPath().resolve("书籍$it.txt"),"正文 $it") }
            assertEquals(4,repo.importBooks(files).added)
            repo.listBooks().forEachIndexed { index,book -> repo.updateBook(book.id,book.title,listOf("文学","文学","旅行","随笔")[index]) }
            repo.reorderCategories(listOf("文学","旅行","随笔"))
        }
        val controller=edt { ReaderController(data) }
        try {
            rule.setContent {
                val palette=lightColors(primary=Color(0xff4b664c),background=Color(0xfff4eedf),surface=Color(0xfffaf5e9),onSurface=Color(0xff34382f),onBackground=Color(0xff34382f),onPrimary=Color.White)
                MaterialTheme(colors=palette) { Surface { Box(Modifier.size(1180.dp,820.dp).testTag("category-test-host")) { LibraryScreen(controller,{}) } } }
            }
            rule.waitUntil(15000) { controller.ready || controller.error!=null }
            assertNull(edt { controller.error })
            rule.onNodeWithTag("library-filter-文学").performClick()
            rule.onNodeWithText("2 本书").assertExists()
            fun edit() {
                rule.onNodeWithTag("library-filter-文学").performMouseInput { click(button=MouseButton.Secondary) }
                rule.onNodeWithText("编辑",substring=false).performClick()
            }
            edit()
            val input=rule.onNode(hasSetTextAction() and hasText("分类名称"))
            input.performTextReplacement("   ")
            rule.onNodeWithText("保存",substring=false).assertIsNotEnabled()
            rule.onNodeWithText("分类名称不能为空").assertExists()
            input.performTextReplacement("旅行")
            rule.onNodeWithText("保存",substring=false).assertIsNotEnabled()
            rule.onNodeWithText("已有同名分类，请换一个名称").assertExists()
            input.performTextReplacement("诗歌")
            rule.onNodeWithText("取消",substring=false).performClick()
            rule.onNodeWithTag("library-filter-文学").assertExists()
            edit()
            input.performTextReplacement("  诗歌  ")
            rule.onNodeWithText("保存",substring=false).performClick()
            rule.waitUntil(10000) { controller.categories==listOf("诗歌","旅行","随笔") }
            rule.onNodeWithTag("library-filter-文学").assertDoesNotExist()
            rule.onNodeWithText("2 本书").assertExists()
            assertEquals(2,edt { controller.books.count { it.category=="诗歌" } })
            rule.onNodeWithTag("category-handle-全部书籍",useUnmergedTree=true).assertDoesNotExist()
            val sidebar=rule.onNodeWithTag("library-categories")
            val origin=sidebar.fetchSemanticsNode().boundsInRoot.topLeft
            val start=rule.onNodeWithTag("category-handle-诗歌",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.center-origin
            val end=rule.onNodeWithTag("category-handle-随笔",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.center-origin
            rule.mainClock.autoAdvance=false
            sidebar.performMouseInput { moveTo(start); press() }
            repeat(12) { step ->
                sidebar.performMouseInput { moveTo(start+(end-start)*((step+1)/12f)); advanceEventTime(20) }
                rule.mainClock.advanceTimeBy(32)
            }
            sidebar.performMouseInput { release() }
            rule.mainClock.autoAdvance=true
            rule.waitUntil(10000) { controller.categories==listOf("旅行","随笔","诗歌") }
            rule.onNodeWithText("2 本书").assertExists()
            val rows=listOf("旅行","随笔","诗歌").map { rule.onNodeWithTag("library-filter-$it").fetchSemanticsNode().boundsInRoot.top }
            assertEquals(rows.sorted(),rows)
            assertNull(edt { controller.error })
            val screenshot=java.io.File("build/test-previews/library-categories.png").apply { parentFile.mkdirs() }
            javax.imageio.ImageIO.write(rule.onNodeWithTag("category-test-host").captureToImage().toAwtImage(),"png",screenshot)
        } finally {
            rule.mainClock.autoAdvance=true
            val done=CountDownLatch(1); edt { controller.close { done.countDown() } }; assertTrue(done.await(20,TimeUnit.SECONDS))
        }
        LibraryRepository(data).use { repo ->
            assertEquals(listOf("旅行","随笔","诗歌"),repo.listCategories())
            assertEquals(2,repo.listBooks().count { it.category=="诗歌" })
        }
    }
    @Test fun draggingAtListEdgesScrollsBothDirectionsAndSavesOnlyOnRelease() {
        val original=(1..20).map { "分类%02d".format(it) }
        val order=mutableStateOf(original)
        var commits=0
        rule.setContent { MaterialTheme { Box(Modifier.size(300.dp,520.dp)) {
            LibraryCategories(emptyList(),order.value,"all",{},{},{ order.value=it; commits++ })
        } } }
        fun dragToEdge(bottom:Boolean) {
            val sidebar=rule.onNodeWithTag("library-categories")
            val area=sidebar.fetchSemanticsNode().boundsInRoot
            val start=rule.onNodeWithTag("category-handle-${original.first()}",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.center-area.topLeft
            val end=Offset(start.x,if(bottom) area.height-12 else 12f)
            val previousCommits=commits
            rule.mainClock.autoAdvance=false
            try {
                sidebar.performMouseInput { moveTo(start); press() }
                repeat(10) { step ->
                    sidebar.performMouseInput { moveTo(start+(end-start)*((step+1)/10f)); advanceEventTime(20) }
                    rule.mainClock.advanceTimeBy(32)
                }
                rule.mainClock.advanceTimeBy(2500)
                assertEquals(previousCommits,commits,"Dragging previews the order without writing each crossing")
                sidebar.performMouseInput { release() }
            } finally { rule.mainClock.autoAdvance=true }
            rule.waitForIdle()
            assertEquals(previousCommits+1,commits)
        }
        dragToEdge(true)
        assertEquals(original.drop(1)+original.first(),order.value)
        dragToEdge(false)
        assertEquals(original,order.value)
    }
}
