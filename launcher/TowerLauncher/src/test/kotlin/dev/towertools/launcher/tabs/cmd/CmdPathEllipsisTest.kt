package dev.towertools.launcher.tabs.cmd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CmdPathEllipsisTest {
    @Test
    fun longPathKeepsDriveAndFilenameEnd() {
        val path = "C:\\All\\Dev\\Repo\\codex-doc\\AA-自用脚本\\启动DeepSeek Handler.cmd"
        val visible = middleEllipsizePath(path, 24, String::length)

        assertTrue(visible.startsWith("C:\\All"))
        assertTrue(visible.endsWith("Handler.cmd"))
        assertTrue("…" in visible)
        assertTrue(visible.length <= 24)
    }

    @Test
    fun fittingPathIsUnchangedAndVeryNarrowWidthHasOnlyEllipsis() {
        val path = "C:\\start.cmd"
        assertEquals(path, middleEllipsizePath(path, path.length, String::length))
        assertEquals("…", middleEllipsizePath(path, 1, String::length))
        assertEquals("", middleEllipsizePath(path, 0, String::length))
    }

    @Test
    fun unicodeCodePointsAreNotSplit() {
        val path = "C:\\😀😀😀\\very-long-folder\\launch.cmd"
        val visible = middleEllipsizePath(path, 23) { it.codePointCount(0, it.length) }
        assertTrue(visible.endsWith("launch.cmd"))
        assertTrue(visible.indices.all { index ->
            !Character.isHighSurrogate(visible[index]) ||
                index + 1 < visible.length && Character.isLowSurrogate(visible[index + 1])
        })
        assertTrue(visible.indices.all { index ->
            !Character.isLowSurrogate(visible[index]) ||
                index > 0 && Character.isHighSurrogate(visible[index - 1])
        })
    }
}
