package dev.towertools.stratalauncher

import dev.towertools.stratalauncher.tabs.presets.PresetCommandSelection
import java.nio.file.Path
import kotlin.test.*

class StrataStateTest {
    @Test fun presetCommandsRemainIndependentFromModelParameters() {
        val entry = PresetEntry(Path.of("C:/presets/test.json"), "test")
        val state = StrataState()
        state.selectPreset(entry)
        state.selectTab("model")
        state.updateConfig(state.config.copy(context = "128k", port = "9090", tune = "quick"))
        state.selectTab("presets")
        assertEquals("strata launch --preset test", state.result(listOf(entry)).command)
        state.updatePresetCommand(state.session.presetCommand.chooseAction("stop"))
        assertEquals("strata launch --stop --preset test", state.result(listOf(entry)).command)
        state.selectTab("model")
        assertEquals("128k", state.config.context)
        assertContains(state.result().command, "--model")
    }

    @Test fun directoryChangesAndRemovedPresetsInvalidateSelection() {
        val entry = PresetEntry(Path.of("C:/presets/test.json"), "test")
        val state = StrataState()
        state.selectPreset(entry)
        assertTrue(state.result(emptyList()).command.isEmpty())
        assertTrue(state.result(listOf(entry.copy(error = "文件已损坏"))).command.isEmpty())
        state.setPresetDirectory("C:/other")
        assertNull(state.session.presetCommand.preset)
        assertTrue(state.result(listOf(entry)).command.isEmpty())
    }

    @Test fun reopeningRestoresDefaultsAndOldTabsCannotBeSelected() {
        val previous = StrataState()
        previous.updateConfig(previous.config.copy(apiKey = "session-only", port = "9090"))
        previous.setPresetDirectory("C:/presets")
        previous.updatePresetCommand(PresetCommandSelection(action = "stop"))
        val reopened = StrataState()
        assertEquals(SessionState(), reopened.session)
        assertEquals("", reopened.message)
        for (tab in listOf("preset", "manage")) assertFailsWith<IllegalArgumentException> { reopened.selectTab(tab) }
    }
}
