package dev.towertools.researchlibrarylauncher

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResearchLibraryControllerTest {
    @Test
    fun defaultScriptUsesTheExistingProjectEntryPoint() {
        assertEquals("start.cmd", ResearchLibraryController.DEFAULT_SCRIPT_PATH.fileName.toString())
        assertTrue(ResearchLibraryController.DEFAULT_SCRIPT_PATH.isAbsolute)
    }

    @Test
    fun launchCommandQuotesPathsWithSpaces() {
        val script = Paths.get("C:\\Example Folder\\start.cmd")
        val command = ResearchLibraryController.buildLaunchCommand(script)

        assertTrue(command.contains("/D \"C:\\Example Folder\""))
        assertTrue(command.endsWith("call \"C:\\Example Folder\\start.cmd\""))
    }
}
