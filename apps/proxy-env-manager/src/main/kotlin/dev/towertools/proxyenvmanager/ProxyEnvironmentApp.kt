package dev.towertools.proxyenvmanager

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun ProxyEnvironmentApp(service: ProxyEnvironmentService) {
    val initialEnvironment = remember { runCatching(service::read) }
    val initialPresets = remember { runCatching(service::presets) }
    var environment by remember { mutableStateOf(initialEnvironment.getOrNull()) }
    var presets by remember { mutableStateOf(initialPresets.getOrNull()) }
    var showSettings by remember { mutableStateOf(false) }
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

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("代理环境变量", style = MaterialTheme.typography.h5)
                Text("当前用户环境变量", style = MaterialTheme.typography.caption)
            }
            OutlinedButton(onClick = {
                runCatching(service::presets)
                    .onSuccess { presets = it; showSettings = true }
                    .onFailure {
                        message = "读取预设失败：${it.message ?: it.javaClass.simpleName}"
                        isError = true
                    }
            }) { Text("设置预设") }
        }

        val values = currentValues(environment)
        val presetValues = presets?.asMap().orEmpty()
        Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("变量", modifier = Modifier.width(110.dp), style = MaterialTheme.typography.caption)
                    Text("当前值", modifier = Modifier.weight(1f), style = MaterialTheme.typography.caption)
                    Text("预设值", modifier = Modifier.weight(1f), style = MaterialTheme.typography.caption)
                    Text("单项操作", modifier = Modifier.width(180.dp), style = MaterialTheme.typography.caption)
                }
                ProxyVariables.names.forEach { name ->
                    Divider(modifier = Modifier.padding(vertical = 2.dp))
                    ProxyVariableRow(
                        name, values[name], presetValues[name], presets?.isConfigured() == true,
                        onApplyPreset = {
                            execute("已为 $name 使用预设。请重启需要使用新配置的程序。") { service.applyPreset(name) }
                        },
                        onDelete = {
                            execute("已删除 $name。请重启需要使用新配置的程序。") { service.clear(name) }
                        },
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = presets?.isConfigured() == true, onClick = {
                execute("已应用预设。请重启需要使用新配置的程序。", service::setLocalProxy)
            }) {
                Text("全部应用预设")
            }
            OutlinedButton(onClick = { execute("已刷新", service::read) }) {
                Text("刷新")
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { execute("已删除四个代理环境变量。请重启需要使用新配置的程序。", service::clear) },
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = MaterialTheme.colors.error,
                    contentColor = MaterialTheme.colors.onError,
                ),
            ) {
                Text("全部删除")
            }
        }

        Text(
            message,
            color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.primary,
            style = MaterialTheme.typography.caption,
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
                        message = "预设已保存；点击单项“使用预设”或“全部应用预设”后才会修改环境变量。"
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置预设") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("保存预设不会立即修改环境变量。")
                OutlinedTextField(httpProxy, { httpProxy = it }, label = { Text("HTTP_PROXY") }, singleLine = true)
                OutlinedTextField(httpsProxy, { httpsProxy = it }, label = { Text("HTTPS_PROXY") }, singleLine = true)
                OutlinedTextField(allProxy, { allProxy = it }, label = { Text("ALL_PROXY") }, singleLine = true)
                OutlinedTextField(noProxy, { noProxy = it }, label = { Text("NO_PROXY") }, singleLine = true)
                Text("HTTP_PROXY、HTTPS_PROXY 和 ALL_PROXY 不能为空；NO_PROXY 可以留空。", style = MaterialTheme.typography.caption)
                if (saveError != null) Text(saveError!!, color = MaterialTheme.colors.error)
            }
        },
        confirmButton = {
            Button(
                enabled = httpProxy.isNotBlank() && httpsProxy.isNotBlank() && allProxy.isNotBlank(),
                onClick = { saveError = onSave(ProxyPresets(httpProxy, httpsProxy, allProxy, noProxy)) },
            ) { Text("保存") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ProxyVariableRow(
    name: String,
    value: String?,
    preset: String?,
    presetsConfigured: Boolean,
    onApplyPreset: () -> Unit,
    onDelete: () -> Unit,
) {
    val comparison = preset?.let { comparePreset(name, value, it, presetsConfigured) }
    Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.width(110.dp)) {
            Text(name, style = MaterialTheme.typography.subtitle2)
            if (comparison != null) {
                Text(
                    when (comparison) {
                        PresetComparison.UNCONFIGURED -> "未配置"
                        PresetComparison.MATCH -> "一致"
                        PresetComparison.DIFFERENT -> "不同"
                    },
                    color = when (comparison) {
                        PresetComparison.UNCONFIGURED -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        PresetComparison.MATCH -> MaterialTheme.colors.primary
                        PresetComparison.DIFFERENT -> MaterialTheme.colors.error
                    },
                    style = MaterialTheme.typography.caption,
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            SelectionContainer {
                Text(
                    value?.takeUnless(String::isEmpty) ?: if (value == null) "未设置" else "（空字符串）",
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    maxLines = 1,
                    softWrap = false,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.body2,
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f)
                .background(MaterialTheme.colors.onSurface.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            SelectionContainer {
                Text(
                    preset?.takeUnless(String::isEmpty) ?: if (preset == null) "读取失败"
                        else if (comparison == PresetComparison.UNCONFIGURED) "未配置" else "（空字符串）",
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    maxLines = 1,
                    softWrap = false,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
        }
        Row(modifier = Modifier.width(180.dp).padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = presetsConfigured, onClick = onApplyPreset) { Text("使用预设") }
            OutlinedButton(
                onClick = onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colors.error),
            ) { Text("删除") }
        }
    }
}
