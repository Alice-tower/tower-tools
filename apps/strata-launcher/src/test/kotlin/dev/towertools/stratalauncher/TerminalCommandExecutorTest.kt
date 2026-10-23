package dev.towertools.stratalauncher

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class TerminalCommandExecutorTest {
    @TempDir lateinit var temp: Path

    @Test fun cmdExecutionPassesExactlyThePreviewArgumentsIncludingQuotedValues() {
        val capture = temp.resolve("arguments.txt")
        Files.writeString(temp.resolve("strata.cmd"), "@echo off\r\n@> \"%STRATA_TEST_CAPTURE%\" echo(%*\r\n")
        for (key in listOf("test & only", "O'Brien | more^next<(x)>end")) {
            val config = LaunchConfig(dataDir = "D:\\models with spaces", keyMode = "literal", apiKey = key)
            val preview = CommandBuilder.build(config)
            val builder = TerminalCommandExecutor.processBuilder(preview)
            val args = builder.command().toMutableList()
            args[args.lastIndex] = args.last().replace("start \"Strata\" ", "start \"Strata\" /b /wait ").replace("/k ", "/c ")
            builder.command(args)
            builder.environment()["PATH"] = "$temp;${builder.environment()["PATH"].orEmpty()}"
            builder.environment()["STRATA_TEST_CAPTURE"] = capture.toString()
            val process = builder.start()
            process.outputStream.close()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            assertEquals(preview.command.removePrefix("strata "), Files.readString(capture).trim())
        }
    }

    @Test fun invalidPreviewCannotStartATerminal() {
        assertFailsWith<IllegalArgumentException> { TerminalCommandExecutor.executeModel(LaunchConfig(port = "invalid")) }
        assertFailsWith<IllegalArgumentException> { TerminalCommandExecutor.processBuilder(CommandResult()) }
    }
}
