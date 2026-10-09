package dev.towertools.proxyenvmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class SaveRecovery(
    val failure: ProxyEnvironmentSaveFailure,
    val original: ProxyEnvironment,
    val latest: ProxyEnvironment?,
)

@Composable
fun ProxyEnvironmentApp(service: ProxyEnvironmentService, closeRequest: Int = 0, onExit: () -> Unit = {}) {
    val initialEnvironment = remember(service) { runCatching(service::read) }
    val initialPresets = remember(service) { runCatching(service::presets) }
    var draft by remember(service) { mutableStateOf(initialEnvironment.getOrNull()?.let(::ProxyEnvironmentDraft)) }
    var presets by remember(service) { mutableStateOf(initialPresets.getOrNull()) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedVariable by remember { mutableStateOf<String?>(null) }
    var confirmClearSave by remember { mutableStateOf(false) }
    var confirmRefresh by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<ProxyEnvironmentConflict?>(null) }
    var recovery by remember { mutableStateOf<SaveRecovery?>(null) }
    var message by remember { mutableStateOf(when {
        initialPresets.isFailure -> "读取预设失败：${initialPresets.exceptionOrNull()?.message}"
        initialEnvironment.isFailure -> "读取环境变量失败：${initialEnvironment.exceptionOrNull()?.message}"
        else -> "调整后点击“保存更改”，才会修改当前用户环境变量。"
    }) }
    var isError by remember { mutableStateOf(initialEnvironment.isFailure || initialPresets.isFailure) }

    fun stage(change: (ProxyEnvironmentDraft) -> ProxyEnvironmentDraft) {
        if (recovery != null) return
        draft = draft?.let(change)
        selectedVariable = null
        message = if (draft?.changes?.isNotEmpty() == true) "更改已准备，尚未写入环境变量。" else "没有待保存的更改。"
        isError = false
    }

    fun refresh(keepChanges: Boolean) {
        if (recovery != null) return
        runCatching(service::read).onSuccess {
            draft = draft?.refresh(it, keepChanges) ?: ProxyEnvironmentDraft(it)
            message = if (draft?.changes?.isNotEmpty() == true) "已读取最新值并保留更改，请检查后保存。" else "已刷新当前用户环境变量。"
            isError = false
        }.onFailure {
            message = "刷新失败：${it.message ?: it.javaClass.simpleName}"
            isError = true
        }
        confirmRefresh = false
        conflict = null
    }

    fun save() {
        if (recovery != null) return
        val edits = draft ?: return
        runCatching { service.saveChanges(edits) }.onSuccess {
            draft = ProxyEnvironmentDraft(it)
            message = "更改已保存。请重启需要使用新配置的程序。"
            isError = false
        }.onFailure {
            if (it is ProxyEnvironmentConflict) conflict = it
            if (it is ProxyEnvironmentSaveFailure && it.rollbackFailures.isNotEmpty()) {
                recovery = SaveRecovery(it, edits.current, it.latest)
                it.latest?.let { latest -> draft = edits.refresh(latest, keepChanges = true) }
            }
            message = it.message ?: "保存失败，请重试。"
            isError = true
            AppLog.logger.warning("Proxy environment save failed: ${it.message}")
        }
    }

    LaunchedEffect(closeRequest) {
        if (closeRequest > 0 && recovery == null) {
            if (draft?.changes?.isNotEmpty() == true) confirmExit = true else onExit()
        }
    }

    val currentAvailable = draft != null && (recovery == null || recovery?.latest != null)
    val canEdit = draft != null && recovery == null
    val current = draft?.current?.asMap().orEmpty()
    val proposed = draft?.proposed?.asMap().orEmpty()
    val changes = draft?.changes.orEmpty()
    val presetValues = presets?.asMap().orEmpty()
    val configured = presets?.isConfigured() == true
    val comparisons = ProxyVariables.names.associateWith { name ->
        if (!currentAvailable) null else presetValues[name]?.let { comparePreset(name, current[name], it, configured) }
    }

    val differentCount = comparisons.values.count { it == PresetComparison.DIFFERENT }
    val summary = when {
        !currentAvailable -> "当前环境变量读取失败"
        presets == null -> "预设读取失败"
        !configured -> "尚未完成预设配置"
        differentCount == 0 -> "当前四项均与预设一致"
        else -> "当前 $differentCount 项与预设不同"
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("代理环境变量", style = MaterialTheme.typography.h5)
                Text("当前用户环境变量 · 先调整，后保存", style = MaterialTheme.typography.caption)
            }
            TextButton(enabled = canEdit && changes.isNotEmpty(), onClick = { stage { it.discard() } }) { Text("撤销全部") }
            TextButton(enabled = recovery == null, onClick = { if (changes.isNotEmpty()) confirmRefresh = true else refresh(false) }) { Text("刷新") }
            OutlinedButton(enabled = recovery == null, onClick = {
                runCatching(service::presets).onSuccess { presets = it; showSettings = true }.onFailure {
                    message = "读取预设失败：${it.message ?: it.javaClass.simpleName}"
                    isError = true
                }
            }) { Text("编辑预设") }
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = 1.dp) {
            Row(modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(summary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.subtitle1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(
                    enabled = canEdit && proposed.values.any { it != null },
                    onClick = { stage { it.clearAll() } },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colors.error),
                ) { Text("清空全部") }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = configured && canEdit && ProxyVariables.names.any { proposed[it] != presetValues[it] },
                    onClick = { presets?.let { saved -> stage { it.useAllPresets(saved) } } },
                ) { Text("使用全部预设") }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = 1.dp) {
            Column {
                ProxyVariables.names.forEachIndexed { index, name ->
                    if (index > 0) Divider()
                    ProxyVariableRow(name, current[name], proposed[name], name in changes, comparisons[name], currentAvailable) {
                        selectedVariable = name
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                if (changes.isNotEmpty()) Text("有 ${changes.size} 项更改未保存", color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption)
                Text(message, color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(enabled = canEdit && changes.isNotEmpty(), onClick = {
                if (proposed.values.all { it == null }) confirmClearSave = true else save()
            }) { Text("保存更改") }
        }
    }

    selectedVariable?.let { name ->
        ProxyVariableDialog(
            name = name, value = current[name], proposed = proposed[name], changed = name in changes,
            preset = presetValues[name], comparison = comparisons[name], environmentAvailable = currentAvailable,
            canApply = configured && canEdit && proposed[name] != presetValues[name],
            onDismiss = { selectedVariable = null },
            onApplyPreset = { presets?.let { saved -> stage { it.usePreset(name, saved) } } },
            onDelete = { stage { it.clear(name) } },
            onUndo = { stage { it.undo(name) } },
        )
    }

    if (showSettings && presets != null) {
        PresetSettingsDialog(initial = presets!!, onDismiss = { showSettings = false }, onSave = { edited ->
            runCatching { service.savePresets(edited) }.fold(onSuccess = {
                presets = it
                showSettings = false
                message = "预设已保存；已准备的更改保持原样。可重新使用预设。"
                isError = false
                null
            }, onFailure = {
                message = "保存预设失败：${it.message ?: it.javaClass.simpleName}"
                isError = true
                message
            })
        })
    }

    if (confirmClearSave) {
        AlertDialog(onDismissRequest = { confirmClearSave = false }, title = { Text("保存并清空全部代理变量？") },
            text = { Text("将删除 ${changes.keys.joinToString("、")}。已保存的预设会保留。") },
            confirmButton = { Button(onClick = { confirmClearSave = false; save() }, colors = ButtonDefaults.buttonColors(
                backgroundColor = MaterialTheme.colors.error, contentColor = MaterialTheme.colors.onError,
            )) { Text("保存并清空") } },
            dismissButton = { TextButton(onClick = { confirmClearSave = false }) { Text("继续编辑") } },
        )
    }

    if (confirmRefresh) {
        AlertDialog(onDismissRequest = { confirmRefresh = false }, title = { Text("刷新时如何处理未保存的更改？") },
            text = { Text("刷新会读取最新环境变量。保留更改后，请检查新的当前值，再点击保存。") },
            buttons = { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmRefresh = false }) { Text("取消") }
                TextButton(onClick = { refresh(false) }) { Text("放弃更改并刷新") }
                TextButton(onClick = { refresh(true) }) { Text("保留更改并刷新") }
            } },
        )
    }

    if (confirmExit) {
        AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("还有未保存的更改") },
            text = { Text("有 ${changes.size} 项更改尚未写入环境变量。退出将放弃这些更改。") },
            confirmButton = { TextButton(onClick = onExit) { Text("放弃更改并退出") } },
            dismissButton = { Button(onClick = { confirmExit = false }) { Text("继续编辑") } },
        )
    }

    recovery?.let { pending ->
        Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.width(620.dp), shape = MaterialTheme.shapes.medium, elevation = 12.dp) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("部分更改未能恢复", style = MaterialTheme.typography.h6)
                    Text(
                        "保存失败后，部分变量未确认恢复。请核对系统当前值；撤销草稿不会恢复已写入的值，退出会放弃其余待保存更改。",
                        style = MaterialTheme.typography.body2,
                    )
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pending.failure.rollbackFailures.forEach { name ->
                            Text(name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.subtitle2)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProxyValuePanel("保存前的值", pending.original.asMap()[name], Modifier.weight(1f))
                                ProxyValuePanel(
                                    "系统当前值", pending.latest?.asMap()?.get(name), Modifier.weight(1f),
                                    nullLabel = if (pending.latest == null) "读取失败" else "未设置",
                                )
                            }
                        }
                    }
                    if (pending.latest == null) {
                        Text("无法读取当前值，请重试读取后再继续编辑。",
                            color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onExit) { Text("保留当前状态并退出") }
                        TextButton(onClick = {
                            runCatching(service::read).onSuccess { latest ->
                                draft = draft?.refresh(latest, keepChanges = true) ?: ProxyEnvironmentDraft(latest)
                                recovery = pending.copy(latest = latest)
                                message = pending.failure.message ?: "请核对系统当前值。"
                            }.onFailure {
                                recovery = pending.copy(latest = null)
                                message = "读取当前值失败：${it.message ?: it.javaClass.simpleName}"
                            }
                            isError = true
                        }) { Text("重新读取当前值") }
                        Button(enabled = pending.latest != null, onClick = {
                            recovery = null
                            message = if (draft?.changes?.isNotEmpty() == true) "已核对系统当前值；仍需保存的更改已保留。"
                                else "已核对系统当前值。没有待保存的更改。"
                            isError = false
                        }) { Text("已核对，继续编辑") }
                    }
                }
            }
        }
    }

    conflict?.let { found ->
        Dialog(onDismissRequest = { conflict = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.width(620.dp), shape = MaterialTheme.shapes.medium, elevation = 12.dp) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("环境变量已被其他程序修改", style = MaterialTheme.typography.h6)
                    Text("本次没有写入任何更改。请核对最新值；保留更改后仍需再次点击保存。", style = MaterialTheme.typography.body2)
                    Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        found.names.forEach { name ->
                            Text(name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.subtitle2)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ProxyValuePanel("原始值", current[name], Modifier.weight(1f))
                                ProxyValuePanel("最新值", found.latest.asMap()[name], Modifier.weight(1f))
                                ProxyValuePanel("待保存值", proposed[name], Modifier.weight(1f), nullLabel = "删除变量")
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { conflict = null }) { Text("返回") }
                        TextButton(onClick = { refresh(false) }) { Text("放弃更改并刷新") }
                        Button(onClick = { refresh(true) }) { Text("保留更改并刷新") }
                    }
                }
            }
        }
    }
}

