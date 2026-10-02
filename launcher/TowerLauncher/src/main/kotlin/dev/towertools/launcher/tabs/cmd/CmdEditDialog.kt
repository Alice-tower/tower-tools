package dev.towertools.launcher.tabs.cmd

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
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.OutlinedButton
import java.awt.Frame
import java.nio.file.Path

@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun CmdEditDialog(
    item: DisplayCmdProject,
    projects: List<DisplayCmdProject>,
    categories: List<String>,
    owner: Frame,
    onDismiss: () -> Unit,
    onSaveAppearance: (String, Int) -> String?,
    onSaveProject: (String, Path, Int?) -> String?,
    onDelete: () -> String?,
) {
    var selectedPage by remember(item.project.id) { mutableStateOf(0) }
    var category by remember(item.project.id) { mutableStateOf(item.category) }
    var orderText by remember(item.project.id) { mutableStateOf(item.order.toString()) }
    var categoryMenuExpanded by remember(item.project.id) { mutableStateOf(false) }
    val projectForm = remember(item.project.id) { ProjectFormState(item.project) }
    var saveError by remember(item.project.id) { mutableStateOf<String?>(null) }
    var saved by remember(item.project.id) { mutableStateOf(false) }
    var confirmDelete by remember(item.project.id) { mutableStateOf(false) }
    var deleteError by remember(item.project.id) { mutableStateOf<String?>(null) }
    val normalizedCategory = category.trim().ifEmpty { "未分类" }
    val parsedOrder = orderText.toIntOrNull()
    val references = projects.filter { it.project.id != item.project.id && it.category == normalizedCategory }
        .sortedWith(compareBy<DisplayCmdProject> { it.order }.thenBy { it.project.name })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 ${item.project.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TabRow(selectedTabIndex = selectedPage) {
                    listOf("分类和排序", "项目设置").forEachIndexed { index, title ->
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
                        Text("排序数字越小越靠前；相同数字按项目名称排列。", color = Color.Gray)
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
                    } else {
                        ProjectFormFields(projectForm, owner, onChange = { saved = false })
                        Divider()
                        Text("删除登记只会从启动器移除项目，不会删除 .cmd 文件或项目目录。",
                            style = MaterialTheme.typography.caption, color = Color.Gray)
                        OutlinedButton(
                            onClick = { deleteError = null; confirmDelete = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colors.error),
                        ) { Text("删除登记") }
                    }
                    saveError?.let { Text(it, color = MaterialTheme.colors.error) }
                    if (saved) Text("当前页已保存。", color = MaterialTheme.colors.primary)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = if (selectedPage == 0) parsedOrder != null else projectForm.canSave,
                onClick = {
                    if (selectedPage == 0) {
                        saveError = onSaveAppearance(normalizedCategory, checkNotNull(parsedOrder))
                        saved = saveError == null
                        if (saved) category = normalizedCategory
                    } else {
                        saved = projectForm.submit(onSaveProject)
                    }
                },
            ) { Text("保存当前页") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${item.project.name}？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("只会删除启动器中的登记，不会删除 .cmd 文件或项目目录。")
                    deleteError?.let { Text(it, color = MaterialTheme.colors.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    deleteError = onDelete()
                    if (deleteError == null) confirmDelete = false
                }) { Text("删除登记") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}
