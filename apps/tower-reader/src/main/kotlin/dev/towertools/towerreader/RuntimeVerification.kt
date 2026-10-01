package dev.towertools.towerreader

import dev.towertools.towerreader.flow.FlowLoader
import dev.towertools.towerreader.flow.FlowPane
import dev.towertools.towerreader.flow.FlowPageState
import dev.towertools.towerreader.library.LibraryRepository
import dev.towertools.towerreader.pdf.PdfSession
import javafx.application.Platform
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.awt.EventQueue
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import javax.swing.JFrame

/** Explicit diagnostic entry point for testing the shipped runtime without touching a real library. */
internal object RuntimeVerification {
    fun run(directory: Path): Int {
        require(!Files.exists(directory) || Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }) { "Verification directory must be empty." }
        Files.createDirectories(directory)
        System.setProperty("javafx.cachedir", directory.resolve("javafx-cache").toString())
        val result = directory.resolve("runtime-verification.txt")
        val asynchronousFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> asynchronousFailure.compareAndSet(null, error) }
        var frame: JFrame? = null
        var pane: FlowPane? = null
        return try {
            Class.forName("jdk.swing.interop.SwingInterOpUtils")
            val txt = directory.resolve("离线测试.txt")
            Files.write(txt, ("第一章 离线阅读\n" + "这是本地阅读、中文编码与分页定位检查。\n".repeat(250)).toByteArray(Charset.forName("GB18030")))
            val pdf = directory.resolve("离线测试.pdf")
            PDDocument().use { document -> repeat(3) { document.addPage(PDPage()) }; document.save(pdf.toFile()) }
            val epub = directory.resolve("离线测试.epub")
            ZipOutputStream(Files.newOutputStream(epub)).use { zip ->
                mapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>",
                    "book.opf" to "<package xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><metadata><dc:title>离线测试</dc:title></metadata><manifest><item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"chapter\"/></spine></package>",
                    "chapter.xhtml" to "<html><body><img src='cover.png'/><h1>离线阅读</h1><p>" + "文字与图片离线装载。".repeat(300) + "</p></body></html>"
                ).forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
                zip.putNextEntry(ZipEntry("cover.png"))
                ImageIO.write(java.awt.image.BufferedImage(200,300,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",zip)
                zip.closeEntry()
            }
            LibraryRepository(directory.resolve("library")).use { repo ->
                val imported = repo.importBooks(listOf(txt, pdf, epub, txt))
                check(imported.added == 3 && imported.duplicates == 1 && imported.failures.isEmpty()) { imported.toString() }
                repo.saveProgress(repo.listBooks().first().id, "check", 0.25)
            }
            val flow = FlowLoader.open(txt, directory.resolve("cache-txt"))
            check(flow.encoding == "GB18030")
            val epubFlow = FlowLoader.open(epub, directory.resolve("cache-epub"))
            check(epubFlow.totalLength > 0)
            PdfSession(pdf).use { session ->
                check(session.pageCount == 3)
                ImageIO.write(session.render(0, 640, 800), "png", directory.resolve("pdf-page.png").toFile())
            }
            // Loading a Compose ImageBitmap resolves the exact shipped Skiko native dependency.
            check(androidx.compose.ui.graphics.ImageBitmap(8, 8).width == 8)
            val positioned = CompletableFuture<FlowPageState>()
            EventQueue.invokeAndWait {
                pane = FlowPane(
                    onPosition = { if (it.textLength > 0 && it.pageCount in 2..50) positioned.complete(it) },
                    onBoundary = {}, onLink = {}, onAction = {},
                    onError = { positioned.completeExceptionally(IllegalStateException(it)) },
                    pageDirectory = directory.resolve("pages"),
                )
                frame = JFrame().apply {
                    isUndecorated = true; opacity = 0f; focusableWindowState = false
                    setSize(1000, 700); setLocation(-3000, -3000); add(pane); isVisible = true
                }
                pane!!.showChapter(Files.readString(epubFlow.chapters.first().htmlPath), 800, ReaderSettings())
            }
            val state = positioned.get(25, TimeUnit.SECONDS)
            check(state.offset == 800 && state.pageCount > 1) { "Unexpected position: " + state }
            val localImage = CompletableFuture<Boolean>()
            Platform.runLater {
                runCatching {
                    val web = pane!!.scene.root.childrenUnmodifiable.last() as javafx.scene.web.WebView
                    web.engine.executeScript("document.querySelector('img').naturalHeight===300") == true
                }.fold(localImage::complete, localImage::completeExceptionally)
            }
            check(localImage.get(5,TimeUnit.SECONDS)) { "The packaged reader failed to display a local EPUB image" }
            Thread.sleep(300)
            asynchronousFailure.get()?.let { throw it }
            Files.writeString(result, "PASS\nPortable JVM, native Skiko, SQLite, GB18030, TXT, EPUB, PDF and JavaFX WebView bridge loaded successfully.\n" +
                "Local EPUB image loaded: true\nRestored text offset: " + state.offset + "\nChapter pages: " + state.pageCount + "\n")
            0
        } catch (failure: Throwable) {
            Files.writeString(result, "FAIL\n" + failure.stackTraceToString())
            1
        } finally {
            EventQueue.invokeAndWait { pane?.disposeReader(); frame?.dispose() }
            Platform.exit()
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }
}
