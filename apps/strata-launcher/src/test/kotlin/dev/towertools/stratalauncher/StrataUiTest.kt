package dev.towertools.stratalauncher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StrataUiTest {
    @get:Rule val rule = createComposeRule()
    private val copied = mutableListOf<String>()
    private val executed = mutableListOf<LaunchConfig>()
    private val portStatus = mutableStateOf(PortStatus.CHECKING)
    private val copiedApis = mutableListOf<String>()
    private val openedWebsites = mutableListOf<String>()

    private fun open(state: StrataState, width: Int = 1100, height: Int = 780, dark: Boolean = false) {
        rule.setContent {
            MaterialTheme(colors = if (dark) darkColors() else lightColors()) {
                Surface { Box(Modifier.size(width.dp, height.dp)) {
                    StrataApp(state, portStatus = portStatus.value, onCopy = { copied += it },
                        onExecuteModelCommand = { executed += it }, onCopyApi = { copiedApis += it },
                        onOpenWeb = { openedWebsites += it })
                } }
            }
        }
        rule.waitForIdle()
    }

    private fun screenshot(name: String) {
        val file = File("../../outputs/verification/strata-launcher/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(rule.onNodeWithTag("strata-host").captureToImage().toAwtImage(), "png", file)
    }

    @Test fun serviceToolbarRemainsGlobalAndUsesTheFixedApiAndWebAddresses() {
        val state = StrataState(SessionState(model = LaunchConfig(port = "9082")))
        open(state, width = 800, height = 660)
        rule.onNodeWithTag("service-port-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "检测中"))
        rule.runOnIdle { portStatus.value = PortStatus.CONNECTED }
        val toolbar = rule.onNodeWithTag("service-toolbar").fetchSemanticsNode().boundsInRoot
        for (tab in listOf("presets", "model")) {
            rule.onNodeWithTag("tab-$tab").performClick()
            rule.onNodeWithTag("service-port-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已连通"))
            rule.onNodeWithTag("copy-service-api").assertIsDisplayed().performClick()
            rule.onNodeWithTag("open-service-web").assertIsDisplayed().performClick()
            assertEquals(toolbar, rule.onNodeWithTag("service-toolbar").fetchSemanticsNode().boundsInRoot)
        }
        assertEquals(listOf("http://127.0.0.1:7271/v1", "http://127.0.0.1:7271/v1"), copiedApis)
        assertEquals(listOf("http://127.0.0.1:7271", "http://127.0.0.1:7271"), openedWebsites)
        assertTrue(copied.isEmpty())
        assertTrue(executed.isEmpty())
        screenshot("service-connected")
        rule.runOnIdle {
            portStatus.value = PortStatus.DISCONNECTED
            state.updateConfig(state.config.copy(port = "invalid"))
        }
        rule.onNodeWithTag("service-port-status").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "未连通"))
        rule.onNodeWithTag("copy-service-api").assertIsEnabled()
        rule.onNodeWithTag("open-service-web").assertIsEnabled()
        rule.onNodeWithTag("execute-command").assertIsNotEnabled()
    }

    @Test
    fun removedHeaderActionsAreAbsentOnEveryPage() {
        open(StrataState(), width = 800, height = 660)
        rule.onNodeWithTag("tab-presets").assertIsSelected()
        rule.onNodeWithTag("presets-empty").assertIsDisplayed()
        listOf("presets", "model").forEach { tab ->
            rule.onNodeWithTag("tab-$tab").performClick()
            listOf("参数说明", "导入", "导出", "重置", "帮助").forEach { label ->
                rule.onNodeWithText(label).assertDoesNotExist()
            }
            rule.onNodeWithTag("copy-command").assertIsDisplayed()
        }
        rule.onNodeWithTag("tab-preset").assertDoesNotExist()
        rule.onNodeWithTag("tab-manage").assertDoesNotExist()
        rule.onNodeWithText("预设查看 / 编辑").assertDoesNotExist()
        rule.onNodeWithTag("use-editor-preset").assertDoesNotExist()
    }

    @Test
    fun manualEditingAndSwitchingTabsKeepsSessionInputsWithoutQuickScenarioButtons() {
        val original = LaunchConfig(context = "64k", port = "9082", dataDir = "C:\\Models\\Strata", parallel = "2", tune = "quick")
        val state = StrataState(SessionState(model = original, activeTab = "model"))
        open(state)
        listOf("普通 128K", "看图 128K", "普通 200K", "看图 200K").forEach {
            rule.onNodeWithText(it).assertDoesNotExist()
        }
        rule.onNodeWithTag("field-context").performScrollTo().performTextReplacement("128k")
        rule.runOnIdle { assertEquals(original.copy(context = "128k"), state.session.model) }
        rule.onNodeWithTag("copy-command").assertIsEnabled().performClick()
        assertEquals(1, copied.size)
        assertTrue(copied.single().contains("--context 128k"))
        assertTrue(copied.single().contains("--port 9082"))
        screenshot("model-wide")
        rule.onNodeWithTag("tab-presets").performClick()
        rule.onNodeWithTag("preset-action-status").performScrollTo().performClick()
        rule.onNodeWithTag("command-preview").assertTextEquals("strata launch --status")
        rule.onNodeWithTag("tab-model").performClick()
        rule.runOnIdle {
            assertEquals("128k", state.config.context)
            assertEquals("9082", state.config.port)
            assertEquals("status", state.session.presetCommand.action)
        }
        rule.onNodeWithTag("tab-presets").performClick()
        rule.onNodeWithTag("preset-action-status").assertIsSelected()
    }

    @Test
    fun narrowDarkLayoutKeepsCopyVisibleAndCopiesOnlyOnAnExplicitClick() {
        val state = StrataState(SessionState(model = LaunchConfig(keyMode = "literal", apiKey = "test-only-key"), activeTab = "model"))
        open(state, width = 800, height = 660, dark = true)
        rule.onNodeWithTag("tab-model").assertIsDisplayed()
        rule.onNodeWithTag("copy-command").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("execute-command").assertIsDisplayed().assertIsEnabled()
        assertTrue(copied.isEmpty())
        rule.onNodeWithTag("copy-command").performClick()
        assertEquals(1, copied.size)
        assertTrue(copied.single().contains("test-only-key"))
        screenshot("model-narrow-dark")
        rule.onNodeWithTag("field-apiKey").performScrollTo().assertExists()
        rule.onNodeWithTag("toggle-apiKey").performClick()
        rule.onNodeWithTag("field-apiKey").assertTextContains("test-only-key")
    }

    @Test fun modelCopyAndExecuteUseTheCurrentPreviewAndInvalidParametersDisableBoth() {
        val state = StrataState(SessionState(activeTab = "model"))
        open(state, width = 800, height = 660)
        rule.onNodeWithTag("copy-command").assertTextEquals("复制")
        rule.onNodeWithTag("execute-command").assertTextEquals("执行")
        assertTrue(executed.isEmpty())
        val dock = rule.onNodeWithTag("command-dock").fetchSemanticsNode().boundsInRoot
        val previewBox = rule.onNodeWithTag("command-preview-box").fetchSemanticsNode().boundsInRoot
        val copyButton = rule.onNodeWithTag("copy-command").fetchSemanticsNode().boundsInRoot
        val executeButton = rule.onNodeWithTag("execute-command").fetchSemanticsNode().boundsInRoot
        for (tab in listOf("presets", "model")) {
            rule.onNodeWithTag("tab-$tab").performClick()
            rule.onNodeWithTag("shell-cmd").assertDoesNotExist()
            rule.onNodeWithTag("shell-powershell").assertDoesNotExist()
            rule.onNodeWithTag("multiline").assertDoesNotExist()
            rule.onNodeWithText("在 CMD 中执行").assertIsDisplayed()
            assertEquals(dock, rule.onNodeWithTag("command-dock").fetchSemanticsNode().boundsInRoot)
            assertEquals(previewBox, rule.onNodeWithTag("command-preview-box").fetchSemanticsNode().boundsInRoot)
            assertEquals(copyButton, rule.onNodeWithTag("copy-command").fetchSemanticsNode().boundsInRoot)
            assertEquals(executeButton, rule.onNodeWithTag("execute-command").fetchSemanticsNode().boundsInRoot)
        }
        val preview = state.result().command
        rule.onNodeWithTag("command-preview").assertTextEquals(preview)
        rule.onNodeWithTag("copy-command").performClick()
        rule.onNodeWithTag("execute-command").performClick()
        assertEquals(listOf(preview), copied)
        assertEquals(preview, CommandBuilder.build(executed.single()).command)
        assertFalse(CommandBuilder.build(executed.single()).command.contains('\n'))
        rule.onNodeWithTag("field-port").performScrollTo().performTextReplacement("invalid")
        rule.onNodeWithTag("copy-command").assertIsNotEnabled()
        rule.onNodeWithTag("execute-command").assertIsNotEnabled()
        assertEquals(1, executed.size)
    }

    @Test fun simplifiedLaunchFormUsesNewDefaultsAndConcreteResourceChoices() {
        val state = StrataState(SessionState(activeTab = "model"))
        open(state)
        rule.onNodeWithTag("field-context").performScrollTo().assertTextContains("128k")
        rule.onNodeWithTag("field-port").performScrollTo().assertTextContains("7271")
        rule.onNodeWithTag("command-preview").assertTextEquals(state.result().command)
        rule.onNodeWithTag("multiline").assertDoesNotExist()
        rule.onNodeWithTag("field-access").performScrollTo().assertTextEquals("仅本机 · local").performClick()
        rule.onNodeWithTag("field-access-option-default").assertDoesNotExist()
        rule.onNodeWithTag("field-access-option-local").performClick()
        rule.runOnIdle { assertEquals("local", state.config.access) }
        listOf("field-noTui", "field-open", "field-plan", "field-yes", "toggle-advanced", "add-advanced",
            "field-output", "field-feed", "field-tuneAllowSmall", "field-apiKeyEnv").forEach {
            rule.onNodeWithTag(it).assertDoesNotExist()
        }
        rule.onNodeWithText("启动行为 / 高级参数").assertDoesNotExist()
        mapOf("vision" to "gpu", "parallel" to "2", "kv" to "q4_0", "vramReserve" to "4096", "tune" to "quick").forEach { (field, value) ->
            rule.onNodeWithTag("field-$field").performScrollTo().performClick()
            rule.onNodeWithTag("field-$field-option-default").assertDoesNotExist()
            rule.onNodeWithTag("field-$field-option-$value").performClick()
        }
        rule.onNodeWithTag("field-keyMode").performScrollTo().performClick()
        rule.onNodeWithTag("field-keyMode-option-env").assertDoesNotExist()
        rule.onNodeWithTag("field-keyMode-option-new").performClick()
        rule.runOnIdle {
            assertEquals("gpu", state.config.vision)
            assertEquals("2", state.config.parallel)
            assertEquals("q4_0", state.config.kv)
            assertEquals("4096", state.config.vramReserve)
            assertEquals("quick", state.config.tune)
            assertTrue("--new-key" in state.result().tokens)
        }
    }
}
