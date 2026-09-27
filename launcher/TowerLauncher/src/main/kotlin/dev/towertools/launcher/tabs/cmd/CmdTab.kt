package dev.towertools.launcher.tabs.cmd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.Checkbox
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@Composable
fun CmdTab(controller: ProjectController, state: CmdTabState, owner: Frame) {
    var projects by state.projects
    var status by state.status
    var editingProject by state.editingProject
    var deletingProject by state.deletingProject
    var portStatuses by state.portStatuses
    var settingsOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ports = projects.mapNotNull(LocalProject::webPort).distinct()

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
                    .onSuccess { projects = it; showResult(OperationResult.info("项目列表已刷新。")) }
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
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(projects, key = LocalProject::id) { project ->
                    ProjectCard(
                        project = project,
                        portStatus = project.webPort?.let(portStatuses::get),
                        onLaunch = {
                            scope.launch {
                                showProjectResult(project, withContext(Dispatchers.IO) { controller.launch(project) })
                            }
                        },
                        onBrowser = { showProjectResult(project, controller.openBrowser(project)) },
                        onDirectory = { showProjectResult(project, controller.openProjectDirectory(project)) },
                        onEdit = { editingProject = project },
                        onDelete = { deletingProject = project },
                    )
                }
            }
        }

        Spacer(Modifier.height(2.dp))
        TabFeedbackBar(
            message = status?.message ?: "可连接仅表示本机端口接受连接，不代表登记的项目已经运行。",
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
                        showResult(OperationResult.info("项目已添加。"))
                        null
                    }, onFailure = { it.message ?: "无法添加项目" })
            },
        )
    }

    editingProject?.let { current ->
        ProjectEditorDialog(
            project = current,
            owner = owner,
            onDismiss = { editingProject = null },
            onSave = { name, scriptPath, webPort ->
                runCatching { controller.saveProject(current.id, name, scriptPath, webPort) }
                    .fold(onSuccess = {
                        projects = it
                        editingProject = null
                        showResult(OperationResult.info("项目已更新。"))
                        null
                    }, onFailure = { it.message ?: "无法保存项目" })
            },
        )
    }

    deletingProject?.let { project ->
        AlertDialog(
            onDismissRequest = { deletingProject = null },
            title = { Text("删除 ${project.name}？") },
            text = { Text("只会删除启动器中的登记，不会删除 .cmd 文件或项目目录。") },
            confirmButton = {
                Button(onClick = {
                    runCatching { controller.deleteProject(project.id) }
                        .onSuccess {
                            projects = it
                            deletingProject = null
                            showResult(OperationResult.info("已删除 ${project.name} 的登记。"))
                        }
                        .onFailure { showResult(OperationResult.error("删除失败：${it.message}")) }
                }) { Text("删除登记") }
            },
            dismissButton = { TextButton(onClick = { deletingProject = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: LocalProject,
    portStatus: PortStatus?,
    onLaunch: () -> Unit,
    onBrowser: () -> Unit,
    onDirectory: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    project.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.subtitle1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                Spacer(Modifier.width(12.dp))
                Button(onClick = onLaunch) { Text("启动") }
                if (project.webPort != null) {
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onBrowser) { Text("打开浏览器") }
                }
                Spacer(Modifier.width(4.dp))
                Box {
                    TextButton(onClick = { menuExpanded = true }) { Text("更多") }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(onClick = { menuExpanded = false; onDirectory() }) { Text("打开目录") }
                        DropdownMenuItem(onClick = { menuExpanded = false; onEdit() }) { Text("编辑项目") }
                        DropdownMenuItem(onClick = { menuExpanded = false; onDelete() }) { Text("删除登记") }
                    }
                }
            }
            SelectionContainer {
                Text(
                    project.scriptPath.toString(),
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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

private class ProjectFormState(project: LocalProject? = null) {
    var name by mutableStateOf(project?.name.orEmpty())
    var scriptPath by mutableStateOf(project?.scriptPath?.toString().orEmpty())
    var isWebService by mutableStateOf(project?.webPort != null)
    var portText by mutableStateOf(project?.webPort?.toString().orEmpty())
    var saveError by mutableStateOf<String?>(null)

    val port: Int? get() = portText.toIntOrNull()
    val canSave: Boolean get() {
        val parsedPort = port
        return name.isNotBlank() && scriptPath.isNotBlank() &&
            (!isWebService || parsedPort != null && parsedPort in 1..65535)
    }

    fun submit(onSave: (String, Path, Int?) -> String?): Boolean {
        val path = runCatching { Paths.get(scriptPath) }.getOrElse {
            saveError = "启动脚本路径无效：${it.message}"
            return false
        }
        saveError = onSave(name, path, if (isWebService) port else null)
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
private fun ProjectFormFields(form: ProjectFormState, owner: Frame) {
    val port = form.port
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            form.name, { form.name = it; form.saveError = null },
            modifier = Modifier.fillMaxWidth(), label = { Text("项目名称") }, singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                form.scriptPath, { form.scriptPath = it; form.saveError = null }, modifier = Modifier.weight(1f),
                label = { Text(".cmd 完整路径") }, singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                chooseCommandFile(owner, form.scriptPath)?.let { form.scriptPath = it.toString(); form.saveError = null }
            }) { Text("浏览") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = form.isWebService, onCheckedChange = { form.isWebService = it; form.saveError = null })
            Text("本地 Web 服务")
        }
        if (form.isWebService) {
            OutlinedTextField(
                form.portText, { form.portText = it; form.saveError = null }, modifier = Modifier.fillMaxWidth(),
                label = { Text("占用端口（1–65535）") }, singleLine = true,
                isError = port == null || port !in 1..65535,
            )
            Text("浏览器将打开 http://127.0.0.1:端口/", style = MaterialTheme.typography.caption)
        }
        form.saveError?.let { Text(it, color = MaterialTheme.colors.error) }
    }
}

@Composable
private fun ProjectEditorDialog(
    project: LocalProject,
    owner: Frame,
    onDismiss: () -> Unit,
    onSave: (String, Path, Int?) -> String?,
) {
    val form = remember(project.id) { ProjectFormState(project) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑项目") },
        text = {
            Column(modifier = Modifier.width(500.dp)) {
                ProjectFormFields(form, owner)
            }
        },
        confirmButton = {
            Button(enabled = form.canSave, onClick = { form.submit(onSave) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun chooseCommandFile(owner: Frame, currentPath: String): Path? {
    val dialog = FileDialog(owner, "选择 .cmd 启动脚本", FileDialog.LOAD).apply {
        directory = runCatching { Paths.get(currentPath).parent?.toString() }.getOrNull()
        file = "*.cmd"
        isVisible = true
    }
    val selectedFile = dialog.file ?: return null
    return Paths.get(dialog.directory, selectedFile)
}
