package dev.towertools.launcher.tabs.cmd

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.ExperimentalMaterialApi
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal sealed interface CmdFilter {
    data object Favorites : CmdFilter
    data object All : CmdFilter
    data class Category(val name: String) : CmdFilter
}

internal fun validCmdFilter(filter: CmdFilter, projects: List<DisplayCmdProject>): CmdFilter = when (filter) {
    CmdFilter.Favorites -> if (projects.any { it.favorite }) filter else CmdFilter.All
    CmdFilter.All -> filter
    is CmdFilter.Category -> if (projects.any { it.category == filter.name }) filter else CmdFilter.All
}

@Composable
internal fun CmdCategorySidebar(
    projects: List<DisplayCmdProject>,
    categories: List<String>,
    selectedFilter: CmdFilter,
    onSelect: (CmdFilter) -> Unit,
    onOrderChange: (List<String>) -> Unit,
    onOrderCancel: (List<String>) -> Unit,
    onOrderCommit: (List<String>, List<String>) -> Unit,
) {
    val listState = rememberLazyListState()
    val drag = remember { CmdCategoryDrag() }
    var draggingCategory by remember { mutableStateOf<String?>(null) }
    val latestCategories by rememberUpdatedState(categories)
    val latestOnOrderChange by rememberUpdatedState(onOrderChange)
    val latestOnOrderCancel by rememberUpdatedState(onOrderCancel)
    val latestOnOrderCommit by rememberUpdatedState(onOrderCommit)

    Card(Modifier.width(184.dp).fillMaxHeight(), elevation = 1.dp) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item(key = "fixed:favorites") {
                CmdCategoryItem("收藏", projects.count { it.favorite }, selectedFilter == CmdFilter.Favorites,
                    { onSelect(CmdFilter.Favorites) })
            }
            item(key = "fixed:all") {
                CmdCategoryItem("全部", projects.size, selectedFilter == CmdFilter.All,
                    { onSelect(CmdFilter.All) })
            }
            items(categories, key = { "category:$it" }) { category ->
                CmdCategoryItem(
                    name = category,
                    count = projects.count { it.category == category },
                    selected = selectedFilter == CmdFilter.Category(category),
                    onClick = { onSelect(CmdFilter.Category(category)) },
                    dragging = draggingCategory == category,
                    dragHandle = Modifier.pointerInput(category) {
                        detectDragGestures(
                            onDragStart = {
                                drag.original = latestCategories
                                drag.current = latestCategories
                                val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "category:$category" }
                                drag.pointerY = (item?.offset ?: 0) + (item?.size ?: 0) / 2f
                                draggingCategory = category
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                drag.pointerY += amount.y
                                val target = drag.current.mapNotNull { candidate ->
                                    val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "category:$candidate" }
                                    item?.let { candidate to abs(it.offset + it.size / 2f - drag.pointerY) }
                                }.minByOrNull { it.second }?.first
                                if (target != null && target != category) {
                                    val moved = moveCmdCategory(drag.current, category, target)
                                    if (moved != drag.current) {
                                        drag.current = moved
                                        latestOnOrderChange(moved)
                                    }
                                }
                            },
                            onDragEnd = {
                                draggingCategory = null
                                if (drag.current != drag.original) latestOnOrderCommit(drag.current, drag.original)
                            },
                            onDragCancel = {
                                draggingCategory = null
                                latestOnOrderCancel(drag.original)
                            },
                        )
                    },
                )
            }
        }
    }
}

private class CmdCategoryDrag {
    var original: List<String> = emptyList()
    var current: List<String> = emptyList()
    var pointerY: Float = 0f
}

@Composable
private fun CmdCategoryItem(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    dragging: Boolean = false,
    dragHandle: Modifier? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = when {
            selected -> MaterialTheme.colors.primary
            dragging -> MaterialTheme.colors.primary.copy(alpha = 0.28f)
            else -> Color.Transparent
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            dragHandle?.let {
                Box(it.width(24.dp).height(24.dp), contentAlignment = Alignment.CenterStart) {
                    Text("≡", color = if (selected) MaterialTheme.colors.onPrimary else Color.Gray)
                }
            }
            Text(name, Modifier.weight(1f), color = if (selected) MaterialTheme.colors.onPrimary else MaterialTheme.colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(count.toString(), color = if (selected) MaterialTheme.colors.onPrimary.copy(alpha = 0.8f) else Color.Gray)
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun CmdAppearanceDialog(
    item: DisplayCmdProject,
    projects: List<DisplayCmdProject>,
    categories: List<String>,
    saveError: String?,
    onDismiss: () -> Unit,
    onSave: (String, Int) -> Unit,
) {
    var category by remember(item.project.id) { mutableStateOf(item.category) }
    var orderText by remember(item.project.id) { mutableStateOf(item.order.toString()) }
    var categoryMenuExpanded by remember(item.project.id) { mutableStateOf(false) }
    val normalizedCategory = category.trim().ifEmpty { "未分类" }
    val parsedOrder = orderText.toIntOrNull()
    val references = projects.filter { it.project.id != item.project.id && it.category == normalizedCategory }
        .sortedWith(compareBy<DisplayCmdProject> { it.order }.thenBy { it.project.name })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${item.project.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("排序数字越小越靠前；相同数字按项目名称排列。", color = Color.Gray)
                saveError?.let { Text(it, color = MaterialTheme.colors.error) }
                ExposedDropdownMenuBox(
                    expanded = categoryMenuExpanded,
                    onExpandedChange = { categoryMenuExpanded = !categoryMenuExpanded },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it; categoryMenuExpanded = false },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("分类（可输入新分类）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                        singleLine = true,
                    )
                    ExposedDropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                        categories.forEach { existing ->
                            DropdownMenuItem(onClick = { category = existing; categoryMenuExpanded = false }) { Text(existing) }
                        }
                    }
                }
                OutlinedTextField(
                    value = orderText, onValueChange = { orderText = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("排序数字") }, singleLine = true, isError = parsedOrder == null,
                )
                if (parsedOrder == null) Text("请输入有效的整数，例如 0、10 或 -10。", color = MaterialTheme.colors.error)
                Divider()
                Text("“$normalizedCategory”中的排序参考", style = MaterialTheme.typography.subtitle2)
                if (references.isEmpty()) {
                    Text("这个分类中暂无其他项目。", color = Color.Gray)
                } else {
                    Column(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        references.forEach { reference ->
                            Row {
                                Text(reference.order.toString(), Modifier.width(60.dp), color = MaterialTheme.colors.primary)
                                Text(reference.project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(enabled = parsedOrder != null, onClick = { onSave(normalizedCategory, checkNotNull(parsedOrder)) }) {
                Text("保存更改")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
