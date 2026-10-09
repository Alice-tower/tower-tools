package dev.towertools.launcher.tabs.cmd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import dev.towertools.launcher.CategoryRenameDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths
import dev.towertools.launcher.TabFeedbackBar
import dev.towertools.launcher.TabPageHeader
import kotlin.math.roundToInt

@Composable
fun CmdTab(controller: ProjectController, state: CmdTabState, owner: Frame) {
    var projects by state.projects
    var editingCategory by state.editingCategory
    var appearance by state.appearance
    var selectedFilter by state.selectedFilter
    var editingAppearance by state.editingAppearance
    var status by state.status
    var portStatuses by state.portStatuses
    var settingsOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ports = projects.mapNotNull(LocalProject::webPort).distinct()
    val displayProjects = displayCmdProjects(projects, appearance)
    val categories = orderedCmdCategories(displayProjects, appearance.categoryOrder)

    LaunchedEffect(ports) {
        while (true) {
            portStatuses = withContext(Dispatchers.IO) { ports.associateWith(ProjectController::portStatus) }
            delay(3000)
        }
    }

    fun showResult(result: OperationResult) { status = result }
    fun showProjectResult(project: LocalProject, result: OperationResult) {
        showResult(result.copy(message = "${project.name}：${result.message}"))
    }

    Column(modifier = Modifier.fillMaxSize().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 4.dp)) {
        TabPageHeader(
            title = "CMD",
            subtitle = "${projects.size} 个项目",
            onRefresh = {
                runCatching(controller::projects)
                    .onSuccess {
                        projects = it
                        val appearanceResult = runCatching(controller::appearance)
                        appearanceResult.onSuccess { loaded -> appearance = loaded }
                        selectedFilter = validCmdFilter(selectedFilter, displayCmdProjects(projects, appearance))
                        showResult(appearanceResult.fold(
                            onSuccess = { OperationResult.info("项目列表已刷新。") },
                            onFailure = { OperationResult.error("项目列表已刷新，但分类设置读取失败：${it.message}") },
                        ))
                    }
                    .onFailure { showResult(OperationResult.error("刷新失败：${it.message}")) }
            },
            onSettings = { settingsOpen = true },
        )
        Spacer(Modifier.height(12.dp))
        if (projects.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f), elevation = 1.dp) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有登记项目", style = MaterialTheme.typography.h6)
                    Text("点击右上角“设置”添加项目。", color = Color.Gray)
                }
            }
        } else {
            Row(Modifier.weight(1f)) {
                CmdCategorySidebar(
                    projects = displayProjects,
                    categories = categories,
                    selectedFilter = selectedFilter,
                    onEditCategory = { editingCategory = it },
                    onSelect = { selectedFilter = it },
                    onOrderChange = { appearance = appearance.copy(categoryOrder = it) },
                    onOrderCancel = { appearance = appearance.copy(categoryOrder = it) },
                    onOrderCommit = { order, previous ->
                        runCatching { controller.updateCategoryOrder(order) }
                            .onSuccess {
                                appearance = it
                                showResult(OperationResult.info("分类顺序已保存。"))
                            }.onFailure {
                                appearance = appearance.copy(categoryOrder = previous)
                                showResult(OperationResult.error(it.message ?: "保存分类顺序失败"))
                            }
                    },
                )
                Spacer(Modifier.width(18.dp))
                val filter = selectedFilter
                val visible = when (filter) {
                    CmdFilter.Favorites -> displayProjects.filter { it.favorite }
                    CmdFilter.All -> displayProjects
                    is CmdFilter.Category -> displayProjects.filter { it.category == filter.name }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (visible.isEmpty()) {
                        item { Text("还没有收藏项目。请到“全部”中点击项目左侧的爱心。", color = Color.Gray) }
                    } else if (selectedFilter == CmdFilter.All) {
                        categories.forEach { category ->
                            val members = displayProjects.filter { it.category == category }
                            item(key = "category:$category") {
                                Text("$category  ·  ${members.size}", style = MaterialTheme.typography.subtitle1,
                                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                            }
                            items(members, key = { it.project.id }) { item ->
                                CmdProjectCardItem(item, portStatuses, controller,
                                    onResult = ::showProjectResult,
                                    onLaunch = { project -> scope.launch {
                                        showProjectResult(project, withContext(Dispatchers.IO) { controller.launch(project) })
                                    } },
                                    onEdit = { editingAppearance = it },
                                    onToggleFavorite = { changed ->
                                        runCatching { controller.updateAppearance(changed.project.id, changed.category, changed.order, !changed.favorite) }
                                            .onSuccess {
                                                appearance = it
                                                selectedFilter = validCmdFilter(selectedFilter, displayCmdProjects(projects, it))
                                                showResult(OperationResult.info(if (changed.favorite) "已取消收藏 ${changed.project.name}" else "已收藏 ${changed.project.name}"))
                                            }.onFailure { showResult(OperationResult.error(it.message ?: "更新收藏状态失败")) }
                                    },
                                )
                            }
                        }
                    } else {
                        items(visible, key = { it.project.id }) { item ->
                            CmdProjectCardItem(item, portStatuses, controller,
                                onResult = ::showProjectResult,
                                onLaunch = { project -> scope.launch {
                                    showProjectResult(project, withContext(Dispatchers.IO) { controller.launch(project) })
                                } },
                                onEdit = { editingAppearance = it },
                                onToggleFavorite = { changed ->
                                    runCatching { controller.updateAppearance(changed.project.id, changed.category, changed.order, !changed.favorite) }
                                        .onSuccess {
                                            appearance = it
                                            selectedFilter = validCmdFilter(selectedFilter, displayCmdProjects(projects, it))
                                            showResult(OperationResult.info(if (changed.favorite) "已取消收藏 ${changed.project.name}" else "已收藏 ${changed.project.name}"))
                                        }.onFailure { showResult(OperationResult.error(it.message ?: "更新收藏状态失败")) }
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        TabFeedbackBar(
            message = status?.message ?: "双击条目启动脚本或打开静态网页 · 端口可连接不代表项目已运行",
            isError = status?.isError == true,
        )
    }

    if (settingsOpen) {
        CmdSettingsDialog(
            owner = owner,
            onDismiss = { settingsOpen = false },
            onAdd = { name, scriptPath, webPort ->
                runCatching { controller.saveProject(null, name, scriptPath, webPort) }
                    .fold(onSuccess = {
                        projects = it
                        selectedFilter = CmdFilter.All
                        showResult(OperationResult.info("项目已添加。"))
                        null
                    }, onFailure = { it.message ?: "无法添加项目" })
            },
        )
    }

    editingCategory?.let { category ->
        CategoryRenameDialog(
            category = category,
            categories = categories,
            onDismiss = { editingCategory = null },
            onSave = { name ->
                runCatching {
                    appearance = controller.renameCategory(category, name)
                    if (selectedFilter == CmdFilter.Category(category)) selectedFilter = CmdFilter.Category(name)
                    editingCategory = null
                    showResult(OperationResult.info("分类名称已更新。"))
                }.exceptionOrNull()?.let { it.message ?: "保存分类名称失败" }
            },
        )
    }

    editingAppearance?.let { item ->
        CmdEditDialog(
            item = item,
            projects = displayProjects,
            categories = categories,
            owner = owner,
            onDismiss = { editingAppearance = null },
            onSaveAppearance = { category, order ->
                runCatching { controller.updateAppearance(item.project.id, category, order) }
                    .fold(onSuccess = {
                        appearance = it
                        selectedFilter = when (selectedFilter) {
                            is CmdFilter.Category -> CmdFilter.Category(category)
                            else -> validCmdFilter(selectedFilter, displayCmdProjects(projects, it))
                        }
                        editingAppearance = displayCmdProjects(projects, it).first { project -> project.project.id == item.project.id }
                        showResult(OperationResult.info("已更新 ${item.project.name} 的分类和排序。"))
                        null
                    }, onFailure = { it.message ?: "保存分类和排序失败" })
            },
            onSaveProject = { name, scriptPath, webPort ->
                runCatching { controller.saveProject(item.project.id, name, scriptPath, webPort) }
                    .fold(onSuccess = {
                        projects = it
                        editingAppearance = displayCmdProjects(it, appearance).first { project -> project.project.id == item.project.id }
                        showResult(OperationResult.info("项目已更新。"))
                        null
                    }, onFailure = { it.message ?: "无法保存项目" })
            },
            onDelete = {
                runCatching { controller.deleteProject(item.project.id) }
                    .fold(onSuccess = {
                        projects = it
                        selectedFilter = validCmdFilter(selectedFilter, displayCmdProjects(it, appearance))
                        editingAppearance = null
                        showResult(OperationResult.info("已删除 ${item.project.name} 的登记。"))
                        null
                    }, onFailure = { it.message ?: "删除登记失败" })
            },
        )
    }
}

@Composable
private fun CmdProjectCardItem(
    item: DisplayCmdProject,
    portStatuses: Map<Int, PortStatus>,
    controller: ProjectController,
    onResult: (LocalProject, OperationResult) -> Unit,
    onLaunch: (LocalProject) -> Unit,
    onEdit: (DisplayCmdProject) -> Unit,
    onToggleFavorite: (DisplayCmdProject) -> Unit,
) {
    val project = item.project
    ProjectCard(
        project = project,
        favorite = item.favorite,
        portStatus = project.webPort?.let(portStatuses::get),
        onLaunch = { onLaunch(project) },
        onBrowser = { onResult(project, controller.openBrowser(project)) },
        onDirectory = { onResult(project, controller.openProjectDirectory(project)) },
        onEdit = { onEdit(item) },
        onToggleFavorite = { onToggleFavorite(item) },
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ProjectCard(
    project: LocalProject,
    favorite: Boolean,
    portStatus: PortStatus?,
    onLaunch: () -> Unit,
    onBrowser: () -> Unit,
    onDirectory: () -> Unit,
    onEdit: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(IntOffset.Zero) }
    Card(
        modifier = Modifier.fillMaxWidth().height(80.dp)
            .onPointerEvent(PointerEventType.Press) {
                it.changes.firstOrNull()?.position?.let { position ->
                    menuPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                }
                if (it.buttons.isSecondaryPressed) menuExpanded = true
            }
            .combinedClickable(onClick = {}, onDoubleClick = onLaunch),
        elevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.width(52.dp)) {
                    Text(
                        if (favorite) "♥" else "♡",
                        modifier = Modifier.semantics {
                            contentDescription = if (favorite) "取消收藏 ${project.name}" else "收藏 ${project.name}"
                        },
                        color = if (favorite) {
                            if (MaterialTheme.colors.isLight) Color(0xFFC62828) else Color(0xFFFF6B81)
                        } else Color.Gray,
                        style = MaterialTheme.typography.h5,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.subtitle1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CmdPathText(project.scriptPath.toString())
                }
                project.webPort?.let { port ->
                    val (label, color) = when (portStatus) {
                        PortStatus.IN_USE -> "可连接" to if (MaterialTheme.colors.isLight) Color(0xFF2E7D32) else Color(0xFF81C784)
                        PortStatus.AVAILABLE -> "未连接" to MaterialTheme.colors.error
                        PortStatus.UNKNOWN -> "检测失败" to Color.Gray
                        null -> "检测中" to Color.Gray
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.size(8.dp).background(color, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("$port · $label", color = color, style = MaterialTheme.typography.body2)
                    }
                }
                if (project.webPort != null) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(onClick = onBrowser) { Text("打开浏览器") }
                }
            }
            Box(Modifier.offset { menuPosition }.size(1.dp)) {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(onClick = { menuExpanded = false; onDirectory() }) { Text("打开所在目录") }
                    DropdownMenuItem(onClick = { menuExpanded = false; onEdit() }) { Text("编辑") }
                }
            }
        }
    }
}

@Composable
private fun CmdPathText(path: String) {
    val style = MaterialTheme.typography.caption
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = with(density) { maxWidth.roundToPx() }
        val visiblePath = remember(path, availableWidth, style, textMeasurer) {
            middleEllipsizePath(path, availableWidth) { text ->
                textMeasurer.measure(text, style = style, maxLines = 1, softWrap = false).size.width
            }
        }
        Text(
            visiblePath,
            style = style,
            color = Color.Gray,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}

@Composable
private fun CmdSettingsDialog(
    owner: Frame,
    onDismiss: () -> Unit,
    onAdd: (String, Path, Int?) -> String?,
) {
    val form = remember { ProjectFormState() }
    var added by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(460.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colors.surface,
            elevation = 24.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("CMD 设置", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.24f)),
                    color = Color.Transparent,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("添加项目", style = MaterialTheme.typography.subtitle1)
                        ProjectFormFields(form, owner)
                        if (added) {
                            Text("项目已添加，可继续添加。", style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(enabled = form.canSave, onClick = {
                                added = false
                                if (form.submit(onAdd)) {
                                    form.reset()
                                    added = true
                                }
                            }) { Text("添加项目") }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

internal class ProjectFormState(project: LocalProject? = null) {
    var name by mutableStateOf(project?.name.orEmpty())
    var scriptPath by mutableStateOf(project?.scriptPath?.toString().orEmpty())
    var isWebService by mutableStateOf(project?.webPort != null)
    var portText by mutableStateOf(project?.webPort?.toString().orEmpty())
    var saveError by mutableStateOf<String?>(null)

    val isStaticWebPage: Boolean get() = ProjectController.isStaticWebPage(scriptPath)
    val port: Int? get() = portText.toIntOrNull()
    val canSave: Boolean get() {
        val parsedPort = port
        return name.isNotBlank() && scriptPath.isNotBlank() &&
            (isStaticWebPage || !isWebService || parsedPort != null && parsedPort in 1..65535)
    }

    fun submit(onSave: (String, Path, Int?) -> String?): Boolean {
        val path = runCatching { Paths.get(scriptPath) }.getOrElse {
            saveError = "启动文件路径无效：${it.message}"
            return false
        }
        saveError = onSave(name, path, if (isWebService && !isStaticWebPage) port else null)
        return saveError == null
    }

    fun reset() {
        name = ""
        scriptPath = ""
        isWebService = false
        portText = ""
        saveError = null
    }
}

@Composable
internal fun ProjectFormFields(form: ProjectFormState, owner: Frame, onChange: () -> Unit = {}) {
    val port = form.port
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            form.name, { form.name = it; form.saveError = null; onChange() },
            modifier = Modifier.fillMaxWidth(), label = { Text("项目名称") }, singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                form.scriptPath, { form.scriptPath = it; form.saveError = null; onChange() }, modifier = Modifier.weight(1f),
                label = { Text("启动文件路径（.cmd / .html / .htm）") }, singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                chooseCommandFile(owner, form.scriptPath)?.let { form.scriptPath = it.toString(); form.saveError = null; onChange() }
            }) { Text("浏览") }
        }
        if (!form.isStaticWebPage) Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = form.isWebService, onCheckedChange = { form.isWebService = it; form.saveError = null; onChange() })
            Text("本地 Web 服务")
        }
        if (form.isStaticWebPage) {
            Text("静态网页将用默认浏览器打开，无需填写端口。", style = MaterialTheme.typography.caption)
        }
        if (form.isWebService && !form.isStaticWebPage) {
            OutlinedTextField(
                form.portText, { form.portText = it; form.saveError = null; onChange() }, modifier = Modifier.fillMaxWidth(),
                label = { Text("占用端口（1–65535）") }, singleLine = true,
                isError = port == null || port !in 1..65535,
            )
            Text("浏览器将打开 http://127.0.0.1:端口/", style = MaterialTheme.typography.caption)
        }
        form.saveError?.let { Text(it, color = MaterialTheme.colors.error) }
    }
}

private fun chooseCommandFile(owner: Frame, currentPath: String): Path? {
    val dialog = FileDialog(owner, "选择 CMD 脚本或静态网页", FileDialog.LOAD).apply {
        directory = runCatching { Paths.get(currentPath).parent?.toString() }.getOrNull()
        file = "*.cmd;*.html;*.htm"
        isVisible = true
    }
    val selectedFile = dialog.file ?: return null
    return Paths.get(dialog.directory, selectedFile)
}
