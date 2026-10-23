package dev.towertools.stratalauncher.tabs.presets

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.mutableStateListOf
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

class PresetListTabTest {
    @get:Rule val rule = createComposeRule()
    private val state = StrataState()
    private val entries = mutableStateListOf(
        PresetEntry(Path.of("C:/presets/test.json"), "test"),
        PresetEntry(Path.of("C:/presets/reading.json"), "阅读 128K"),
        PresetEntry(Path.of("C:/presets/broken.json"), "broken.json", "JSON 格式不正确。"),
    )
    private val copied = mutableListOf<String>()
    private val executed = mutableListOf<PresetCommandSelection>()
    private val opened = mutableListOf<PresetEntry>()

    private fun open(loading: Boolean = false, busy: Boolean = false) {
        rule.setContent {
            MaterialTheme { Surface { Box(Modifier.size(800.dp, 660.dp)) {
                StrataApp(state, entries, presetsLoading = loading, actionBusy = busy,
                    onCopy = { copied += it }, onExecutePresetCommand = { executed += it },
                    onOpenPresetJson = { opened += it })
            } } }
        }
    }

    private fun screenshot(tag: String, name: String) {
        val file = File("../../outputs/verification/strata-launcher/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(rule.onNodeWithTag(tag).captureToImage().toAwtImage(), "png", file)
    }

    @Test fun customDirectoryIsIncludedInPreviewAndClipboard() {
        state.setPresetDirectory("C:/custom presets")
        open()
        rule.onNodeWithTag("preset-row-0").performClick()
        rule.onNodeWithTag("preset-action-delete-preset").performScrollTo().performClick()
        val expected = "set \"STRATA_LAUNCH_PRESET_DIR=C:\\custom presets\" && strata launch --delete-preset test"
        rule.onNodeWithTag("command-preview").assertTextEquals(expected)
        rule.onNodeWithTag("copy-command").performClick()
        assertEquals(listOf(expected), copied)
        rule.onNodeWithTag("execute-command").performClick()
        assertTrue(executed.isEmpty())
        rule.onNodeWithTag("confirm-delete-preset").performClick()
        assertEquals(expected, CmdCommand.withPresetDirectory(executed.single().result(), state.session.presetDirectory).command)
    }

    @Test fun selectionOnlyUpdatesPreviewAndButtonsCopyOrExecuteExactlyThatCommand() {
        open()
        rule.onNodeWithTag("tab-presets").assertIsSelected()
        rule.onNodeWithTag("tab-manage").assertDoesNotExist()
        assertTrue(rule.onNodeWithTag("preset-list-column").fetchSemanticsNode().boundsInRoot.right <
            rule.onNodeWithTag("preset-operations-column").fetchSemanticsNode().boundsInRoot.left)
        rule.onNodeWithTag("global-operations").assertIsSelected()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --status")
        rule.onNodeWithTag("copy-command").assertIsEnabled()
        rule.onNodeWithTag("execute-command").assertIsEnabled()
        screenshot("strata-host", "global-operations")
        rule.onNodeWithTag("preset-row-0").performClick().assertIsSelected()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --preset test")
        rule.onNodeWithTag("preset-row-1").performClick().assertIsSelected()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --preset \"阅读 128K\"")
        assertTrue(copied.isEmpty())
        assertTrue(executed.isEmpty())
        rule.onNodeWithTag("copy-command").assertIsDisplayed().performClick()
        rule.onNodeWithTag("execute-command").assertIsDisplayed().performClick()
        assertEquals(listOf("strata launch --preset \"阅读 128K\""), copied)
        assertEquals(copied.single(), executed.single().result().command)
        screenshot("strata-host", "presets-list")
    }

