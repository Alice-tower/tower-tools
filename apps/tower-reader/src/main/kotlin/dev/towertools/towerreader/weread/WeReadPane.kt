package dev.towertools.towerreader.weread

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import java.awt.EventQueue
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** Native child window: Chromium content must not be painted into SwingPanel's offscreen buffer. */
internal class WeReadPane(
    private val window: Window,
    private val directory: Path,
    private val initialUrl: () -> String,
    private val onState: (WebViewBrowser.BrowserState) -> Unit,
    private val onShortcut: (Boolean) -> Unit,
) {
    private var browser: WebViewBrowser? = null
    private var disposed = false
    private var restarting = false
    private var generation = 0
    private var showingPage = true
    private var immersive = false
    private var bounds: Rectangle? = null
    private var closure = CompletableFuture.completedFuture(Unit)
    private val moves = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent?) { browser?.resize() }
        override fun componentResized(e: ComponentEvent?) { browser?.resize() }
    }
    init { window.addComponentListener(moves) }
    private fun start() {
        if (browser != null || disposed || restarting || !window.isDisplayable) return
        val token = ++generation
        browser = WebViewBrowser(HWND(Native.getComponentPointer(window)), directory, initialUrl(),
            onState = { if (!disposed && generation == token) onState(it) },
            onShortcut = { if (!disposed && showingPage && generation == token) onShortcut(it) })
        browser?.fullscreen = immersive
        bounds?.let { browser?.setBounds(it) }
        browser?.setVisible(showingPage)
    }
    fun setBounds(value: Rectangle) { bounds = value; if (browser == null) start() else browser?.setBounds(value) }
    fun showPage(value: Boolean, fullscreen: Boolean) {
        immersive = fullscreen; browser?.fullscreen = fullscreen
        showingPage = value; browser?.setVisible(value)
    }
    fun restart() {
        restarting = true
        val old = browser; browser = null; generation++
        onState(WebViewBrowser.BrowserState())
        closure = old?.close() ?: CompletableFuture.completedFuture(Unit)
        closure.whenComplete { _, _ -> EventQueue.invokeLater { restarting = false; if (!disposed) start() } }
    }
    fun back() { browser?.back() }
    fun forward() { browser?.forward() }
    fun reload() { browser?.reload() }
    fun shelf() { browser?.navigate(WeReadLocation.shelf) }
    fun focusPage() { browser?.focus() }
    fun clearData(): CompletableFuture<Unit> = browser?.clearData()
        ?: CompletableFuture.failedFuture(IllegalStateException("网页尚未就绪"))
    fun evaluate(script: String): CompletableFuture<String> = browser?.evaluate(script)
        ?: CompletableFuture.failedFuture(IllegalStateException("网页尚未就绪"))
    fun close(): CompletableFuture<Unit> {
        if (!disposed) {
            disposed = true; generation++; window.removeComponentListener(moves)
            closure = browser?.close() ?: closure; browser = null
        }
        return closure
    }
}