private fun displayValue(value: String?) = value?.takeUnless(String::isEmpty) ?: if (value == null) "未设置" else "（空字符串）"

@Composable
private fun ProxyVariableRow(name: String, value: String?, proposed: String?, changed: Boolean, comparison: PresetComparison?, environmentAvailable: Boolean, onOpen: () -> Unit) {
    val status = when (comparison) {
        PresetComparison.MATCH -> "与预设一致"
        PresetComparison.DIFFERENT -> "与预设不同"
        PresetComparison.UNCONFIGURED -> "未配置预设"
        null -> "状态未知"
    }
    Row(modifier = Modifier.fillMaxWidth().height(56.dp).testTag("variable-$name").clickable(onClick = onOpen).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, modifier = Modifier.width(130.dp), style = MaterialTheme.typography.subtitle2, fontFamily = FontFamily.Monospace)
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(if (environmentAvailable) displayValue(value) else "读取失败", style = MaterialTheme.typography.body2, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (changed) Text("待保存：${if (proposed == null) "删除变量" else displayValue(proposed)}", color = MaterialTheme.colors.primary,
                style = MaterialTheme.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(status, color = when (comparison) {
                PresetComparison.DIFFERENT -> MaterialTheme.colors.error
                PresetComparison.MATCH -> MaterialTheme.colors.primary
                else -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            }, style = MaterialTheme.typography.caption)
            if (changed) Text(if (proposed == null) "待删除" else "待保存", color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption)
        }
        Spacer(Modifier.width(14.dp))
        Text("详情 ›", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary)
    }
}

