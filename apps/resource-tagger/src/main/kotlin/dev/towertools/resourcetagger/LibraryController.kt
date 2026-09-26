package dev.towertools.resourcetagger

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.EventQueue
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LibraryController(private val databasePath: java.nio.file.Path = AppPaths.dataDirectory.resolve("library.sqlite"), private val fileSystem: ResourceFileSystem = LocalFileSystem()) : AutoCloseable {
    var scanControl by mutableStateOf<ScanControl?>(null); private set
    private val scanner = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "resource-scan").apply { isDaemon = true } }
    var data by mutableStateOf(Snapshot()); private set
    var selected by mutableStateOf<Set<String>>(emptySet())
    var previewRevision by mutableStateOf(0L); private set
    var total by mutableStateOf(0); private set
    var offset by mutableStateOf(0); private set
    val pageSize = 200
    var querying by mutableStateOf(true); private set
    var focusData by mutableStateOf(Snapshot()); private set
    var detailLoading by mutableStateOf(false); private set
    var editCounts by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var editLoading by mutableStateOf(false); private set
    var busy by mutableStateOf<String?>("正在打开资料库…"); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf("添加 Root，开始组织本地资源。"); private set
    val loading get() = querying || detailLoading || editLoading
    private val executor = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "resource-library").apply { isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val generation = AtomicLong()
    private val focusGeneration = AtomicLong()
    private val editGeneration = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var query = Query()
    @Volatile private var requestedOffset = 0
    @Volatile private var focused: String? = null
    @Volatile private var editing: Set<String> = emptySet()
    private var scheduled: ScheduledFuture<*>? = null
    private var library: Library? = null
    private var metadata = Snapshot()
    init {
        executor.execute {
            try {
                library = Library(Database(databasePath), fileSystem)
                metadata = library!!.overview()
                loadPage(generation.get())
                ui { busy = null }
            } catch (e: Exception) { failure(e); ui { busy = null; querying = false } }
        }
    }
    private fun ui(block: () -> Unit) = EventQueue.invokeLater { if (!closed) block() }
    fun clearError() { error = null }
    fun search(value: Query, start: Int = 0, debounce: Boolean = true) {
        if (query != value) selected = emptySet()
        query = value; requestedOffset = start; querying = true
        val token = generation.incrementAndGet()
        scheduled?.cancel(false)
        scheduled = executor.schedule({
            if (!closed && token == generation.get()) try { loadPage(token) } catch (e: Exception) { if (token == generation.get()) { failure(e); ui { querying = false } } }
        }, if (debounce) 150 else 0, TimeUnit.MILLISECONDS)
    }
    fun movePage(delta: Int) { search(query, (offset + delta * pageSize).coerceAtLeast(0), false) }
    private fun loadPage(token: Long, pruneSelection: Boolean = false) {
        val current = library ?: run { ui { if (token == generation.get()) querying = false }; return }
        val result = current.queryPage(query, requestedOffset, pageSize)
        val meta = metadata
        val selection = selected
        val retained = if (pruneSelection && selection.isNotEmpty()) current.retainedSelection(query, selection) else selection
        ui {
            if (token == generation.get()) {
                data = meta.copy(resources = result.snapshot.resources, links = result.snapshot.links, reviews = result.snapshot.reviews)
                total = result.total; offset = result.offset; requestedOffset = result.offset; querying = false
                if (selected == selection) selected = retained
            }
        }
    }
    fun focus(id: String?) {
        focused = id; focusData = Snapshot(); detailLoading = id != null
        val token = focusGeneration.incrementAndGet()
        executor.execute { loadFocus(token) }
    }
    private fun loadFocus(token: Long) {
        try {
            val result = focused?.let { library?.detail(it) } ?: Snapshot()
            ui { if (token == focusGeneration.get()) { focusData = result; detailLoading = false } }
        } catch (e: Exception) { if (token == focusGeneration.get()) { failure(e); ui { detailLoading = false } } }
    }
    fun editSelection(ids: Set<String>) {
        editing = ids; editCounts = emptyMap(); editLoading = ids.isNotEmpty()
        val token = editGeneration.incrementAndGet()
        executor.execute { loadEditCounts(token) }
    }
    private fun loadEditCounts(token: Long) {
        try {
            val counts = if (editing.isEmpty()) emptyMap() else library?.selectionCounts(editing).orEmpty()
            ui { if (token == editGeneration.get()) { editCounts = counts; editLoading = false } }
        } catch (e: Exception) { if (token == editGeneration.get()) { failure(e); ui { editLoading = false } } }
    }
    fun selectAll(onResult: (Set<String>) -> Unit) {
        val requested = query
        var ids = emptySet<String>()
        submit("选择全部匹配资源", { if (query == requested) onResult(ids) }, refresh = false) { ids = it.matchingIds(requested) }
    }
    fun scan(rootId: String) {
        if (busy != null || closed) return
        val control = ScanControl()
        scanControl = control; busy = "正在扫描"; error = null
        // Dispatch through the library queue so initialization and earlier writes finish first.
        executor.execute {
            val current = library
            scanner.execute {
                var outcome = "扫描完成"
                try { (current ?: error("资料库未能打开，请检查错误并重启。")).scan(rootId, control) }
                catch (e: java.util.concurrent.CancellationException) { outcome = "扫描已取消，原有数据已保留。" }
                catch (e: Exception) { outcome = "扫描失败，原有数据已保留。"; failure(e) }
                executor.execute {
                    if (!closed) {
                        try {
                            metadata = current?.overview() ?: Snapshot()
                            loadPage(generation.get(), true)
                            loadFocus(focusGeneration.get()); loadEditCounts(editGeneration.get())
                        } catch (e: Exception) { failure(e); ui { querying = false } }
                        ui { scanControl = null; busy = null; message = outcome; previewRevision++ }
                    }
                }
            }
        }
    }
    fun submit(label: String, onSuccess: () -> Unit = {}, refresh: Boolean = true, action: (Library) -> Unit) {
        if (busy != null || closed) return
        busy = label; error = null
        if (refresh) { generation.incrementAndGet(); scheduled?.cancel(false); querying = true }
        executor.execute {
            var succeeded = false
            try { action(library ?: error("资料库未能打开，请检查错误并重启。")); succeeded = true }
            catch (e: Exception) { failure(e) }
            if (refresh) {
                try { metadata = library!!.overview(); loadPage(generation.get(), true); loadFocus(focusGeneration.get()); loadEditCounts(editGeneration.get()) }
                catch (e: Exception) { succeeded = false; failure(e); ui { querying = false } }
            }
            ui { if (refresh) previewRevision++; busy = null; if (succeeded) { message = "$label · 完成"; onSuccess() } }
        }
    }
    private fun failure(e: Exception) {
        AppLog.logger.log(java.util.logging.Level.WARNING, "Library operation failed", e)
        ui { error = e.message ?: e.javaClass.simpleName }
    }
    override fun close() {
        if (closed) return
        closed = true; scanControl?.cancel(); scheduled?.cancel(false)
        // Enqueue cleanup after scan dispatch, then after the scanner releases the database.
        executor.execute {
            scanner.execute {
                executor.execute { library?.close() }
                executor.shutdown()
            }
            scanner.shutdown()
        }
    }
}
