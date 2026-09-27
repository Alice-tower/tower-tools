package dev.towertools.launcher.tabs.repositories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.awt.Frame
import java.nio.file.Paths
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@Composable
fun RepositoryTab(controller: RepositoryController, state: RepositoryTabState, owner: Frame) {
    val locations by state.locations
    val message by state.message
    val isError by state.isError
    val isRefreshing by state.isRefreshing
    val repositories = discoveredRepositories(locations)
    var settingsOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("仓库", style = MaterialTheme.typography.h5)
                Text(
                    "${repositories.size} 个仓库 · ${locations.size} 个扫描路径",
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray,
                )
            }
            OutlinedButton(
                onClick = state::refreshAll,
                enabled = locations.isNotEmpty() && !isRefreshing,
            ) { Text(if (isRefreshing) "扫描中" else "刷新") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { settingsOpen = true }) { Text("设置") }
        }

        message?.let {
            Text(it, color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.primary)
        }

        if (repositories.isEmpty()) {
            Card(Modifier.fillMaxWidth().weight(1f), elevation = 1.dp) {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val title = when {
                        locations.isEmpty() -> "还没有添加扫描路径"
                        locations.none { it.scan != null } -> "尚未扫描仓库"
                        else -> "没有发现仓库"
                    }
                    Text(title, style = MaterialTheme.typography.h6)
                    Text(
                        if (locations.isEmpty()) "点击右上角“设置”添加目录。" else "点击右上角“刷新”扫描所有已添加的目录。",
                        color = Color.Gray,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repositories, key = { "${it.locationId}/${it.name}" }) { repository ->
                    Card(Modifier.fillMaxWidth(), elevation = 2.dp) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(repository.name, style = MaterialTheme.typography.subtitle1)
                            SelectionContainer {
                                Text(repository.path.toString(), style = MaterialTheme.typography.body2, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        RepositorySettingsDialog(controller, state, owner, onDismiss = { settingsOpen = false })
    }
}

@Composable
private fun RepositorySettingsDialog(
    controller: RepositoryController,
    state: RepositoryTabState,
    owner: Frame,
    onDismiss: () -> Unit,
) {
    var locations by state.locations
    var pathInput by state.pathInput
    var message by state.message
    var isError by state.isError
    val isRefreshing by state.isRefreshing
    val refreshingId by state.refreshingId
    var scanFailures by state.scanFailures

    fun showMessage(text: String, error: Boolean = false) {
        message = text
        isError = error
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("仓库设置") },
        text = {
            Column(
                Modifier.widthIn(min = 460.dp, max = 620.dp).heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("扫描路径", style = MaterialTheme.typography.subtitle1)
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目录路径") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                JFileChooser().apply {
                                    dialogTitle = "选择要扫描的目录"
                                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                                    isAcceptAllFileFilterUsed = false
                                }.let { chooser ->
                                    if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                                        pathInput = chooser.selectedFile.absolutePath
                                    }
                                }
                            }.onFailure { showMessage("选择目录失败：${it.message}", error = true) }
                        },
                        enabled = !isRefreshing,
                    ) { Text("选择目录") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            runCatching { controller.add(Paths.get(pathInput.trim())) }
                                .onSuccess {
                                    locations = it
                                    pathInput = ""
                                    showMessage("路径已添加，点击“刷新”开始扫描。")
                                }
                                .onFailure { showMessage("添加失败：${it.message}", error = true) }
                        },
                        enabled = !isRefreshing && pathInput.isNotBlank(),
                    ) { Text("添加") }
                }

                message?.let {
                    Text(it, color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.primary)
                }

                if (locations.isEmpty()) {
                    Text("还没有添加路径。", color = Color.Gray)
                } else {
                    locations.forEach { location ->
                        Card(Modifier.fillMaxWidth(), elevation = 1.dp) {
                            Column(
                                Modifier.fillMaxWidth().padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    SelectionContainer(Modifier.weight(1f)) {
                                        Text(location.path.toString(), style = MaterialTheme.typography.body2)
                                    }
                                    TextButton(
                                        onClick = {
                                            runCatching { controller.remove(location.id) }
                                                .onSuccess {
                                                    locations = it
                                                    scanFailures = scanFailures - location.id
                                                    showMessage("已移除扫描路径，原目录未受影响。")
                                                }
                                                .onFailure { showMessage("移除失败：${it.message}", error = true) }
                                        },
                                        enabled = !isRefreshing,
                                    ) { Text("移除") }
                                }
                                val scan = location.scan
                                Text(
                                    when {
                                        refreshingId == location.id -> "正在扫描…"
                                        scan == null -> "尚未扫描"
                                        else -> "${scan.directoryCount} 个子目录 · ${scan.repositoryNames.size} 个仓库 · 上次扫描 ${timeFormat.format(scan.scannedAt.atZone(ZoneId.systemDefault()))}"
                                    },
                                    style = MaterialTheme.typography.caption,
                                    color = Color.Gray,
                                )
                                scanFailures[location.id]?.let { error ->
                                    Text("扫描失败：$error", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
