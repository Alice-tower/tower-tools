package dev.towertools.stratalauncher

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Opens CMD without writing commands or credentials to temporary scripts. */
object TerminalCommandExecutor {
    internal fun processBuilder(
        result: CommandResult,
    ): ProcessBuilder {
        require(CmdCommand.validate(result).errors.isEmpty() && result.command.isNotBlank()) { "请先修正参数，再执行命令。" }
        val cmd = CmdCommand.executable
        return ProcessBuilder(cmd, "/d", "/v:off", "/s", "/c", CmdCommand.terminalArgument(result))
            .directory(Path.of(System.getProperty("user.home")).toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                result.environment.forEach { (key, value) ->
                    environment().keys.filter { it.equals(key, true) }.toList().forEach { environment().remove(it) }
                    environment()[key] = value
                }
            }
    }

    fun executeModel(config: LaunchConfig) = execute(CommandBuilder.build(config))

    internal fun execute(result: CommandResult) {
        val process = processBuilder(result).start()
        process.outputStream.close()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            error("打开终端超时，请检查系统终端。")
        }
        check(process.exitValue() == 0) { "无法打开终端，请检查系统终端。" }
    }
}
