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
            override fun scan(path: Path) = listOf(Found("alpha", Kind.File), Found("beta", Kind.File))
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
}
