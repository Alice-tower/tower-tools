package dev.towertools.stratalauncher

import java.nio.file.Path

/** One command plan owns both the copied CMD text and the child process environment. */
object CmdCommand {
    internal const val LIMIT = 8191
    internal val executable: String
        get() = Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "cmd.exe").toString()

    internal fun terminalArgument(result: CommandResult) =
        "start \"Strata\" \"$executable\" /d /v:off /k ${result.invocation}"

    fun withPresetDirectory(result: CommandResult, directory: String): CommandResult {
        if (result.errors.isNotEmpty() || directory.isBlank()) return result
        return try {
            val path = Path.of(directory).toAbsolutePath().normalize().toString()
            CommandBuilder.quote(path) // Reject expansion, quotes and control characters in SET too.
            val environment = mapOf("STRATA_LAUNCH_PRESET_DIR" to path)
            validate(result.copy(
                command = "set \"STRATA_LAUNCH_PRESET_DIR=$path\" && ${result.invocation}",
                environment = environment,
            ))
        } catch (_: IllegalArgumentException) {
            result.copy(command = "", invocation = "", tokens = emptyList(),
                errors = listOf("预设目录不能用于 CMD：请检查路径，并移除双引号、百分号、感叹号或控制字符。"))
        }
    }

    fun validate(result: CommandResult): CommandResult {
        if (result.errors.isNotEmpty()) return result
        val wrapped = "\"$executable\" /d /v:off /s /c ${terminalArgument(result)}"
        return if (result.command.length <= LIMIT && wrapped.length <= LIMIT) result
        else result.copy(command = "", invocation = "", tokens = emptyList(),
            errors = listOf("完整命令超过 CMD 的 8191 字符限制（包含目录及终端包装），请缩短参数。"))
    }
}
