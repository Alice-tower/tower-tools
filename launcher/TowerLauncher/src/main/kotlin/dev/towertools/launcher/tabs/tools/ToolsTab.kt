package dev.towertools.launcher.tabs.tools

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import dev.towertools.launcher.TabFeedbackBar
import dev.towertools.launcher.TabPageHeader
import kotlin.math.abs
import kotlin.math.roundToInt

private const val UNCATEGORIZED = "未分类"

internal sealed interface ToolFilter {
    data object Favorites : ToolFilter
    data object All : ToolFilter
    data class Category(val name: String) : ToolFilter
}

internal fun defaultFilter(tools: List<LauncherTool>): ToolFilter =
    if (tools.any { it.favorite }) ToolFilter.Favorites else ToolFilter.All

internal fun validFilter(filter: ToolFilter, tools: List<LauncherTool>): ToolFilter = when (filter) {
    ToolFilter.Favorites -> if (tools.any { it.favorite }) filter else ToolFilter.All
    ToolFilter.All -> filter
    is ToolFilter.Category -> if (tools.any { it.category == filter.name }) filter else ToolFilter.All
}

@Composable
internal fun ToolsTab(repository: CatalogRepository, state: ToolsTabState) {
    var tools by state.tools
    var categoryOrder by state.categoryOrder
    var selectedFilter by state.selectedFilter
    var message by state.message
    var messageIsError by state.messageIsError
    var editingTool by state.editingTool
    var saveError by state.saveError
    var settingsOpen by remember { mutableStateOf(false) }
    val categories = orderedCategories(tools, categoryOrder)

    fun showMessage(text: String, error: Boolean = false) {
        message = text
        messageIsError = error
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 4.dp)) {
        TabPageHeader(
            title = "工具塔",
            subtitle = "${tools.size} 个工具",
            onRefresh = {
                tools = repository.load()
                val orderResult = repository.loadCategoryOrderResult()
                categoryOrder = orderResult.getOrDefault(emptyList())
                selectedFilter = validFilter(selectedFilter, tools)
                orderResult.fold(
                    onSuccess = { showMessage("工具清单已刷新") },
                    onFailure = { showMessage("工具清单已刷新，但分类顺序读取失败：${it.message ?: "未知错误"}", error = true) },
                )
            },
            onSettings = { settingsOpen = true },
        )

        Spacer(Modifier.height(12.dp))
        if (tools.isEmpty()) {
            EmptyToolList(Modifier.weight(1f))
        } else {
            Row(modifier = Modifier.weight(1f)) {
                CategorySidebar(
                    tools = tools,
                    categories = categories,
                    selectedFilter = selectedFilter,
                    onSelect = { selectedFilter = it },
                    onOrderChange = { categoryOrder = it },
                    onOrderCancel = { categoryOrder = it },
                    onOrderCommit = { order, previous ->
                        runCatching {
                            repository.updateCategoryOrder(order)
                            categoryOrder = repository.loadCategoryOrder()
                            showMessage("分类顺序已保存")
                        }.onFailure {
                            categoryOrder = previous
                            showMessage(it.message ?: "保存分类顺序失败", error = true)
                        }
                    },
                )
                Spacer(Modifier.width(18.dp))
                ToolList(
                    tools = tools,
                    categories = categories,
                    selectedFilter = selectedFilter,
                    onMessage = { showMessage(it, error = true) },
                    onEdit = { saveError = null; editingTool = it },
                    onToggleFavorite = { tool ->
                        runCatching {
                            repository.updateOverride(tool.id, tool.category, tool.order, !tool.favorite)
                            tools = repository.load()
                            selectedFilter = validFilter(selectedFilter, tools)
                            showMessage(if (tool.favorite) "已取消收藏 ${tool.displayName}" else "已收藏 ${tool.displayName}")
                        }.onFailure {
                            showMessage(it.message ?: "更新收藏状态失败", error = true)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(2.dp))
        TabFeedbackBar(
            message = message ?: "已加载 ${tools.size} 个工具 · 双击工具可直接启动",
            isError = message != null && messageIsError,
        )
    }

    if (settingsOpen) {
        Dialog(onDismissRequest = { settingsOpen = false }) {
            Surface(
                modifier = Modifier.width(320.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colors.surface,
                elevation = 24.dp,
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text("工具设置", style = MaterialTheme.typography.h6)
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        onClick = {
                            runCatching { ToolActions.openLauncherConfig() }
                                .onFailure { showMessage(it.message ?: "无法打开启动器配置", error = true) }
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("打开启动器配置") }
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = {
                            runCatching { ToolActions.openToolsConfig() }
                                .onFailure { showMessage(it.message ?: "无法打开工具配置", error = true) }
                        },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text("打开工具配置") }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { settingsOpen = false }) { Text("完成") }
                    }
                }
            }
        }
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
                    categoryOrder = repository.loadCategoryOrder()
                    selectedFilter = when (selectedFilter) {
                        is ToolFilter.Category -> ToolFilter.Category(category)
                        else -> validFilter(selectedFilter, tools)
                    }
                    editingTool = null
                    showMessage("已更新 ${tool.displayName} 的设置")
                }.onFailure {
                    saveError = it.message ?: "保存工具设置失败"
                }
            },
        )
    }
}

