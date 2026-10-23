package dev.towertools.stratalauncher

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.towertools.stratalauncher.tabs.presets.PresetListTab
import dev.towertools.stratalauncher.tabs.presets.PresetOperations
import dev.towertools.stratalauncher.tabs.presets.PresetCommandSelection

@Composable
fun StrataApp(
    state: StrataState,
    entries: List<PresetEntry> = emptyList(),
    presetsLoading: Boolean = false,
    actionBusy: Boolean = false,
    portStatus: PortStatus = PortStatus.CHECKING,
    onExecutePresetCommand: (PresetCommandSelection) -> Unit = {},
    onExecuteModelCommand: (LaunchConfig) -> Unit = {},
    onOpenPresetJson: (PresetEntry) -> Unit = {},
    onOpenExperimental: () -> Unit = {},
    onChooseDataDir: () -> Unit = {},
    onChoosePresetDir: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenPresetDir: () -> Unit = {},
    onCopy: (String) -> Unit = {},
    onCopyApi: (String) -> Unit = {},
    onOpenWeb: (String) -> Unit = {},
) {
    val presetMode = state.session.activeTab == "presets"
    val selection = state.session.presetCommand.resolve(entries)
    val result = state.result(entries)
    val canAct = result.command.isNotEmpty() && !actionBusy && (!presetMode || !presetsLoading)
    var pendingDelete by remember(state.session.presetDirectory, selection, presetMode) { mutableStateOf(false) }
    val copy = {
        if (canAct) onCopy(result.command)
    }
    Column(Modifier.fillMaxSize().testTag("strata-host").onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && it.isCtrlPressed) {
            copy(); true
        } else false
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Strata 启动配置器", style = MaterialTheme.typography.h6)
                Text("预设启动 · 参数配置 · 命令生成", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = .65f))
            }
            ServiceToolbar(portStatus, onCopyApi, onOpenWeb)
        }
        val tabs = listOf("presets" to "预设列表", "model" to "自定义启动")
        TabRow(tabs.indexOfFirst { it.first == state.session.activeTab }.coerceAtLeast(0), backgroundColor = MaterialTheme.colors.surface) {
            tabs.forEach { (key, label) -> Tab(state.session.activeTab == key, { state.selectTab(key) },
                modifier = Modifier.testTag("tab-$key"), text = { Text(label) }) }
        }
        if (presetMode) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(16.dp, 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PresetDirectoryBar(state.session.presetDirectory, presetsLoading || actionBusy,
                    onChoosePresetDir, onRefresh, onOpenPresetDir)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val listScroll = rememberScrollState()
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("preset-list-column")) {
                        Column(Modifier.fillMaxSize().verticalScroll(listScroll).padding(end = 12.dp)) {
                            key(state.session.presetDirectory) {
                                PresetListTab(entries, presetsLoading, actionBusy, selection.preset, selection.global, state::selectGlobal,
                                    state::selectPreset)
                            }
                        }
                        VerticalScrollbar(rememberScrollbarAdapter(listScroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                    Divider(Modifier.fillMaxHeight().width(1.dp))
                    Box(Modifier.weight(1f).fillMaxHeight().testTag("preset-operations-column")) {
                        key(state.session.presetDirectory, selection.preset?.path, selection.global) {
                            PresetOperations(selection, actionBusy || presetsLoading, state::updatePresetCommand,
                                onOpenPresetJson, onOpenExperimental)
                        }
                    }
                }
            }
        } else {
            val scroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp, 12.dp, 28.dp, 16.dp)) {
                    LaunchForm(state.config, state::updateConfig, onChooseDataDir)
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        Divider()
        CommandDock(state.message, result, canAct, copy, {
            if (canAct) {
                if (presetMode) {
                    if (selection.action == "delete-preset") pendingDelete = true else onExecutePresetCommand(selection)
                } else onExecuteModelCommand(state.config)
            }
        })
    }
    if (pendingDelete) {
        AlertDialog(onDismissRequest = { pendingDelete = false }, title = { Text("删除预设？") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将在终端删除预设“${selection.preset?.name.orEmpty()}”，此操作无法撤销。")
                Text(result.command, style = MaterialTheme.typography.caption)
            } },
            confirmButton = { TextButton({
                pendingDelete = false
                if (canAct) onExecutePresetCommand(selection)
            }, enabled = canAct, modifier = Modifier.testTag("confirm-delete-preset")) { Text("执行删除") } },
            dismissButton = { TextButton({ pendingDelete = false }, modifier = Modifier.testTag("cancel-delete-preset")) { Text("取消") } })
    }
}

@Composable
private fun PresetDirectoryBar(directory: String, busy: Boolean, onChoose: () -> Unit, onRefresh: () -> Unit, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(directory, {}, readOnly = true, label = { Text("本地预设目录") },
            singleLine = true, modifier = Modifier.weight(1f).testTag("preset-directory"))
        OutlinedButton(onChoose, enabled = !busy) { Text("选择目录") }
        OutlinedButton(onRefresh, enabled = !busy) { Text(if (busy) "读取中…" else "刷新") }
        TextButton(onOpen, enabled = directory.isNotBlank()) { Text("打开目录") }
    }
}

@Composable
private fun CommandDock(message: String, result: CommandResult, canAct: Boolean, onCopy: () -> Unit, onExecute: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("command-dock").padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("命令预览", style = MaterialTheme.typography.subtitle1, modifier = Modifier.weight(1f))
            Text("在 CMD 中执行", style = MaterialTheme.typography.caption)
        }
        Surface(Modifier.fillMaxWidth().height(90.dp).testTag("command-preview-box"), color = MaterialTheme.colors.onSurface.copy(alpha = .045f), border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = .15f))) {
            Box {
                val scroll = rememberScrollState()
                SelectionContainer {
                    Text(result.command.ifEmpty { "请先修正参数，再生成命令。" },
                        Modifier.fillMaxWidth().verticalScroll(scroll).padding(10.dp, 8.dp, 20.dp, 8.dp).testTag("command-preview"),
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        if (result.errors.isNotEmpty() || result.warnings.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = 58.dp).verticalScroll(rememberScrollState())) {
            result.errors.forEach { Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption) }
            result.warnings.forEach { Text(it, color = MaterialTheme.colors.onSurface, style = MaterialTheme.typography.caption) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.caption, modifier = Modifier.testTag("feedback"))
                Text("点击执行后打开终端，结果请在终端查看。", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = .65f))
            }
            OutlinedButton(onCopy, enabled = canAct, modifier = Modifier.testTag("copy-command")) {
                Text("复制")
            }
            Button(onExecute, enabled = canAct, modifier = Modifier.testTag("execute-command")) { Text("执行") }
        }
    }
}
