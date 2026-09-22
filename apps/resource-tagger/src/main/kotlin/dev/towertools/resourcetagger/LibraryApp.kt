package dev.towertools.resourcetagger

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.awt.Window
import javax.swing.JFileChooser

private data class Form(val title: String, val labels: List<String>, val initial: List<String>, val explanation: String = "", val browse: Int? = null, val directoriesOnly: Boolean = true, val mergeOption: Boolean = false, val save: (Library, List<String>, Boolean) -> Unit)
private data class Confirmation(val title: String, val body: String, val action: (Library) -> Unit)

@Composable fun LibraryApp(owner: Window?, controller: LibraryController = remember { LibraryController() }) {
    DisposableEffect(controller) { onDispose { controller.close() } }
    val data = controller.data
    var page by remember { mutableStateOf("资源") }
    var query by remember { mutableStateOf(Query()) }
    val selected = controller.selected
    var focused by remember { mutableStateOf<String?>(null) }
    var tagSearch by remember { mutableStateOf("") }
    var form by remember { mutableStateOf<Form?>(null) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var editTags by remember { mutableStateOf<Set<String>?>(null) }
    val busy = controller.busy != null
    val results = data.resources
    val detailData = controller.focusData
    val detail = detailData.resources.singleOrNull()?.takeIf { resource -> data.roots.any { it.id == resource.rootId } }
    val tagsById = remember(data.tags) { data.tags.associateBy { it.id } }
    val rootsById = remember(data.roots) { data.roots.associateBy { it.id } }
    val reviewsByResource = remember(data.reviews) { data.reviews.groupBy { it.resourceId } }
    LaunchedEffect(query) { controller.search(query) }
    LaunchedEffect(focused) { controller.focus(focused) }
    LaunchedEffect(editTags) { controller.editSelection(editTags.orEmpty()) }
    LaunchedEffect(data.roots, data.tags) {
        query = query.copy(
            rootId = query.rootId?.takeIf { root -> data.roots.any { it.id == root } },
            tags = query.tags.filterKeys { tag -> data.tags.any { it.id == tag } },
        )
    }
    fun rootForm(root: Root? = null) {
        form = Form(if (root == null) "添加 Root" else "重新定位 Root", listOf("显示名称", "目录路径"), listOf(root?.name ?: "", root?.path ?: ""), "只扫描直接子项。Root 不得重复、嵌套或为整块磁盘。重新定位保留资源 ID 和标签，完成后请扫描。", browse = 1) { lib, values, _ -> lib.saveRoot(root?.id, values[1], values[0]) }
    }
    fun relocation(resource: Resource) {
        form = Form("重新定位资源", listOf("目标完整路径"), listOf(""), "目标必须是已配置 Root 的直接子项，且类型一致。合并仅适用于无标签、尚未确认的新发现记录。", browse = 0, directoriesOnly = resource.kind == Kind.Directory, mergeOption = true) { lib, values, merge -> lib.relocate(resource.id, values[0], merge) }
    }
    fun removeResource(resource: Resource) {
        confirmation = Confirmation("仅从数据库移除", "移除「${resource.name}」的记录、标签关联和待处理项，不删除磁盘内容。仍存在的对象下次扫描会重新发现；长期隐藏请使用“忽略”。") { it.removeResource(resource.id) }
    }
    fun navigate(resource: Resource, open: Boolean) {
        val root = data.roots.single { it.id == resource.rootId }
        controller.submit(if (open) "打开目录" else "在所在目录中显示", refresh = false) { Navigator.navigate(root, resource, open) }
    }
    fun menu(resource: Resource): List<ContextMenuItem> = buildList {
        if (!busy) {
            if (resource.status == Status.Active) {
                if (resource.kind == Kind.Directory) add(ContextMenuItem("打开目录") { navigate(resource, true) })
                add(ContextMenuItem("在所在目录中显示") { navigate(resource, false) })
            }
            add(ContextMenuItem("编辑标签") { editTags = setOf(resource.id) })
            if (resource.status == Status.Missing) {
                add(ContextMenuItem("重新定位") { relocation(resource) })
                if (data.reviews.none { it.resourceId == resource.id && it.reason == Reason.TypeChanged }) add(ContextMenuItem("保留记录") { controller.submit("保留记录") { it.acknowledge(resource.id) } })
            }
            if (resource.status == Status.Ignored) add(ContextMenuItem("取消忽略") { controller.submit("取消忽略") { it.unignore(resource.id) } })
            else add(ContextMenuItem("忽略") { controller.submit("忽略资源") { it.ignore(resource.id) } })
            add(ContextMenuItem("从数据库移除") { removeResource(resource) })
        }
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("本地资源语义管理器", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
            listOf("资源", "Root 管理", "标签管理").forEach { destination ->
                OutlinedButton(onClick = { page = destination }) { Text(if (page == destination) "● $destination" else destination) }
            }
        }
        Divider()
        when (page) {
            "Root 管理" -> Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("扫描入口", style = MaterialTheme.typography.h6, modifier = Modifier.weight(1f))
                    Button(onClick = { rootForm() }, enabled = !busy) { Text("添加 Root") }
                }
                Text("仅管理直接文件和目录；不递归、不改变磁盘内容。", modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(data.roots, key = { it.id }) { root ->
                        Card(Modifier.fillMaxWidth(), elevation = 2.dp) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(root.name, fontWeight = FontWeight.Bold)
                                SelectionContainer { Text(root.path) }
                                Text("${root.availability} · 最近成功：${root.lastSuccess ?: "尚未扫描"}", style = MaterialTheme.typography.caption)
                                root.lastScan?.let { Text("最近尝试：$it", style = MaterialTheme.typography.caption) }
                                root.error?.let { Text(it, color = MaterialTheme.colors.error) }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !busy, onClick = { controller.submit("扫描 ${root.name}") { it.scan(root.id) } }) { Text("扫描") }
                                    OutlinedButton(enabled = !busy, onClick = { form = Form("编辑 Root 名称", listOf("名称"), listOf(root.name)) { lib, values, _ -> lib.renameRoot(root.id, values[0]) } }) { Text("改名") }
                                    OutlinedButton(enabled = !busy, onClick = { rootForm(root) }) { Text("重新定位") }
                                    TextButton(enabled = !busy, onClick = { confirmation = Confirmation("移除 Root", "将移除「${root.name}」及其 ${data.rootCounts[root.id] ?: 0} 条资源记录、关联和待处理项。保留所有标签定义及磁盘内容。") { it.removeRoot(root.id) } }) { Text("移除") }
                                }
                            }
                        }
                    }
                }
            }
            "标签管理" -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(tagSearch, { tagSearch = it }, label = { Text("搜索规范名称或别名") }, modifier = Modifier.weight(1f), singleLine = true)
                    Button(enabled = !busy, onClick = { form = Form("创建标签", listOf("规范名称"), listOf(tagSearch)) { lib, values, _ -> lib.createTag(values[0]) } }) { Text("创建标签") }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(data.tags.filter { it.matches(tagSearch) }, key = { it.id }) { tag ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                val count = data.tagCounts[tag.id] ?: 0
                                Text("${tag.name} · $count 个资源", fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(enabled = !busy, onClick = { form = Form("修改规范名称", listOf("名称"), listOf(tag.name)) { lib, values, _ -> lib.renameTag(tag.id, values[0]) } }) { Text("改名") }
                                    TextButton(enabled = !busy, onClick = { form = Form("添加别名", listOf("别名"), listOf("")) { lib, values, _ -> lib.addAlias(tag.id, values[0]) } }) { Text("添加别名") }
                                    TextButton(enabled = !busy, onClick = { confirmation = Confirmation("删除标签", "「${tag.name}」关联 $count 个资源。删除此标签、所有别名和关联，保留资源记录及磁盘内容。") { it.removeTag(tag.id) } }) { Text("删除") }
                                }
                                tag.aliases.forEach { alias -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(alias, modifier = Modifier.weight(1f))
                                    TextButton(enabled = !busy, onClick = { controller.submit("删除别名") { it.removeAlias(tag.id, alias) } }) { Text("移除别名") }
                                } }
                            }
                        }
                    }
                }
            }
            else -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(Modifier.width(245.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("范围与筛选", fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { query = Query(); controller.selected = emptySet() }, modifier = Modifier.fillMaxWidth()) { Text("全部资源 / 清空筛选") }
                    OutlinedButton(onClick = { query = Query(reviewOnly = true) }, modifier = Modifier.fillMaxWidth()) { Text("${if (query.reviewOnly) "● " else ""}待处理 · ${data.pendingCount}") }
                    Choice("Root", listOf(null to "全部 Root") + data.roots.map { it.id to it.name }, query.rootId) { query = query.copy(rootId = it) }
                    Button(onClick = { rootForm() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("添加 Root") }
                    query.rootId?.let { rootId -> OutlinedButton(enabled = !busy, onClick = { controller.submit("扫描 Root") { it.scan(rootId) } }, modifier = Modifier.fillMaxWidth()) { Text("扫描当前 Root") } }
                    Choice("类型", listOf(null to "全部类型") + Kind.entries.map { it to it.title }, query.kind) { query = query.copy(kind = it) }
                    Choice("状态", listOf(null to "日常结果（隐藏忽略）") + Status.entries.map { it to it.title }, query.status) { query = query.copy(status = it) }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(query.untagged, { query = query.copy(untagged = it) }); Text("仅无标签") }
                    Divider()
                    OutlinedTextField(tagSearch, { tagSearch = it }, label = { Text("标签 / 别名搜索") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("包含：全部满足；排除：任意命中即排除", style = MaterialTheme.typography.caption)
                    data.tags.filter { it.matches(tagSearch) }.forEach { tag ->
                        Text(tag.name, fontWeight = FontWeight.Medium)
                        Row {
                            TagFilter.entries.forEach { mode ->
                                TextButton(onClick = { query = query.copy(tags = query.tags + (tag.id to mode)) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text((if ((query.tags[tag.id] ?: TagFilter.Neutral) == mode) "●" else "") + mode.title, style = MaterialTheme.typography.caption)
                                }
                            }
                        }
                    }
                }
                Divider(Modifier.width(1.dp).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(query.text, { query = query.copy(text = it) }, label = { Text("搜索资源名称或相对路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val activeFilters = data.tags.mapNotNull { tag -> when (query.tags[tag.id]) { TagFilter.Include -> "+ ${tag.name}"; TagFilter.Exclude -> "− ${tag.name}"; else -> null } }
                    if (activeFilters.isNotEmpty()) Text(activeFilters.joinToString("   "), style = MaterialTheme.typography.caption)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(controller.total > 0 && selected.size == controller.total, { if (it) controller.selectAll { ids -> controller.selected = ids } else controller.selected = emptySet() }, enabled = !busy && !controller.querying, modifier = Modifier.semantics { contentDescription = "全选结果" })
                        Text("${controller.total} 项 · 已选 ${selected.size}", modifier = Modifier.weight(1f))
                        OutlinedButton(enabled = !busy && selected.isNotEmpty(), onClick = { editTags = selected }) { Text("批量标签") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("全选覆盖全部匹配结果", style = MaterialTheme.typography.caption, modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy && !controller.querying && controller.offset > 0, onClick = { controller.movePage(-1) }) { Text("上一页") }
                        Text("${controller.offset / controller.pageSize + 1} / ${maxOf(1, (controller.total + controller.pageSize - 1) / controller.pageSize)}", style = MaterialTheme.typography.caption)
                        TextButton(enabled = !busy && !controller.querying && controller.offset + controller.pageSize < controller.total, onClick = { controller.movePage(1) }) { Text("下一页") }
                    }
                    if (data.roots.isEmpty()) Text("还没有 Root。添加一个资源目录，然后在 Root 管理中扫描。", modifier = Modifier.padding(20.dp))
                    else if (results.isEmpty()) Text(if (query.reviewOnly) "没有待处理资源。" else "没有匹配结果。可清空筛选，或手动扫描 Root。", modifier = Modifier.padding(20.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(results, key = { it.id }) { resource ->
                            ContextMenuArea(items = { menu(resource) }) {
                                Row(Modifier.fillMaxWidth().background(if (resource.id == focused) MaterialTheme.colors.primary.copy(alpha = .12f) else MaterialTheme.colors.surface).clickable { focused = resource.id }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(resource.id in selected, { controller.selected = if (it) selected + resource.id else selected - resource.id; focused = resource.id }, enabled = !busy && !controller.querying, modifier = Modifier.semantics { contentDescription = "选择资源 ${resource.name}" })
                                    Column(Modifier.weight(1f)) {
                                        Text("${if (resource.kind == Kind.Directory) "▣" else "▤"} ${resource.name}", maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                        Text("${resource.kind.title} · ${rootsById[resource.rootId]?.name} · ${resource.status.title}", style = MaterialTheme.typography.caption)
                                        Text(data.links[resource.id].orEmpty().mapNotNull { tagsById[it]?.name }.sorted().joinToString(" · ").ifEmpty { "无标签" }, style = MaterialTheme.typography.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val reviews = reviewsByResource[resource.id].orEmpty()
                                        if (reviews.isNotEmpty()) Text(reviews.joinToString { it.reason.title }, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption)
                                    }
                                }
                            }
                        }
                    }
                }
                Divider(Modifier.width(1.dp).fillMaxHeight())
                Column(Modifier.width(295.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("资源详情", fontWeight = FontWeight.Bold)
                    if (detail == null) Text(if (controller.detailLoading) "正在读取详情…" else "单击资源查看详情；勾选可批量编辑标签。")
                    else {
                        val root = data.roots.single { it.id == detail.rootId }
                        Text(detail.name, style = MaterialTheme.typography.h6)
                        Text("${detail.kind.title} · ${detail.status.title}")
                        Text("Root：${root.name}")
                        SelectionContainer { Text(PathsPolicy.actual(root, detail).toString(), style = MaterialTheme.typography.body2) }
                        if (root.error != null) Text("Root 最近扫描失败：${root.error}", color = MaterialTheme.colors.error)
                        Text("创建：${detail.created}", style = MaterialTheme.typography.caption)
                        Text("最近发现：${detail.lastSeen ?: "—"}", style = MaterialTheme.typography.caption)
                        Divider()
                        Text("标签", fontWeight = FontWeight.Bold)
                        Text(detailData.links[detail.id].orEmpty().mapNotNull { tagsById[it]?.name }.sorted().joinToString(" · ").ifEmpty { "尚无标签" })
                        OutlinedButton(enabled = !busy, onClick = { editTags = setOf(detail.id) }) { Text("编辑标签") }
                        if (detail.status == Status.Active) {
                            if (detail.kind == Kind.Directory) Button(enabled = !busy, onClick = { navigate(detail, true) }) { Text("打开目录") }
                            OutlinedButton(enabled = !busy, onClick = { navigate(detail, false) }) { Text("在所在目录中显示") }
                        }
                        detailData.reviews.forEach { pending ->
                            Text("待处理：${pending.reason.title}", color = MaterialTheme.colors.primary)
                            if (pending.reason == Reason.TypeChanged) {
                                Text("当前对象是${pending.observedKind?.title}。确认后，原有标签将关联到当前对象。")
                                Button(enabled = !busy, onClick = { confirmation = Confirmation("确认类型变化", "将「${detail.name}」由${detail.kind.title}改为${pending.observedKind?.title}，保留原 ID 和所有标签，并关联到当前对象。") { it.acceptType(detail.id) } }) { Text("确认关联当前对象") }
                            } else Button(enabled = !busy, onClick = { controller.submit(if (pending.reason == Reason.New) "确认收录" else "保留记录") { it.acknowledge(detail.id) } }) { Text(if (pending.reason == Reason.New) "确认收录" else "保留记录并确认提醒") }
                        }
                        if (detail.status != Status.Ignored) OutlinedButton(enabled = !busy, onClick = { relocation(detail) }) { Text("重新定位") }
                        OutlinedButton(enabled = !busy, onClick = { controller.submit(if (detail.status == Status.Ignored) "取消忽略" else "忽略资源") { if (detail.status == Status.Ignored) it.unignore(detail.id) else it.ignore(detail.id) } }) { Text(if (detail.status == Status.Ignored) "取消忽略" else "忽略") }
                        TextButton(enabled = !busy, onClick = { removeResource(detail) }) { Text("从数据库移除") }
                    }
                }
            }
        }
        if (busy || controller.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(controller.busy ?: if (controller.querying) "正在查询…" else controller.message, style = MaterialTheme.typography.caption)
        controller.error?.let { error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(error, color = MaterialTheme.colors.error, modifier = Modifier.weight(1f))
            TextButton(onClick = controller::clearError) { Text("关闭提示") }
        } }
    }
    form?.let { current ->
        key(current) {
            var values by remember { mutableStateOf(current.initial) }
            var merge by remember { mutableStateOf(false) }
            AppDialog(current.title, { if (!busy) form = null }) {
                if (current.explanation.isNotBlank()) Text(current.explanation)
                current.labels.forEachIndexed { index, label ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(values[index], { value -> values = values.mapIndexed { i, old -> if (i == index) value else old } }, label = { Text(label) }, modifier = Modifier.weight(1f), singleLine = true, enabled = !busy)
                        if (current.browse == index) OutlinedButton(enabled = !busy, onClick = {
                            val chooser = JFileChooser().apply { fileSelectionMode = if (current.directoriesOnly) JFileChooser.DIRECTORIES_ONLY else JFileChooser.FILES_ONLY; dialogTitle = current.title }
                            if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                                values = values.mapIndexed { i, old -> if (i == index) chooser.selectedFile.absolutePath else if (current.labels.size == 2 && i == 0 && old.isBlank()) chooser.selectedFile.name else old }
                            }
                        }) { Text("浏览") }
                    }
                }
                if (current.mergeOption) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(merge, { merge = it }, enabled = !busy); Text("允许合并无标签、未处理的新发现记录") }
                controller.error?.let { Text(it, color = MaterialTheme.colors.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = { form = null }) { Text("取消") }
                    Button(enabled = !busy, onClick = { controller.submit(current.title, { form = null }) { current.save(it, values, merge) } }) { Text("保存") }
                }
            }
        }
    }
    confirmation?.let { current -> AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text(current.title) }, text = { Column { Text(current.body); controller.error?.let { Text(it, color = MaterialTheme.colors.error) } } }, confirmButton = { Button(enabled = !busy, onClick = { controller.submit(current.title, { confirmation = null }, action = current.action) }) { Text("确认") } }, dismissButton = { TextButton(enabled = !busy, onClick = { confirmation = null }) { Text("取消") } }) }
    editTags?.let { ids ->
        var search by remember { mutableStateOf("") }
        AppDialog("编辑 ${ids.size} 个资源的标签", { if (!busy) editTags = null }) {
            OutlinedTextField(search, { search = it }, label = { Text("搜索标签或别名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("添加或移除立即保存；批量操作只修改指定标签。", style = MaterialTheme.typography.caption)
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(data.tags.filter { it.matches(search) }, key = { it.id }) { tag ->
                    val count = controller.editCounts[tag.id] ?: 0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${tag.name} ($count/${ids.size})", modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy && !controller.editLoading && count < ids.size, onClick = { controller.submit("添加标签") { it.setTag(ids, tag.id, true) } }) { Text("添加") }
                        TextButton(enabled = !busy && !controller.editLoading && count > 0, onClick = { controller.submit("移除标签") { it.setTag(ids, tag.id, false) } }) { Text("移除") }
                    }
                }
            }
            controller.error?.let { Text(it, color = MaterialTheme.colors.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = !busy && search.isNotBlank(), onClick = { controller.submit("创建并添加标签") { lib ->
                    lib.setTag(ids, lib.findTag(search) ?: lib.createTag(search), true)
                } }) { Text("创建 / 使用并添加") }
                Button(enabled = !busy, onClick = { editTags = null }) { Text("完成") }
            }
        }
    }
}

@Composable private fun <T> Choice(label: String, choices: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label：${choices.find { it.first == selected }?.second ?: "全部"}") }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { (value, text) -> DropdownMenuItem(onClick = { expanded = false; onSelect(value) }) { Text(text) } } }
    }
}
@Composable private fun AppDialog(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.width(650.dp).heightIn(max = 700.dp), shape = MaterialTheme.shapes.medium, elevation = 12.dp) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.h6); content() }
        }
    }
}
