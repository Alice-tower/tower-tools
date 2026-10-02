@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.towertools.launcher

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrayMenuTest {
    @Test
    fun compactMenuRendersAndRoutesActionsAtCommonDisplayScalesInBothThemes() {
        val destination = Path.of("../../outputs/diagnostics/tray-menu").toAbsolutePath().normalize()
        Files.createDirectories(destination)
        listOf(1f, 1.25f, 1.5f, 2f).forEach { scale ->
            listOf(false, true).forEach { dark ->
                var shows = 0
                var exits = 0
                val scene = ImageComposeScene(
                    width = (TRAY_MENU_WIDTH * scale).roundToInt(),
                    height = (TRAY_MENU_HEIGHT * scale).roundToInt(),
                    density = Density(scale),
                ) {
                    MaterialTheme(colors = if (dark) darkColors() else lightColors()) {
                        TrayMenuContent(onShow = { shows++ }, onExit = { exits++ })
                    }
                }
                try {
                    val image = scene.render()
                    try {
                        val data = checkNotNull(image.encodeToData())
                        try {
                            val theme = if (dark) "dark" else "light"
                            Files.write(destination.resolve("$theme-${(scale * 100).roundToInt()}.png"), data.bytes)
                        } finally {
                            data.close()
                        }
                    } finally {
                        image.close()
                    }
                    listOf("显示启动器", "退出").forEach { label ->
                        val bounds = checkNotNull(trayMenuNode(scene.semanticsOwners, label)).boundsInRoot
                        // Fractional scaling can distribute one rounding pixel to the last row.
                        assertTrue(abs(bounds.height - TRAY_MENU_ROW_HEIGHT * scale) <= 1f)
                        assertEquals((TRAY_MENU_WIDTH * scale).roundToInt(), bounds.width.roundToInt())
                        assertTrue(bounds.bottom <= (TRAY_MENU_HEIGHT * scale).roundToInt())
                    }
                    assertTrue(checkNotNull(trayMenuAction(scene.semanticsOwners, "显示启动器")).invoke())
                    assertEquals(1, shows)
                    assertEquals(0, exits)
                    assertTrue(checkNotNull(trayMenuAction(scene.semanticsOwners, "退出")).invoke())
                    assertEquals(1, exits)
                } finally {
                    scene.close()
                }
            }
        }
    }

    @Test
    fun placementHugsPointerAndFlipsOnlyAtScreenEdges() {
        val size = Dimension(TRAY_MENU_WIDTH, TRAY_MENU_HEIGHT)
        val screen = Rectangle(0, 0, 1920, 1080)
        assertEquals(Point(102, 102), fitTrayMenuPosition(Point(100, 100), size, screen))
        assertEquals(Point(1760, 999), fitTrayMenuPosition(Point(1910, 1070), size, screen))
        assertEquals(Point(1760, 102), fitTrayMenuPosition(Point(1910, 100), size, screen))
        assertEquals(Point(2, 2), fitTrayMenuPosition(Point(0, 0), size, screen))
        assertEquals(Point(-160, 979),
            fitTrayMenuPosition(Point(-10, 1050), size, Rectangle(-1920, 0, 1920, 1080)))
        assertEquals(Point(-1920, 0),
            fitTrayMenuPosition(Point(-3000, -20), size, Rectangle(-1920, 0, 1920, 1080)))
        // A taskbar must not push the menu away from the click to the work-area boundary.
        val bottomTray = Point(100, 1060)
        val bottomMenu = fitTrayMenuPosition(bottomTray, size, screen)
        assertEquals(Point(102, 989), bottomMenu)
        assertEquals(bottomTray.y - 2, bottomMenu.y + size.height)
        assertTrue(bottomMenu.y + size.height > 1040)
        assertEquals(Point(602, 22), fitTrayMenuPosition(Point(600, 20), size, screen))
        assertEquals(Point(32, 502), fitTrayMenuPosition(Point(30, 500), size, screen))
        assertEquals(Point(1750, 502), fitTrayMenuPosition(Point(1900, 500), size, screen))
        assertEquals(Point(50, 40),
            fitTrayMenuPosition(Point(80, 60), size, Rectangle(50, 40, 100, 50)))
    }
}