@Composable
private fun ProxyVariableDialog(
    name: String, value: String?, proposed: String?, changed: Boolean, preset: String?, comparison: PresetComparison?,
    environmentAvailable: Boolean, canApply: Boolean, onDismiss: () -> Unit, onApplyPreset: () -> Unit, onDelete: () -> Unit, onUndo: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(620.dp), shape = MaterialTheme.shapes.medium, elevation = 12.dp) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MaterialTheme.typography.h6, fontFamily = FontFamily.Monospace)
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProxyValuePanel("当前值", value, Modifier.fillMaxWidth(), nullLabel = if (environmentAvailable) "未设置" else "读取失败")
                    if (changed) ProxyValuePanel("待保存值", proposed, Modifier.fillMaxWidth(), nullLabel = "删除变量")
                    ProxyValuePanel("预设值", preset, Modifier.fillMaxWidth(), isPreset = true,
                        emptyLabel = if (comparison == PresetComparison.UNCONFIGURED) "未配置" else "（空字符串）", nullLabel = "读取失败")
                }
                Text("此处操作只准备更改，请返回主界面保存。", style = MaterialTheme.typography.caption)
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(enabled = environmentAvailable && proposed != null, onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colors.error)) { Text("清空此项") }
                    TextButton(enabled = changed, onClick = onUndo) { Text("撤销此项") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = canApply, onClick = onApplyPreset) { Text("使用此项预设") }
                }
            }
        }
    }
}

