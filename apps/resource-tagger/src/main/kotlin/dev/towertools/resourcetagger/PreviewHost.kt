package dev.towertools.resourcetagger

import androidx.compose.runtime.*
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level

/** Independent from the database executor; bounded queue never runs I/O on the caller. */
private fun previewExecutor() = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(128),
    ThreadFactory { task -> Thread(task, "resource-preview").apply { isDaemon = true } })

class PreviewRuntime(private val dataDirectory: Path = AppPaths.dataDirectory) : AutoCloseable {
    private val executor = previewExecutor()
    fun host(pluginId: String) = PreviewHost(pluginId, dataDirectory, PreviewTasks(executor, false))
    override fun close() { executor.shutdownNow() }
}

class PreviewTasks internal constructor(private val executor: ThreadPoolExecutor, private val ownsExecutor: Boolean) : AutoCloseable {
    constructor() : this(previewExecutor(), true)
    private val pending = ConcurrentHashMap.newKeySet<FutureTask<Unit>>()
    private val closed = AtomicBoolean()
    internal fun child() = PreviewTasks(executor, false)
    @Synchronized fun <T> submit(work: () -> T, complete: (Result<T>) -> Unit): AutoCloseable {
        val cancelled = AtomicBoolean()
        fun deliver(result: Result<T>) = EventQueue.invokeLater {
            if (!closed.get() && !cancelled.get()) complete(result)
        }
        lateinit var task: FutureTask<Unit>
        task = FutureTask<Unit> {
            try { if (!closed.get() && !cancelled.get()) deliver(runCatching(work)) }
            finally { pending.remove(task) }
        }
        if (!closed.get()) try { pending.add(task); executor.execute(task) }
        catch (e: RejectedExecutionException) { pending.remove(task); deliver(Result.failure(IllegalStateException("预览任务繁忙，请刷新重试。", e))) }
        return AutoCloseable { cancelled.set(true); task.cancel(true); executor.remove(task); pending.remove(task) }
    }
    @Synchronized override fun close() {
        closed.set(true)
        pending.forEach { it.cancel(true); executor.remove(it) }
        pending.clear()
        if (ownsExecutor) executor.shutdownNow()
    }
}

class PreviewStorage(private val base: Path, pluginId: String) {
    init { validatePreviewId(pluginId) }
    private val directory = base.resolve("plugins").resolve(pluginId)
    fun configDirectory(): Path = Files.createDirectories(directory.resolve("config"))
    fun cacheDirectory(): Path = Files.createDirectories(directory.resolve("cache"))
}

class PreviewAccess {
    /** Call from a preview task, including immediately before opening a viewer. */
    fun resolve(target: PreviewTarget): Path {
        require(target.resource.rootId == target.root.id) { "资源与 Root 不匹配。" }
        require(target.resource.status == Status.Active) { "资源当前不可预览，请检查状态。" }
        val path = PathsPolicy.actual(target.root, target.resource)
        require(Files.isDirectory(Path.of(target.root.path))) { "Root 当前不可访问。" }
        require(LocalFileSystem().inspect(path) == target.resource.kind) { "资源不存在或类型已变化，请重新扫描。" }
        return path
    }
    fun openExternal(target: PreviewTarget, executable: Path, arguments: List<String> = emptyList()) {
        val path = resolve(target)
        ProcessBuilder(listOf(executable.toString()) + arguments + path.toString()).start()
    }
}

/** One host per active plugin/revision. Disposing it invalidates all pending completions. */
class PreviewHost(val pluginId: String, private val dataDirectory: Path = AppPaths.dataDirectory, val tasks: PreviewTasks = PreviewTasks()) : AutoCloseable {
    private val active = AtomicBoolean(true)
    val files = PreviewAccess()
    val storage = PreviewStorage(dataDirectory, pluginId)
    var error by mutableStateOf<String?>(null); private set
    fun report(error: Throwable) {
        AppLog.logger.log(Level.WARNING, "Preview plugin $pluginId failed", error)
        this.error = error.message ?: error.javaClass.simpleName
    }
    fun clearError() { error = null }
    internal fun child() = PreviewHost(pluginId, dataDirectory, tasks.child())
    fun onUi(action: () -> Unit) = EventQueue.invokeLater { if (active.get()) action() }
    fun execute(action: PreviewAction, target: PreviewTarget) {
        tasks.submit({ files.resolve(target); action.execute(target, this) }) { result ->
            result.exceptionOrNull()?.let {
                AppLog.logger.log(Level.WARNING, "Preview action $pluginId/${action.id}, resource ${target.resource.id} failed", it)
                error = it.message ?: it.javaClass.simpleName
            }
        }
    }
    override fun close() { active.set(false); tasks.close() }
}

sealed interface PreviewLoad<out T> {
    data object Loading : PreviewLoad<Nothing>
    data class Ready<T>(val value: T) : PreviewLoad<T>
    data class Failed(val message: String) : PreviewLoad<Nothing>
}

/** Compose only visible items / the active detail tab; disposal cancels their requests. */
@Composable fun <T> rememberPreview(host: PreviewHost, requestKey: Any, load: () -> T): PreviewLoad<T> {
    var state by remember(host, requestKey) { mutableStateOf<PreviewLoad<T>>(PreviewLoad.Loading) }
    val currentLoad by rememberUpdatedState(load)
    DisposableEffect(host, requestKey) {
        val job = host.tasks.submit(currentLoad) { result ->
            state = result.fold({ PreviewLoad.Ready(it) }, {
                AppLog.logger.log(Level.WARNING, "Preview ${host.pluginId}, request $requestKey failed", it)
                PreviewLoad.Failed(it.message ?: it.javaClass.simpleName)
            })
        }
        onDispose { job.close() }
    }
    return state
}
