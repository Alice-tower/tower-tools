package dev.towertools.launcher

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.SystemTray
import java.awt.Canvas
import java.awt.TrayIcon
import java.awt.event.MouseEvent
import java.awt.event.ActionEvent
import java.awt.event.WindowEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LauncherWindowTest {
    @Test
    fun windowCloseKeepsTrayAndStateUntilTrayExit() {
        assertTrue(SystemTray.isSupported(), "Windows desktop tray must be available")
        val tray = SystemTray.getSystemTray()
        val originalIcons = tray.trayIcons.toSet()
        val failure = AtomicReference<Throwable?>(null)
        val exit = AtomicReference<() -> Unit>({})
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        var starts = 0
        var disposals = 0
        var activate: (() -> Unit)? = null
        var registeredTray: TrayIcon? = null
        val timeout = watchdog.schedule({
            failure.compareAndSet(null, AssertionError("Launcher lifecycle check timed out"))
            EventQueue.invokeLater { exit.get().invoke() }
        }, 20, TimeUnit.SECONDS)
        try {
            application(exitProcessOnExit = false) {
                exit.set { exitApplication() }
                val state = rememberWindowState(
                    position = WindowPosition(-5000.dp, -5000.dp),
                    width = 400.dp,
                    height = 300.dp,
                )
                LauncherWindow(registerActivation = { activate = it }, state = state) {
                    DisposableEffect(Unit) {
                        starts++
                        onDispose { disposals++ }
                    }
                    LaunchedEffect(Unit) {
                        try {
                            withTimeout(10_000) {
                                while (tray.trayIcons.none { it !in originalIcons } || !window.isVisible) delay(20)
                                val icon = tray.trayIcons.single { it !in originalIcons }
                                registeredTray = icon
                                assertEquals(AppMetadata.displayName, icon.toolTip)
                                assertNull(icon.popupMenu, "Chinese text must use the launcher renderer")

                                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                                while (window.isVisible) delay(20)
                                assertTrue(window.isDisplayable)
                                assertTrue(icon in tray.trayIcons)
                                assertEquals(1, starts)
                                assertEquals(0, disposals)

                                // The same activation callback is used by a second launcher process.
                                checkNotNull(activate).invoke()
                                while (!window.isVisible) delay(20)
                                assertFalse(state.isMinimized)
                                assertEquals(1, starts)

                                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                                while (window.isVisible) delay(20)
                                icon.mouseListeners.forEach {
                                    it.mouseReleased(MouseEvent(Canvas(), MouseEvent.MOUSE_RELEASED,
                                        System.currentTimeMillis(), 0, 100, 100, 1, true, MouseEvent.BUTTON3))
                                }
                                while (activeTrayMenuWindow()?.let { trayMenuAction(it, "显示启动器") } == null) delay(20)
                                val popup = checkNotNull(activeTrayMenuWindow())
                                assertEquals(java.awt.Window.Type.POPUP, popup.type)
                                assertTrue(popup.isAlwaysOnTop)
                                assertTrue(checkNotNull(trayMenuAction(popup, "显示启动器")).invoke())
                                while (!window.isVisible) delay(20)
                                assertEquals(1, starts)

                                window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
                                while (window.isVisible) delay(20)
                                icon.actionListeners.forEach {
                                    it.actionPerformed(ActionEvent(icon, ActionEvent.ACTION_PERFORMED, "double-click"))
                                }
                                while (!window.isVisible) delay(20)
                                assertEquals(0, disposals)

                                icon.mouseListeners.forEach {
                                    it.mouseReleased(MouseEvent(Canvas(), MouseEvent.MOUSE_RELEASED,
                                        System.currentTimeMillis(), 0, 100, 100, 1, true, MouseEvent.BUTTON3))
                                }
                                while (activeTrayMenuWindow()?.let { trayMenuAction(it, "退出") } == null) delay(20)
                                assertTrue(checkNotNull(trayMenuAction(checkNotNull(activeTrayMenuWindow()), "退出")).invoke())
                            }
                        } catch (error: Throwable) {
                            failure.set(error)
                            exitApplication()
                        }
                    }
                }
            }
        } finally {
            timeout.cancel(false)
            watchdog.shutdownNow()
        }
        failure.get()?.let { throw it }
        assertEquals(1, disposals)
        assertFalse(registeredTray in tray.trayIcons)
        assertEquals(originalIcons, tray.trayIcons.toSet())
        assertNull(activeTrayMenuWindow())
    }
}
