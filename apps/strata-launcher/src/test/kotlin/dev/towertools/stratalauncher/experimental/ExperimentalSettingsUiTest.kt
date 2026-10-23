package dev.towertools.stratalauncher.experimental

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.towertools.stratalauncher.*
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

class ExperimentalSettingsUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun globalFooterOpensDialogAndIsAbsentForIndividualPresets() {
        val state = StrataState()
        val entry = PresetEntry(Path.of("C:/presets/test.json"), "test")
        var opened by mutableStateOf(false)
        rule.setContent { MaterialTheme { Surface { Box(Modifier.size(800.dp, 660.dp)) {
            StrataApp(state, entries = listOf(entry), onOpenExperimental = { opened = true })
            if (opened) ExperimentalSettingsDialog(emptyList(), false, null, "", "", {}, { _, _ -> }, { opened = false })
        } } } }
        val footer = rule.onNodeWithTag("open-experimental-settings").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val container = rule.onNodeWithTag("preset-operations-column").fetchSemanticsNode().boundsInRoot
        assertTrue(container.bottom - footer.bottom < 10f)
        rule.onNodeWithTag("open-experimental-settings").performClick()
        rule.onNodeWithTag("experimental-dialog").assertIsDisplayed()
        rule.onNodeWithTag("base-configs-empty").assertIsDisplayed()
        rule.onNodeWithTag("close-experimental").performClick()
        rule.onNodeWithTag("experimental-dialog").assertDoesNotExist()
        rule.onNodeWithTag("preset-row-0").performClick()
        rule.onNodeWithTag("open-experimental-settings").assertDoesNotExist()
        rule.onNodeWithTag("open-preset-json").assertIsDisplayed()
    }

    @Test fun perConfigButtonsReflectStateAndInvalidRowsCannotBeChanged() {
        val entries = mutableStateListOf(
            EspConfigEntry(Path.of("C:/Strata/install/strata-unsloth-ud-iq4_xs.json"), "Qwen3.8-Flash-Next UD-IQ4_XS", enabled = true),
            EspConfigEntry(Path.of("C:/Strata/install/strata-qwen.json"), "Qwen3.8-Flash-Next IQ3_S"),
            EspConfigEntry(Path.of("C:/Strata/install/strata-broken.json"), "broken", error = "JSON 格式不正确。"),
        )
        val actions = mutableListOf<Pair<Path, Boolean>>()
        var busyPath by mutableStateOf<Path?>(null)
        rule.setContent { MaterialTheme { ExperimentalSettingsDialog(entries, false, busyPath, "", "", {}, { e, on ->
            actions += e.path to on
            entries[entries.indexOf(e)] = e.copy(enabled = on)
        }, {}) } }
        assertTrue(actions.isEmpty())
        rule.onNodeWithTag("toggle-esp-0").assertTextEquals("关闭 ESP").performClick()
        rule.onNodeWithTag("toggle-esp-0").assertTextEquals("启用 ESP")
        rule.onNodeWithTag("toggle-esp-1").performClick().assertTextEquals("关闭 ESP")
        rule.onNodeWithTag("toggle-esp-2").assertIsNotEnabled()
        assertEquals(listOf(entries[0].path to false, entries[1].path to true), actions)
        val image = File("../../outputs/verification/strata-launcher/experimental-settings.png").apply { parentFile.mkdirs() }
        ImageIO.write(rule.onNodeWithTag("experimental-dialog").captureToImage().toAwtImage(), "png", image)
        rule.runOnIdle { busyPath = entries[0].path }
        rule.onNodeWithTag("toggle-esp-1").assertIsNotEnabled()
        rule.onNodeWithTag("close-experimental").assertIsNotEnabled()
    }
}
