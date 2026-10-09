package dev.towertools.proxyenvmanager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProxyEnvironmentUiTest {
    @get:Rule val rule = createComposeRule()
    private val original = ProxyEnvironment("http://old-http", "http://old-https", "socks5://old", "localhost")
    private val presets = ProxyPresets("http://new-http", "http://new-https", "socks5://new", "")
    private val closeRequest = mutableStateOf(0)
    private var exits = 0

    private fun open(store: EditingTestStore, presetStore: EditingTestPresets = EditingTestPresets(presets)) {
        val service = ProxyEnvironmentService(store, presetStore)
        rule.setContent {
            MaterialTheme { Surface { Box(Modifier.size(780.dp, 442.dp).testTag("proxy-host")) {
                ProxyEnvironmentApp(service, closeRequest.value) { exits++ }
            } } }
        }
        rule.waitForIdle()
    }

    private fun detail(name: String) { rule.onNodeWithTag("variable-$name").performClick() }
    private fun screenshot(name: String) {
        val target = File("../../outputs/verification/proxy-env-manager/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(rule.onNodeWithTag("proxy-host").captureToImage().toAwtImage(), "png", target)
    }

    @Test fun mixedChangesArePreviewedAndOnlySavedOnceExplicitlyRequested() {
        val store = EditingTestStore(original)
        open(store)
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        detail(ProxyVariables.HTTP_PROXY)
        rule.onNodeWithText("使用此项预设").performClick()
        detail(ProxyVariables.HTTPS_PROXY)
        rule.onNodeWithText("清空此项").performClick()
        rule.onNodeWithText("有 2 项更改未保存").assertExists()
        assertEquals(original.asMap().filterValues { it != null }, store.values)
        assertTrue(store.mutations.isEmpty())
        detail(ProxyVariables.HTTP_PROXY)
        rule.onNodeWithText("待保存值").assertExists()
        rule.onNodeWithText("撤销此项").performClick()
        rule.onNodeWithText("有 1 项更改未保存").assertExists()
        detail(ProxyVariables.HTTP_PROXY)
        rule.onNodeWithText("使用此项预设").performClick()
        ProxyVariables.names.forEach { rule.onNodeWithTag("variable-$it").assertIsDisplayed() }
        rule.onNodeWithText("保存更改").assertIsDisplayed()
        screenshot("pending-changes")
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("有 2 项更改未保存").assertDoesNotExist()
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        assertEquals(listOf(ProxyVariables.HTTP_PROXY, ProxyVariables.HTTPS_PROXY), store.mutations)
        assertEquals("http://new-http", store.values[ProxyVariables.HTTP_PROXY])
        assertEquals(null, store.values[ProxyVariables.HTTPS_PROXY])
        assertEquals("socks5://old", store.values[ProxyVariables.ALL_PROXY])
        rule.runOnIdle { closeRequest.value++ }
        rule.waitForIdle()
        assertEquals(1, exits)
    }

    @Test fun clearingEverythingIsConfirmedAtSaveAndCanBeCancelledOrUndone() {
        val store = EditingTestStore(original)
        val presetStore = EditingTestPresets(presets)
        open(store, presetStore)
        rule.onNodeWithText("清空全部").performClick()
        rule.onNodeWithText("有 4 项更改未保存").assertExists()
        rule.onNodeWithText("保存并清空").assertDoesNotExist()
        assertTrue(store.mutations.isEmpty())
        rule.onNodeWithText("撤销全部").performClick()
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        rule.onNodeWithText("清空全部").performClick()
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("继续编辑").performClick()
        assertTrue(store.mutations.isEmpty())
        rule.onNodeWithText("有 4 项更改未保存").assertExists()
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("保存并清空").performClick()
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        assertTrue(store.values.isEmpty())
        assertEquals(presets, presetStore.value)
    }

    @Test fun refreshAndWindowCloseRespectUnsavedChanges() {
        val store = EditingTestStore(original)
        open(store)
        detail(ProxyVariables.HTTP_PROXY)
        rule.onNodeWithText("使用此项预设").performClick()
        rule.onNodeWithText("刷新").performClick()
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("有 1 项更改未保存").assertExists()
        rule.runOnIdle { store.values[ProxyVariables.NO_PROXY] = "external-host" }
        rule.onNodeWithText("刷新").performClick()
        rule.onNodeWithText("保留更改并刷新").performClick()
        rule.onNodeWithText("external-host").assertExists()
        rule.onNodeWithText("有 1 项更改未保存").assertExists()
        rule.runOnIdle { closeRequest.value++ }
        rule.onNodeWithText("还有未保存的更改").assertExists()
        rule.onNodeWithText("继续编辑").performClick()
        assertEquals(0, exits)
        rule.onNodeWithText("刷新").performClick()
        rule.onNodeWithText("放弃更改并刷新").performClick()
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        detail(ProxyVariables.HTTP_PROXY)
        rule.onNodeWithText("使用此项预设").performClick()
        rule.runOnIdle { closeRequest.value++ }
        rule.onNodeWithText("放弃更改并退出").performClick()
        rule.waitForIdle()
        assertEquals(1, exits)
        assertTrue(store.mutations.isEmpty())
    }

    @Test fun conflictShowsLatestValueAndRequiresAnotherExplicitSaveAfterRefresh() {
        val store = EditingTestStore(original)
        open(store)
        detail(ProxyVariables.HTTP_PROXY)
        rule.onNodeWithText("使用此项预设").performClick()
        rule.runOnIdle { store.values[ProxyVariables.HTTP_PROXY] = "http://external" }
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("环境变量已被其他程序修改").assertExists()
        rule.onNodeWithText("http://external").assertExists()
        assertTrue(store.mutations.isEmpty())
        rule.onNodeWithText("保留更改并刷新").performClick()
        rule.onNodeWithText("有 1 项更改未保存").assertExists()
        assertTrue(store.mutations.isEmpty())
        rule.onNodeWithText("保存更改").performClick()
        assertEquals("http://new-http", store.values[ProxyVariables.HTTP_PROXY])
        assertEquals(listOf(ProxyVariables.HTTP_PROXY), store.mutations)
    }

    @Test fun editingPresetsDoesNotRewriteAlreadyPreparedChanges() {
        val store = EditingTestStore(original)
        val presetStore = EditingTestPresets(presets)
        open(store, presetStore)
        detail(ProxyVariables.HTTP_PROXY)
        rule.onNodeWithText("使用此项预设").performClick()
        rule.onNodeWithText("编辑预设").performClick()
        rule.onNode(hasSetTextAction() and hasText(presets.httpProxy)).performTextReplacement("http://later")
        rule.onNodeWithText("保存预设").performClick()
        assertEquals("http://later", presetStore.value.httpProxy)
        assertTrue(store.mutations.isEmpty())
        rule.onNodeWithText("有 1 项更改未保存").assertExists()
        rule.onNodeWithText("保存更改").performClick()
        assertEquals("http://new-http", store.values[ProxyVariables.HTTP_PROXY])
    }

    @Test fun failedSaveKeepsTheDraftAndAllowsRetry() {
        val store = EditingTestStore(original).apply { failOnce = ProxyVariables.ALL_PROXY }
        open(store)
        rule.onNodeWithText("使用全部预设").performClick()
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("保存失败，已恢复修改前的值", substring = true).assertExists()
        rule.onNodeWithText("有 4 项更改未保存").assertExists()
        assertEquals(original.asMap().filterValues { it != null }, store.values)
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        assertEquals(presets.asMap(), store.values)
    }

    @Test fun emptyNoProxyPresetCanReplaceADeletionAndIsWrittenAsAnEmptyString() {
        val store = EditingTestStore(ProxyEnvironment(presets.httpProxy, presets.httpsProxy, presets.allProxy, "localhost"))
        open(store)
        detail(ProxyVariables.NO_PROXY)
        rule.onNodeWithText("清空此项").performClick()
        rule.onNodeWithText("使用全部预设").assertIsEnabled()
        detail(ProxyVariables.NO_PROXY)
        rule.onNodeWithText("使用此项预设").assertIsEnabled().performClick()
        rule.onNodeWithText("有 1 项更改未保存").assertExists()
        assertTrue(store.mutations.isEmpty())
        rule.onNodeWithText("保存更改").performClick()
        assertTrue(ProxyVariables.NO_PROXY in store.values)
        assertEquals("", store.values[ProxyVariables.NO_PROXY])
        assertEquals(listOf(ProxyVariables.NO_PROXY), store.mutations)
    }

    @Test fun usingAllPresetsCancelsADeletionWhenTheOriginalNoProxyIsEmpty() {
        val store = EditingTestStore(ProxyEnvironment(presets.httpProxy, presets.httpsProxy, presets.allProxy, ""))
        open(store)
        detail(ProxyVariables.NO_PROXY)
        rule.onNodeWithText("清空此项").performClick()
        rule.onNodeWithText("有 1 项更改未保存").assertExists()
        rule.onNodeWithText("使用全部预设").assertIsEnabled().performClick()
        rule.onNodeWithText("有 1 项更改未保存").assertDoesNotExist()
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        assertTrue(store.mutations.isEmpty())
        assertEquals("", store.values[ProxyVariables.NO_PROXY])
    }

    @Test fun incompleteRollbackMustBeReviewedBeforeUndoOrExitAndShowsActualCurrentValue() {
        val store = EditingTestStore(original).apply {
            failOnce = ProxyVariables.ALL_PROXY
            failRestore = ProxyVariables.HTTPS_PROXY
        }
        open(store)
        rule.onNodeWithText("使用全部预设").performClick()
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("部分更改未能恢复").assertExists()
        rule.onNodeWithTag("variable-HTTPS_PROXY").assertTextContains(presets.httpsProxy)
        assertEquals(presets.httpsProxy, store.values[ProxyVariables.HTTPS_PROXY])
        rule.onNodeWithText("撤销全部").assertIsNotEnabled()
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        rule.runOnIdle { closeRequest.value++ }
        rule.waitForIdle()
        assertEquals(0, exits)
        rule.onNodeWithText("部分更改未能恢复").assertExists()
        rule.onNodeWithText("已核对，继续编辑").performClick()
        rule.onNodeWithText("有 3 项更改未保存").assertExists()
        rule.onNodeWithText("撤销全部").performClick()
        rule.onNodeWithTag("variable-HTTPS_PROXY").assertTextContains(presets.httpsProxy)
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        rule.runOnIdle { closeRequest.value++ }
        rule.waitForIdle()
        assertEquals(1, exits)
    }

    @Test fun reviewedIncompleteRollbackCanRetryWithoutAConflictAgainstItsOwnPartialWrite() {
        val store = EditingTestStore(original).apply {
            failOnce = ProxyVariables.ALL_PROXY
            failRestore = ProxyVariables.HTTPS_PROXY
        }
        open(store)
        rule.onNodeWithText("使用全部预设").performClick()
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("已核对，继续编辑").performClick()
        rule.runOnIdle { store.failRestore = null; store.mutations.clear() }
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("保存更改").assertIsNotEnabled()
        rule.onNodeWithText("环境变量已被其他程序修改").assertDoesNotExist()
        assertEquals(presets.asMap(), store.values)
        assertEquals(listOf(ProxyVariables.HTTP_PROXY, ProxyVariables.ALL_PROXY, ProxyVariables.NO_PROXY), store.mutations)
    }

    @Test fun unreadableRecoveryBlocksEditingUntilLatestValuesCanBeRead() {
        val store = EditingTestStore(original).apply {
            failOnce = ProxyVariables.ALL_PROXY
            failRestore = ProxyVariables.HTTPS_PROXY
            failReadAfterFailure = ProxyVariables.NO_PROXY
        }
        open(store)
        rule.onNodeWithText("使用全部预设").performClick()
        rule.onNodeWithText("保存更改").performClick()
        rule.onNodeWithText("部分更改未能恢复").assertExists()
        rule.onNodeWithTag("variable-HTTPS_PROXY").assertTextContains("读取失败")
        rule.onNodeWithText("已核对，继续编辑").assertIsNotEnabled()
        rule.onNodeWithText("撤销全部").assertIsNotEnabled()
        rule.onNodeWithText("重新读取当前值").performClick()
        rule.onNodeWithText("已核对，继续编辑").assertIsNotEnabled()
        rule.runOnIdle { closeRequest.value++ }
        rule.waitForIdle()
        assertEquals(0, exits)
        rule.runOnIdle { store.failReadAfterFailure = null }
        rule.onNodeWithText("重新读取当前值").performClick()
        rule.onNodeWithTag("variable-HTTPS_PROXY").assertTextContains(presets.httpsProxy)
        rule.onNodeWithText("已核对，继续编辑").assertIsEnabled().performClick()
        rule.onNodeWithText("有 3 项更改未保存").assertExists()
        rule.onNodeWithText("保存更改").performClick()
        assertEquals(presets.asMap(), store.values)
    }
}
