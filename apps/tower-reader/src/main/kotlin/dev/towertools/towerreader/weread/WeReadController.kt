package dev.towertools.towerreader.weread

import androidx.compose.runtime.*
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.Executors

/** Small independent navigation state. Browser credentials stay in WebView2's own profile. */
internal class WeReadController(private val directory: Path) {
    var ready by mutableStateOf(false); private set
    var selected by mutableStateOf(false); private set
    var activated by mutableStateOf(false); private set
    var state by mutableStateOf(WebViewBrowser.BrowserState()); private set
    var clearing by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    var pane: WeReadPane? = null
    private var url = WeReadLocation.shelf
    private val storage = Executors.newSingleThreadExecutor { Thread(it, "weread-preferences").apply { isDaemon = true } }
    private var closed = false
    init {
        storage.execute {
            val prefs = runCatching {
                Properties().apply { val file = directory.resolve("navigation.properties"); if (Files.isRegularFile(file)) Files.newInputStream(file).use(::load) }
            }.getOrElse { Properties() }
            EventQueue.invokeLater {
                if (!closed) {
                    url = WeReadLocation.restore(prefs.getProperty("url", ""))
                    selected = prefs.getProperty("module") == "weread"
                    activated = selected; ready = true
                }
            }
        }
    }
    fun select(weread: Boolean) {
        if (!ready || closed || selected == weread) return
        selected = weread
        if (weread) activated = true
        save()
    }
    fun createPane(window: java.awt.Window, onShortcut: (Boolean) -> Unit): WeReadPane = WeReadPane(window, directory, { url }, { value ->
        state = value
        if (WeReadLocation.restorable(value.url) && value.url != url) { url = value.url; save() }
    }, onShortcut).also { pane = it }
    private fun save() {
        val destination = selected; val location = url
        storage.execute {
            runCatching {
                Files.createDirectories(directory)
                val temp = Files.createTempFile(directory, "navigation-", ".tmp")
                try {
                    Files.newOutputStream(temp).use { output -> Properties().apply {
                        setProperty("module", if (destination) "weread" else "local"); setProperty("url", location)
                    }.store(output, "TowerReader navigation") }
                    Files.move(temp, directory.resolve("navigation.properties"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } finally { Files.deleteIfExists(temp) }
            }.onFailure { EventQueue.invokeLater { if (!closed) message = "无法保存上次打开的页面：${it.message}" } }
        }
    }
    fun clearLogin() {
        if (clearing || closed) return
        clearing = true
        pane?.clearData()?.whenComplete { _, error -> EventQueue.invokeLater {
            if (!closed) {
                clearing = false
                if (error == null) { url = WeReadLocation.shelf; save(); pane?.shelf(); message = "登录数据已清除。" }
                else message = "清除失败：${error.message}"
            }
        } } ?: run { clearing = false }
    }
    fun close(onClosed: () -> Unit) {
        if (closed) return
        if (ready) save()
        closed = true
        pane?.showPage(false, false)
        val stopped = pane?.close()
        storage.execute {
            runCatching { stopped?.get(15, java.util.concurrent.TimeUnit.SECONDS) }
            EventQueue.invokeLater(onClosed)
        }
        storage.shutdown()
    }
}
