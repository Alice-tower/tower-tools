package dev.towertools.stratalauncher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.towertools.stratalauncher.tabs.presets.PresetCommandSelection

/** Only retained for the current window; every new application session starts with defaults. */
data class SessionState(
    val model: LaunchConfig = LaunchConfig(),
    val presetCommand: PresetCommandSelection = PresetCommandSelection(),
    val activeTab: String = "presets",
    val presetDirectory: String = "",
)

class StrataState(initial: SessionState = SessionState()) {
    var session by mutableStateOf(initial)
        private set
    var message by mutableStateOf("")

    val config: LaunchConfig get() = session.model

    fun updateConfig(value: LaunchConfig) {
        session = session.copy(model = value)
        message = ""
    }

    fun selectTab(tab: String) {
        require(tab in listOf("presets", "model"))
        session = session.copy(activeTab = tab)
        message = ""
    }

    fun setPresetDirectory(value: String) {
        if (value != session.presetDirectory) session = session.copy(presetDirectory = value, presetCommand = session.presetCommand.copy(preset = null))
    }
    fun updatePresetCommand(value: PresetCommandSelection) { session = session.copy(presetCommand = value); message = "" }
    fun selectPreset(value: PresetEntry) {
        updatePresetCommand(session.presetCommand.choosePreset(value))
    }
    fun selectGlobal() { updatePresetCommand(session.presetCommand.chooseGlobal()) }

    fun result(entries: List<PresetEntry> = emptyList()): CommandResult = when (session.activeTab) {
        "presets" -> CmdCommand.withPresetDirectory(session.presetCommand.resolve(entries).result(), session.presetDirectory)
        else -> CommandBuilder.build(config)
    }
}
