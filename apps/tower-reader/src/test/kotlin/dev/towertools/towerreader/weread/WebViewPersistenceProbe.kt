package dev.towertools.towerreader.weread

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.net.httpserver.HttpServer
import java.awt.Canvas
import java.awt.EventQueue
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame

object WebViewPersistenceProbe {
    @JvmStatic fun main(args: Array<String>) {
        var frame: JFrame? = null
        var browser: WebViewBrowser? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var failure: Throwable? = null
        try {
            server.createContext("/") { exchange ->
                if (exchange.requestURI.path == "/set") exchange.responseHeaders.add("Set-Cookie", "readerTest=kept; Max-Age=86400; HttpOnly; Path=/; SameSite=Lax")
                val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty().contains("readerTest=kept")
                val html = "<html><body style='background:#f4eedf'><h1>持久化验证</h1><script>window.instance=Math.random().toString();window.hasCookie=$cookie;</script></body></html>"
                val bytes = html.toByteArray(); exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val base = "http://127.0.0.1:${server.address.port}"
            val parent = CompletableFuture<HWND>()
            EventQueue.invokeAndWait {
                val canvas = Canvas()
                frame = JFrame("静读 · 登录数据保存验证").apply { setSize(800,600); add(canvas); isVisible = true }
                parent.complete(HWND(Native.getComponentPointer(canvas)))
            }
            val current = AtomicReference<WebViewBrowser.BrowserState>()
            fun start(url: String): WebViewBrowser {
                val loaded = CompletableFuture<Unit>()
                return WebViewBrowser(parent.get(), Path.of(args[0]), url, { it.startsWith("$base/") }, {
                    current.set(it)
                    if (it.error != null) loaded.completeExceptionally(IllegalStateException(it.error))
                    else if (it.ready && !it.loading) loaded.complete(Unit)
                }, {}).also { loaded.get(15, TimeUnit.SECONDS) }
            }
            fun value(script: String) = browser!!.evaluate(script).get(5, TimeUnit.SECONDS)
            browser = start("$base/set")
            check(value("localStorage.setItem('reader-test','stored');localStorage.getItem('reader-test')") == "\"stored\"")
            val marker = value("window.instance")
            browser!!.setVisible(false); Thread.sleep(200); browser!!.setVisible(true); Thread.sleep(200)
            check(value("window.instance") == marker) { "Hiding recreated the page" }
            browser!!.close().get(15,TimeUnit.SECONDS); browser = null
            browser = start("$base/read")
            check(value("localStorage.getItem('reader-test')") == "\"stored\"") { "Local storage was lost after restart" }
            check(value("window.hasCookie") == "true") { "Persistent HTTP-only cookie was lost after restart" }
            browser!!.clearData().get(10,TimeUnit.SECONDS)
            browser!!.navigate("$base/cleared")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (current.get()?.url != "$base/cleared" || current.get()?.loading != false) {
                check(System.nanoTime() < deadline) { "Cleared page did not load" }; Thread.sleep(25)
            }
            check(value("localStorage.getItem('reader-test')") == "null") { "Clear did not remove local storage" }
            check(value("window.hasCookie") == "false") { "Clear did not remove cookies" }
            println("PERSISTENCE_PASS: same page on hide/show, cookies and storage survive restart, explicit clear removes both")
        } catch (error: Throwable) { failure = error; error.printStackTrace() }
        finally {
            runCatching { browser?.close()?.get(15, TimeUnit.SECONDS) }.onFailure { failure = it; it.printStackTrace() }
            EventQueue.invokeAndWait { frame?.dispose() }; server.stop(0)
        }
        kotlin.system.exitProcess(if (failure == null) 0 else 1)
    }
}
