package dev.towertools.launcher.tabs.cmd

import androidx.compose.runtime.mutableStateOf

class CmdTabState(controller: ProjectController) {
    private val initialProjects = runCatching(controller::projects)
    private val initialAppearance = runCatching(controller::appearance)

    val projects = mutableStateOf(initialProjects.getOrDefault(emptyList()))
    val appearance = mutableStateOf(initialAppearance.getOrDefault(CmdAppearanceSettings()))
    internal val selectedFilter = mutableStateOf<CmdFilter>(
        if (displayCmdProjects(projects.value, appearance.value).any { it.favorite }) CmdFilter.Favorites else CmdFilter.All,
    )
    val editingAppearance = mutableStateOf<DisplayCmdProject?>(null)
    val appearanceSaveError = mutableStateOf<String?>(null)
    val status = mutableStateOf<OperationResult?>(
        initialProjects.exceptionOrNull()?.let { OperationResult.error("读取项目列表失败：${it.message}") }
            ?: initialAppearance.exceptionOrNull()?.let { OperationResult.error("读取 CMD 分类设置失败：${it.message}") },
    )
    val editingProject = mutableStateOf<LocalProject?>(null)
    val deletingProject = mutableStateOf<LocalProject?>(null)
    val portStatuses = mutableStateOf<Map<Int, PortStatus>>(emptyMap())
}
