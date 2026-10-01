package dev.towertools.towerreader

import androidx.compose.runtime.*
import dev.towertools.towerreader.flow.*
import dev.towertools.towerreader.library.*
import dev.towertools.towerreader.pdf.*
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ReaderController(private val dataDirectory: Path = AppPaths.dataDirectory) {
    var books by mutableStateOf<List<BookRecord>>(emptyList()); private set
    var categories by mutableStateOf<List<String>>(emptyList()); private set
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var importing by mutableStateOf(false); private set
    var importStatus by mutableStateOf(""); private set
    var active by mutableStateOf<BookRecord?>(null); private set
    var flow by mutableStateOf<FlowDocument?>(null); private set
    var pdf by mutableStateOf<PdfSession?>(null); private set
    var chapter by mutableStateOf(0); private set
    var pdfPage by mutableStateOf(0); private set
    var pageState by mutableStateOf<FlowPageState?>(null); private set
    var chapterHtml by mutableStateOf<String?>(null); private set
    var chapterPreloadHtml by mutableStateOf<String?>(null); private set
    var chapterOffset by mutableStateOf(0); private set
    var chapterToken by mutableStateOf(0L); private set
    var loading by mutableStateOf(false); private set
    var settings by mutableStateOf(ReaderSettings()); private set
    var closing by mutableStateOf(false); private set
    @Volatile private var repository: LibraryRepository? = null
    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<PdfSession>()
    private var pdfColumns = 1
    private var currentProgress by mutableDoubleStateOf(0.0)
    private var pendingSettings: ScheduledFuture<*>? = null
    private val background = Executors.newSingleThreadExecutor { Thread(it, "reader-library").apply { isDaemon = true } }
    val readerExecutor = Executors.newSingleThreadExecutor { Thread(it, "reader-pages").apply { isDaemon = true } }
    private val persistence = Executors.newSingleThreadScheduledExecutor { Thread(it, "reader-save").apply { isDaemon = true } }
    private val generation = AtomicLong()
    // Accessed only by readerExecutor, limited to current/nearby chapter fragments.
    private val chapterCache = LinkedHashMap<Path, String>(4, 0.75f, true)
    private var pendingSave: ScheduledFuture<*>? = null
    private var lastSave: Triple<String, String, Double>? = null
    // Only a page that was actually displayed may be used to recover a failed request.
    private var lastRendered: Pair<Long, FlowPageState>? = null
    private var encodingOverride: String? = null
    private var closed = false
    private val importCancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    fun cancelImport() { importCancelled.set(true); importStatus = "正在结束当前文件…" }

    init {
        background.execute {
            try {
                val repo = LibraryRepository(dataDirectory)
                repository = repo
                val prefs = ReaderSettings(
                    theme = repo.setting("theme", "paper").takeIf { it in setOf("paper", "dark") } ?: "paper",
                    fontFamily = repo.setting("font", "Microsoft YaHei"),
                    fontSize = repo.setting("size", "24").toIntOrNull()?.coerceIn(14, 48) ?: 24,
                    lineHeight = repo.setting("line", "1.8").toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(1.2, 2.4) ?: 1.8,
                    margin = repo.setting("margin", "40").toIntOrNull()?.coerceIn(16, 80) ?: 40,
                    spread = repo.setting("spread", "auto").takeIf { it in setOf("single", "double", "auto") } ?: "auto",
                    pdfDark = repo.setting("pdfDark", "false").toBoolean(),
                    pdfZoom = repo.setting("pdfZoom", "1.0").toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(1.0, 3.0) ?: 1.0,
                )
                val all = repo.listBooks()
                val ordered = repo.listCategories()
                ui { repository = repo; settings = prefs; books = all; categories = ordered; ready = true }
            } catch (e: Exception) { fail("无法打开书库", e) }
        }
    }
    fun importFiles(files: List<Path>) {
        if (!ready || importing || closed) return
        importCancelled.set(false)
        importing = true; importStatus = "准备导入 " + files.size + " 个文件"
        background.execute {
            try {
                val report = repository!!.importBooks(files, shouldCancel = importCancelled::get) { p -> ui { importStatus = "已处理 " + p.completed + "/" + p.total + " · " + p.fileName } }
                val all = repository!!.listBooks()
                ui {
                    books = all; importing = false; importStatus = ""
                    notice = (if (report.cancelled) "已取消后续导入 · " else "") + "导入 " + report.added + " 本，重复 " + report.duplicates + " 本" +
                        if (report.failures.isEmpty()) "" else "，失败 " + report.failures.size + " 本"
                    if (report.failures.isNotEmpty()) error = report.failures.joinToString("\n")
                }
            } catch (e: Exception) { ui { importing = false; importStatus = "" }; fail("导入失败", e) }
        }
    }
    fun coverPath(book: BookRecord): Path? = repository?.coverPath(book)
    fun editBook(book: BookRecord, title: String, category: String) = background.execute {
        try { repository!!.updateBook(book.id, title, category); refresh() } catch (e: Exception) { fail("保存书籍信息失败", e) }
    }
    fun removeBook(book: BookRecord) = background.execute {
        try { repository!!.removeBook(book.id); refresh() } catch (e: Exception) { fail("移出书库失败", e) }
    }
    fun renameCategory(previous: String, name: String, onFinished: (Boolean) -> Unit) = background.execute {
        try { repository!!.renameCategory(previous, name); refresh { onFinished(true) } }
        catch (e: Exception) { fail("保存分类失败", e); ui { onFinished(false) } }
    }
    fun reorderCategories(order: List<String>) {
        if (closed || order == categories) return
        categories = order
        background.execute {
            try { repository!!.reorderCategories(order); refresh() }
            catch (e: Exception) { fail("保存分类顺序失败", e); runCatching { refresh() } }
        }
    }
    private fun refresh(onUpdated: () -> Unit = {}) {
        val list = repository!!.listBooks(); val ordered = repository!!.listCategories()
        ui { books = list; categories = ordered; onUpdated() }
    }
    fun open(requestedBook: BookRecord, encoding: String? = null) {
        if (!ready || closed) return
        val saved = lastSave?.takeIf { it.first == requestedBook.id }
        val book = if (saved != null) requestedBook.copy(locator = saved.second, progress = saved.third) else requestedBook
        currentProgress = book.progress
        flushProgress()
        val oldPdf = pdf
        val token = generation.incrementAndGet()
        lastRendered = null
        active = book; flow = null; pdf = null; pageState = null; chapterHtml = null; chapterPreloadHtml = null
        loading = true; encodingOverride = encoding
        readerExecutor.execute {
            try {
                chapterCache.clear()
                oldPdf?.let { it.close(); sessions.remove(it) }
                val repo = repository!!
                val path = repo.bookPath(book)
                val sourceEncoding = encoding ?: repo.setting("encoding." + book.id, "").ifBlank { null }
                if (book.format == BookFormat.PDF) {
                    val session = PdfSession(path)
                    sessions.add(session)
                    if (generation.get() != token) { session.close(); sessions.remove(session); return@execute }
                    val p = book.locator.removePrefix("pdf:").toIntOrNull()?.coerceIn(0, session.pageCount - 1) ?: 0
                    ui {
                        if (generation.get() == token) { pdf = session; pdfPage = p; loading = false; savePdfPosition() }
                        else readerExecutor.execute { session.close(); sessions.remove(session) }
                    }
                } else {
                    val cache = dataDirectory.resolve("cache").resolve(book.id)
                    val document = FlowLoader.open(path, cache, sourceEncoding)
                    val parts = book.locator.split(':')
                    val c = if (parts.size >= 3 && parts[0] == "flow") parts[1].toIntOrNull() ?: 0 else 0
                    val o = if (parts.size >= 3 && parts[0] == "flow") parts[2].toIntOrNull() ?: 0 else 0
                    val safeChapter = c.coerceIn(0, document.chapters.lastIndex)
                    val html = readChapter(document, safeChapter)
                    if (encoding != null) repo.setSetting("encoding." + book.id, encoding)
                    ui {
                        if (generation.get() == token) {
                            flow = document; chapter = safeChapter; chapterOffset = o.coerceAtLeast(0)
                            chapterHtml = html; chapterToken++; loading = false
                            if (document.warnings.isNotEmpty()) notice = document.warnings.joinToString("；")
                        }
                    }
                    prefetch(document, safeChapter + 1, token)
                }
                repo.markOpened(book.id); refresh()
            } catch (e: Exception) {
                failCurrent(token, "打开失败", e)
            }
        }
    }
    fun goChapter(index: Int, offset: Int = 0) {
        val document = flow ?: return
        if (index !in document.chapters.indices || closed) return
        val token = generation.incrementAndGet()
        val direction = if (index < chapter) -1 else 1
        pageState = null; chapterPreloadHtml = null; loading = true
        readerExecutor.execute {
            try {
                val html = readChapter(document, index)
                ui { if (generation.get() == token) {
                    chapter = index; chapterHtml = html; chapterOffset = if (offset < 0) Int.MAX_VALUE else offset
                    chapterToken++; loading = false
                } }
                prefetch(document, index + direction, token)
            } catch (e: Exception) {
                AppLog.logger.warning("读取章节失败: " + e.stackTraceToString())
                ui { if (generation.get() == token) {
                    loading = false; error = "读取章节失败：" + (e.message ?: e.javaClass.simpleName)
                    val rendered = lastRendered
                    if (rendered != null && rendered.first == chapterToken) pageState = rendered.second
                    else if (chapterHtml != null) {
                        // The latest HTML may have committed before its first display report.
                        // Resubmit it with a fresh token instead of reusing another chapter's state.
                        pageState = null; chapterToken++
                    }
                } }
            }
        }
    }
    private fun readChapter(document: FlowDocument, index: Int): String {
        val path = document.chapters[index].htmlPath
        // Missing files remain a visible error even if an earlier preview was cached.
        require(Files.isRegularFile(path)) { "章节缓存不存在，请重新打开这本书" }
        return chapterCache.getOrPut(path) { Files.readString(path) }.also {
            while (chapterCache.size > 3) chapterCache.remove(chapterCache.keys.first())
        }
    }
    private fun prefetch(document: FlowDocument, index: Int, token: Long) {
        if (index !in document.chapters.indices || generation.get() != token) return
        // A speculative failure must not interrupt the page the user is reading.
        val html = runCatching { readChapter(document, index) }.getOrNull() ?: return
        ui { if (generation.get() == token && flow === document) chapterPreloadHtml = html }
    }
    fun onFlowPosition(value: FlowPageState) {
        if (flow == null || loading) return
        pageState = value
        lastRendered = chapterToken to value
        val doc = flow!!
        val preceding = doc.chapters.take(chapter).sumOf { it.textLength.toLong() }
        val ratio = if (chapter == doc.chapters.lastIndex && value.page + value.columns >= value.pageCount) 1.0
            else (preceding + value.offset).toDouble() / doc.totalLength.coerceAtLeast(1)
        savePosition("flow:" + chapter + ":" + value.offset, ratio)
    }
    fun turnPdf(direction: Int, columns: Int) {
        val session = pdf ?: return
        pdfPage = (pdfPage + direction * columns).coerceIn(0, session.pageCount - 1)
        savePdfPosition()
    }
    fun jumpPdfPage(value: Int) {
        val session = pdf ?: return
        pdfPage = value.coerceIn(0, session.pageCount - 1)
        savePdfPosition()
    }
    private fun savePdfPosition() {
        val count = pdf?.pageCount ?: return
        savePosition("pdf:" + pdfPage, if (pdfPage + pdfColumns >= count) 1.0 else pdfPage.toDouble() / (count - 1).coerceAtLeast(1))
    }
    val progress: Double get() = currentProgress
    fun updatePdfColumns(value: Int) {
        if (value != pdfColumns) { pdfColumns = value; savePdfPosition() }
    }
    fun seek(progress: Double) {
        val percent = progress.coerceIn(0.0, 1.0)
        pdf?.let { jumpPdfPage((percent * (it.pageCount - 1)).toInt()); return }
        val doc = flow ?: return
        var remaining = (doc.totalLength * percent).toLong()
        for ((i, c) in doc.chapters.withIndex()) {
            if (remaining < c.textLength || i == doc.chapters.lastIndex) { goChapter(i, remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()); return }
            remaining -= c.textLength
        }
    }
    private fun savePosition(locator: String, progress: Double) {
        val id = active?.id ?: return
        val next = Triple(id, locator, progress.coerceIn(0.0, 1.0))
        if (lastSave == next) return
        lastSave = next
        currentProgress = next.third
        pendingSave?.cancel(false)
        pendingSave = persistence.schedule({ persist(next) }, 400, TimeUnit.MILLISECONDS)
    }
    private fun persist(value: Triple<String, String, Double>) {
        try { repository?.saveProgress(value.first, value.second, value.third) } catch (e: Exception) { fail("进度保存失败", e) }
    }
    fun flushProgress() {
        pendingSave?.cancel(false)
        lastSave?.let { value -> persistence.execute { persist(value) } }
    }
    fun updateSettings(requested: ReaderSettings) {
        val value = requested.copy(theme = requested.theme.takeIf { it == "dark" } ?: "paper")
        if (settings == value) return
        settings = value
        pendingSettings?.cancel(false)
        pendingSettings = persistence.schedule({
            try {
                repository?.let { r ->
                    r.setSetting("theme", value.theme); r.setSetting("font", value.fontFamily)
                    r.setSetting("size", value.fontSize.toString()); r.setSetting("line", value.lineHeight.toString())
                    r.setSetting("margin", value.margin.toString()); r.setSetting("spread", value.spread)
                    r.setSetting("pdfDark", value.pdfDark.toString()); r.setSetting("pdfZoom", value.pdfZoom.toString())
                }
            } catch (e: Exception) { fail("阅读设置保存失败", e) }
        }, 250, TimeUnit.MILLISECONDS)
    }
    fun returnToLibrary() {
        flushProgress()
        generation.incrementAndGet()
        lastRendered = null
        val old = pdf; pdf = null; flow = null; active = null; chapterHtml = null; chapterPreloadHtml = null; loading = false
        readerExecutor.execute { chapterCache.clear(); runCatching { old?.let { it.close(); sessions.remove(it) } }.onFailure { fail("关闭书籍失败", it) } }
        persistence.execute { runCatching { refresh() }.onFailure { fail("刷新书库失败", it) } }
    }
    fun changeEncoding(encoding: String) {
        val book = active ?: return
        val saved = lastSave?.takeIf { it.first == book.id }
        open(if (saved != null) book.copy(locator = saved.second, progress = saved.third) else book, encoding)
    }
    fun close(onClosed: () -> Unit) {
        if (closing) return
        importCancelled.set(true)
        closing = true; flushProgress(); generation.incrementAndGet(); closed = true
        val old = pdf; pdf = null
        readerExecutor.execute { sessions.toList().forEach { runCatching { it.close() }; sessions.remove(it) } }
        background.execute {
            persistence.shutdown(); persistence.awaitTermination(15, TimeUnit.SECONDS)
            readerExecutor.shutdown(); readerExecutor.awaitTermination(30, TimeUnit.SECONDS)
            runCatching { repository?.close() }.onFailure { AppLog.logger.warning(it.message) }
            EventQueue.invokeLater(onClosed)
            background.shutdown()
        }
    }
    private fun ui(action: () -> Unit) { EventQueue.invokeLater { if (!closed) action() } }
    private fun failCurrent(token: Long, label: String, e: Throwable) {
        AppLog.logger.warning(label + ": " + e.stackTraceToString())
        ui { if (generation.get() == token) { loading = false; error = label + "：" + (e.message ?: e.javaClass.simpleName) } }
    }
    private fun fail(label: String, e: Throwable) {
        AppLog.logger.warning(label + ": " + e.stackTraceToString())
        ui { error = label + "：" + (e.message ?: e.javaClass.simpleName) }
    }
}
