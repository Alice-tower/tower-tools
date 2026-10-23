package dev.towertools.stratalauncher.tabs.presets

import dev.towertools.stratalauncher.PresetRepository
import dev.towertools.stratalauncher.CmdCommand
import dev.towertools.stratalauncher.TerminalCommandExecutor
import java.nio.file.Path

object PresetCommandExecutor {
    fun execute(directory: Path, selection: PresetCommandSelection) {
        val result = CmdCommand.withPresetDirectory(selection.result(), directory.toString())
        require(result.errors.isEmpty()) { result.errors.joinToString(" ") }
        if (selection.needsPreset) {
            val entry = requireNotNull(selection.preset)
            require(entry.path.toAbsolutePath().normalize().parent == directory.toAbsolutePath().normalize()) {
                "预设目录已改变，请刷新后重试。"
            }
            val currentName = PresetRepository.readName(entry.path)
            require(currentName == entry.name) { "预设名称已改变，请刷新后重试。" }
        }
        TerminalCommandExecutor.execute(result)
    }
}
