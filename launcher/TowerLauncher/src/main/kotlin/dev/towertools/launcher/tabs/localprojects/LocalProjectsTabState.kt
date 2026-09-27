package dev.towertools.launcher.tabs.localprojects

import androidx.compose.runtime.mutableStateOf

class LocalProjectsTabState(controller: ProjectController) {
    private val initialProjects = runCatching(controller::projects)

    val projects = mutableStateOf(initialProjects.getOrDefault(emptyList()))
    val status = mutableStateOf<OperationResult?>(
        initialProjects.exceptionOrNull()?.let { OperationResult.error("读取项目列表失败：${it.message}") },
    )
    val editingProject = mutableStateOf<LocalProject?>(null)
    val addingProject = mutableStateOf(false)
    val deletingProject = mutableStateOf<LocalProject?>(null)
    val portStatuses = mutableStateOf<Map<Int, PortStatus>>(emptyMap())
    val rowResults = mutableStateOf<Map<String, OperationResult>>(emptyMap())
    val search = mutableStateOf("")
}
