package dev.towertools.stratalauncher.tabs.presets

import androidx.compose.foundation.BorderStroke
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
fun PresetListTab(
    entries: List<PresetEntry>,
    loading: Boolean,
    busy: Boolean,
    selected: PresetEntry?,
    globalSelected: Boolean,
    onSelectGlobal: () -> Unit,
    onSelect: (PresetEntry) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(Modifier.fillMaxWidth().testTag("global-operations").selectable(globalSelected, enabled = !busy,
            role = Role.RadioButton, onClick = onSelectGlobal),
            color = if (globalSelected) MaterialTheme.colors.primary.copy(alpha = .08f) else MaterialTheme.colors.surface,
            border = BorderStroke(1.dp, if (globalSelected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = .15f)),
            shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(globalSelected, onClick = null, enabled = !busy)
                Text("全局操作")
            }
        }
        Text("全部预设（${entries.size}）", style = MaterialTheme.typography.subtitle1)
        when {
            loading -> Text("正在读取预设…", modifier = Modifier.testTag("presets-loading"))
            entries.isEmpty() -> Text("当前目录没有预设，请选择预设目录或刷新。", modifier = Modifier.testTag("presets-empty"))
            else -> entries.forEachIndexed { index, entry ->
                key(entry.path) {
                    PresetListRow(entry, index, !globalSelected && selected?.path == entry.path, busy, onSelect)
                }
            }
        }
    }
}

@Composable
private fun PresetListRow(
    entry: PresetEntry,
    index: Int,
    selected: Boolean,
    busy: Boolean,
    onSelect: (PresetEntry) -> Unit,
) {
    Box(Modifier.fillMaxWidth().testTag("preset-row-$index").selectable(selected, enabled = !busy,
        role = Role.RadioButton, onClick = { onSelect(entry) })) {
        Surface(Modifier.fillMaxWidth(), color = if (selected) MaterialTheme.colors.primary.copy(alpha = .08f) else MaterialTheme.colors.surface,
            border = BorderStroke(1.dp, if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = .15f)),
            shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected, onClick = null, enabled = !busy)
                Column(Modifier.weight(1f)) {
                    Text(entry.name, modifier = Modifier.testTag("preset-name-$index"))
                    if (entry.error != null) Text(entry.error, color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption)
                }
            }
        }
    }
}
