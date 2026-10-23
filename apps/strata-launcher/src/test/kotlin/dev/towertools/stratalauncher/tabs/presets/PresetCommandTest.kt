package dev.towertools.stratalauncher.tabs.presets

import dev.towertools.stratalauncher.CommandBuilder
import dev.towertools.stratalauncher.CmdCommand
import dev.towertools.stratalauncher.PresetEntry
import dev.towertools.stratalauncher.TerminalCommandExecutor
import kotlin.test.*
import java.nio.file.Path
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.io.TempDir

class PresetCommandTest {
    @TempDir lateinit var temp: Path
    private fun selection(name: String = "test") = PresetCommandSelection().choosePreset(PresetEntry(temp.resolve("test.json"), name))
    @Test fun launchOnlyIncludesThePresetName() {
        val result = CommandBuilder.preset("test")
        assertEquals("strata launch --preset test", result.command)
        assertEquals(listOf("strata", "launch", "--preset", "test"), result.tokens)
        assertTrue(result.errors.isEmpty())
    }

    @Test fun namesAreQuotedAndNeverBecomeShellCommandsOrExtraFlags() {
        assertEquals("strata launch --preset \"阅读 & echo test\"", CommandBuilder.preset("阅读 & echo test").command)
        assertEquals("strata launch --preset \"O'Brien\"", CommandBuilder.preset("O'Brien").command)
        assertEquals("strata launch --preset=--stop", CommandBuilder.preset("--stop").command)
        listOf("", "bad\nname", "%PATH%", "!PATH!", "a\" & echo test").forEach {
            assertTrue(CommandBuilder.preset(it).command.isEmpty())
            assertTrue(CommandBuilder.preset(it).errors.isNotEmpty())
        }
    }

    @Test fun terminalLaunchUsesOnlyThePresetAndTheSelectedDirectory() {
        val builder = TerminalCommandExecutor.processBuilder(CmdCommand.withPresetDirectory(selection().result(), temp.toString()))
        assertTrue(builder.command().last().endsWith("/k strata launch --preset test"))
        assertEquals(temp.toAbsolutePath().normalize().toString(), builder.environment()["STRATA_LAUNCH_PRESET_DIR"])
        assertFalse(builder.command().last().contains("--tune"))
        assertFalse(builder.command().last().contains("--no-tui"))
        assertFalse(builder.command().last().contains("--open"))
        assertFailsWith<IllegalArgumentException> { TerminalCommandExecutor.processBuilder(CmdCommand.withPresetDirectory(selection("bad\" & echo injected").result(), temp.toString())) }
        assertFailsWith<IllegalArgumentException> { PresetCommandExecutor.execute(temp.resolve("missing"), selection()) }
    }

    @Test fun windowsShellPassesThePresetAsOneArgumentWithoutAddingFlags() {
        val capture = temp.resolve("arguments.txt")
        Files.writeString(temp.resolve("strata.cmd"), "@echo off\r\n@> \"%STRATA_TEST_CAPTURE%\" echo(%*\r\n")
        listOf("launch", "delete-preset").forEach { action ->
            listOf("test", "reading & copy|more^next<(x)>end").forEach { name ->
                val builder = TerminalCommandExecutor.processBuilder(CmdCommand.withPresetDirectory(selection(name).chooseAction(action).result(), temp.toString()))
                // Exercise the same two CMD parsing passes with a harmless stub, without opening a window.
                val arguments = builder.command().toMutableList()
                arguments[arguments.lastIndex] = arguments.last().replace("start \"Strata\" ", "start \"Strata\" /b /wait ")
                    .replace("/k ", "/c ")
                builder.command(arguments)
                builder.environment()["PATH"] = "$temp;${builder.environment()["PATH"].orEmpty()}"
                builder.environment()["STRATA_TEST_CAPTURE"] = capture.toString()
                val process = builder.start()
                process.outputStream.close()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS))
                assertEquals(0, process.exitValue())
                val flag = if (action == "launch") "--preset" else "--delete-preset"
                assertEquals("launch $flag ${CommandBuilder.quote(name)}", Files.readString(capture).trim())
            }
        }
    }
    @Test fun globalAndPresetOperationsAreSeparatedAndProduceExpectedCommands() {
        val selected = selection()
        val expected = mapOf("launch" to "strata launch --preset test", "stop" to "strata launch --stop --preset test",
            "show-key" to "strata launch --show-key --preset test", "delete-preset" to "strata launch --delete-preset test")
        expected.forEach { (action, command) ->
            val choice = selected.chooseAction(action)
            assertEquals(command, choice.result().command)
            assertTrue(TerminalCommandExecutor.processBuilder(CmdCommand.withPresetDirectory(choice.result(), temp.toString())).command().last().endsWith("/k $command"))
        }
        val global = selected.chooseGlobal()
        assertTrue(global.global)
        assertNull(global.preset)
        assertEquals("strata launch --status", global.result().command)
        assertEquals("strata launch --stop", global.chooseAction("stop").result().command)
        assertEquals("strata launch --list-presets", global.chooseAction("list-presets").result().command)
        assertFalse(global.result().command.contains("--output"))
        assertEquals("launch", global.chooseAction("stop").choosePreset(selected.preset!!).action)
    }

    @Test fun invalidScopeActionsCannotExecuteAndMissingPresetNeverBecomesGlobal() {
        val global = PresetCommandSelection()
        assertEquals("status", global.action)
        assertTrue(global.result().errors.isEmpty())
        assertFailsWith<IllegalArgumentException> { global.chooseAction("launch") }
        assertFailsWith<IllegalArgumentException> { global.chooseAction("delete-preset") }
        assertFailsWith<IllegalArgumentException> { selection().chooseAction("status") }
        assertTrue(global.copy(action = "show-key").result().command.isEmpty())
        assertTrue(selection().copy(action = "status").result().command.isEmpty())
        val missing = selection().resolve(emptyList())
        assertFalse(missing.global)
        assertTrue(missing.result().command.isEmpty())
        assertTrue(missing.chooseAction("stop").result().command.isEmpty())
        assertTrue(missing.chooseAction("delete-preset").result().command.isEmpty())
    }

    @Test fun executionRejectsChangedOrMissingTargetFilesBeforeOpeningTerminal() {
        listOf("launch", "delete-preset").forEach { action ->
            val missing = selection().copy(preset = PresetEntry(temp.resolve("missing.json"), "test"))
            assertFailsWith<IllegalArgumentException> { PresetCommandExecutor.execute(temp, missing.chooseAction(action)) }
            Files.writeString(temp.resolve("test.json"), """{"schema":"strata.launch/1","name":"renamed"}""")
            assertFailsWith<IllegalArgumentException> { PresetCommandExecutor.execute(temp, selection().chooseAction(action)) }
        }
    }

    @Test fun deletionQuotesOneNameAndDoesNotInheritLaunchFlags() {
        val result = selection("阅读 & test").chooseAction("delete-preset").result()
        assertEquals("strata launch --delete-preset \"阅读 & test\"", result.command)
        assertEquals(listOf("strata", "launch", "--delete-preset", "阅读 & test"), result.tokens)
        assertEquals("strata launch --delete-preset=--stop", CommandBuilder.deletePreset("--stop").command)
        listOf("", "bad\nname", "%PATH%", "!PATH!", "a\" & echo test").forEach {
            assertTrue(CommandBuilder.deletePreset(it).command.isEmpty())
            assertTrue(CommandBuilder.deletePreset(it).errors.isNotEmpty())
        }
    }

}
