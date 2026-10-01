package dev.towertools.towerreader.weread

import com.sun.jna.*
import com.sun.jna.Function
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinDef.*
import com.sun.jna.ptr.PointerByReference
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/** All WebView2 COM calls and its message pump live on one dedicated STA thread. */
internal class WebViewBrowser(
    private val parent: HWND,
    private val dataDirectory: Path,
    private val initialUrl: String,
    private val navigationAllowed: (String) -> Boolean = WeReadLocation::allowed,
    private val onState: (BrowserState) -> Unit,
    private val onShortcut: (Boolean) -> Unit,
) {
    data class BrowserState(val ready: Boolean = false, val loading: Boolean = true,
        val url: String = "", val back: Boolean = false, val forward: Boolean = false, val error: String? = null)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()
    private val callbacks = mutableListOf<ComCallback>()
    private var host: HWND? = null
    private var parentThread = 0
    private var inputAttached = false
    @Volatile private var hostBounds: java.awt.Rectangle? = null
    private var controller: Com? = null
    private var web: Com? = null
    private var environment: Com? = null
    private var state = BrowserState()
    @Volatile private var threadId = 0
    @Volatile private var visible = true
    @Volatile var fullscreen = false
    private val closing = AtomicBoolean(false)
    private val terminated = CompletableFuture<Unit>()
    private var initializing = true
    private var finished = false
    private var browserProcessId = 0L
    private var pendingClear: CompletableFuture<Unit>? = null
    private val messageId = 0x8000 + 48
    init { Thread(::run, "weread-webview-sta").apply { isDaemon = true; start() } }

    private fun run() {
        var comStarted = false
        try {
            checkHr(Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED).toInt()); comStarted = true
            val message = WinUser.MSG()
            User32.INSTANCE.PeekMessage(message, null, 0, 0, 0)
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            parentThread = User32.INSTANCE.GetWindowThreadProcessId(parent, null)
            inputAttached = User32.INSTANCE.AttachThreadInput(DWORD(threadId.toLong()), DWORD(parentThread.toLong()), true)
            host = checkNotNull(User32.INSTANCE.CreateWindowEx(0, "STATIC", "", WinUser.WS_CHILD or WinUser.WS_CLIPCHILDREN or WinUser.WS_CLIPSIBLINGS,
                0, 0, 1, 1, parent, null, null, null)) { "无法创建网页显示区域" }
            val library = WebViewLoader.load(dataDirectory.resolve("cache"))
            Files.createDirectories(dataDirectory.resolve("profile"))
            val completed = completion("4e8a3389-c9d8-4bd2-b6b5-124fee6cc14d") { result, value ->
                checkHr(result)
                if (closing.get()) { initializing = false; shutdown(); return@completion }
                environment = Com(checkNotNull(value)).retain()
                val created = completion("6c4819f3-c9b7-4260-8127-c9f5bde7f68c") { hr, browser ->
                    checkHr(hr)
                    initializing = false
                    controller = Com(checkNotNull(browser)).retain()
                    if (closing.get()) { shutdown(); return@completion }
                    initialize()
                }
                environment!!.call(3, host, created.pointer)
            }
            val create = library.getFunction("CreateCoreWebView2EnvironmentWithOptions", Function.ALT_CONVENTION)
            val result = create.invokeInt(arrayOf(null, WString(dataDirectory.resolve("profile").toAbsolutePath().toString()), null, completed.pointer))
            if (result < 0) throw IllegalStateException("无法启动网页组件。请确认已安装 Microsoft Edge WebView2 Runtime。错误码：0x${Integer.toHexString(result)}")
            drain()
            while (!finished) {
                val status = User32.INSTANCE.GetMessage(message, null, 0, 0)
                if (status <= 0) break
                if (message.message == messageId) drain()
                else { User32.INSTANCE.TranslateMessage(message); User32.INSTANCE.DispatchMessage(message) }
            }
        } catch (error: Throwable) { fail(error) }
        finally {
            runCatching { controller?.call(24) }
            listOf(web, controller, environment).forEach { runCatching { it?.release() } }
            web = null; controller = null; environment = null
            host?.let { User32.INSTANCE.DestroyWindow(it) }; host = null
            // Native code can no longer call these after closing the controller and COM apartment.
            if (inputAttached) User32.INSTANCE.AttachThreadInput(DWORD(threadId.toLong()), DWORD(parentThread.toLong()), false)
            if (comStarted) Ole32.INSTANCE.CoUninitialize()
            callbacks.clear(); threadId = 0
            // Closing is asynchronous in WebView2. Wait for this profile's browser to release its files.
            if (browserProcessId != 0L) runCatching {
                ProcessHandle.of(browserProcessId).orElse(null)?.onExit()?.get(10, java.util.concurrent.TimeUnit.SECONDS)
            }
            terminated.complete(Unit)
        }
    }
    private fun initialize() {
        val control = checkNotNull(controller)
        val view = control.objectAt(25); web = view
        browserProcessId = view.number(37).toLong()
        val settings = view.objectAt(3)
        try { settings.call(6, 0); settings.call(16, 0); settings.call(10, 0) } finally { settings.release() }
        event(view, 7, "9adbe429-f36d-432b-9ddc-f8881fbd76e3") { _, args ->
            val url = args.text(3)
            if (!navigationAllowed(url) && !(url == "about:blank" && pendingClear != null)) { args.call(8, 1); publish(state.copy(loading = false, error = "此链接不属于微信读书或微信登录页面，已停止打开。")) }
            else publish(state.copy(loading = true, error = null))
        }
        event(view, 15, "d33a35bf-1c49-4f98-93ab-006e0533fe1c") { _, args ->
            val success = args.number(3) != 0
            val code = args.number(4)
            publish(state.copy(loading = false, error = if (success || code == 14) null else "网页未能加载，请检查网络后点击刷新。（$code）"))
            updateLocation()
            if (!visible) suspendHidden()
            if (pendingClear != null && view.text(4) == "about:blank") {
                if (success) performClear() else { pendingClear?.completeExceptionally(IllegalStateException("无法准备清除登录数据")); pendingClear = null }
            }
        }
        event(view, 11, "3c067f9f-5388-4772-8b48-79f7ef1ab37c") { _, _ -> updateLocation() }
        event(view, 13, "c79a420c-efd9-4058-9295-3e8b4bcab645") { _, _ -> updateLocation() }
        event(view, 44, "d4c185fe-c81c-4989-97af-2d3fa7ab5651") { _, args ->
            val url = args.text(3); args.call(6, 1)
            if (navigationAllowed(url)) navigate(url)
            else publish(state.copy(error = "此链接不属于微信读书，已停止打开。"))
        }
        event(view, 23, "15e1c6a3-c72a-4df3-91d7-d097fbec6bfd") { _, args -> args.call(7, 2) }
        event(view, 25, "79e0aea4-990b-42d9-aa1d-0fcc2e5bc7f1") { _, _ ->
            publish(state.copy(loading = false, error = "网页进程已停止，请点击重新打开。", ready = false))
        }
        event(control, 19, "b29c7e28-fa79-41a8-8e44-65811c76dcb2") { _, args ->
            val key = args.number(4); val kind = args.number(3); val repeat = args.number(5) and (1 shl 30) != 0
            if (key == 122 || (key == 27 && fullscreen)) {
                args.call(8, 1)
                if ((kind == 0 || kind == 2) && !repeat) {
                    if (fullscreen) evaluate("if(document.fullscreenElement) document.exitFullscreen();")
                    EventQueue.invokeLater { if (!closing.get()) onShortcut(key == 122) }
                }
            }
        }
        event(view, 52, "e45d98b1-afef-45be-8baf-6c7728867f73") { _, _ ->
            if (view.number(54) != 0 && !fullscreen) EventQueue.invokeLater { onShortcut(true) }
        }
        resizeNow()
        control.call(4, if (visible) 1 else 0)
        host?.let { User32.INSTANCE.ShowWindow(it, if (visible) WinUser.SW_SHOWNA else WinUser.SW_HIDE) }
        publish(state.copy(ready = true))
        view.call(5, WString(initialUrl))
    }
    private fun updateLocation() {
        val view = web ?: return
        publish(state.copy(url = view.text(4), back = view.number(38) != 0, forward = view.number(39) != 0))
    }
    private fun publish(value: BrowserState) { state = value; EventQueue.invokeLater { if (!closing.get()) onState(value) } }
    private fun fail(error: Throwable) { publish(state.copy(loading = false, ready = false, error = error.message ?: "网页组件发生错误。")) }
    private fun completion(iid: String, body: (Int, Pointer?) -> Unit): ComCallback = ComCallback(iid, ComCompletion { _, result, value ->
        try { body(result, value) } catch (error: Throwable) { initializing = false; fail(error); if (closing.get()) shutdown() }
        0
    }).also(callbacks::add)
    private fun event(target: Com, slot: Int, iid: String, body: (Com, Com) -> Unit) {
        val callback = ComCallback(iid, ComEvent { _, sender, args ->
            // Notification-only events (such as HistoryChanged) have null event arguments.
            try { if (!closing.get()) body(Com(sender), Com(args ?: sender)) } catch (error: Throwable) { fail(error) }
            0
        }).also(callbacks::add)
        target.call(slot, callback.pointer, Memory(8))
    }
    private fun submit(body: () -> Unit) {
        if (closing.get()) return
        commands.add(body)
        if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, messageId, WPARAM(0), LPARAM(0))
    }
    private fun drain() { while (true) { val command = commands.poll() ?: break; try { command() } catch (error: Throwable) { fail(error) } } }
    fun navigate(url: String) = submit { if (navigationAllowed(url)) web?.call(5, WString(url)) }
    fun back() = submit { web?.takeIf { it.number(38) != 0 }?.call(40) }
    fun forward() = submit { web?.takeIf { it.number(39) != 0 }?.call(41) }
    fun reload() = submit { web?.call(31) }
    fun focus() = submit { controller?.call(12, 0) }
    fun resize() = submit { resizeNow() }
    fun setBounds(bounds: java.awt.Rectangle) { if (hostBounds != bounds) { hostBounds = bounds; resize() } }
    private fun resizeNow() {
        val owner = host ?: return
        val client = RECT()
        if (User32.INSTANCE.GetClientRect(parent, client)) {
            val bounds = hostBounds ?: java.awt.Rectangle(0, 0, client.right, client.bottom)
            User32.INSTANCE.SetWindowPos(owner, HWND(Pointer(0)), bounds.x, bounds.y,
                bounds.width.coerceAtLeast(1), bounds.height.coerceAtLeast(1), WinUser.SWP_NOACTIVATE)
            controller?.call(6, WebRect().apply { right = bounds.width.coerceAtLeast(1); bottom = bounds.height.coerceAtLeast(1) })
            controller?.call(23)
        }
    }
    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        submit {
            controller?.call(4, if (value) 1 else 0)
            host?.let { User32.INSTANCE.ShowWindow(it, if (value) WinUser.SW_SHOWNA else WinUser.SW_HIDE) }
            if (value) {
                runCatching { web?.query("A0D6DF20-3B92-416D-AA0C-437A9C727857")?.let { view -> try { view.call(69) } finally { view.release() } } }
                resizeNow()
            } else suspendHidden()
        }
    }
    private var suspendCallback: ComCallback? = null
    private fun suspendHidden() {
        if (visible || state.loading || pendingClear != null) return
        runCatching {
            val view = web?.query("A0D6DF20-3B92-416D-AA0C-437A9C727857") ?: return
            val callback = suspendCallback ?: ComCallback("00f206a7-9d17-4605-91f6-4e8e4de192e3", ComSuspend { _, _, _ -> 0 })
                .also { callbacks.add(it); suspendCallback = it }
            try { view.call(68, callback.pointer) } finally { view.release() }
        }
    }
    fun evaluate(script: String): CompletableFuture<String> {
        val result = CompletableFuture<String>()
        submit {
            val callback = completion("49511172-cc67-4bca-9923-137112f4c4cc") { hr, value ->
                if (hr < 0) result.completeExceptionally(IllegalStateException("Script failed"))
                else result.complete(value?.getWideString(0).orEmpty())
            }
            web?.call(29, WString(script), callback.pointer) ?: result.completeExceptionally(IllegalStateException("WebView is not ready"))
        }
        return result
    }
    fun clearData(): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        if (closing.get()) return CompletableFuture.failedFuture(IllegalStateException("网页已关闭"))
        submit {
            try {
                check(pendingClear == null) { "正在清除登录数据" }
                val view = checkNotNull(web) { "网页尚未就绪" }
                pendingClear = result
                // Unload the site before clearing; its scripts must not recreate cookies mid-clear.
                view.call(5, WString("about:blank"))
            } catch (error: Throwable) { pendingClear = null; result.completeExceptionally(error) }
        }
        return result
    }
    private fun performClear() {
        val result = pendingClear ?: return
        try {
            val view = checkNotNull(web).query("f75f09a8-667e-4983-88d6-c8773f315e84")
            val profile = try { view.objectAt(105) } finally { view.release() }
            val data = try { profile.query("fa740d4b-5eae-4344-a8ad-74be31925397") } finally { profile.release() }
            val callback = ComCallback("e9710a06-1d1d-49b2-8234-226f35846ae5", ComFinished { _, hr ->
                pendingClear = null
                if (hr < 0) result.completeExceptionally(IllegalStateException("无法清除登录数据")) else result.complete(Unit)
                0
            }).also(callbacks::add)
            try { data.call(12, callback.pointer) } finally { data.release() }
        } catch (error: Throwable) { pendingClear = null; result.completeExceptionally(error) }
    }
    fun close(): CompletableFuture<Unit> {
        if (closing.compareAndSet(false, true)) {
            commands.add { if (!initializing) shutdown() }
            if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, messageId, WPARAM(0), LPARAM(0))
        }
        return terminated
    }
    private fun shutdown() { finished = true; User32.INSTANCE.PostQuitMessage(0) }
}