internal class ToolsTabState(repository: CatalogRepository) {
    private val initialOrder = repository.loadCategoryOrderResult()
    val tools = mutableStateOf(repository.load())
    val categoryOrder = mutableStateOf(initialOrder.getOrDefault(emptyList()))
    val selectedFilter = mutableStateOf(defaultFilter(tools.value))
    val message = mutableStateOf(initialOrder.exceptionOrNull()?.let { "分类顺序读取失败：${it.message ?: "未知错误"}" })
    val messageIsError = mutableStateOf(initialOrder.isFailure)
    val editingTool = mutableStateOf<LauncherTool?>(null)
    val saveError = mutableStateOf<String?>(null)
}

@Composable
private fun EmptyToolList(modifier: Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
    selectedFilter: ToolFilter,
    onSelect: (ToolFilter) -> Unit,
    onOrderChange: (List<String>) -> Unit,
    onOrderCancel: (List<String>) -> Unit,
    onOrderCommit: (List<String>, List<String>) -> Unit,
) {
    val listState = rememberLazyListState()
    val drag = remember { CategoryDragState() }
    var draggingCategory by remember { mutableStateOf<String?>(null) }
    val latestCategories by rememberUpdatedState(categories)
    val latestOnOrderChange by rememberUpdatedState(onOrderChange)
    val latestOnOrderCancel by rememberUpdatedState(onOrderCancel)
    val latestOnOrderCommit by rememberUpdatedState(onOrderCommit)

    Card(modifier = Modifier.width(184.dp).fillMaxHeight(), elevation = 1.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "fixed:favorites") {
                CategoryItem(
                    name = "收藏",
                    count = tools.count { it.favorite },
                    selected = selectedFilter == ToolFilter.Favorites,
                    onClick = { onSelect(ToolFilter.Favorites) },
                )
            }
            item(key = "fixed:all") {
                CategoryItem(
                    name = "全部",
                    count = tools.size,
                    selected = selectedFilter == ToolFilter.All,
                    onClick = { onSelect(ToolFilter.All) },
                )
            }
            items(categories, key = { "category:$it" }) { category ->
                CategoryItem(
                    name = category,
                    count = tools.count { it.category == category },
                    selected = selectedFilter == ToolFilter.Category(category),
                    onClick = { onSelect(ToolFilter.Category(category)) },
                    dragging = draggingCategory == category,
                    dragHandleModifier = Modifier.pointerInput(category) {
                        detectDragGestures(
                            onDragStart = {
                                drag.originalOrder = latestCategories
                                drag.currentOrder = latestCategories
                                val item = listState.layoutInfo.visibleItemsInfo
                                    .firstOrNull { it.key == "category:$category" }
                                drag.pointerY = (item?.offset ?: 0) + (item?.size ?: 0) / 2f
                                draggingCategory = category
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                drag.pointerY += amount.y
                                val target = drag.currentOrder.mapNotNull { candidate ->
                                    val item = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key == "category:$candidate" }
                                    item?.let { candidate to abs(it.offset + it.size / 2f - drag.pointerY) }
                                }.minByOrNull { it.second }?.first
                                if (target != null && target != category) {
                                    val moved = moveCategory(drag.currentOrder, category, target)
                                    if (moved != drag.currentOrder) {
                                        drag.currentOrder = moved
                                        latestOnOrderChange(moved)
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingCategory = null
                                if (drag.currentOrder != drag.originalOrder) {
                                    latestOnOrderCommit(drag.currentOrder, drag.originalOrder)
                                }
                            },
                            onDragCancel = {
                                draggingCategory = null
                                latestOnOrderCancel(drag.originalOrder)
                            },
                        )
                    },
                )
            }
        }
    }
}

