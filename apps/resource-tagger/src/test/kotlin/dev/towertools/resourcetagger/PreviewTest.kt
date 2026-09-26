package dev.towertools.resourcetagger

import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class PreviewTest {
    @TempDir lateinit var temp: Path
    private fun CountDownLatch.awaitChecked() = assertTrue(await(10, TimeUnit.SECONDS), "Timed out")

    @Test fun `cancelled and closed requests never publish late results even if work ignores interruption`() {
        val tasks = PreviewTasks()
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val deliveries = AtomicInteger()
        fun work(): String {
            started.countDown()
            while (true) try { release.await(); break } catch (_: InterruptedException) { }
            finished.countDown()
            return "obsolete"
        }
        val first = tasks.submit(::work) { deliveries.incrementAndGet() }
        tasks.submit(::work) { deliveries.incrementAndGet() }
        try {
            started.awaitChecked()
            first.close(); tasks.close(); release.countDown(); finished.awaitChecked()
            EventQueue.invokeAndWait { }
            assertEquals(0, deliveries.get())
        } finally { release.countDown(); tasks.close() }
    }

    @Test fun `preview queue is bounded reports failure on UI thread and recovers`() {
        PreviewTasks().use { tasks ->
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val rejected = CountDownLatch(1)
            val completed = CountDownLatch(1)
            repeat(2) { tasks.submit({ started.countDown(); release.await() }) { } }
            try {
                started.awaitChecked()
                val queued = (0 until 128).map { tasks.submit({ 1 }) {} }
                tasks.submit({ error("Must not run on caller") }) { result ->
                    assertTrue(EventQueue.isDispatchThread())
                    assertTrue(result.exceptionOrNull()?.message?.contains("繁忙") == true)
                    rejected.countDown()
                }
                rejected.awaitChecked()
                queued.forEach { it.close() }
                tasks.submit({ assertFalse(EventQueue.isDispatchThread()); 42 }) {
                    assertEquals(42, it.getOrThrow()); completed.countDown()
                }
                release.countDown(); completed.awaitChecked()
            } finally { release.countDown() }
        }
    }

    @Test fun `plugin identity storage and path access reject invalid or stale targets`() {
        assertFailsWith<IllegalArgumentException> { PreviewStorage(temp, "../escape") }
        assertFailsWith<IllegalArgumentException> { PreviewRegistry(listOf(StandardPreviewPlugin, StandardPreviewPlugin)) }
        assertEquals(StandardPreviewPlugin, PreviewRegistry().find("removed-plugin"))
        assertFailsWith<IllegalArgumentException> { checkedContributions(listOf("same", "same")) { it } }
        val a = PreviewStorage(temp, "one").configDirectory()
        val b = PreviewStorage(temp, "two").cacheDirectory()
        assertNotEquals(a.parent, b.parent); assertTrue(a.startsWith(temp)); assertTrue(b.startsWith(temp))
        val rootPath = Files.createDirectory(temp.resolve("root")); Files.createDirectory(rootPath.resolve("bucket-000001"))
        val file = Files.writeString(rootPath.resolve("bucket-000001/file.txt"), "unchanged")
        val root = Root("root", rootPath.toString(), "Root", "Available", null, null, null)
        val resource = Resource("resource", root.id, "bucket-000001/file.txt", Kind.File, "file", Status.Active, "now", null)
        val access = PreviewAccess()
        assertEquals(file, access.resolve(PreviewTarget(root, resource)))
        assertFailsWith<IllegalArgumentException> { access.resolve(PreviewTarget(root, resource.copy(status = Status.Ignored))) }
        assertFailsWith<IllegalArgumentException> { access.resolve(PreviewTarget(root, resource.copy(relativePath = "../file.txt"))) }
        assertFailsWith<IllegalArgumentException> { access.resolve(PreviewTarget(root.copy(id = "other"), resource)) }
        assertEquals("unchanged", Files.readString(file))
        Files.delete(file); Files.createDirectory(file)
        assertFailsWith<IllegalArgumentException> { access.resolve(PreviewTarget(root, resource)) }
    }

    @Test fun `closing plugin scope cancels queued actions without blocking another plugin`() {
        PreviewRuntime(temp).use { runtime ->
            val first = runtime.host("first")
            val started = CountDownLatch(2)
            val release = CountDownLatch(1)
            val obsolete = AtomicInteger()
            repeat(2) { first.tasks.submit({ started.countDown(); release.await() }) {} }
            started.awaitChecked()
            first.tasks.submit({ obsolete.incrementAndGet() }) { obsolete.incrementAndGet() }
            first.close()
            release.countDown()
            runtime.host("second").use { second ->
                val done = CountDownLatch(1)
                second.tasks.submit({ "new" }) { assertEquals("new", it.getOrThrow()); done.countDown() }
                done.awaitChecked(); assertEquals(0, obsolete.get())
            }
        }
    }
}
