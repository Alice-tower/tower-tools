package dev.towertools.towerreader

import dev.towertools.towerreader.flow.FlowPageState
import dev.towertools.towerreader.library.BookRecord
import dev.towertools.towerreader.library.LibraryRepository
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Exercises the real loader, executors and SQLite persistence without creating a window. */
class ControllerLifecycleTest {
    @TempDir lateinit var directory:Path
    private fun <T> edt(action:()->T):T {
        if(EventQueue.isDispatchThread()) return action()
        val task=FutureTask<T> { action() }
        EventQueue.invokeAndWait(task)
        return task.get(10,TimeUnit.SECONDS)
    }
    private fun await(label:String,condition:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15)
        while(System.nanoTime()<deadline) {
            if(edt(condition)) return
            Thread.sleep(10)
        }
        fail("等待超时：$label")
    }
    private fun close(controller:ReaderController) {
        if(edt { controller.closing }) return
        val done=CountDownLatch(1)
        edt { controller.close { done.countDown() } }
        assertTrue(done.await(20,TimeUnit.SECONDS),"关闭应等待保存完成并释放数据库")
    }
    private fun fixture():Pair<Path,BookRecord> {
        val source=Files.writeString(directory.resolve("fixture.txt"),
            "第一章 开始\n"+"第一章正文。".repeat(80)+"\n第二章 中途\n"+"第二章正文。".repeat(80)+"\n第三章 结束\n"+"第三章正文。".repeat(80))
        val data=directory.resolve("data")
        val book=LibraryRepository(data).use { repo -> assertEquals(1,repo.importBooks(listOf(source)).added); repo.listBooks().single() }
        return data to book
    }
    private fun opened(data:Path,book:BookRecord):ReaderController {
        val controller=edt { ReaderController(data) }
        await("初始化书库") { controller.ready || controller.error!=null }
        assertNull(edt { controller.error })
        edt { controller.open(book) }
        await("加载TXT") { !controller.loading }
        assertNotNull(edt { controller.flow })
        return controller
    }
    @Test fun immediatelyReopeningOldBookRecordPreservesLatestPosition() {
        val (data,oldBook)=fixture()
        val controller=opened(data,oldBook)
        try {
            edt {
                controller.onFlowPosition(FlowPageState(137,2,10,1,500))
                controller.returnToLibrary()
                controller.open(oldBook)
            }
            await("用旧书籍记录重开") { !controller.loading && controller.flow!=null }
            assertEquals(137,edt { controller.chapterOffset })
            assertEquals(0,edt { controller.chapter })
            close(controller)
            LibraryRepository(data).use { assertEquals("flow:0:137",it.listBooks().single().locator) }
        } finally { close(controller) }
    }
    @Test fun failedChapterKeepsCurrentPageAndDoesNotWriteWrongChapterProgress() {
        val (data,book)=fixture()
        val controller=opened(data,book)
        try {
            val previousHtml=edt { controller.chapterHtml }
            val missing=edt { controller.flow!!.chapters[1].htmlPath }
            Files.delete(missing)
            edt {
                controller.onFlowPosition(FlowPageState(63,1,10,1,500))
                controller.goChapter(1)
                // A position already queued by the disposed old pane must be ignored while loading.
                controller.onFlowPosition(FlowPageState(999,9,10,1,1000))
            }
            await("章节读取失败") { !controller.loading && controller.error!=null }
            assertEquals(0,edt { controller.chapter })
            assertEquals(previousHtml,edt { controller.chapterHtml })
            assertEquals(63,edt { controller.pageState?.offset })
            close(controller)
            LibraryRepository(data).use { assertEquals("flow:0:63",it.listBooks().single().locator) }
        } finally { close(controller) }
    }
    @Test fun staleChapterFailureCannotReplaceNewerSuccessfulRequest() {
        val (data,book)=fixture()
        val controller=opened(data,book)
        val release=CountDownLatch(1)
        try {
            Files.delete(edt { controller.flow!!.chapters[1].htmlPath })
            val entered=CountDownLatch(1)
            controller.readerExecutor.execute { entered.countDown(); release.await(10,TimeUnit.SECONDS) }
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            edt { controller.goChapter(1); controller.goChapter(2,87) }
            release.countDown()
            await("新的章节请求") { !controller.loading && controller.chapter==2 }
            assertNull(edt { controller.error })
            assertEquals(87,edt { controller.chapterOffset })
            assertTrue(edt { controller.chapterHtml!!.contains("第三章") })
        } finally { release.countDown(); close(controller) }
    }
    @Test fun closeFlushesPendingSettingsAndProgressAndReleasesDatabase() {
        val (data,book)=fixture()
        val controller=opened(data,book)
        try {
            edt {
                controller.onFlowPosition(FlowPageState(211,3,10,1,500))
                controller.updateSettings(controller.settings.copy(theme="dark",fontSize=31,margin=52,pdfZoom=1.7))
            }
            close(controller)
            LibraryRepository(data).use { repo ->
                assertEquals("flow:0:211",repo.listBooks().single().locator)
                assertEquals("dark",repo.setting("theme","")); assertEquals("31",repo.setting("size",""))
                assertEquals("52",repo.setting("margin","")); assertEquals("1.7",repo.setting("pdfZoom",""))
            }
            val database=data.resolve("library.sqlite")
            val moved=data.resolve("saved.sqlite")
            Files.move(database,moved)
            Files.move(moved,database)
            val reopened=edt { ReaderController(data) }
            try {
                await("再次打开控制器") { reopened.ready || reopened.error!=null }
                assertNull(edt { reopened.error })
                assertEquals(31,edt { reopened.settings.fontSize })
                assertEquals("flow:0:211",edt { reopened.books.single().locator })
            } finally { close(reopened) }
        } finally { close(controller) }
    }
    @Test fun failedInitializationCanCloseWithoutKeepingDatabaseLocked() {
        val data=Files.createDirectory(directory.resolve("data"))
        val database=Files.writeString(data.resolve("library.sqlite"),"damaged database")
        val controller=edt { ReaderController(data) }
        try {
            await("损坏书库错误") { controller.error!=null }
            assertFalse(edt { controller.ready })
            close(controller)
            assertEquals("damaged database",Files.readString(database))
            Files.move(database,data.resolve("preserved.sqlite"))
        } finally { close(controller) }
    }
    @Test fun twoQueuedFailedChaptersRestoreLastActuallyRenderedPage() {
        val (data,book)=fixture()
        val controller=opened(data,book)
        val release=CountDownLatch(1)
        try {
            val baseline=FlowPageState(123,2,10,1,500)
            edt { controller.onFlowPosition(baseline) }
            val originalHtml=edt { controller.chapterHtml }
            // The first neighbour may already be prefetched. Its disappearance must still fail.
            Files.delete(edt { controller.flow!!.chapters[1].htmlPath })
            Files.delete(edt { controller.flow!!.chapters[2].htmlPath })
            val entered=CountDownLatch(1)
            controller.readerExecutor.execute { entered.countDown(); release.await(10,TimeUnit.SECONDS) }
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            edt { controller.goChapter(1); controller.goChapter(2) }
            release.countDown()
            await("最新失败恢复真正显示的页面") { !controller.loading && controller.error!=null }
            assertEquals(0,edt { controller.chapter })
            assertEquals(originalHtml,edt { controller.chapterHtml })
            assertEquals(baseline,edt { controller.pageState })
            // Once the error is dismissed, the restored page can continue reporting positions.
            edt { controller.error=null; controller.onFlowPosition(baseline.copy(offset=144,page=3)) }
            close(controller)
            LibraryRepository(data).use { assertEquals("flow:0:144",it.listBooks().single().locator) }
        } finally { release.countDown(); close(controller) }
    }
    @Test fun failureAfterHtmlCommitResubmitsCurrentChapterWithoutReusingOlderState() {
        val (data,book)=fixture()
        val controller=opened(data,book)
        try {
            edt { controller.onFlowPosition(FlowPageState(57,1,10,1,500)); controller.goChapter(1,89) }
            await("第二章HTML提交但尚无显示回报") { !controller.loading && controller.chapter==1 }
            val committedHtml=edt { controller.chapterHtml }
            val committedToken=edt { controller.chapterToken }
            assertNull(edt { controller.pageState })
            Files.delete(edt { controller.flow!!.chapters[2].htmlPath })
            edt { controller.goChapter(2) }
            await("失败后的当前HTML重新提交") { !controller.loading && controller.error!=null }
            assertEquals(1,edt { controller.chapter })
            assertEquals(committedHtml,edt { controller.chapterHtml })
            assertEquals(89,edt { controller.chapterOffset })
            assertEquals(committedToken+1,edt { controller.chapterToken })
            assertNull(edt { controller.pageState },"不能用第一章页面状态恢复第二章")
            close(controller)
            LibraryRepository(data).use { assertEquals("flow:0:57",it.listBooks().single().locator) }
        } finally { close(controller) }
    }
}
