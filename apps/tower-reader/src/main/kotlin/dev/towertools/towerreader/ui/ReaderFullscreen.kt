package dev.towertools.towerreader.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sun.jna.Native
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import java.awt.EventQueue
import java.awt.Window

/** Must run before creating any Compose window, including runtime verification. */
fun configureReaderRendering() {
    System.setProperty("compose.interop.blending", "true")
}

/** Fullscreen the containing HWND without recreating the embedded reading surface. */
class ReaderFullscreen(private val window: Window) {
    var active by mutableStateOf(false)
        private set
    private data class Saved(val style: Int, val placement: WinUser.WINDOWPLACEMENT)
    private var saved: Saved? = null
    private val user32 get() = User32.INSTANCE
    private fun handle() = HWND(Native.getComponentPointer(window))

    fun toggle() {
        check(EventQueue.isDispatchThread())
        if (active) exit() else enter()
    }

    private fun enter() {
        val hwnd = handle()
        val placement = WinUser.WINDOWPLACEMENT()
        check(user32.GetWindowPlacement(hwnd, placement).booleanValue()) { "无法读取窗口位置" }
        val monitor = WinUser.MONITORINFO()
        check(user32.GetMonitorInfo(user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor).booleanValue()) { "无法读取屏幕尺寸" }
        val previous = Saved(user32.GetWindowLong(hwnd, WinUser.GWL_STYLE), placement)
        saved = previous
        try {
            // Skiko's native fullscreen layer obscures the embedded reader on Windows.
            // Blended Swing rendering + a borderless top-level window keeps both visible.
            user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
            setStyle(hwnd, previous.style and (WinUser.WS_OVERLAPPEDWINDOW or WinUser.WS_MAXIMIZE).inv())
            val bounds = monitor.rcMonitor
            check(user32.SetWindowPos(hwnd, null, bounds.left, bounds.top,
                bounds.right - bounds.left, bounds.bottom - bounds.top,
                WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOOWNERZORDER or WinUser.SWP_NOZORDER)) { "无法进入全屏" }
            active = true
            window.validate()
            window.repaint()
        } catch (error: Exception) {
            runCatching { restore(hwnd, previous) }.onFailure(error::addSuppressed)
            saved = null
            throw error
        }
    }

    fun exit() {
        check(EventQueue.isDispatchThread())
        val previous = saved ?: return
        if (window.isDisplayable) restore(handle(), previous)
        saved = null
        active = false
    }

    private fun setStyle(hwnd: HWND, style: Int) {
        Native.setLastError(0)
        val previous = user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style)
        check(previous != 0 || Native.getLastError() == 0) { "无法调整窗口边框" }
    }

    private fun restore(hwnd: HWND, previous: Saved) {
        // Let SetWindowPlacement perform the maximize transition and notify AWT.
        setStyle(hwnd, previous.style and WinUser.WS_MAXIMIZE.inv())
        check(user32.SetWindowPlacement(hwnd, previous.placement).booleanValue()) { "无法恢复窗口位置" }
        check(user32.SetWindowPos(hwnd, null, 0, 0, 0, 0,
            WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE or
                WinUser.SWP_NOOWNERZORDER or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE)) { "无法恢复窗口边框" }
        window.validate()
        window.repaint()
    }
}
