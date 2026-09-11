package dev.towertools.launcher

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val UNCATEGORIZED = "未分类"

@Composable
fun LauncherApp(repository: CatalogRepository) {
    var tools by remember { mutableStateOf(repository.load()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingTool by remember { mutableStateOf<LauncherTool?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val categories = orderedCategories(tools)

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("工具塔", style = MaterialTheme.typography.h4)
                Text("轻量启动 Alice-tower 的本地工具", color = Color.Gray)
            }
            TextButton(onClick = {
                tools = repository.load()
                if (selectedCategory != null && tools.none { it.category == selectedCategory }) {
                    selectedCategory = null
                }
                message = "工具清单已刷新"
            }) {
                Text("刷新")
            }
        }

        Spacer(Modifier.height(16.dp))
        if (tools.isEmpty()) {
            EmptyToolList()
        } else {
            Row(modifier = Modifier.weight(1f)) {
                CategorySidebar(
                    tools = tools,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onSelect = { selectedCategory = it },
                )
                Spacer(Modifier.width(18.dp))
                ToolList(
                    tools = tools,
                    categories = categories,
                    selectedCategory = selectedCategory,
                    onMessage = { message = it },
                    onEdit = { saveError = null; editingTool = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            message ?: "已加载 ${tools.size} 个工具 · 双击工具可直接启动",
            style = MaterialTheme.typography.caption,
            color = if (message == null) Color.Gray else MaterialTheme.colors.primary,
        )
    }

    editingTool?.let { tool ->
        EditToolDialog(
            tool = tool,
            allTools = tools,
            categories = categories,
            saveError = saveError,
            onDismiss = { editingTool = null },
            onSave = { category, order ->
                runCatching {
                    repository.updateOverride(tool.id, category, order)
                    tools = repository.load()
                    if (selectedCategory != null) selectedCategory = category
                    editingTool = null
                    message = "已更新 ${tool.displayName} 的分类和排序"
                }.onFailure {
                    saveError = it.message ?: "保存分类和排序失败"
                }
            },
        )
    }
}

@Composable
private fun EmptyToolList() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("还没有登记工具", style = MaterialTheme.typography.h6)
            Text("使用 scripts/New-Tool.ps1 创建第一个工具", color = Color.Gray)
        }
    }
}

@Composable
private fun CategorySidebar(
    tools: List<LauncherTool>,
    categories: List<String>,
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
) {
    Card(modifier = Modifier.width(184.dp).fillMaxHeight(), elevation = 1.dp) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                CategoryItem(
                    name = "全部",
                    count = tools.size,
                    selected = selectedCategory == null,
                    onClick = { onSelect(null) },
                )
            }
            items(categories, key = { it }) { category ->
                CategoryItem(
                    name = category,
                    count = tools.count { it.category == category },
                    selected = selectedCategory == category,
                    onClick = { onSelect(category) },
                )
            }
        }
    }
}

@Composable
private fun CategoryItem(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colors.primary.copy(alpha = 0.14f) else Color.Transparent,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count.toString(), color = Color.Gray)
        }
    }
}

@Composable
private fun ToolList(
    tools: List<LauncherTool>,
    categories: List<String>,
    selectedCategory: String?,
    onMessage: (String) -> Unit,
    onEdit: (LauncherTool) -> Unit,
    modifier: Modifier,
) {
    val visibleTools = selectedCategory?.let { category -> tools.filter { it.category == category } } ?: tools

    Column(modifier = modifier.fillMaxHeight()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(selectedCategory ?: "全部工具", style = MaterialTheme.typography.h5, modifier = Modifier.weight(1f))
            Text("${visibleTools.size} 个 · 数字越小越靠前", color = Color.Gray)
        }
        Spacer(Modifier.height(8.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selectedCategory == null) {
                categories.forEach { category ->
                    val categoryTools = tools.filter { it.category == category }
                    item(key = "category-$category") {
                        Text(
                            "$category  ·  ${categoryTools.size}",
                            style = MaterialTheme.typography.subtitle1,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(categoryTools, key = { it.id }) { tool ->
                        ToolRow(tool, onMessage, { onEdit(tool) })
                    }
                }
            } else {
                items(visibleTools, key = { it.id }) { tool ->
                    ToolRow(tool, onMessage, { onEdit(tool) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ToolRow(tool: LauncherTool, onMessage: (String) -> Unit, onEdit: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
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
        elevation = 1.dp,
    ) {
        Box {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tool.order.toString(),
                    modifier = Modifier.width(52.dp),
                    color = MaterialTheme.colors.primary,
                    style = MaterialTheme.typography.subtitle1,
                )
                Column(modifier = Modifier.width(178.dp)) {
                    Text(tool.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${tool.projectName} · ${tool.version}", style = MaterialTheme.typography.caption)
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    tool.description,
                    modifier = Modifier.weight(1f),
                    color = Color.Gray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(12.dp))
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
                    onEdit()
                }) { Text("编辑分类和排序") }
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

@Composable
private fun EditToolDialog(
    tool: LauncherTool,
    allTools: List<LauncherTool>,
    categories: List<String>,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (category: String, order: Int) -> Unit,
) {
    var category by remember(tool.id) { mutableStateOf(tool.category) }
    var orderText by remember(tool.id) { mutableStateOf(tool.order.toString()) }
    var categoryMenuExpanded by remember(tool.id) { mutableStateOf(false) }
    val normalizedCategory = category.trim().ifEmpty { UNCATEGORIZED }
    val parsedOrder = orderText.toIntOrNull()
    val references = allTools
        .filter { it.id != tool.id && it.category == normalizedCategory }
        .sortedWith(compareBy<LauncherTool> { it.order }.thenBy { it.displayName })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${tool.displayName}") },
        text = {
            Column(modifier = Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("排序数字越小越靠前；相同数字按工具名称排列。", color = Color.Gray)
                saveError?.let { Text(it, color = MaterialTheme.colors.error) }
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("分类") },
                    singleLine = true,
                )
                Box {
                    TextButton(onClick = { categoryMenuExpanded = true }) {
                        Text("选择已有分类")
                    }
                    DropdownMenu(
                        expanded = categoryMenuExpanded,
                        onDismissRequest = { categoryMenuExpanded = false },
                    ) {
                        categories.forEach { existingCategory ->
                            DropdownMenuItem(onClick = {
                                category = existingCategory
                                categoryMenuExpanded = false
                            }) {
                                Text(existingCategory)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = orderText,
                    onValueChange = { orderText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("排序数字") },
                    singleLine = true,
                    isError = parsedOrder == null,
                )
                if (parsedOrder == null) {
                    Text("请输入有效的整数，例如 0、10 或 -10。", color = MaterialTheme.colors.error)
                }
                Divider()
                Text("“$normalizedCategory”中的排序参考", style = MaterialTheme.typography.subtitle2)
                if (references.isEmpty()) {
                    Text("这个分类中暂无其他工具。", color = Color.Gray)
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        references.forEach { reference ->
                            Row {
                                Text(reference.order.toString(), modifier = Modifier.width(60.dp), color = MaterialTheme.colors.primary)
                                Text(reference.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = parsedOrder != null,
                onClick = { onSave(normalizedCategory, checkNotNull(parsedOrder)) },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

private fun orderedCategories(tools: List<LauncherTool>): List<String> = tools
    .map { it.category }
    .distinct()
    .sortedWith(compareBy<String> { it == UNCATEGORIZED }.thenBy { it })
