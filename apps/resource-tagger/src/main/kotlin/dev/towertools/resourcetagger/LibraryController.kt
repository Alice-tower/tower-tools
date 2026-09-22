package dev.towertools.resourcetagger

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.EventQueue
import java.util.concurrent.Executors

class LibraryController(private val databasePath: java.nio.file.Path = AppPaths.dataDirectory.resolve("library.sqlite")) : AutoCloseable {
    var data by mutableStateOf(Snapshot()); private set
    var busy by mutableStateOf<String?>("正在打开资料库…"); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf("添加 Root，开始组织本地资源。"); private set
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "resource-library").apply { isDaemon = true } }
    private var library: Library? = null
    init {
        executor.submit {
            try {
                library = Library(Database(databasePath))
                val initial = library!!.snapshot()
                EventQueue.invokeLater { data = initial; busy = null }
            } catch (e: Exception) { failure(e) }
        }
    }
    fun clearError() { error = null }
    fun submit(label: String, onSuccess: () -> Unit = {}, action: (Library) -> Unit) {
        if (busy != null) return
        busy = label; error = null
        executor.submit {
            try {
                val current = library ?: error("资料库未能打开，请检查错误并重启。")
                action(current)
                val refreshed = current.snapshot()
                EventQueue.invokeLater { data = refreshed; busy = null; message = "$label · 完成"; onSuccess() }
            } catch (e: Exception) {
                val refreshed = runCatching { library?.snapshot() }.getOrNull()
                EventQueue.invokeLater { refreshed?.let { data = it } }
                failure(e)
            }
        }
    }
    private fun failure(e: Exception) {
        AppLog.logger.log(java.util.logging.Level.WARNING, "Library operation failed", e)
        EventQueue.invokeLater { error = e.message ?: e.javaClass.simpleName; busy = null }
    }
    override fun close() { executor.submit { library?.close() }; executor.shutdown() }
}