private class CategoryDragState {
    var originalOrder: List<String> = emptyList()
    var currentOrder: List<String> = emptyList()
    var pointerY: Float = 0f
}

@Composable
private fun CategoryItem(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    dragHandleModifier: Modifier? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        color = when {
            selected -> MaterialTheme.colors.primary
            dragging -> MaterialTheme.colors.primary.copy(alpha = 0.28f)
            else -> Color.Transparent
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            dragHandleModifier?.let {
                Box(modifier = it.width(24.dp).height(24.dp), contentAlignment = Alignment.CenterStart) {
                    Text("≡", color = if (selected) MaterialTheme.colors.onPrimary else Color.Gray)
                }
            }
            Text(
                name,
                modifier = Modifier.weight(1f),
                color = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(count.toString(), color = if (selected) MaterialTheme.colors.onPrimary.copy(alpha = 0.8f) else Color.Gray)
        }
    }
}

@Composable
private fun ToolList(
    tools: List<LauncherTool>,
    categories: List<String>,
    selectedFilter: ToolFilter,
    onMessage: (String) -> Unit,
    onEdit: (LauncherTool) -> Unit,
    onToggleFavorite: (LauncherTool) -> Unit,
    modifier: Modifier,
) {
    val visibleTools = when (selectedFilter) {
        ToolFilter.Favorites -> tools.filter { it.favorite }
        ToolFilter.All -> tools
        is ToolFilter.Category -> tools.filter { it.category == selectedFilter.name }
    }

    LazyColumn(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (visibleTools.isEmpty()) {
            item { Text("还没有收藏工具。请到“全部”中点击工具左侧的爱心。", color = Color.Gray) }
        } else if (selectedFilter == ToolFilter.All) {
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
                    ToolRow(tool, onMessage, { onEdit(tool) }, { onToggleFavorite(tool) })
                }
            }
        } else {
            items(visibleTools, key = { it.id }) { tool ->
                ToolRow(tool, onMessage, { onEdit(tool) }, { onToggleFavorite(tool) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ToolRow(
    tool: LauncherTool,
    onMessage: (String) -> Unit,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(IntOffset.Zero) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .onPointerEvent(PointerEventType.Press) {
                it.changes.firstOrNull()?.position?.let { position ->
                    menuPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                }
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
                modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.width(52.dp)) {
                    Text(
                        if (tool.favorite) "♥" else "♡",
                        modifier = Modifier.semantics {
                            contentDescription = if (tool.favorite) "取消收藏 ${tool.displayName}" else "收藏 ${tool.displayName}"
                        },
                        color = if (tool.favorite) {
                            if (MaterialTheme.colors.isLight) Color(0xFFC62828) else Color(0xFFFF6B81)
                        } else {
                            Color.Gray
                        },
                        style = MaterialTheme.typography.h5,
                    )
                }
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

            Box(Modifier.offset { menuPosition }.size(1.dp)) {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        runCatching { ToolActions.openDirectory(tool) }
                            .onFailure { onMessage(it.message ?: "无法打开目录") }
                    }) { Text("打开所在目录") }
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        onEdit()
                    }) { Text("编辑分类和排序") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
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
        title = { Text("编辑 ${tool.displayName}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(modifier = Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("排序数字越小越靠前；相同数字按工具名称排列。", color = Color.Gray)
                saveError?.let { Text(it, color = MaterialTheme.colors.error) }
                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { categoryMenuExpanded = !categoryMenuExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {
                            category = it
                            categoryMenuExpanded = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分类（可输入新分类）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
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
                Text("保存更改")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
