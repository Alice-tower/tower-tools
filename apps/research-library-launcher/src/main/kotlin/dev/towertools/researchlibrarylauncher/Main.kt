package dev.towertools.researchlibrarylauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths

fun main() {
    val singleInstance = runCatching { SingleInstance.acquire(AppMetadata.id) }
        .onFailure { AppLog.logger.severe("Unable to initialize single-instance control: ${it.message}") }
        .getOrNull() ?: return
    val controller = ProjectController()

    application {
        val state = rememberWindowState(width = 860.dp, height = 600.dp)
        Window(onCloseRequest = ::exitApplication, state = state, title = AppMetadata.displayName) {
            DisposableEffect(window) {
                singleInstance.onActivate {
                    EventQueue.invokeLater {
                        window.extendedState = Frame.NORMAL
                        window.isVisible = true
                        window.toFront()
                        window.requestFocus()
                    }
                }
                onDispose(singleInstance::close)
            }
            MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ProjectLauncherApp(controller, window)
                }
            }
        }
    }
}

@Composable
private fun ProjectLauncherApp(controller: ProjectController, owner: Frame) {
    val initialProjects = remember { runCatching(controller::projects) }
    var projects by remember { mutableStateOf(initialProjects.getOrDefault(emptyList())) }
    var status by remember {
        mutableStateOf<OperationResult?>(
            initialProjects.exceptionOrNull()?.let { OperationResult.error("读取项目列表失败：${it.message}") },
        )
    }
    var editingProject by remember { mutableStateOf<LocalProject?>(null) }
    var addingProject by remember { mutableStateOf(false) }
    var deletingProject by remember { mutableStateOf<LocalProject?>(null) }
    var portStatuses by remember { mutableStateOf<Map<Int, PortStatus>>(emptyMap()) }
    var rowResults by remember { mutableStateOf<Map<String, OperationResult>>(emptyMap()) }
    var search by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ports = projects.mapNotNull(LocalProject::webPort).distinct()
    val visibleProjects = projects.filter {
        search.isBlank() || it.name.contains(search.trim(), ignoreCase = true) ||
            it.scriptPath.toString().contains(search.trim(), ignoreCase = true)
    }

    LaunchedEffect(ports) {
        while (true) {
            portStatuses = withContext(Dispatchers.IO) { ports.associateWith(ProjectController::portStatus) }
            delay(3000)
        }
    }

    fun showResult(result: OperationResult) { status = result }
    fun showRowResult(id: String, result: OperationResult) {
        rowResults = rowResults + (id to result)
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(AppMetadata.displayName, style = MaterialTheme.typography.h5)
                Text("${projects.size} 个项目", style = MaterialTheme.typography.caption, color = Color.Gray)
            }
            Button(onClick = { addingProject = true }) { Text("添加项目") }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                label = { Text("搜索名称或路径") },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                runCatching(controller::projects)
                    .onSuccess { projects = it; showResult(OperationResult.info("项目列表已刷新。")) }
                    .onFailure { showResult(OperationResult.error("刷新失败：${it.message}")) }
            }) { Text("刷新") }
        }
        status?.let { result ->
            Text(result.message, color = if (result.isError) MaterialTheme.colors.error else MaterialTheme.colors.primary)
        }

        if (projects.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth().weight(1f), elevation = 1.dp) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("还没有登记项目", style = MaterialTheme.typography.h6)
                    Text("点击右上角“添加项目”，填写名称和 .cmd 完整路径。", color = Color.Gray)
                }
            }
        } else if (visibleProjects.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("没有匹配的项目", style = MaterialTheme.typography.h6)
                Text("试试其他名称或路径关键词。", color = Color.Gray)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visibleProjects, key = LocalProject::id) { project ->
                    ProjectCard(
                        project = project,
                        portStatus = project.webPort?.let(portStatuses::get),
                        result = rowResults[project.id],
                        onLaunch = {
                            scope.launch {
                                showRowResult(project.id, withContext(Dispatchers.IO) { controller.launch(project) })
                            }
                        },
                        onBrowser = { showRowResult(project.id, controller.openBrowser(project)) },
                        onDirectory = { showRowResult(project.id, controller.openProjectDirectory(project)) },
                        onEdit = { editingProject = project },
                        onDelete = { deletingProject = project },
                    )
                }
            }
        }

        Text(
            "可连接仅表示本机端口接受连接，不代表登记的项目已经运行。",
            style = MaterialTheme.typography.caption,
            color = Color.Gray,
        )
    }

    if (addingProject || editingProject != null) {
        val current = editingProject
        ProjectEditorDialog(
            project = current,
            owner = owner,
            onDismiss = { addingProject = false; editingProject = null },
            onSave = { name, scriptPath, webPort ->
                runCatching { controller.saveProject(current?.id, name, scriptPath, webPort) }
                    .fold(onSuccess = {
                        projects = it
                        addingProject = false
                        editingProject = null
                        showResult(OperationResult.info(if (current == null) "项目已添加。" else "项目已更新。"))
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
                            rowResults = rowResults - project.id
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
    result: OperationResult?,
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
            result?.let {
                Text(
                    it.message,
                    style = MaterialTheme.typography.caption,
                    color = if (it.isError) MaterialTheme.colors.error else MaterialTheme.colors.primary,
                )
            }
        }
    }
}

@Composable
private fun ProjectEditorDialog(
    project: LocalProject?,
    owner: Frame,
    onDismiss: () -> Unit,
    onSave: (String, Path, Int?) -> String?,
) {
    var name by remember(project?.id) { mutableStateOf(project?.name.orEmpty()) }
    var scriptPath by remember(project?.id) { mutableStateOf(project?.scriptPath?.toString().orEmpty()) }
    var isWebService by remember(project?.id) { mutableStateOf(project?.webPort != null) }
    var portText by remember(project?.id) { mutableStateOf(project?.webPort?.toString().orEmpty()) }
    var saveError by remember(project?.id) { mutableStateOf<String?>(null) }
    val port = portText.toIntOrNull()
    val canSave = name.isNotBlank() && scriptPath.isNotBlank() && (!isWebService || port != null && port in 1..65535)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) "添加项目" else "编辑项目") },
        text = {
            Column(modifier = Modifier.width(500.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("项目名称") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        scriptPath, { scriptPath = it }, modifier = Modifier.weight(1f),
                        label = { Text(".cmd 完整路径") }, singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        chooseCommandFile(owner, scriptPath)?.let { scriptPath = it.toString() }
                    }) { Text("浏览") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isWebService, onCheckedChange = { isWebService = it })
                    Text("本地 Web 服务")
                }
                if (isWebService) {
                    OutlinedTextField(
                        portText, { portText = it }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("占用端口（1–65535）") }, singleLine = true,
                        isError = port == null || port !in 1..65535,
                    )
                    Text("浏览器将打开 http://127.0.0.1:端口/", style = MaterialTheme.typography.caption)
                }
                saveError?.let { Text(it, color = MaterialTheme.colors.error) }
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = {
                val path = runCatching { Paths.get(scriptPath) }.getOrElse {
                    saveError = "启动脚本路径无效：${it.message}"
                    return@Button
                }
                saveError = onSave(name, path, if (isWebService) port else null)
            }) { Text("保存") }
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
