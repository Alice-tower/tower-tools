package dev.towertools.stratalauncher.experimental

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.nio.file.Path

@Composable
fun ExperimentalSettingsDialog(
    entries: List<EspConfigEntry>,
    loading: Boolean,
    busyPath: Path?,
    message: String,
    error: String,
    onRefresh: () -> Unit,
    onToggle: (EspConfigEntry, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val busy = loading || busyPath != null
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(740.dp).height(500.dp).testTag("experimental-dialog"),
            shape = MaterialTheme.shapes.medium, elevation = 8.dp) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("实验性设置", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
                    TextButton(onRefresh, enabled = !busy, modifier = Modifier.testTag("refresh-base-configs")) { Text("刷新") }
                }
                Text("基础配置 · ESP", style = MaterialTheme.typography.subtitle2)
                Text("开关状态来自配置文件；保存后需重启模型，才会影响运行中的服务。",
                    style = MaterialTheme.typography.caption)
                Divider()
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (loading) Text("正在读取基础配置…", modifier = Modifier.testTag("base-configs-loading"))
                        else if (entries.isEmpty() && error.isBlank()) Text("未找到已安装的基础配置。", modifier = Modifier.testTag("base-configs-empty"))
                        entries.forEachIndexed { index, entry ->
                            Row(Modifier.fillMaxWidth().testTag("base-config-$index"), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(entry.model, style = MaterialTheme.typography.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(entry.path.toString(), style = MaterialTheme.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (entry.error != null) Text(entry.error, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                                    else {
                                        Text(if (entry.enabled) "ESP 已启用" else "ESP 未启用", style = MaterialTheme.typography.caption,
                                            color = if (entry.enabled) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = .65f))
                                        if (!entry.enabled && entry.unavailable != null) Text(entry.unavailable, style = MaterialTheme.typography.caption)
                                    }
                                }
                                OutlinedButton(onClick = { onToggle(entry, !entry.enabled) },
                                    enabled = !busy && entry.error == null && (entry.enabled || entry.unavailable == null),
                                    modifier = Modifier.testTag("toggle-esp-$index")) {
                                    Text(if (busyPath == entry.path) "保存中…" else if (entry.enabled) "关闭 ESP" else "启用 ESP")
                                }
                            }
                            Divider()
                        }
                    }
                    VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption,
                    modifier = Modifier.testTag("experimental-error"))
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.caption, modifier = Modifier.testTag("experimental-feedback"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, enabled = !busy, modifier = Modifier.testTag("close-experimental")) { Text("关闭") }
                }
            }
        }
    }
}
