package dev.towertools.launcher.tabs.repositories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun RepositoryEditDialog(
    item: DisplayRepository,
    repositories: List<DisplayRepository>,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSaveAppearance: (String, Int) -> String?,
    onSaveDisplayName: (String) -> String?,
) {
    var selectedPage by remember(item.key) { mutableStateOf(0) }
    var category by remember(item.key) { mutableStateOf(item.category) }
    var orderText by remember(item.key) { mutableStateOf(item.order.toString()) }
    var name by remember(item.key) { mutableStateOf(item.displayName.orEmpty()) }
    var categoryMenuExpanded by remember(item.key) { mutableStateOf(false) }
    var saveError by remember(item.key) { mutableStateOf<String?>(null) }
    var saved by remember(item.key) { mutableStateOf(false) }
    val normalizedCategory = category.trim().ifEmpty { "未分类" }
    val parsedOrder = orderText.toIntOrNull()
    val references = repositories.filter { it.key != item.key && it.category == normalizedCategory }
        .sortedWith(compareBy<DisplayRepository> { it.order }.thenBy { it.repository.name })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${item.repository.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TabRow(selectedTabIndex = selectedPage) {
                    listOf("分类和排序", "展示名称").forEachIndexed { index, title ->
                        Tab(selected = selectedPage == index,
                            onClick = { selectedPage = index; saveError = null; saved = false },
                            text = { Text(title) })
                    }
                }
                Text("各页签单独保存，切换页签会保留输入。", style = MaterialTheme.typography.caption, color = Color.Gray)
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 340.dp, max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (selectedPage == 0) {
                        Text("排序数字越小越靠前；相同数字按仓库名称排列。", color = Color.Gray)
                        ExposedDropdownMenuBox(
                            expanded = categoryMenuExpanded,
                            onExpandedChange = { categoryMenuExpanded = !categoryMenuExpanded },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedTextField(
                                value = category,
                                onValueChange = { category = it; categoryMenuExpanded = false; saveError = null; saved = false },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("分类（可输入新分类）") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryMenuExpanded) },
                                singleLine = true,
                            )
                            ExposedDropdownMenu(expanded = categoryMenuExpanded, onDismissRequest = { categoryMenuExpanded = false }) {
                                categories.forEach { existing ->
                                    DropdownMenuItem(onClick = { category = existing; categoryMenuExpanded = false; saveError = null; saved = false }) {
                                        Text(existing)
                                    }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = orderText,
                            onValueChange = { orderText = it; saveError = null; saved = false },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("排序数字") },
                            singleLine = true,
                            isError = parsedOrder == null,
                        )
                        if (parsedOrder == null) Text("请输入有效的整数，例如 0、10 或 -10。", color = MaterialTheme.colors.error)
                        Divider()
                        Text("“$normalizedCategory”中的排序参考", style = MaterialTheme.typography.subtitle2)
                        if (references.isEmpty()) {
                            Text("这个分类中暂无其他仓库。", color = Color.Gray)
                        } else {
                            Column(Modifier.fillMaxWidth().heightIn(max = 150.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                references.forEach { reference ->
                                    Row {
                                        Text(reference.order.toString(), Modifier.width(60.dp), color = MaterialTheme.colors.primary)
                                        Text(reference.repository.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it; saveError = null; saved = false },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("展示名称") },
                            singleLine = true,
                        )
                        Text("留空并保存可恢复为目录名。", color = Color.Gray)
                    }
                    saveError?.let { Text(it, color = MaterialTheme.colors.error) }
                    if (saved) Text("当前页已保存。", color = MaterialTheme.colors.primary)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selectedPage == 1 || parsedOrder != null,
                onClick = {
                    saveError = if (selectedPage == 0) {
                        onSaveAppearance(normalizedCategory, checkNotNull(parsedOrder))
                    } else {
                        onSaveDisplayName(name)
                    }
                    saved = saveError == null
                    if (saved) {
                        if (selectedPage == 0) category = normalizedCategory else name = name.trim()
                    }
                },
            ) { Text("保存当前页") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
