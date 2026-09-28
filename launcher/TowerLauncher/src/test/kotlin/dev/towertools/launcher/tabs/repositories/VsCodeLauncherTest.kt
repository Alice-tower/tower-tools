package dev.towertools.launcher.tabs.repositories

import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VsCodeLauncherTest {
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun findsPortableInstallThroughCodeCommandOnPath() {
        val root = createTempDirectory("vs-code-launcher-")
        try {
            val install = Files.createDirectory(root.resolve("VS Code Custom"))
            val bin = Files.createDirectory(install.resolve("bin"))
            Files.createFile(bin.resolve("code.cmd"))
            val executable = Files.createFile(install.resolve("Code.exe"))

            assertEquals(executable, findVsCodeExecutable(
                pathValue = root.resolve("missing").toString() + ";" + bin,
                localAppData = null,
                programFiles = null,
                programFilesX86 = null,
            ))
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun findsStandardInstallAndIgnoresMissingExecutable() {
        val root = createTempDirectory("vs-code-launcher-")
        try {
            val install = root.resolve("Programs/Microsoft VS Code")
            Files.createDirectories(install)
            val executable = Files.createFile(install.resolve("Code.exe"))
            assertEquals(executable, findVsCodeExecutable("", root.toString(), null, null))

            Files.delete(executable)
            assertNull(findVsCodeExecutable("", root.toString(), null, null))
        } finally {
            root.deleteRecursively()
        }
    }
}
