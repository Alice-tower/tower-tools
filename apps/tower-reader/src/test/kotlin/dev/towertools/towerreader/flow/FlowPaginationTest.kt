package dev.towertools.towerreader.flow

import dev.towertools.towerreader.ReaderSettings
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.embed.swing.SwingFXUtils
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.stage.StageStyle
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

/** Exercises the shipped HTML and JavaFX WebKit, including real CSS column geometry. */
class FlowPaginationTest {
    companion object {
        @BeforeAll @JvmStatic fun startToolkit() {
            EventQueue.invokeAndWait { JFXPanel() }
            fx { Platform.setImplicitExit(false) }
        }
        @AfterAll @JvmStatic fun stopToolkit() { Platform.exit() }
        private fun <T> fx(action: () -> T): T {
            if (Platform.isFxApplicationThread()) return action()
            val result = CompletableFuture<T>()
            Platform.runLater { try { result.complete(action()) } catch (failure: Throwable) { result.completeExceptionally(failure) } }
            return result.get(15, TimeUnit.SECONDS)
        }
    }
    private data class State(val offset: Int, val page: Int, val count: Int, val columns: Int, val length: Int)
    private inner class Browser(width: Int, height: Int) : AutoCloseable {
        val view = fx { WebView() }
        private var root = fx { StackPane(view) }
        private var scene = fx { Scene(root, width.toDouble(), height.toDouble()) }
        private var stage: Stage? = null
        private val pageFiles = FlowPageFiles(Path.of("build", "verification", "webview-pages"))
        init { fx { root.resize(width.toDouble(),height.toDouble()); root.applyCss(); root.layout(); view.resize(width.toDouble(),height.toDouble()) } }
        fun script(expression: String): Any = fx { view.engine.executeScript(expression) }
        fun state(): State = script("Reader.state()").toString().split(',').map(String::toInt).let { State(it[0],it[1],it[2],it[3],it[4]) }
        fun load(body: String, settings: ReaderSettings, offset: Int = 0, allowSinglePage: Boolean = false) {
            val pageFile = pageFiles.write(body, settings)
            val done = CompletableFuture<Unit>()
            fx {
                view.engine.loadWorker.stateProperty().addListener(object : javafx.beans.value.ChangeListener<Worker.State> {
                    override fun changed(property: javafx.beans.value.ObservableValue<out Worker.State>, old: Worker.State, value: Worker.State) {
                        if (value == Worker.State.SUCCEEDED || value == Worker.State.FAILED || value == Worker.State.CANCELLED) {
                            view.engine.loadWorker.stateProperty().removeListener(this)
                            if (value == Worker.State.SUCCEEDED) done.complete(Unit) else done.completeExceptionally(IllegalStateException("WebView load $value",view.engine.loadWorker.exception))
                        }
                    }
                })
                view.engine.load(pageFile.toUri().toASCIIString())
            }
            done.get(20,TimeUnit.SECONDS)
            // Some JavaFX distributions need a native peer for WebKit layout. It is fully transparent.
            if ((script("window.innerWidth") as Number).toInt() < 200) fx {
                stage = Stage(StageStyle.UTILITY).apply { opacity = 0.0; this.scene = this@Browser.scene; show() }
                root.applyCss(); root.layout()
            }
            script("Reader.init(${settings.scriptObject()},$offset)")
            await { state().length == (script("document.getElementById('content').textContent.length + document.querySelectorAll('#content img').length") as Number).toInt() && (allowSinglePage || state().count > 1) }
            Thread.sleep(200)
        }
        fun resize(width: Int,height: Int) {
            fx {
                root.children.clear(); root = StackPane(view); scene = Scene(root,width.toDouble(),height.toDouble())
                stage?.let { it.scene = scene; it.width = width.toDouble(); it.height = height.toDouble() }
                root.resize(width.toDouble(),height.toDouble()); root.applyCss(); root.layout(); view.resize(width.toDouble(),height.toDouble())
            }
            await { kotlin.math.abs((script("window.innerWidth") as Number).toInt()-width) < 50 }
            Thread.sleep(250)
        }
        fun anchorVisible(offset: Int): Boolean = script("""
            (function(){var walker=document.createTreeWalker(document.getElementById('content'),NodeFilter.SHOW_TEXT,null,false),n,at=$offset;
            while((n=walker.nextNode())){if(at<n.length){var r=document.createRange();r.setStart(n,at);r.setEnd(n,at+1);
            var box=r.getBoundingClientRect(),v=document.getElementById('viewport').getBoundingClientRect();
            return box.left>=v.left-2 && box.right<=v.right+2 && box.top>=v.top-2 && box.bottom<=v.bottom+2;}at-=n.length;}return false;})()
        """.trimIndent()) as Boolean
        fun snapshot(name: String) {
            val image = fx { view.snapshot(null,null) }
            val directory = Path.of("build","test-previews"); Files.createDirectories(directory)
            ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",directory.resolve(name).toFile())
        }
        override fun close() { fx { runCatching { view.engine.executeScript("Reader.stop()") }; view.engine.loadWorker.cancel(); view.engine.load(null); stage?.close(); root.children.clear() }; pageFiles.close() }
    }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(10)
        while(System.nanoTime()<end) { if(condition()) return; Thread.sleep(25) }
        error("WebView layout did not become ready within 10 seconds")
    }
    private fun textBody(label: String = "章节甲") = "<h1 id='heading'>$label</h1>" + (0 until 180).joinToString("") { "<p>第${it}段："+"这是用于验证真实分页的中文内容，阅读位置应该在调整字体和窗口之后仍然可见。".repeat(5)+"</p>" }
    @Test fun singleAndDoublePageTurnsRemainContinuousAndLastPageCanGoBack() {
        Browser(1100,700).use { browser ->
            browser.load(textBody(),ReaderSettings(spread="single")); val first = browser.state(); assertEquals(1,first.columns); assertTrue(first.count>3)
            var previous = first
            repeat(3) { browser.script("Reader.turn(1)"); val next=browser.state(); assertEquals(previous.page+1,next.page); assertTrue(next.offset>previous.offset); previous=next }
            browser.script("Reader.turn(-1)"); assertEquals(previous.page-1,browser.state().page)
            browser.script("Reader.settings(${ReaderSettings(spread="double").scriptObject()},0)"); val double = browser.state(); assertEquals(2,double.columns)
            browser.script("Reader.turn(1)"); assertEquals(2,browser.state().page); assertTrue(browser.state().offset>0)
            browser.script("Reader.jump(${double.length})"); val last=browser.state(); assertTrue(last.page+last.columns>=last.count)
            browser.script("Reader.turn(-1)"); assertEquals(last.page-2,browser.state().page)
            browser.snapshot("double-page.png")
        }
    }
    @Test fun fontAndWindowReflowKeepTheOriginalTextVisible() {
        Browser(1000,700).use { browser ->
            val settings = ReaderSettings(spread="single"); browser.load(textBody(),settings,5000)
            assertEquals(5000,browser.state().offset); assertTrue(browser.anchorVisible(5000))
            browser.script("Reader.settings(${settings.copy(fontSize=38,lineHeight=2.0).scriptObject()})")
            assertEquals(5000,browser.state().offset); assertTrue(browser.anchorVisible(5000))
            browser.resize(720,560); assertEquals(5000,browser.state().offset); assertTrue(browser.anchorVisible(5000))
            browser.snapshot("reflow.png")
        }
    }
    @Test fun keyboardFallbackIgnoresModifierShortcutsAndCanYieldToWindowDispatcher() {
        Browser(1000,700).use { browser ->
            browser.load(textBody(),ReaderSettings(spread="single"))
            fun key(modifiers:String="") = browser.script("document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true$modifiers}))")
            val start=browser.state()
            key(",ctrlKey:true"); key(",altKey:true"); key(",metaKey:true")
            assertEquals(start.page,browser.state().page)
            key(); assertEquals(start.page+1,browser.state().page)
            browser.script("Reader.useHostKeyboard(true)")
            key(); assertEquals(start.page+1,browser.state().page)
        }
    }
    @Test fun replacingChapterResetsTextIndexAndRestoresItsOwnOffset() {
        Browser(900,650).use { browser ->
            val style = ReaderSettings(spread="single"); browser.load(textBody("章节甲"),style,3200)
            assertTrue(browser.anchorVisible(3200)); val firstLength = browser.state().length
            val second = "<h2>章节乙</h2>"+(0 until 100).joinToString("") { "<p>另一本章节的第${it}段，"+"新的文字和独立位置。".repeat(9)+"</p>" }
            browser.load(second,style,1800); assertEquals(1800,browser.state().offset); assertNotEquals(firstLength,browser.state().length); assertTrue(browser.anchorVisible(1800))
            browser.script("Reader.turn(1)"); assertTrue(browser.state().offset>1800)
            browser.load(textBody("章节甲"),style,3200); assertEquals(3200,browser.state().offset); assertTrue(browser.anchorVisible(3200))
        }
    }
    @Test fun tallIllustrationFitsInsideAReadingPage() {
        val picture = java.awt.image.BufferedImage(300,1500,java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = picture.createGraphics(); graphics.color = java.awt.Color(48,110,160); graphics.fillRect(0,0,300,1500); graphics.dispose()
        val bytes = java.io.ByteArrayOutputStream(); ImageIO.write(picture,"png",bytes)
        val body = "<img id='illustration' src='data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())+"'/>"+textBody()
        Browser(900,650).use { browser ->
            browser.load(body,ReaderSettings(spread="single"))
            await { browser.script("document.getElementById('illustration').complete && document.getElementById('illustration').naturalHeight===1500") as Boolean }
            browser.script("Reader.jump(0)")
            assertTrue(browser.script("(function(){var i=document.getElementById('illustration').getBoundingClientRect(),v=document.getElementById('viewport').getBoundingClientRect();return i.height>=400&&Math.abs(i.width/i.height-0.2)<0.01&&i.height<=v.height+1&&i.top>=v.top-1&&i.bottom<=v.bottom+1;})()") as Boolean,
                "A tall illustration must remain entirely inside its page")
            browser.snapshot("illustration.png")
        }
    }
    @Test fun repeatedTurnsAndSettingsKeepPositionsAndBoundedHeap() {
        Browser(1000,700).use { browser ->
            val settings = ReaderSettings(spread="single"); browser.load(textBody(),settings)
            var turns = 0; var reflows = 0
            fun exercise(iterations: Int) {
                repeat(iterations) { step ->
                    val before = browser.state()
                    if(before.page+before.columns>=before.count) browser.script("Reader.jump(0)") else {
                        browser.script("Reader.turn(1)"); val after = browser.state()
                        assertEquals(before.page+before.columns,after.page); assertTrue(after.offset>=before.offset); turns++
                    }
                    if(step%20==0) {
                        val anchor = browser.state().offset
                        val changed = settings.copy(fontSize=if(step%40==0) 28 else 24,spread=if(step%60==0) "double" else "single")
                        browser.script("Reader.settings(${changed.scriptObject()})")
                        assertEquals(anchor,browser.state().offset); assertTrue(browser.anchorVisible(anchor), "Reflow must keep offset $anchor visible")
                        reflows++
                    }
                }
            }
            val start = System.nanoTime(); exercise(250)
            System.gc(); Thread.sleep(200); fx { /* Drain JavaFX tasks before measuring a settled phase. */ }
            val warmHeap = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
            exercise(500)
            System.gc(); Thread.sleep(200); fx { }
            val finalHeap = Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
            val elapsedMs = (System.nanoTime()-start)/1_000_000
            val report = """
                operation_iterations=750
                successful_forward_turns=$turns
                font_or_spread_reflows=$reflows
                elapsed_ms=$elapsedMs
                used_heap_after_250_iterations_gc_bytes=$warmHeap
                used_heap_after_750_iterations_gc_bytes=$finalHeap
                settled_heap_growth_bytes=${finalHeap-warmHeap}
                jvm_max_heap_bytes=${Runtime.getRuntime().maxMemory()}
                note=Bounded automated WebView repetition; not equivalent to hours of interactive use. Native WebKit memory is not included in JVM heap metrics.
            """.trimIndent()
            val output = Path.of("build","verification","webview-repetition.txt"); Files.createDirectories(output.parent); Files.writeString(output,report)
            assertTrue(turns>=600)
            assertTrue(finalHeap-warmHeap < 128L*1024*1024, "Settled JVM heap grew by more than 128 MiB; see $output")
        }
    }
    @Test fun illustrationOnlyChapterTurnsAndRestoresEachImage() {
        val picture = java.awt.image.BufferedImage(300,1500,java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = picture.createGraphics(); graphics.color = java.awt.Color(48,110,160); graphics.fillRect(0,0,300,1500); graphics.dispose()
        val bytes = java.io.ByteArrayOutputStream(); ImageIO.write(picture,"png",bytes)
        val uri = "data:image/png;base64,"+java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())
        val body = (0 until 3).joinToString("") { "<img id='picture-$it' src='$uri'/>" }
        Browser(900,650).use { browser ->
            val settings = ReaderSettings(spread="single"); browser.load(body,settings)
            await { browser.script("Array.prototype.every.call(document.querySelectorAll('img'),function(i){return i.complete&&i.naturalHeight===1500;})") as Boolean }
            assertEquals(3,browser.state().length); assertEquals(0,browser.state().offset)
            fun pictureVisible(index: Int): Boolean = browser.script("(function(){var r=document.getElementById('picture-$index').getBoundingClientRect(),v=document.getElementById('viewport').getBoundingClientRect();return r.height>=300&&r.width>=60&&Math.abs(r.width/r.height-.2)<.01&&r.left>=v.left-1&&r.right<=v.right+1&&r.top>=v.top-1&&r.bottom<=v.bottom+1;})()") as Boolean
            assertTrue(pictureVisible(0),"The first picture must be fully visible")
            browser.script("Reader.turn(1)"); assertEquals(1,browser.state().offset); assertTrue(pictureVisible(1))
            browser.script("Reader.turn(1)"); assertEquals(2,browser.state().offset); assertTrue(pictureVisible(2))
            browser.script("Reader.turn(-1)"); assertEquals(1,browser.state().offset); assertTrue(pictureVisible(1))
            browser.script("Reader.settings(${settings.copy(fontSize=32).scriptObject()})"); assertEquals(1,browser.state().offset); assertTrue(pictureVisible(1))
            browser.resize(740,550); assertEquals(1,browser.state().offset); assertTrue(pictureVisible(1))
            browser.load(body,settings.copy(fontSize=32),1); assertEquals(1,browser.state().offset); assertTrue(pictureVisible(1))
            browser.snapshot("illustration-only-restored.png")
        }
    }
    @Test fun sanitizedCoverOpensAsOneFullImageOnTheFirstPage() {
        val fixtureRoot = Path.of("build","verification","cover-fixtures"); Files.createDirectories(fixtureRoot)
        val temporary = Files.createTempDirectory(fixtureRoot,"cover-")
        try {
            val image = java.awt.image.BufferedImage(400,600,java.awt.image.BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics(); graphics.color = java.awt.Color(48,110,160); graphics.fillRect(0,0,400,600); graphics.dispose()
            val coverBodies = listOf(
                "<h2 hidden='hidden' title='封面'></h2><div id='warp1'><div id='center1'><div id='main1'><img src='../Images/cover.png' alt='cover'/></div></div></div>",
                "<svg xmlns='http://www.w3.org/2000/svg' xmlns:xlink='http://www.w3.org/1999/xlink' viewBox='0 0 400 600'><image width='400' height='600' xlink:href='../Images/cover.png'/></svg>"
            )
            coverBodies.forEachIndexed { index, body ->
                val file = temporary.resolve("cover-$index.epub")
                java.util.zip.ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                    val entries = linkedMapOf(
                        "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
                        "OPS/book.opf" to "<package><manifest><item id='cover' href='Text/cover.xhtml'/><item id='picture' href='Images/cover.png' media-type='image/png' properties='cover-image'/></manifest><spine><itemref idref='cover'/></spine><guide><reference type='cover' href='Text/cover.xhtml'/></guide></package>",
                        "OPS/Text/cover.xhtml" to "<html><body>$body</body></html>"
                    )
                    entries.forEach { (name,text) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
                    zip.putNextEntry(java.util.zip.ZipEntry("OPS/Images/cover.png")); ImageIO.write(image,"png",zip); zip.closeEntry()
                }
                val chapter = FlowLoader.open(file,temporary.resolve("cache")).chapters.single()
                Browser(900,650).use { browser ->
                    browser.load(Files.readString(chapter.htmlPath),ReaderSettings(spread="single"),allowSinglePage=true)
                    try {
                        await { browser.script("document.querySelector('img').complete&&document.querySelector('img').naturalHeight===600") as Boolean }
                    } catch (failure: IllegalStateException) {
                        browser.snapshot("cover-load-failure.png")
                        error("Cover image did not load: " + browser.script("JSON.stringify({html:document.querySelector('img').outerHTML,complete:document.querySelector('img').complete,height:document.querySelector('img').naturalHeight,origin:location.href})"))
                    }
                    assertEquals(0,browser.state().page); assertEquals(1,browser.state().count); assertEquals(1,browser.state().length)
                    assertTrue(browser.script("(function(){var r=document.querySelector('img').getBoundingClientRect(),v=document.getElementById('viewport').getBoundingClientRect();return r.height>=450&&Math.abs(r.width/r.height-2/3)<.01&&r.left>=v.left-1&&r.right<=v.right+1&&r.top>=v.top-1&&r.bottom<=v.bottom+1;})()") as Boolean,
                        "The normalized cover must fill a substantial part of the first page without spilling into a second column")
                    browser.snapshot(if(index==0) "cover-page.png" else "cover-svg-page.png")
                }
            }
        } finally {
            Files.walk(temporary).use { paths -> paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }
    @Test fun trailingWhitespaceAndHiddenContentPreserveAllPagesAndStableOffsets() {
        val fixtureRoot=Files.createDirectories(Path.of("build","verification","whitespace-fixtures"))
        val paragraphs=(0 until 80).joinToString("") { "<p>段落$it："+"分页仍应显示完整正文。".repeat(30)+"</p>" }
        val settings=ReaderSettings(spread="single")
        Browser(1000,700).use { browser ->
            browser.load(paragraphs,settings); val originalPages=browser.state().count
            for((index,suffix) in listOf("\n"," \n\t","<span hidden='hidden'>隐藏的末尾文字</span>").withIndex()) {
                val file=fixtureRoot.resolve("tail-$index.epub")
                java.util.zip.ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                    mapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>",
                        "book.opf" to "<package><manifest><item id='c' href='c.xhtml'/></manifest><spine><itemref idref='c'/></spine></package>",
                        "c.xhtml" to "<html><body>$paragraphs$suffix</body></html>").forEach { (name,body) -> zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry() }
                }
                val chapter=FlowLoader.open(file,fixtureRoot.resolve("cache-$index")).chapters.single()
                val html=Files.readString(chapter.htmlPath)
                browser.load(html,settings,allowSinglePage=true)
                assertEquals(originalPages,browser.state().count,"Trailing non-visible text must not truncate pages")
                var previous=browser.state().offset
                repeat(originalPages-1) {
                    browser.script("Reader.turn(1)")
                    val state=browser.state()
                    assertTrue(state.offset>previous); assertTrue(browser.anchorVisible(state.offset))
                    previous=state.offset
                }
                val anchor=browser.state().offset
                browser.script("Reader.settings(${settings.copy(fontSize=30).scriptObject()})")
                assertEquals(anchor,browser.state().offset); assertTrue(browser.anchorVisible(anchor))
                browser.load(html,settings,anchor,allowSinglePage=true)
                assertEquals(anchor,browser.state().offset); assertTrue(browser.anchorVisible(anchor))
                browser.script("Reader.jump(${chapter.textLength})")
                assertEquals(originalPages-1,browser.state().page)
            }
        }
    }
    @Test fun hiddenMiddleNodesAndCollapsedSpacesDoNotBreakPageAnchors() {
        val body="<span hidden>旧位置仍包含这些文字</span>\n"+(0 until 100).joinToString("\n<span hidden>hidden</span>\n") {
            "<p>段落$it："+"正文    和空白仍然保留。".repeat(20)+"</p>"
        }+"\n"
        Browser(1000,700).use { browser ->
            browser.load(body,ReaderSettings(spread="double"),allowSinglePage=true)
            assertTrue(browser.state().count>10)
            repeat(8) {
                val before=browser.state()
                browser.script("Reader.turn(1)")
                assertEquals(before.page+2,browser.state().page)
                assertTrue(browser.state().offset>before.offset); assertTrue(browser.anchorVisible(browser.state().offset))
            }
            val position=browser.state().offset
            browser.resize(760,560)
            assertEquals(position,browser.state().offset); assertTrue(browser.anchorVisible(position))
        }
    }
}
