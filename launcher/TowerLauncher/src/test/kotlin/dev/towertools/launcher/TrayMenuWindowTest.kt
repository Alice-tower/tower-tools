package dev.towertools.launcher

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.application
import java.awt.Canvas
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.SystemTray
import java.awt.event.MouseEvent
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrayMenuWindowTest {
    @Test
    fun popupUsesLogicalPointerAndTargetMonitorInsteadOfPhysicalEventCoordinates() {
        val tray = SystemTray.getSystemTray()
        val originalIcons = tray.trayIcons.toSet()
        val configuration = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
        val bounds = configuration.bounds
        val anchor = TrayMenuAnchor(Point(bounds.x + bounds.width / 2, bounds.y + bounds.height - 2), configuration)
        val failure = AtomicReference<Throwable?>(null)
        val exit = AtomicReference<() -> Unit>({})
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val timeout = watchdog.schedule({
            failure.compareAndSet(null, AssertionError("Tray menu positioning check timed out"))
            EventQueue.invokeLater { exit.get().invoke() }
        }, 20, TimeUnit.SECONDS)
        var shown = 0
        try {
            application(exitProcessOnExit = false) {
                exit.set { exitApplication() }
                LauncherTray(onShow = { shown++ }, onExit = { exitApplication() }, readAnchor = { anchor })
                LaunchedEffect(Unit) {
                    try {
                        withTimeout(10_000) {
                            while (tray.trayIcons.none { it !in originalIcons }) delay(20)
                            val icon = tray.trayIcons.single { it !in originalIcons }
                            fun rightClick() {
                                icon.mouseListeners.forEach {
                                    // Deliberately different physical event coordinates: the pointer source must win.
                                    it.mouseReleased(MouseEvent(Canvas(), MouseEvent.MOUSE_RELEASED,
                                        System.currentTimeMillis(), 0, 30000, 30000, 1, true, MouseEvent.BUTTON3))
                                }
                            }
                            rightClick()
                            while (activeTrayMenuWindow()?.let { trayMenuAction(it, "显示启动器") } == null) delay(20)
                            val popup = checkNotNull(activeTrayMenuWindow())
                            val screenBounds = configuration.bounds
                            assertEquals(Dimension(TRAY_MENU_WIDTH, TRAY_MENU_HEIGHT), popup.size)
                            assertEquals(fitTrayMenuPosition(anchor.position, popup.size, screenBounds), popup.location)
                            assertEquals(configuration, popup.graphicsConfiguration)
                            assertTrue(screenBounds.contains(popup.bounds))
                            assertEquals(anchor.position.x + 2, popup.x)
                            assertEquals(anchor.position.y - 2, popup.y + popup.height)
                            assertTrue(checkNotNull(trayMenuAction(popup, "显示启动器")).invoke())
                            while (activeTrayMenuWindow() != null) delay(20)
                            assertEquals(1, shown)
                            rightClick()
                            while (activeTrayMenuWindow()?.let { trayMenuAction(it, "退出") } == null) delay(20)
                            assertTrue(checkNotNull(trayMenuAction(checkNotNull(activeTrayMenuWindow()), "退出")).invoke())
                        }
                    } catch (error: Throwable) {
                        failure.set(error)
                        exitApplication()
                    }
                }
            }
        } finally {
            timeout.cancel(false)
            watchdog.shutdownNow()
        }
        failure.get()?.let { throw it }
        assertEquals(originalIcons, tray.trayIcons.toSet())
        assertNull(activeTrayMenuWindow())
    }
}