    @Test fun deletionPreviewsAndCopiesBeforeConfirmationAndUsesTheNativeCommand() {
        open()
        rule.onNodeWithTag("open-preset-json").assertDoesNotExist()
        rule.onNodeWithTag("preset-action-delete-preset").assertDoesNotExist()
        rule.onNodeWithTag("preset-row-1").performClick()
        rule.onNodeWithTag("preset-row-0").performMouseInput { click(button = MouseButton.Secondary) }
        rule.onNodeWithTag("preset-context-menu-0").assertDoesNotExist()
        rule.onNodeWithTag("preset-row-1").assertIsSelected()
        assertTrue(opened.isEmpty())
        rule.onNodeWithTag("open-preset-json").assertIsDisplayed().performClick()
        assertEquals(listOf(entries[1]), opened)
        val footerBefore = rule.onNodeWithTag("open-preset-json").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("preset-action-delete-preset").performScrollTo().performClick().assertIsSelected()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --delete-preset \"阅读 128K\"")
        assertEquals(footerBefore, rule.onNodeWithTag("open-preset-json").fetchSemanticsNode().boundsInRoot)
        val container = rule.onNodeWithTag("preset-operations-column").fetchSemanticsNode().boundsInRoot
        assertTrue(container.bottom - footerBefore.bottom <= 10f)
        screenshot("strata-host", "preset-file-actions")
        assertTrue(executed.isEmpty())
        rule.onNodeWithTag("confirm-delete-preset").assertDoesNotExist()
        rule.onNodeWithTag("copy-command").performClick()
        assertEquals(listOf("strata launch --delete-preset \"阅读 128K\""), copied)
        rule.onNodeWithTag("execute-command").performClick()
        assertTrue(executed.isEmpty())
        rule.onNodeWithTag("cancel-delete-preset").performClick()
        assertEquals(3, entries.size)
        assertTrue(executed.isEmpty())
        rule.onNodeWithTag("execute-command").performClick()
        rule.onNodeWithTag("confirm-delete-preset").performClick()
        assertEquals(copied.single(), executed.single().result().command)
        assertEquals(3, entries.size) // Sending a command is not proof that Strata deleted the preset.
        rule.runOnIdle { entries.removeAt(1) } // A later directory refresh observes the deletion.
        rule.onNodeWithText("阅读 128K").assertDoesNotExist()
        rule.onNodeWithTag("copy-command").assertIsNotEnabled()
        rule.onNodeWithTag("execute-command").assertIsNotEnabled()
        rule.onNodeWithTag("open-preset-json").assertIsNotEnabled()
    }

    @Test fun globalCategoryIsDefaultAndOnlyShowsGlobalActions() {
        open()
        rule.onNodeWithTag("global-operations").assertIsSelected()
        rule.onNodeWithTag("preset-action-launch").assertDoesNotExist()
        rule.onNodeWithTag("preset-action-show-key").assertDoesNotExist()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --status")
        rule.onNodeWithTag("preset-json-output").assertDoesNotExist()
        rule.onNodeWithText("JSON 输出").assertDoesNotExist()
        rule.onNodeWithTag("preset-action-list-presets").performScrollTo().performClick()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --list-presets")
        rule.onNodeWithTag("preset-action-stop").performScrollTo().performClick()
        rule.onNodeWithText("停止全部服务").assertExists()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --stop")
        rule.onNodeWithText("该命令会停止所有由 strata launch 启动的服务。").assertDoesNotExist()
        rule.onNodeWithTag("execute-command").performClick()
        rule.onNodeWithTag("preset-row-0").performClick()
        rule.onNodeWithTag("preset-action-status").assertDoesNotExist()
        rule.onNodeWithTag("preset-action-list-presets").assertDoesNotExist()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --preset test")
        rule.onNodeWithTag("preset-action-stop").performClick()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --stop --preset test")
        rule.onNodeWithTag("preset-action-show-key").performClick()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --show-key --preset test")
        rule.onNodeWithText("执行后会在终端显示 API 密钥。").assertDoesNotExist()
        rule.onNodeWithTag("execute-command").performClick()
        assertEquals(listOf("strata launch --stop", "strata launch --show-key --preset test"), executed.map { it.result().command })
        screenshot("strata-host", "preset-operations")
        rule.onNodeWithTag("global-operations").performClick().assertIsSelected()
        rule.onNodeWithTag("preset-action-status").assertIsSelected()
        rule.onNodeWithTag("preset-action-launch").assertDoesNotExist()
        rule.onNodeWithTag("open-preset-json").assertDoesNotExist()
        rule.onNodeWithTag("preset-action-delete-preset").assertDoesNotExist()
    }

    @Test fun brokenPresetCanBeOpenedButCannotExecuteNativeDeletion() {
        open()
        val broken = entries[2]
        rule.onNodeWithTag("preset-row-2").assertIsEnabled().performClick().assertIsSelected()
        rule.onNodeWithTag("execute-command").assertIsNotEnabled()
        rule.onNodeWithTag("copy-command").assertIsNotEnabled()
        rule.onNodeWithTag("open-preset-json").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf(broken), opened)
        rule.onNodeWithTag("preset-action-delete-preset").performScrollTo().performClick()
        rule.onNodeWithTag("execute-command").assertIsNotEnabled()
        rule.onNodeWithTag("copy-command").assertIsNotEnabled()
        rule.onNodeWithTag("confirm-delete-preset").assertDoesNotExist()
        assertTrue(executed.isEmpty())
    }

    @Test fun loadingAndBusyStatesDisableExecutionAndSelection() {
        state.selectPreset(entries[0])
        open(loading = true, busy = true)
        rule.onNodeWithTag("presets-loading").assertIsDisplayed()
        rule.onNodeWithTag("copy-command").assertIsNotEnabled()
        rule.onNodeWithTag("execute-command").assertIsNotEnabled()
        rule.onNodeWithTag("preset-action-launch").assertIsNotEnabled()
        rule.onNodeWithTag("open-preset-json").assertIsNotEnabled()
    }

    @Test fun longListsScrollIndependentlyAndKeepBottomActionsVisible() {
        entries.clear()
        repeat(40) { entries += PresetEntry(Path.of("C:/presets/$it.json"), "preset-$it") }
        open()
        rule.onNodeWithTag("preset-row-39").performScrollTo().performClick()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --preset preset-39")
        rule.onNodeWithTag("preset-action-launch").assertIsDisplayed()
        fun footerBottomInset(): Float = rule.onNodeWithTag("preset-operations-column").fetchSemanticsNode().boundsInRoot.bottom -
            rule.onNodeWithTag("open-preset-json").fetchSemanticsNode().boundsInRoot.bottom
        val inset = footerBottomInset()
        rule.onNodeWithTag("preset-action-show-key").performScrollTo().performClick()
        rule.onNodeWithTag("open-preset-json").assertIsDisplayed()
        assertEquals(inset, footerBottomInset())
        rule.onNodeWithTag("copy-command").assertIsDisplayed()
        rule.onNodeWithTag("execute-command").assertIsDisplayed()
    }
}
