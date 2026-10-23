package dev.towertools.stratalauncher

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class CmdCommandTest {
    @TempDir lateinit var temp: Path

    @Test fun copiedAndExecutedCommandsUseTheSameDirectoryDespiteInheritedDefault() {
        val selected = temp.resolve("space & caret^ (test)")
        Files.writeString(temp.resolve("strata.cmd"), "@echo off\r\n@set STRATA_LAUNCH_PRESET_DIR\r\n@echo(%*\r\n")
        for (base in listOf(CommandBuilder.preset("test"), CommandBuilder.deletePreset("test"),
            CommandBuilder.management(ManagementConfig(action = "list-presets", scope = "all")))) {
            val plan = CmdCommand.withPresetDirectory(base, selected.toString())
            val execute = TerminalCommandExecutor.processBuilder(plan)
            val args = execute.command().toMutableList()
            args[args.lastIndex] = args.last().replace("start \"Strata\" ", "start \"Strata\" /b /wait ").replace("/k ", "/c ")
            execute.command(args)
            val copied = ProcessBuilder(CmdCommand.executable, "/d", "/v:off", "/c", plan.command)
            copied.environment()["STRATA_LAUNCH_PRESET_DIR"] = temp.resolve("wrong-default").toString()
            val outputs = listOf(execute, copied).mapIndexed { index, builder ->
                val capture = temp.resolve("capture-$index.txt")
                builder.environment()["PATH"] = "$temp;${builder.environment()["PATH"].orEmpty()}"
                builder.redirectOutput(capture.toFile()).redirectErrorStream(true)
                val process = builder.start()
                process.outputStream.close()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                Files.readString(capture).trim()
            }
            assertEquals(outputs[0], outputs[1])
            assertContains(outputs[0], "STRATA_LAUNCH_PRESET_DIR=$selected")
            assertContains(outputs[0], base.command.removePrefix("strata "))
        }
    }

    @Test fun expandedDirectoryAndCompleteCommandLengthsAreValidated() {
        assertTrue(CommandBuilder.preset("x".repeat(8192)).errors.isNotEmpty())
        assertTrue(CommandBuilder.build(LaunchConfig(model = "x".repeat(4200), dataDir = "y".repeat(4200))).errors.isNotEmpty())
        val overhead = "\"${CmdCommand.executable}\" /d /v:off /s /c ${CmdCommand.terminalArgument(CommandResult())}".length
        val limit = CmdCommand.LIMIT - overhead
        assertTrue(CmdCommand.validate(CommandResult("x".repeat(limit))).errors.isEmpty())
        assertTrue(CmdCommand.validate(CommandResult("x".repeat(limit + 1))).errors.isNotEmpty())
        val base = CommandBuilder.preset("x".repeat(7900))
        assertTrue(base.errors.isEmpty())
        assertTrue(CmdCommand.withPresetDirectory(base, "C:/" + "d".repeat(400)).errors.isNotEmpty())
        for (path in listOf("C:/%PATH%", "C:/!name!", "C:/bad\npath", "C:/bad\"path")) {
            assertTrue(CmdCommand.withPresetDirectory(CommandBuilder.preset("test"), path).errors.isNotEmpty())
        }
    }
}
