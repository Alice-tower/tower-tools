package dev.towertools.towerreader.flow

import dev.towertools.towerreader.ReaderSettings
import dev.towertools.towerreader.AppPaths
import dev.towertools.towerreader.jsString
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import java.awt.EventQueue
import java.awt.Color
import java.nio.file.Path
import java.util.concurrent.Executors

data class FlowPageState(val offset: Int, val page: Int, val pageCount: Int, val columns: Int, val textLength: Int, val requestId: Long = 0)

/** One Swing surface and exactly two reusable pages: visible chapter + prepared neighbour. */
class FlowPane(
    private val onPosition: (FlowPageState) -> Unit,
    private val onBoundary: (Int) -> Unit,
    private val onLink: (String) -> Unit,
    private val onAction: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val windowKeyboard: Boolean = false,
    pageDirectory: Path = AppPaths.dataDirectory.resolve("cache/pages"),
) : JFXPanel() {
    private data class Request(val html: String, val offset: Int, val id: Long, val serial: Long)
    private var root: StackPane? = null
    private var slots = emptyList<PageSlot>()
    private var front: PageSlot? = null
    private var desired: Request? = null
    private var preload: String? = null
    private var settings = ReaderSettings()
    @Volatile private var disposed = false
    @Volatile private var request = 0L
    private var displayedSerial = -1L
    private var initializationScheduled = false
    private var reveal: AnimationTimer? = null
    private var preloadSubmitted: String? = null
    private val pageFiles = FlowPageFiles(pageDirectory)

    init {
        background = Color.decode(settings.background)
        Platform.setImplicitExit(false)
    }
    override fun addNotify() {
        // Finish NodeHelper/Node initialization on AWT before creating WebViews on FX.
        super.addNotify()
        if (initializationScheduled) return
        initializationScheduled = true
        Platform.runLater {
            if (!disposed) {
                val container = StackPane()
                container.style = "-fx-background-color: ${settings.background};"
                root = container
                slots = listOf(PageSlot(), PageSlot())
                container.children.addAll(slots.map { it.web })
                scene = Scene(container)
                displayRequested()
            }
        }
    }
    fun showChapter(html: String, offset: Int, style: ReaderSettings, requestId: Long = 0) {
        val serial = ++request
        Platform.runLater {
            if (!disposed && serial == request) {
                settings = style
                desired = Request(html, offset.coerceAtLeast(0), requestId, serial)
                displayRequested()
            }
        }
    }
    fun preloadChapter(html: String?) {
        if (preloadSubmitted == html) return
        preloadSubmitted = html
        Platform.runLater { if (!disposed) { preload = html; prepareNeighbour() } }
    }
    private fun displayRequested() {
        val target = desired ?: return
        if (slots.isEmpty() || disposed) return
        reveal?.stop(); reveal = null
        val slot = slots.firstOrNull { it.html == target.html } ?: slots.first { it !== front }
        if (slot.html != target.html) slot.load(target.html, target.offset)
        else if (slot.ready) reveal(slot)
    }
    private fun prepareNeighbour() {
        val html = preload ?: return
        if (front == null || displayedSerial != desired?.serial || html == front?.html) return
        val spare = slots.first { it !== front }
        if (spare.html != html) spare.load(html, 0)
    }
    private fun reveal(slot: PageSlot) {
        val target = desired ?: return
        if (slot.html != target.html || !slot.ready || slot.state == null) return
        reveal?.stop()
        // While preparing, callbacks from the old page must not become the new chapter's progress.
        displayedSerial = -1
        slot.applySettings()
        slot.web.engine.executeScript("Reader.jump(${target.offset})")
        val revision = slot.revision
        var pulses = 0
        val timer = object : AnimationTimer() {
            override fun handle(now: Long) {
                if (disposed || desired?.serial != target.serial || slot.revision != revision) { stop(); return }
                // Let layout and painting complete behind the current page before revealing it.
                if (++pulses < 2) return
                stop(); reveal = null
                front = slot; displayedSerial = target.serial
                slot.web.toFront()
                publishPosition(slot)
                prepareNeighbour()
            }
        }
        reveal = timer
        timer.start()
    }
    fun updateSettings(style: ReaderSettings) {
        background = Color.decode(style.background)
        Platform.runLater {
            if (settings == style || disposed) return@runLater
            settings = style
            root?.style = "-fx-background-color: ${style.background};"
            slots.filter { it.ready }.forEach { it.applySettings() }
        }
    }
    fun turn(direction: Int) = script("Reader.turn(${direction.coerceIn(-1, 1)})")
    fun jump(offset: Int) = script("Reader.jump(${offset.coerceAtLeast(0)})")
    fun fragment(id: String) = script("Reader.fragment(${jsString(id)})")
    private fun script(value: String) {
        Platform.runLater {
            if (!disposed && displayedSerial == request) runCatching { front?.web?.engine?.executeScript(value) }
                .onFailure { publishError(it.message ?: "阅读操作失败") }
        }
    }
    fun disposeReader() {
        request++; disposed = true
        Platform.runLater {
            reveal?.stop(); reveal = null
            slots.forEach { it.revision++; it.stop(); it.web.engine.load(null) }
            root?.children?.clear(); slots = emptyList(); front = null; root = null; scene = null
            desired = null; preload = null; preloadSubmitted = null
            pageIo.execute { runCatching { pageFiles.close() } }
        }
    }
    private fun publishError(message: String) {
        val serial = request
        EventQueue.invokeLater { if (!disposed && serial == request) onError(message) }
    }
    private fun publishPosition(slot: PageSlot) {
        val target = desired ?: return
        val state = slot.state ?: return
        if (slot !== front || displayedSerial != target.serial) return
        EventQueue.invokeLater {
            if (!disposed && target.serial == request) onPosition(state.copy(requestId = target.id))
        }
    }
    private fun action(slot: PageSlot, action: () -> Unit) {
        val serial = displayedSerial
        if (slot !== front || serial != request) return
        EventQueue.invokeLater { if (!disposed && serial == request) action() }
    }
    internal inner class PageSlot {
        val web = WebView()
        var html: String? = null
        var ready = false
        @Volatile var revision = 0L
        var state: FlowPageState? = null
        private var offset = 0
        private var appliedStyle = settings
        private val bridge = ReaderBridge(this)
        private var pageFile: Path? = null
        private var loadingUrl: String? = null
        init {
            web.isContextMenuEnabled = false
            web.engine.createPopupHandler = javafx.util.Callback { null }
            web.engine.setOnAlert { }
            web.engine.loadWorker.stateProperty().addListener { _, _, status ->
                if (disposed || loadingUrl == null || web.engine.location != loadingUrl) return@addListener
                if (status == Worker.State.SUCCEEDED) runCatching {
                    (web.engine.executeScript("window") as JSObject).setMember("readerHost", bridge)
                    appliedStyle = settings
                    web.engine.executeScript("Reader.useHostKeyboard($windowKeyboard)")
                    web.engine.executeScript("Reader.init(${settings.scriptObject()},$offset)")
                    ready = true
                    if (desired?.html == html) reveal(this)
                }.onFailure { if (desired?.html == html) publishError(it.message ?: "文字页面加载失败") }
                else if (status == Worker.State.FAILED && desired?.html == html) publishError("文字页面加载失败")
            }
        }
        fun stop() { runCatching { if (ready) web.engine.executeScript("Reader.stop()") }; ready = false }
        fun load(body: String, at: Int) {
            stop(); revision++; html = body; offset = at; state = null
            loadingUrl = null
            web.engine.loadWorker.cancel()
            web.toBack()
            val expectedRevision = revision
            val style = settings
            // A loadContent/about:blank document cannot read local file images. Give the sanitized
            // page a local origin, writing only the current and spare HTML shells off the UI thread.
            pageIo.execute {
                if (disposed || revision != expectedRevision) return@execute
                val result = runCatching { pageFiles.write(body, style) }
                Platform.runLater {
                    if (disposed || revision != expectedRevision) {
                        result.getOrNull()?.let { file -> pageIo.execute { runCatching { pageFiles.remove(file) } } }
                    } else result.fold(onSuccess = { file ->
                        val previous = pageFile
                        pageFile = file
                        loadingUrl = file.toUri().toASCIIString()
                        web.engine.load(loadingUrl)
                        previous?.let { pageIo.execute { runCatching { pageFiles.remove(it) } } }
                    }, onFailure = { if (desired?.html == html) publishError(it.message ?: "文字页面加载失败") })
                }
            }
        }
        fun applySettings() {
            if (ready && appliedStyle != settings) {
                appliedStyle = settings
                web.engine.executeScript("Reader.settings(${settings.scriptObject()})")
            }
        }
    }
    // Public for the WebKit Java bridge; keep one strong reference per reusable page.
    inner class ReaderBridge internal constructor(private val slot: PageSlot) {
        fun updated(offset: Int, page: Int, pages: Int, columns: Int, length: Int) {
            slot.state = FlowPageState(offset, page, pages, columns, length)
            if (slot.ready) publishPosition(slot)
        }
        fun boundary(direction: Int) { action(slot) { onBoundary(direction) } }
        fun link(href: String) { action(slot) { onLink(href) } }
        fun action(key: String) { action(slot) { onAction(key) } }
    }
    companion object {
        private val pageIo = Executors.newSingleThreadExecutor { task -> Thread(task, "reader-page-files").apply { isDaemon = true } }
        private val scriptText by lazy { FlowPane::class.java.getResourceAsStream("/reader.js")!!.bufferedReader().use { it.readText() } }
        fun document(body: String, style: ReaderSettings): String = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src file: data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'\">" +
            "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;}body{background:" + style.background + ";color:" + style.foreground + ";}" +
            "#viewport{position:absolute;overflow:hidden;}#content{column-fill:auto;overflow:visible;overflow-wrap:anywhere;line-break:strict;word-break:normal;text-align:justify;}" +
            "p{margin:0 0 .7em;text-indent:2em;}h1,h2,h3,h4,h5,h6{line-height:1.45;break-after:avoid;text-indent:0;}h1{font-size:1.5em;}h2{font-size:1.3em;}h3{font-size:1.15em;}" +
            "img{max-width:100%;object-fit:contain;display:block;margin:8px auto;break-inside:avoid;}" +
            "table{max-width:100%;border-collapse:collapse;font-size:.85em;}td,th{border:1px solid #888;padding:.25em;}pre{white-space:pre-wrap;}blockquote{margin:1em;padding-left:1em;border-left:2px solid #999;}" +
            "a{color:inherit;text-decoration:underline;}figure{margin:0;}ul,ol{padding-left:1.5em;}li{margin:.25em 0;}</style></head><body>" +
            "<div id=\"viewport\"><article id=\"content\">" + body + "</article></div><script>" + scriptText + "</script></body></html>"
    }
}
