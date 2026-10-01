package dev.towertools.towerreader.flow

import dev.towertools.towerreader.ReaderSettings
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.scene.web.WebView
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.EventQueue
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowBufferTest {
    @TempDir lateinit var directory: Path
    @Test fun preparedChaptersSwitchWithoutReplacingSurfaceOrFlashingWhite() {
        val classpath = (generateSequence(javaClass.classLoader) { it.parent }.filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }.filter { it.protocol == "file" }.map { Path.of(it.toURI()).toString() }.toList() +
            System.getProperty("java.class.path").split(java.io.File.pathSeparator)).distinct().joinToString(java.io.File.pathSeparator)
        val output = directory.resolve("probe.log")
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(),
            "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-cp", classpath,
            FlowBufferProbe::class.java.name, directory.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(35, TimeUnit.SECONDS), "Buffer probe timed out")
            assertEquals(0, process.exitValue(), Files.readString(output))
            assertTrue(Files.readString(output).contains("BUFFER_PASS"))
        } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
    }
}

object FlowBufferProbe {
    private fun <T> fx(action: () -> T): T {
        val result = CompletableFuture<T>()
        Platform.runLater { try { result.complete(action()) } catch (error: Throwable) { result.completeExceptionally(error) } }
        return result.get(8, TimeUnit.SECONDS)
    }
    @JvmStatic fun main(args: Array<String>) {
        try { run(Path.of(args[0])); println("BUFFER_PASS"); kotlin.system.exitProcess(0) }
        catch (failure: Throwable) { failure.printStackTrace(); kotlin.system.exitProcess(1) }
    }
    private fun run(directory: Path) {
        System.setProperty("javafx.cachedir", directory.resolve("javafx").toString())
        val positions = LinkedBlockingQueue<FlowPageState>()
        val errors = LinkedBlockingQueue<String>()
        val style = ReaderSettings(theme = "dark")
        val picture = directory.resolve("local-picture.png")
        val pixels = java.awt.image.BufferedImage(200,300,java.awt.image.BufferedImage.TYPE_INT_RGB)
        javax.imageio.ImageIO.write(pixels,"png",picture.toFile())
        val pages = directory.resolve("pages")
        fun body(id: Int) = "<img src='${picture.toUri().toASCIIString()}'/><h1 id='chapter-marker'>Chapter $id</h1>" + "<p>这是预排版、返回和缓冲切换测试。</p>".repeat(300)
        lateinit var panel: FlowPane
        lateinit var frame: JFrame
        EventQueue.invokeAndWait {
            panel = FlowPane({ positions.offer(it) }, {}, {}, {}, { errors.offer(it) }, pageDirectory = pages)
            frame = JFrame().apply {
                isUndecorated = true; opacity = 0f; focusableWindowState = false
                setSize(1000, 700); setLocation(-3000, -3000); add(panel); isVisible = true
            }
            panel.showChapter(body(0), 0, style, 1)
        }
        fun awaitState(id: Long): FlowPageState {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (System.nanoTime() < deadline) {
                check(errors.isEmpty()) { errors.toString() }
                val state = positions.poll(50, TimeUnit.MILLISECONDS)
                if (state?.requestId == id) return state
            }
            error("No page state for request $id; errors=$errors")
        }
        check(awaitState(1).pageCount > 1)
        val scene = panel.scene
        val views = fx { scene.root.childrenUnmodifiable.filterIsInstance<WebView>() }
        check(views.size == 2)
        var samples = 0
        fun sample() {
            val image = fx { scene.root.snapshot(null, null) }
            val pixel = image.pixelReader.getColor(10, 10)
            check(pixel.opacity > 0.99 && pixel.brightness < 0.4) { "Blank/white frame while switching: $pixel" }
            check(panel.scene === scene)
            check(fx { scene.root.childrenUnmodifiable.filterIsInstance<WebView>().toSet() } == views.toSet())
            samples++
        }
        sample()
        repeat(16) { index ->
            val next = body(index + 1)
            EventQueue.invokeAndWait { panel.preloadChapter(next) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var prepared: WebView? = null
            while (prepared == null && System.nanoTime() < deadline) {
                prepared = fx { views.firstOrNull { it.engine.loadWorker.state == Worker.State.SUCCEEDED &&
                    runCatching { it.engine.executeScript("document.getElementById('chapter-marker').textContent") == "Chapter ${index + 1}" }.getOrDefault(false) } }
                if (prepared == null) Thread.sleep(10)
            }
            check(prepared != null) { "Neighbour was not prepared" }
            check(fx { prepared!!.engine.executeScript("document.querySelector('img').naturalHeight===300") } == true) { "A local image did not load in the prepared chapter" }
            val documentBefore = fx { prepared!!.engine.document }
            positions.clear()
            EventQueue.invokeAndWait { panel.showChapter(next, 137, style, index + 2L) }
            repeat(3) { sample() }
            val state = awaitState(index + 2L)
            check(state.offset == 137)
            check(fx { prepared!!.engine.document } === documentBefore) { "Prepared document was loaded again" }
            sample()
        }
        fun htmlCount() = Files.walk(pages).use { files -> files.filter { it.fileName.toString().endsWith(".html") }.count() }
        val settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (htmlCount() > 2 && System.nanoTime() < settled) Thread.sleep(10)
        check(htmlCount() == 2L) { "Only the two reusable page shells may remain on disk" }
        EventQueue.invokeAndWait { panel.disposeReader(); frame.dispose() }
        val cleaned = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (htmlCount() > 0 && System.nanoTime() < cleaned) Thread.sleep(10)
        check(htmlCount() == 0L) { "Closing the reader did not clean up its temporary page shells" }
        Platform.exit()
        println("16 chapter switches with local images, $samples sampled dark frames, two stable WebViews, bounded and cleaned page files")
    }
}
