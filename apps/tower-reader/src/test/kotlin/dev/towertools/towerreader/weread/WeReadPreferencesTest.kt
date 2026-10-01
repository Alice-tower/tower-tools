package dev.towertools.towerreader.weread

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WeReadPreferencesTest {
    @TempDir lateinit var directory: Path
    private fun <T> ui(block: () -> T): T {
        val value = CompletableFuture<T>()
        EventQueue.invokeAndWait { runCatching(block).fold(value::complete, value::completeExceptionally) }
        return value.get(3,TimeUnit.SECONDS)
    }
    private fun ready(controller: WeReadController) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
        while(!ui { controller.ready }) { check(System.nanoTime()<deadline); Thread.sleep(10) }
    }
    private fun close(controller: WeReadController) {
        val done=CompletableFuture<Unit>(); ui { controller.close { done.complete(Unit) } }; done.get(3,TimeUnit.SECONDS)
    }
    @Test fun lastModuleSurvivesRestartAndLocalStartupDoesNotCreateBrowser() {
        val first=ui { WeReadController(directory) }; ready(first)
        ui { assertFalse(first.selected); assertFalse(first.activated); assertNull(first.pane); first.select(true) }; close(first)
        val second=ui { WeReadController(directory) }; ready(second)
        ui { assertTrue(second.selected); assertTrue(second.activated); assertNull(second.pane); second.select(false) }; close(second)
        val third=ui { WeReadController(directory) }; ready(third)
        ui { assertFalse(third.selected); assertFalse(third.activated); assertNull(third.pane) }; close(third)
    }
    @Test fun onlyReadingLocationsAreRestoredAndAuthenticationUrlsAreNotStored() {
        assertTrue(WeReadLocation.restorable("https://weread.qq.com/web/reader/abc123"))
        listOf("https://weread.qq.com.evil.example/web/shelf", "https://evil@weread.qq.com/web/shelf", "file:///C:/secret", "javascript:alert(1)",
            "https://weread.qq.com/web/shelf?code=token", "https://open.weixin.qq.com/connect/qrconnect?code=token").forEach {
            assertFalse(WeReadLocation.restorable(it)); assertEquals(WeReadLocation.shelf, WeReadLocation.restore(it))
        }
        assertTrue(WeReadLocation.allowed("https://open.weixin.qq.com/connect/qrconnect"))
        assertFalse(WeReadLocation.allowed("https://qq.com.evil.example/"))
        Files.newOutputStream(directory.resolve("navigation.properties")).use { out -> Properties().apply {
            setProperty("module","weread");setProperty("url","javascript:alert(1)")
        }.store(out,"") }
        val controller=ui { WeReadController(directory) };ready(controller);close(controller)
        val values=Properties().apply { Files.newInputStream(directory.resolve("navigation.properties")).use(::load) }
        assertEquals(WeReadLocation.shelf,values.getProperty("url"))
    }
}
