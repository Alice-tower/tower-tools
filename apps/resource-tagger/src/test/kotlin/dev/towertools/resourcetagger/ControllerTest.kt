package dev.towertools.resourcetagger

import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class ControllerTest {
    @TempDir lateinit var temp: Path
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            var complete = false
            EventQueue.invokeAndWait { complete = condition() }
            if (complete) return
            Thread.sleep(10)
        }
        fail("Controller did not finish within 10 seconds")
    }
    @Test fun `database initialization failure remains visible and search does not spin indefinitely`() {
        lateinit var controller: LibraryController
        EventQueue.invokeAndWait { controller = LibraryController(Files.createDirectory(temp.resolve("not-a-database"))) }
        try {
            await { controller.busy == null }
            var originalError: String? = null
            EventQueue.invokeAndWait { originalError = controller.error; controller.search(Query(text = "test"), debounce = false) }
            await { !controller.loading }
            assertNotNull(originalError); assertEquals(originalError, controller.error)
        } finally { controller.close() }
    }
    @Test fun `late query completion cannot overwrite a newer search and navigation does not reload data`() {
        val path = temp.resolve("data.sqlite")
        val fs = object : ResourceFileSystem {
            override fun inspect(path: Path) = Kind.File
            override fun scan(path: Path) = listOf(Found("bucket-000001/" + "alpha", Kind.File), Found("bucket-000001/" + "beta", Kind.File))
        }
        Library(Database(path), fs).use { val root = it.saveRoot(null, Files.createDirectory(temp.resolve("root")).toString(), "Root"); it.scan(root) }
        lateinit var controller: LibraryController
        EventQueue.invokeAndWait { controller = LibraryController(path) }
        try {
            await { controller.busy == null && !controller.loading }
            EventQueue.invokeAndWait {
                controller.search(Query(text = "alpha"), debounce = false)
                // Let the worker queue the old completion while the UI is still processing newer input.
                Thread.sleep(100)
                controller.search(Query(text = "beta"), debounce = false)
            }
            await { !controller.loading }
            assertEquals("beta", controller.data.resources.single().name)
            val before = controller.data
            EventQueue.invokeAndWait { controller.submit("只读操作", refresh = false) {} }
            await { controller.busy == null }
            assertSame(before, controller.data)
        } finally { controller.close() }
    }

    @Test fun `startup search and tagging use local database while the NAS root is unavailable`() {
        val path = temp.resolve("offline.sqlite")
        val root = Files.createDirectory(temp.resolve("nas"))
        val bucket = Files.createDirectory(root.resolve("bucket-000001"))
        Files.writeString(bucket.resolve("file.txt"), "source")
        var tag = ""
        Library(Database(path)).use { lib -> lib.scan(lib.saveRoot(null, root.toString(), "NAS")); tag = lib.createTag("offline") }
        Files.move(root, temp.resolve("unavailable"))
        lateinit var controller: LibraryController
        EventQueue.invokeAndWait { controller = LibraryController(path) }
        try {
            await { controller.busy == null && !controller.loading }
            assertEquals(1, controller.total); assertEquals(Status.Active, controller.data.resources.single().status)
            EventQueue.invokeAndWait { controller.search(Query(text = "bucket-000001/file"), debounce = false) }
            await { !controller.loading }
            val resource = controller.data.resources.single()
            EventQueue.invokeAndWait { controller.submit("离线标签") { it.setTag(setOf(resource.id), tag, true) } }
            await { controller.busy == null && !controller.loading }
            assertEquals(setOf(tag), controller.data.links[resource.id]); assertNull(controller.error)
            assertEquals("source", Files.readString(temp.resolve("unavailable/bucket-000001/file.txt")))
        } finally { controller.close() }
    }

    @Test fun `slow NAS scan allows search paging and details while cancellation preserves the database`() {
        exerciseSlowScan(cancel = true)
    }
    @Test fun `slow NAS scan refreshes the latest query after a complete commit`() {
        exerciseSlowScan(cancel = false)
    }
    @Test fun `cancelled NAS read returning an IO error still preserves scan metadata`() {
        exerciseSlowScan(cancel = true, failRead = true)
    }
    private fun exerciseSlowScan(cancel: Boolean, failRead: Boolean = false) {
        val path = temp.resolve("slow.sqlite")
        val rootPath = Files.createDirectory(temp.resolve("slow-root"))
        val entries = (1..450).map { Found("bucket-000001/item-$it", Kind.File) }
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val fs = object : ResourceFileSystem {
            override fun inspect(path: Path) = Kind.File
            override fun scan(path: Path) = entries
            override fun scan(path: Path, control: ScanControl): List<Found> {
                control.report("bucket-000001", 12)
                started.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                if (failRead) throw java.io.IOException("NAS disconnected")
                control.check()
                return listOf(Found("bucket-000001/replacement", Kind.File))
            }
        }
        lateinit var root: String
        lateinit var before: Snapshot
        Library(Database(path), fs).use { lib ->
            root = lib.saveRoot(null, rootPath.toString(), "NAS")
            // Seed through the legacy fixture enumeration, without blocking.
            val seed = object : ResourceFileSystem {
                override fun inspect(path: Path) = Kind.File
                override fun scan(path: Path) = entries
            }
            Library(Database(path), seed).use { it.scan(root) }
            before = lib.snapshot()
        }
        lateinit var controller: LibraryController
        EventQueue.invokeAndWait { controller = LibraryController(path, fs) }
        try {
            await { controller.busy == null && !controller.loading }
            EventQueue.invokeAndWait { controller.scan(root) }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val start = System.nanoTime()
            EventQueue.invokeAndWait { controller.movePage(1) }
            await { controller.offset == 200 && !controller.loading }
            val item = controller.data.resources.first()
            EventQueue.invokeAndWait { controller.focus(item.id); controller.search(Query(text = "item-450"), debounce = false) }
            await { !controller.loading && controller.total == 1 && controller.focusData.resources.singleOrNull()?.id == item.id }
            val millis = (System.nanoTime() - start) / 1_000_000
            println("NAS blocked scan: paging + search + detail completed in $millis ms")
            assertTrue(millis < 2000, "Local reads queued behind NAS: $millis ms")
            assertEquals(12, controller.scanControl?.progress?.count)
            var wrote = false
            EventQueue.invokeAndWait {
                controller.submit("must stay disabled") { wrote = true }
                if (cancel) controller.scanControl!!.cancel()
            }
            assertFalse(wrote)
            assertNotNull(controller.busy)
            release.countDown()
            await { controller.busy == null && !controller.loading }
            assertNull(controller.error)
            Library(Database(path)).use { lib ->
                if (cancel) assertEquals(before, lib.snapshot())
                else {
                    assertEquals(451, lib.snapshot().resources.size)
                    assertEquals(Status.Missing, controller.data.resources.single().status)
                    assertTrue(controller.data.roots.single().lastSuccess != before.roots.single().lastSuccess)
                }
            }
        } finally { release.countDown(); controller.close() }
    }
}
