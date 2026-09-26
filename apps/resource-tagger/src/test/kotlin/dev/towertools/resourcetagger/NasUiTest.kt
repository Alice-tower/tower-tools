package dev.towertools.resourcetagger

import androidx.compose.material.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class NasUiTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val folder = TemporaryFolder()
    @Test fun `scan banner cancels while paging remains enabled`() {
        val database = folder.root.toPath().resolve("nas.sqlite")
        val rootPath = folder.newFolder("nas").toPath()
        val release = CountDownLatch(1)
        var block = false
        val fs = object : ResourceFileSystem {
            override fun inspect(path: Path) = Kind.File
            override fun scan(path: Path): List<Found> {
                if (block) check(release.await(15, TimeUnit.SECONDS))
                return (1..250).map { Found("bucket-000001/item-$it", Kind.File) }
            }
        }
        Library(Database(database), fs).use { it.scan(it.saveRoot(null, rootPath.toString(), "NAS")) }
        block = true
        val controller = LibraryController(database, fs)
        try {
            rule.setContent { MaterialTheme { LibraryApp(null, controller) } }
            rule.waitUntil(10000) { controller.busy == null && !controller.loading }
            rule.onNodeWithText("Root 管理").performClick()
            rule.onNodeWithText("扫描", substring = false).performClick()
            rule.onNodeWithText("取消扫描").assertIsEnabled()
            rule.onNodeWithText("资源", substring = false).performClick()
            rule.onNodeWithText("下一页").assertIsEnabled().performClick()
            rule.waitUntil(5000) { controller.offset == 200 && !controller.querying }
            rule.onNodeWithText("取消扫描").performClick().assertIsNotEnabled()
            assertNotNull(controller.busy)
            release.countDown()
            rule.waitUntil(10000) { controller.busy == null }
            rule.onNodeWithText("扫描已取消，原有数据已保留。").assertExists()
            assertEquals(250, controller.total)
            assertNull(controller.error)
        } finally { release.countDown(); controller.close() }
    }
}
