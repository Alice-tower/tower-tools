package dev.towertools.towerreader.flow

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.towertools.towerreader.ReaderController
import dev.towertools.towerreader.ReaderSettings
import dev.towertools.towerreader.library.LibraryRepository
import dev.towertools.towerreader.ui.ReaderScreen
import javafx.application.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.awt.EventQueue
import java.awt.Component
import java.awt.Container
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFrame
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Each probe has a fresh JVM: warm JavaFX classes hide first-open initialization races. */
class FlowColdStartTest {
    @TempDir lateinit var directory: Path

    private fun probe(mode: String, book: String? = null) {
        val classpath = (generateSequence(javaClass.classLoader) { it.parent }
            .filterIsInstance<URLClassLoader>().flatMap { it.urLs.asSequence() }
            .filter { it.protocol == "file" }.map { Path.of(it.toURI()).toString() }.toList() +
            System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct()
            .joinToString(java.io.File.pathSeparator)
        val root = Files.createTempDirectory(directory, mode)
        val log = root.resolve("process.log")
        val command = mutableListOf(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
            "-cp", classpath, FlowColdStartProbe::class.java.name, mode, root.toString())
        if (book != null) command += book
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            assertTrue(process.waitFor(35, TimeUnit.SECONDS), "Fresh JVM froze in $mode: ${Files.readString(log)}")
            assertEquals(0, process.exitValue(), Files.readString(log))
            assertTrue(Files.readString(log).contains("COLD_START_PASS"), Files.readString(log))
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    @Test fun webViewWaitsForSwingAttachmentAndThenPaginates() = probe("attachment")

    @Test fun firstEpubOpenThroughComposeWorksInFreshProcesses() {
        repeat(3) { probe("compose") }
    }

    @Test fun reportedEpubOpensThroughComposeWhenProvidedLocally() {
        val path = System.getenv("TOWER_READER_REGRESSION_EPUB")
        assumeTrue(!path.isNullOrBlank(), "Optional private EPUB reproduction file was not supplied")
        probe("compose", path)
    }
}

object FlowColdStartProbe {
    @JvmStatic fun main(args: Array<String>) {
        dev.towertools.towerreader.ui.configureReaderRendering()
        try {
            val root = Path.of(args[1])
            System.setProperty("javafx.cachedir", root.resolve("javafx").toString())
            when (args[0]) {
                "attachment" -> attachment()
                "compose" -> compose(root, args.getOrNull(2)?.let(Path::of))
                else -> error("Unknown probe")
            }
            println("COLD_START_PASS")
            kotlin.system.exitProcess(0)
        } catch (failure: Throwable) {
            failure.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }

    private fun attachment() {
        val positioned = CompletableFuture<FlowPageState>()
        lateinit var panel: FlowPane
        lateinit var frame: JFrame
        EventQueue.invokeAndWait {
            panel = FlowPane({ if (it.textLength > 0) positioned.complete(it) }, {}, {}, {},
                { positioned.completeExceptionally(IllegalStateException(it)) })
            panel.showChapter("<p>" + "中文分页和首次打开。".repeat(1000) + "</p>", 800, ReaderSettings())
        }
        val pendingFxWork = CompletableFuture<Unit>()
        Platform.runLater { pendingFxWork.complete(Unit) }
        pendingFxWork.get(10, TimeUnit.SECONDS)
        // Deterministic regression: old FlowPane starts Node/WebView here, before addNotify.
        check(panel.scene == null) { "WebView initialized before Swing attachment completed" }
        EventQueue.invokeAndWait {
            frame = JFrame().apply {
                isUndecorated = true; opacity = 0f; focusableWindowState = false
                setSize(1000, 700); setLocation(-3000, -3000); add(panel); isVisible = true
            }
        }
        val state = positioned.get(15, TimeUnit.SECONDS)
        check(state.offset == 800 && state.pageCount > 1) { state.toString() }
        EventQueue.invokeAndWait { panel.disposeReader(); frame.dispose() }
        Platform.exit()
    }

    private fun fixture(root: Path): Path {
        val file = root.resolve("cold-start.epub")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            mapOf(
                "mimetype" to "application/epub+zip",
                "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>",
                "book.opf" to "<package xmlns:dc='http://purl.org/dc/elements/1.1/'><metadata><dc:title>首次打开</dc:title></metadata><manifest>" +
                    (0..2).joinToString("") { "<item id='c$it' href='chapter-$it.xhtml' media-type='application/xhtml+xml'/>" } +
                    "</manifest><spine>" + (0..2).joinToString("") { "<itemref idref='c$it'/>" } + "</spine></package>",
                "chapter-0.xhtml" to "<html><body>" + "<p>第一节，首次打开 EPUB，界面应保持响应。</p>".repeat(600) + "</body></html>",
                "chapter-1.xhtml" to "<html><body>" + "<p>第二节，切换章节后，左右键继续翻页。</p>".repeat(600) + "</body></html>",
                "chapter-2.xhtml" to "<html><body>" + "<p>第三节，阅读面板保持同一个实例。</p>".repeat(600) + "</body></html>"
            ).forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
        }
        return file
    }

    private fun <T> onAwt(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        val task = FutureTask<T> { action() }
        EventQueue.invokeAndWait(task)
        return task.get(10, TimeUnit.SECONDS)
    }
    private fun findPane(component: Component): FlowPane? {
        if (component is FlowPane) return component
        if (component is Container) for (child in component.components) findPane(child)?.let { return it }
        return null
    }
    private fun dispatchKey(source: Component, key: Int) = onAwt {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val pressed = KeyEvent(source, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, key, KeyEvent.CHAR_UNDEFINED)
        val released = KeyEvent(source, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, key, KeyEvent.CHAR_UNDEFINED)
        manager.dispatchEvent(pressed)
        manager.dispatchEvent(released)
        check(pressed.isConsumed && released.isConsumed) { "Window reader shortcuts did not consume $key from ${source.javaClass.name}" }
    }
    private fun compose(root: Path, input: Path?) {
        val data = root.resolve("library")
        val book = LibraryRepository(data).use { repo ->
            val report = repo.importBooks(listOf(input ?: fixture(root)))
            check(report.added == 1 && report.failures.isEmpty()) { report.toString() }
            repo.listBooks().single()
        }
        lateinit var host: java.awt.Window
        var failure: Throwable? = null
        application(exitProcessOnExit = false) {
            val controller = remember { ReaderController(data) }
            val fullscreen = remember { mutableStateOf(false) }
            val readingVisible = remember { mutableStateOf(true) }
            Window(onCloseRequest = {}, title = "Reader cold start probe", undecorated = true) {
                DisposableEffect(window) {
                    host = window
                    window.opacity = 0f; window.focusableWindowState = false; window.setLocation(-3000, -3000)
                    onDispose { }
                }
                MaterialTheme { if (readingVisible.value) ReaderScreen(controller, fullscreen.value, { fullscreen.value = !fullscreen.value }, window) }
            }
            LaunchedEffect(Unit) {
                try {
                    withTimeout(25000) {
                        while (!controller.ready) { check(controller.error == null) { controller.error.orEmpty() }; delay(20) }
                        repeat(3) {
                            controller.open(book)
                            while (controller.pageState == null) { check(controller.error == null) { controller.error.orEmpty() }; delay(20) }
                            check(controller.pageState!!.textLength > 0)
                            if (input != null && it == 0 && controller.flow!!.chapters.first().textLength == 1) {
                                controller.goChapter(0,0)
                                while (controller.loading || controller.pageState == null) { check(controller.error == null) { controller.error.orEmpty() }; delay(20) }
                                val coverPane = onAwt { findPane(host) ?: error("Missing cover reader") }
                                val coverVisible = CompletableFuture<Boolean>()
                                Platform.runLater {
                                    runCatching {
                                        val web = coverPane.scene.root.childrenUnmodifiable.last() as javafx.scene.web.WebView
                                        web.engine.executeScript("(function(){var i=document.querySelector('img'),v=document.getElementById('viewport').getBoundingClientRect();if(!i||!i.naturalHeight)return false;var r=i.getBoundingClientRect();return r.height>200&&r.left>=v.left-1&&r.right<=v.right+1&&r.top>=v.top-1&&r.bottom<=v.bottom+1;})()") == true
                                    }.fold(coverVisible::complete,coverVisible::completeExceptionally)
                                }
                                while (!coverVisible.isDone) delay(10)
                                check(coverVisible.get()) { "Private EPUB cover is not visible within its first page" }
                                check(controller.pageState!!.page == 0 && controller.pageState!!.pageCount == 1)
                            }
                            if (input == null) {
                                check(controller.flow!!.chapters.size >= 3)
                                val originalPane = onAwt { findPane(host) ?: error("Compose window has no attached FlowPane") }
                                dispatchKey(host, KeyEvent.VK_F11)
                                while (!fullscreen.value) delay(10)
                                delay(160)
                                check(onAwt { findPane(host) === originalPane }) { "Entering fullscreen replaced the FlowPane" }
                                dispatchKey(originalPane, KeyEvent.VK_ESCAPE)
                                while (fullscreen.value) delay(10)
                                delay(160)
                                check(controller.active != null) { "Escape returned to the library instead of leaving fullscreen" }
                                check(onAwt { findPane(host) === originalPane }) { "Leaving fullscreen replaced the FlowPane" }
                                for (chapter in 0..2) {
                                    controller.goChapter(chapter, 0)
                                    while (controller.loading || controller.pageState == null) { check(controller.error == null) { controller.error.orEmpty() }; delay(20) }
                                    check(controller.chapter == chapter)
                                    check(onAwt { findPane(host) === originalPane }) { "Chapter change replaced the FlowPane instance" }
                                    check(controller.pageState!!.pageCount > 3) { "Synthetic chapter must contain several pages" }
                                    val composePanel = onAwt { originalPane.parent ?: host }
                                    for (source in listOf<Component>(originalPane, host, composePanel)) {
                                        val before = controller.pageState!!
                                        dispatchKey(source, KeyEvent.VK_RIGHT)
                                        while (controller.pageState?.page != before.page + before.columns) { check(controller.error == null) { controller.error.orEmpty() }; delay(10) }
                                        // Wait past asynchronous reports: a second handler must not turn again.
                                        delay(90)
                                        check(controller.pageState!!.page == before.page + before.columns) { "Right key turned more than one page" }
                                        dispatchKey(source, KeyEvent.VK_LEFT)
                                        while (controller.pageState?.page != before.page) { check(controller.error == null) { controller.error.orEmpty() }; delay(10) }
                                        delay(90)
                                        check(controller.pageState!!.page == before.page) { "Left key did not return exactly one page" }
                                    }
                                }
                            }
                            controller.seek(0.5)
                            while (controller.loading || controller.pageState == null) { check(controller.error == null) { controller.error.orEmpty() }; delay(20) }
                            val offset = controller.pageState!!.offset
                            controller.updateSettings(controller.settings.copy(fontSize = 26 + it * 2))
                            delay(200)
                            check(controller.pageState!!.offset == offset)
                            if (it == 0) {
                                readingVisible.value = false; delay(100)
                                check(onAwt { findPane(host) == null })
                                readingVisible.value = true
                                var restoredOffset = -1
                                while (restoredOffset != offset) {
                                    delay(30)
                                    val attached = onAwt { findPane(host) } ?: continue
                                    val position = CompletableFuture<Int>()
                                    Platform.runLater {
                                        position.complete(runCatching {
                                            val view = attached.scene.root.childrenUnmodifiable.last() as javafx.scene.web.WebView
                                            (view.engine.executeScript("Number(Reader.state().split(',')[0])") as Number).toInt()
                                        }.getOrDefault(-1))
                                    }
                                    while (!position.isDone) delay(10)
                                    restoredOffset = position.get()
                                }
                            }
                            controller.returnToLibrary()
                            delay(80)
                        }
                    }
                } catch (error: Throwable) { failure = error }
                controller.close { Platform.exit(); exitApplication() }
            }
        }
        failure?.let { throw it }
    }
}
