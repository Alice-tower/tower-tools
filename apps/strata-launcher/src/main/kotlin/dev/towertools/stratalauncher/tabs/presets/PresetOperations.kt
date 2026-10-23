package dev.towertools.stratalauncher.tabs.presets

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.towertools.stratalauncher.PresetEntry

@Composable
fun PresetOperations(
    selection: PresetCommandSelection,
    busy: Boolean,
    onChange: (PresetCommandSelection) -> Unit,
    onOpenJson: (PresetEntry) -> Unit,
    onOpenExperimental: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (selection.global) "全局操作" else "预设操作", style = MaterialTheme.typography.subtitle1)
                selection.actions.forEach { (action, title) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).testTag("preset-action-$action")
                        .selectable(selection.action == action, enabled = !busy, role = Role.RadioButton,
                            onClick = { onChange(selection.chooseAction(action)) }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selection.action == action, onClick = null, enabled = !busy)
                        Text(title, style = MaterialTheme.typography.body2,
                            color = if (action == "delete-preset") MaterialTheme.colors.error else LocalContentColor.current)
                    }
                }
                Divider()
                when (selection.action) {
                    "launch" -> Text("按所选预设启动，不附加其他参数。", style = MaterialTheme.typography.caption)
                    "status" -> Text("查看所有 launch 服务的状态。", style = MaterialTheme.typography.caption)
                    "list-presets" -> Text("列出当前目录中的启动预设。", style = MaterialTheme.typography.caption)
                    "stop" -> Text(if (selection.global) "停止所有由 launch 管理的服务。" else "仅停止左侧所选预设对应的服务。",
                        style = MaterialTheme.typography.caption)
                    "show-key" -> Text("查看所选预设的 API 密钥，结果会显示在终端。", style = MaterialTheme.typography.caption)
                    "delete-preset" -> Text("由 Strata 删除所选预设。执行完成后请刷新列表，结果以终端为准。", style = MaterialTheme.typography.caption)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        if (selection.global) {
            Divider()
            OutlinedButton(onOpenExperimental, enabled = !busy,
                modifier = Modifier.fillMaxWidth().testTag("open-experimental-settings")) { Text("实验性设置") }
        } else {
            Divider()
            val entry = selection.preset
            OutlinedButton(onClick = { entry?.let(onOpenJson) }, enabled = !busy && entry != null,
                modifier = Modifier.fillMaxWidth().testTag("open-preset-json")) { Text("打开 JSON") }
        }
    }
}