@Composable
private fun ProxyValuePanel(
    title: String,
    value: String?,
    modifier: Modifier,
    isPreset: Boolean = false,
    emptyLabel: String = "（空字符串）",
    nullLabel: String = "未设置",
) {
    Column(
        modifier = modifier.background(
            MaterialTheme.colors.onSurface.copy(alpha = if (isPreset) 0.07f else 0.035f),
            RoundedCornerShape(6.dp),
        ).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f))
        SelectionContainer {
            Text(
                value?.takeUnless(String::isEmpty) ?: if (value == null) nullLabel else emptyLabel,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.body2,
            )
        }
    }
}

@Composable
private fun PresetSettingsDialog(
    initial: ProxyPresets,
    onDismiss: () -> Unit,
    onSave: (ProxyPresets) -> String?,
) {
    var httpProxy by remember(initial) { mutableStateOf(initial.httpProxy) }
    var httpsProxy by remember(initial) { mutableStateOf(initial.httpsProxy) }
    var allProxy by remember(initial) { mutableStateOf(initial.allProxy) }
    var noProxy by remember(initial) { mutableStateOf(initial.noProxy) }
    var saveError by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(520.dp), shape = MaterialTheme.shapes.medium, elevation = 12.dp) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("编辑代理预设", style = MaterialTheme.typography.h6)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PresetInputRow("HTTP_PROXY", httpProxy, { httpProxy = it })
                    PresetInputRow("HTTPS_PROXY", httpsProxy, { httpsProxy = it })
                    PresetInputRow("ALL_PROXY", allProxy, { allProxy = it })
                    PresetInputRow("NO_PROXY", noProxy, { noProxy = it }, lines = 2)
                }
                Text("前三项必填；NO_PROXY 可留空、用逗号分隔。保存预设后需在主界面使用并保存更改。", style = MaterialTheme.typography.caption)
                if (saveError != null) Text(saveError!!, color = MaterialTheme.colors.error, style = MaterialTheme.typography.body2)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = httpProxy.isNotBlank() && httpsProxy.isNotBlank() && allProxy.isNotBlank(),
                        onClick = { saveError = onSave(ProxyPresets(httpProxy, httpsProxy, allProxy, noProxy)) },
                    ) { Text("保存预设") }
                }
            }
        }
    }
}

@Composable
private fun PresetInputRow(name: String, value: String, onValueChange: (String) -> Unit, lines: Int = 1) {
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = Modifier.fillMaxWidth().height(if (lines == 1) 48.dp else 68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, modifier = Modifier.width(122.dp), style = MaterialTheme.typography.subtitle2, fontFamily = FontFamily.Monospace)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).fillMaxHeight()
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.045f), shape)
                .border(1.dp, if (focused) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.24f), shape)
                .padding(horizontal = 12.dp, vertical = if (lines == 1) 12.dp else 10.dp),
            textStyle = MaterialTheme.typography.body2.copy(
                color = MaterialTheme.colors.onSurface,
                fontFamily = FontFamily.Monospace,
            ),
            singleLine = lines == 1,
            minLines = lines,
            maxLines = lines,
            interactionSource = interactionSource,
        )
    }
}
