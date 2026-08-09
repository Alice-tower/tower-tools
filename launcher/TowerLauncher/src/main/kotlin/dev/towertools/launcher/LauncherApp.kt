package dev.towertools.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp

@Composable
fun LauncherApp(repository: CatalogRepository) {
    var tools by remember { mutableStateOf(repository.load()) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("工具塔", style = MaterialTheme.typography.h4)
                Text("轻量启动 Alice-tower 的本地工具", color = Color.Gray)
            }
            TextButton(onClick = {
                tools = repository.load()
                message = "工具清单已刷新"
            }) {
                Text("刷新")
            }
        }

        message?.let {
            Text(it, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colors.primary)
        }
        Spacer(Modifier.height(20.dp))

        if (tools.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有登记工具", style = MaterialTheme.typography.h6)
                    Text("使用 scripts/New-Tool.ps1 创建第一个工具", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tools.groupBy { it.category }.forEach { (category, categoryTools) ->
                    item { Text(category, style = MaterialTheme.typography.h6, modifier = Modifier.padding(top = 8.dp)) }
                    items(categoryTools, key = { it.id }) { tool ->
                        ToolCard(
                            tool = tool,
                            onMessage = { message = it },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ToolCard(tool: LauncherTool, onMessage: (String) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Press) {
                if (it.buttons.isSecondaryPressed) menuExpanded = true
            }
            .combinedClickable(
                onClick = {},
                onDoubleClick = {
                    runCatching { ToolActions.launch(tool) }
                        .onFailure { onMessage(it.message ?: "启动失败") }
                },
                onLongClick = { menuExpanded = true },
            ),
        elevation = 2.dp,
    ) {
        Box {
            Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tool.displayName, style = MaterialTheme.typography.h6)
                    Text(tool.description, color = Color.Gray)
                    Text("${tool.projectName} · ${tool.version}", style = MaterialTheme.typography.caption)
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = {
                    runCatching { ToolActions.launch(tool) }
                        .onFailure { onMessage(it.message ?: "启动失败") }
                }) {
                    Text("启动")
                }
            }

            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(onClick = {
                    menuExpanded = false
                    runCatching { ToolActions.openDirectory(tool) }
                        .onFailure { onMessage(it.message ?: "无法打开目录") }
                }) { Text("打开所在目录") }
                DropdownMenuItem(onClick = {
                    menuExpanded = false
                    runCatching { ToolActions.openLogs(tool) }
                        .onFailure { onMessage(it.message ?: "无法打开日志") }
                }) { Text("查看日志") }
            }
        }
    }
}
