package dev.towertools.stratalauncher.tabs.presets

import dev.towertools.stratalauncher.CommandBuilder
import dev.towertools.stratalauncher.CommandResult
import dev.towertools.stratalauncher.ManagementConfig
import dev.towertools.stratalauncher.PresetEntry

enum class PresetCommandScope { GLOBAL, PRESET }

data class PresetCommandSelection(
    val scope: PresetCommandScope = PresetCommandScope.GLOBAL,
    val preset: PresetEntry? = null,
    val action: String = "status",
) {
    val global: Boolean get() = scope == PresetCommandScope.GLOBAL
    val needsPreset: Boolean get() = !global
    val actions: List<Pair<String, String>> get() = if (global) listOf(
        "status" to "查看状态", "list-presets" to "列出预设", "stop" to "停止全部服务",
    ) else listOf("launch" to "启动预设", "stop" to "停止该预设服务", "show-key" to "查看密钥",
        "delete-preset" to "删除预设")

    fun chooseGlobal(): PresetCommandSelection = if (global) this else PresetCommandSelection()

    fun choosePreset(value: PresetEntry): PresetCommandSelection {
        return copy(scope = PresetCommandScope.PRESET, preset = value, action = if (global) "launch" else action)
    }

    fun chooseAction(value: String): PresetCommandSelection {
        require(actions.any { it.first == value })
        return copy(action = value)
    }

    fun resolve(entries: List<PresetEntry>): PresetCommandSelection = copy(
        preset = preset?.let { selected -> entries.firstOrNull { it.path == selected.path } },
    )

    fun result(): CommandResult {
        if (actions.none { it.first == action }) return CommandResult(errors = listOf("请选择当前分类下的操作。"))
        if (needsPreset && (preset == null || preset.error != null)) return CommandResult(errors = listOf("请在左侧选择一个有效预设。"))
        return when (action) {
            "launch" -> CommandBuilder.preset(preset!!.name)
            "delete-preset" -> CommandBuilder.deletePreset(preset!!.name)
            else -> CommandBuilder.management(ManagementConfig(
                action = action, scope = if (global) "all" else "preset", preset = if (global) "" else preset?.name.orEmpty(),
            ))
        }
    }
}
