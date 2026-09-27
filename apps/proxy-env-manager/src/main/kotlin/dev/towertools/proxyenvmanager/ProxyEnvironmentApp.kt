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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ProxyEnvironmentApp(service: ProxyEnvironmentService) {
    val initialEnvironment = remember { runCatching(service::read) }
    val initialPresets = remember { runCatching(service::presets) }
    var environment by remember { mutableStateOf(initialEnvironment.getOrNull()) }
    var presets by remember { mutableStateOf(initialPresets.getOrNull()) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedVariable by remember { mutableStateOf<String?>(null) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(when {
        initialPresets.isFailure -> "读取预设失败：${initialPresets.exceptionOrNull()?.message}"
        initialEnvironment.isFailure -> "读取环境变量失败：${initialEnvironment.exceptionOrNull()?.message}"
        else -> "读取的是当前用户环境变量，不包含系统级变量。"
    }) }
    var isError by remember { mutableStateOf(initialEnvironment.isFailure || initialPresets.isFailure) }

    fun currentValues(value: ProxyEnvironment?): Map<String, String?> = linkedMapOf(
        ProxyVariables.HTTP_PROXY to value?.httpProxy,
        ProxyVariables.HTTPS_PROXY to value?.httpsProxy,
        ProxyVariables.ALL_PROXY to value?.allProxy,
        ProxyVariables.NO_PROXY to value?.noProxy,
    )

    fun execute(successMessage: String, operation: () -> ProxyEnvironment) {
        runCatching(operation)
            .onSuccess {
                environment = it
                message = successMessage
                isError = false
            }
            .onFailure {
                AppLog.logger.warning("Proxy environment operation failed: ${it.message}")
                message = "操作失败：${it.message ?: it.javaClass.simpleName}"
                isError = true
            }
    }

    val values = currentValues(environment)
    val presetValues = presets?.asMap().orEmpty()
    val configured = presets?.isConfigured() == true
    val comparisons = ProxyVariables.names.associateWith { name ->
        if (environment == null) null else presetValues[name]?.let { comparePreset(name, values[name], it, configured) }
    }
    val differentCount = comparisons.values.count { it == PresetComparison.DIFFERENT }
    val summary = when {
        presets == null -> "预设读取失败"
        !configured -> "尚未完成预设配置"
        environment == null -> "当前环境变量读取失败"
        differentCount == 0 -> "四项均与预设一致"
        else -> "$differentCount 项与预设不同"
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("代理环境变量", style = MaterialTheme.typography.h5)
                Text("当前用户环境变量", style = MaterialTheme.typography.caption)
            }
            TextButton(onClick = { execute("已刷新当前用户环境变量。", service::read) }) { Text("刷新") }
            OutlinedButton(onClick = {
                runCatching(service::presets)
                    .onSuccess { presets = it; showSettings = true }
                    .onFailure {
                        message = "读取预设失败：${it.message ?: it.javaClass.simpleName}"
                        isError = true
                    }
            }) { Text("编辑预设") }
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = 1.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(summary, modifier = Modifier.weight(1f), style = MaterialTheme.typography.subtitle1)
                if (configured && environment != null && differentCount > 0) {
                    Button(onClick = {
                        execute("已应用全部预设。请重启需要使用新配置的程序。", service::setLocalProxy)
                    }) { Text("应用全部预设") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = 1.dp) {
            Column {
                ProxyVariables.names.forEachIndexed { index, name ->
                    if (index > 0) Divider()
                    ProxyVariableRow(
                        name = name,
                        value = values[name],
                        comparison = comparisons[name],
                        environmentAvailable = environment != null,
                        onOpen = { selectedVariable = name },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                modifier = Modifier.weight(1f),
                color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                style = MaterialTheme.typography.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                enabled = values.values.any { it != null },
                onClick = { confirmClearAll = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colors.error),
            ) { Text("删除全部当前值") }
        }
    }

    selectedVariable?.let { name ->
        ProxyVariableDialog(
            name = name,
            value = values[name],
            preset = presetValues[name],
            comparison = comparisons[name],
            environmentAvailable = environment != null,
            canApply = configured && environment != null && comparisons[name] != PresetComparison.MATCH,
            onDismiss = { selectedVariable = null },
            onApplyPreset = {
                selectedVariable = null
                execute("已为 $name 应用预设。请重启需要使用新配置的程序。") { service.applyPreset(name) }
            },
            onDelete = {
                selectedVariable = null
                execute("已删除 $name。请重启需要使用新配置的程序。") { service.clear(name) }
            },
        )
    }

    if (showSettings && presets != null) {
        PresetSettingsDialog(
            initial = presets!!,
            onDismiss = { showSettings = false },
            onSave = { edited ->
                runCatching { service.savePresets(edited) }
                    .fold(onSuccess = {
                        presets = it
                        showSettings = false
                        message = "预设已保存，尚未应用到环境变量。"
                        isError = false
                        null
                    }, onFailure = {
                        message = "保存预设失败：${it.message ?: it.javaClass.simpleName}"
                        isError = true
                        message
                    })
            },
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("删除全部当前值？") },
            text = { Text("将从当前用户环境变量中删除 HTTP_PROXY、HTTPS_PROXY、ALL_PROXY 和 NO_PROXY。已保存的预设会保留。") },
            confirmButton = {
                Button(
                    onClick = {
                        confirmClearAll = false
                        execute("已删除四个代理环境变量。请重启需要使用新配置的程序。", service::clear)
                    },
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = MaterialTheme.colors.error,
                        contentColor = MaterialTheme.colors.onError,
                    ),
                ) { Text("删除全部") }
            },
            dismissButton = { TextButton(onClick = { confirmClearAll = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProxyVariableRow(
    name: String,
    value: String?,
    comparison: PresetComparison?,
    environmentAvailable: Boolean,
    onOpen: () -> Unit,
) {
    val status = when (comparison) {
        PresetComparison.MATCH -> "一致"
        PresetComparison.DIFFERENT -> "不同"
        PresetComparison.UNCONFIGURED -> "未配置"
        null -> "未知"
    }
    Row(
        modifier = Modifier.fillMaxWidth().height(58.dp).clickable(onClick = onOpen).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, modifier = Modifier.width(130.dp), style = MaterialTheme.typography.subtitle2, fontFamily = FontFamily.Monospace)
        Text(
            value?.takeUnless(String::isEmpty) ?: if (value == null) {
                if (environmentAvailable) "未设置" else "读取失败"
            } else "（空字符串）",
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.body2,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            status,
            color = when (comparison) {
                PresetComparison.DIFFERENT -> MaterialTheme.colors.error
                PresetComparison.MATCH -> MaterialTheme.colors.primary
                else -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            },
            style = MaterialTheme.typography.caption,
        )
        Spacer(Modifier.width(14.dp))
        Text("详情 ›", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary)
    }
}

@Composable
private fun ProxyVariableDialog(
    name: String,
    value: String?,
    preset: String?,
    comparison: PresetComparison?,
    environmentAvailable: Boolean,
    canApply: Boolean,
    onDismiss: () -> Unit,
    onApplyPreset: () -> Unit,
    onDelete: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(620.dp), shape = MaterialTheme.shapes.medium, elevation = 12.dp) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MaterialTheme.typography.h6, fontFamily = FontFamily.Monospace)
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProxyValuePanel(
                        "当前值", value, modifier = Modifier.fillMaxWidth(),
                        nullLabel = if (environmentAvailable) "未设置" else "读取失败",
                    )
                    ProxyValuePanel(
                        "预设值", preset, modifier = Modifier.fillMaxWidth(), isPreset = true,
                        emptyLabel = if (comparison == PresetComparison.UNCONFIGURED) "未配置" else "（空字符串）",
                        nullLabel = "读取失败",
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = value != null,
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colors.error),
                    ) { Text("删除当前值") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = canApply, onClick = onApplyPreset) { Text("应用此项") }
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
                Text("前三项必填；NO_PROXY 可留空、用逗号分隔。保存后再应用。", style = MaterialTheme.typography.caption)
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
