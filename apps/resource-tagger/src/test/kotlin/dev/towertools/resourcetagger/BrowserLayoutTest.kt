package dev.towertools.resourcetagger

import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class BrowserLayoutTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val folder = TemporaryFolder()

    private fun items(): List<BrowserItem> {
        val path = folder.newFolder().toPath(); Files.createDirectory(path.resolve("bucket-000001"))
        val root = Root("root", path.toString(), "Root", "可访问", null, null, null)
        return listOf("a", "b").map { name ->
            Files.writeString(path.resolve("bucket-000001").resolve(name), name)
            BrowserItem(PreviewTarget(root, Resource(name, root.id, "bucket-000001/$name", Kind.File, name, Status.Active, "now", null)), emptyList(), emptyList())
        }
    }

    @Test fun `only visible gallery composes large preview and switches dispose its content and session`() {
        val items = items()
        val host = PreviewHost("test", folder.root.toPath())
        var layout by mutableStateOf(BrowserLayout.List)
        var focused by mutableStateOf("a")
        var visible by mutableStateOf(true)
        var pluginEnabled by mutableStateOf(true)
        val active = AtomicInteger()
        val disposed = AtomicInteger()
        val hosts = CopyOnWriteArrayList<PreviewHost>()
        val plugin = object : PreviewPlugin {
            override val id = "test"
            override val title = "Test"
            override val largePreview = LargePreviewProvider { request, scoped ->
                DisposableEffect(request.target) {
                    hosts.add(scoped); active.incrementAndGet()
                    onDispose { active.decrementAndGet(); disposed.incrementAndGet() }
                }
                PreviewPresentation.Ready { modifier -> Text("large-${request.target.resource.id}", modifier) }
            }
        }
        try {
            rule.setContent { MaterialTheme {
                if (visible) ResourceBrowser(if (pluginEnabled) plugin else StandardPreviewPlugin,
                    BrowserContext(items, emptySet(), focused, true, { focused = it }, { _, _ -> }, { emptyList() }),
                    host, Modifier.size(650.dp, 500.dp), layout)
            } }
            rule.waitForIdle(); assertEquals(0, active.get())
            rule.runOnIdle { layout = BrowserLayout.Grid }; rule.waitForIdle(); assertEquals(0, active.get())
            rule.runOnIdle { layout = BrowserLayout.Gallery }; rule.waitForIdle()
            rule.onNodeWithText("large-a").assertExists(); assertEquals(1, active.get())
            val oldHost = hosts.last()
            rule.runOnIdle { focused = "b" }; rule.waitForIdle()
            rule.onNodeWithText("large-b").assertExists(); assertEquals(1, active.get()); assertEquals(1, disposed.get())
            val late = AtomicInteger()
            oldHost.onUi { late.incrementAndGet() }; rule.waitForIdle(); assertEquals(0, late.get())
            rule.runOnIdle { pluginEnabled = false }; rule.waitForIdle(); assertEquals(0, active.get())
            rule.onNodeWithText("暂无预览").assertExists()
            rule.runOnIdle { pluginEnabled = true }; rule.waitForIdle(); assertEquals(1, active.get())
            rule.runOnIdle { layout = BrowserLayout.List }; rule.waitForIdle(); assertEquals(0, active.get())
            rule.runOnIdle { layout = BrowserLayout.Gallery }; rule.waitForIdle(); assertEquals(1, active.get())
            rule.runOnIdle { visible = false }; rule.waitForIdle(); assertEquals(0, active.get())
        } finally { host.close() }
    }

    @Test fun `thumbnail fallback requests large dimensions and failed or missing previews retain resources`() {
        val items = items()
        val host = PreviewHost("thumb", folder.root.toPath())
        var layout by mutableStateOf(BrowserLayout.Grid)
        val requests = CopyOnWriteArrayList<PreviewRequest>()
        val plugin = object : PreviewPlugin {
            override val id = "thumb"
            override val title = "Thumb"
            override val thumbnail = ThumbnailProvider { request, _ ->
                requests.add(request)
                if (request.target.resource.id == "b") error("decode failure")
                ImageBitmap(8, 8)
            }
        }
        try {
            rule.setContent { MaterialTheme {
                ResourceBrowser(plugin, BrowserContext(items, emptySet(), "a", true, {}, { _, _ -> }, { emptyList() }), host, Modifier.size(650.dp, 500.dp), layout)
            } }
            rule.waitUntil(10000) { rule.onAllNodesWithText("预览失败").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithContentDescription("选择资源 b").assertExists()
            rule.runOnIdle { layout = BrowserLayout.Gallery }; rule.waitForIdle()
            rule.waitUntil(10000) { requests.any { it.target.resource.id == "a" && it.size.width > 400 && it.size.height > 200 } }
            rule.onNodeWithContentDescription("选择资源 a").assertExists()
            assertTrue(requests.all { it.size.width > 0 && it.size.height > 0 })
        } finally { host.close() }
    }
}
