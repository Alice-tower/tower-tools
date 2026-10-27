package dev.towertools.launcher.tabs.repositories

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.Card
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import dev.towertools.launcher.CategoryEditMenu
import dev.towertools.launcher.CategoryRenameDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.towertools.launcher.AppLog
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
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

internal sealed interface RepositoryFilter {
    data object Favorites : RepositoryFilter
    data object All : RepositoryFilter
    data class Category(val name: String) : RepositoryFilter
}

internal fun validRepositoryFilter(filter: RepositoryFilter, repositories: List<DisplayRepository>): RepositoryFilter = when (filter) {
    RepositoryFilter.Favorites -> if (repositories.any { it.favorite }) filter else RepositoryFilter.All
    RepositoryFilter.All -> filter
    is RepositoryFilter.Category -> if (repositories.any { it.category == filter.name }) filter else RepositoryFilter.All
}

@Composable
internal fun RepositoryBrowser(controller: RepositoryController, state: RepositoryTabState, modifier: Modifier = Modifier) {
    val locations by state.locations
    var settings by state.appearance
    var editingCategory by state.editingCategory
    var selectedFilter by state.selectedFilter
    var editingRepository by state.editingRepository
    val gitDialog by state.gitDialog
    val repositories = displayRepositories(discoveredRepositories(locations), settings)
    val categories = orderedRepositoryCategories(repositories, settings.categoryOrder)

    fun showMessage(text: String, error: Boolean = false) {
        state.message.value = text
        state.isError.value = error
    }

    Row(modifier) {
        RepositoryCategorySidebar(
            repositories = repositories,
            categories = categories,
            selectedFilter = selectedFilter,
            onEditCategory = { editingCategory = it },
            onSelect = { selectedFilter = it },
            onOrderChange = { settings = settings.copy(categoryOrder = it) },
            onOrderCancel = { settings = settings.copy(categoryOrder = it) },
            onOrderCommit = { order, previous ->
                runCatching { controller.updateCategoryOrder(order) }
                    .onSuccess {
                        settings = it
                        showMessage("分类顺序已保存")
                    }
                    .onFailure {
                        settings = settings.copy(categoryOrder = previous)
                        showMessage(it.message ?: "保存分类顺序失败", error = true)
                    }
            },
        )
        Spacer(Modifier.width(18.dp))
        RepositoryList(
            repositories = repositories,
            locations = locations,
            categories = categories,
            selectedFilter = selectedFilter,
            minimalButtons = settings.minimalButtons,
            onEdit = { editingRepository = it },
            onGitStatus = state::openGitDialog,
            onRemoteFailure = state::recordRemoteFailure,
            onToggleFavorite = { item ->
                runCatching {
                    controller.updateAppearance(item.repository.path, item.category, item.order, !item.favorite)
                }.onSuccess {
                    settings = it
                    selectedFilter = validRepositoryFilter(selectedFilter, displayRepositories(discoveredRepositories(locations), it))
                    showMessage(if (item.favorite) "已取消收藏 ${item.repository.name}" else "已收藏 ${item.repository.name}")
                }.onFailure { showMessage(it.message ?: "更新收藏状态失败", error = true) }
            },
            onMessage = { showMessage(it, error = true) },
            modifier = Modifier.weight(1f),
        )
    }

    gitDialog?.let { dialog ->
        RepositoryGitDialog(dialog, onDismiss = { state.gitDialog.value = null })
    }

    editingCategory?.let { category ->
        CategoryRenameDialog(
            category = category,
            categories = categories,
            onDismiss = { editingCategory = null },
            onSave = { name ->
                runCatching {
                    settings = controller.renameCategory(category, name)
                    if (selectedFilter == RepositoryFilter.Category(category)) selectedFilter = RepositoryFilter.Category(name)
                    editingCategory = null
                    showMessage("分类名称已更新")
                }.exceptionOrNull()?.let { it.message ?: "保存分类名称失败" }
            },
        )
    }

    editingRepository?.let { item ->
        RepositoryEditDialog(
            item = item,
            repositories = repositories,
            categories = categories,
            onDismiss = { editingRepository = null },
            onSaveAppearance = { category, order ->
                runCatching { controller.updateAppearance(item.repository.path, category, order) }
                    .fold(onSuccess = {
                        settings = it
                        selectedFilter = when (selectedFilter) {
                            is RepositoryFilter.Category -> RepositoryFilter.Category(category)
                            else -> validRepositoryFilter(selectedFilter, displayRepositories(discoveredRepositories(locations), it))
                        }
                        editingRepository = displayRepositories(discoveredRepositories(locations), it)
                            .firstOrNull { repository -> repository.key == item.key }
                            ?: item.copy(category = category, order = order)
                        showMessage("已更新 ${item.repository.name} 的分类和排序")
                        null
                    }, onFailure = { it.message ?: "保存仓库设置失败" })
            },
            onSaveDisplayName = { name ->
                runCatching { controller.updateDisplayName(item.repository.path, name) }
                    .fold(onSuccess = {
                        settings = it
                        editingRepository = displayRepositories(discoveredRepositories(locations), it)
                            .firstOrNull { repository -> repository.key == item.key }
                            ?: item.copy(displayName = name.trim().takeIf(String::isNotEmpty))
                        showMessage(if (name.isBlank()) "已清除 ${item.repository.name} 的展示名称" else "已更新 ${item.repository.name} 的展示名称")
                        null
                    }, onFailure = { it.message ?: "保存展示名称失败" })
            },
        )
    }
}

@Composable
private fun RepositoryCategorySidebar(
    repositories: List<DisplayRepository>,
    categories: List<String>,
    selectedFilter: RepositoryFilter,
    onEditCategory: (String) -> Unit,
    onSelect: (RepositoryFilter) -> Unit,
    onOrderChange: (List<String>) -> Unit,
    onOrderCancel: (List<String>) -> Unit,
    onOrderCommit: (List<String>, List<String>) -> Unit,
) {
    val listState = rememberLazyListState()
    val drag = remember { RepositoryCategoryDrag() }
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
                RepositoryCategoryItem("收藏", repositories.count { it.favorite }, selectedFilter == RepositoryFilter.Favorites,
                    { onSelect(RepositoryFilter.Favorites) })
            }
            item(key = "fixed:all") {
                RepositoryCategoryItem("全部", repositories.size, selectedFilter == RepositoryFilter.All,
                    { onSelect(RepositoryFilter.All) })
            }
            items(categories, key = { "category:$it" }) { category ->
                RepositoryCategoryItem(
                    name = category,
                    count = repositories.count { it.category == category },
                    selected = selectedFilter == RepositoryFilter.Category(category),
                    onClick = { onSelect(RepositoryFilter.Category(category)) },
                    onEdit = { onEditCategory(category) },
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
                                    val moved = moveRepositoryCategory(drag.current, category, target)
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

private class RepositoryCategoryDrag {
    var original: List<String> = emptyList()
    var current: List<String> = emptyList()
    var pointerY: Float = 0f
}

@Composable
private fun RepositoryCategoryItem(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    dragging: Boolean = false,
    dragHandle: Modifier? = null,
) {
    CategoryEditMenu(onEdit) {
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
}

@Composable
private fun RepositoryList(
    repositories: List<DisplayRepository>,
    locations: List<RepositoryLocation>,
    categories: List<String>,
    selectedFilter: RepositoryFilter,
    minimalButtons: Boolean,
    onEdit: (DisplayRepository) -> Unit,
    onGitStatus: (DisplayRepository) -> Unit,
    onRemoteFailure: (Path, String?) -> Unit,
    onToggleFavorite: (DisplayRepository) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier,
) {
    val visible = when (selectedFilter) {
        RepositoryFilter.Favorites -> repositories.filter { it.favorite }
        RepositoryFilter.All -> repositories
        is RepositoryFilter.Category -> repositories.filter { it.category == selectedFilter.name }
    }
    LazyColumn(modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (visible.isEmpty()) {
            item { Text("还没有收藏仓库。请到“全部”中点击仓库左侧的爱心。", color = Color.Gray) }
        } else if (selectedFilter == RepositoryFilter.All) {
            categories.forEach { category ->
                val members = repositories.filter { it.category == category }
                item(key = "category:$category") {
                    Text("$category  ·  ${members.size}", style = MaterialTheme.typography.subtitle1,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
                items(members, key = { it.key }) { item ->
                    RepositoryRow(item, locations, minimalButtons, { onEdit(item) }, { onGitStatus(item) },
                        { onToggleFavorite(item) }, onRemoteFailure, onMessage)
                }
            }
        } else {
            items(visible, key = { it.key }) { item ->
                RepositoryRow(item, locations, minimalButtons, { onEdit(item) }, { onGitStatus(item) },
                    { onToggleFavorite(item) }, onRemoteFailure, onMessage)
            }
        }
    }
}

private fun openRepositoryDirectory(item: DisplayRepository) {
    require(Files.isDirectory(item.repository.path)) { "找不到仓库目录：${item.repository.path}" }
    Desktop.getDesktop().open(item.repository.path.toFile())
}

private fun openRepositoryParentDirectory(item: DisplayRepository) {
    val parent = requireNotNull(item.repository.path.parent) { "仓库目录没有父目录：${item.repository.path}" }
    require(Files.isDirectory(parent)) { "找不到仓库的父目录：$parent" }
    Desktop.getDesktop().open(parent.toFile())
}

private fun openRepositoryDocument(directory: Path, name: String) {
    val document = directory.resolve(name)
    require(Files.isRegularFile(document)) { "找不到文档：$document" }
    Desktop.getDesktop().open(document.toFile())
}

private sealed interface RemoteInspection {
    data object Checking : RemoteInspection
    data class Loaded(val remotes: RepositoryRemotes) : RemoteInspection
    data class Failed(val reason: String) : RemoteInspection
}

private data class RepositoryRowMetadata(
    val readme: Boolean = false,
    val agents: Boolean = false,
    val remote: RemoteInspection = RemoteInspection.Checking,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RepositoryRow(
    item: DisplayRepository,
    locations: List<RepositoryLocation>,
    minimalButtons: Boolean,
    onEdit: () -> Unit,
    onGitStatus: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRemoteFailure: (Path, String?) -> Unit,
    onMessage: (String) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuPosition by remember { mutableStateOf(IntOffset.Zero) }
    val scope = rememberCoroutineScope()
    val metadata by produceState(RepositoryRowMetadata(), item.repository.path, locations) {
        val inspected = withContext(Dispatchers.IO) {
            val remote = try {
                RemoteInspection.Loaded(readRepositoryRemotes(item.repository.path))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.logger.log(Level.WARNING, "Unable to read Git remotes: ${item.repository.path}", error)
                RemoteInspection.Failed(error.message ?: error.javaClass.simpleName)
            }
            RepositoryRowMetadata(
                readme = Files.isRegularFile(item.repository.path.resolve("README.md")),
                agents = Files.isRegularFile(item.repository.path.resolve("AGENTS.md")),
                remote = remote,
            )
        }
        value = inspected
        onRemoteFailure(item.repository.path, (inspected.remote as? RemoteInspection.Failed)?.reason)
    }
    val githubUrl = (metadata.remote as? RemoteInspection.Loaded)?.remotes?.githubUrl
    val openReadme: () -> Unit = {
        runCatching { openRepositoryDocument(item.repository.path, "README.md") }
            .onFailure { onMessage(it.message ?: "无法打开 README.md") }
    }
    val openAgents: () -> Unit = {
        runCatching { openRepositoryDocument(item.repository.path, "AGENTS.md") }
            .onFailure { onMessage(it.message ?: "无法打开 AGENTS.md") }
    }
    val openGitHub: () -> Unit = {
        githubUrl?.let { url ->
            runCatching { Desktop.getDesktop().browse(URI(url)) }
                .onFailure { onMessage(it.message ?: "无法打开 GitHub 地址") }
        }
    }
    val openVsCode: () -> Unit = {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val executable = findVsCodeExecutable()
                        ?: error("未找到 VS Code。请确认已安装；自定义安装请将 Code.exe 所在目录加入 PATH，然后重启启动器。")
                    openRepositoryInVsCode(executable, item.repository.path)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onMessage("无法用 VS Code 打开仓库：${error.message ?: "未知错误"}")
            }
        }
    }
    val openDirectory: () -> Unit = {
        runCatching { openRepositoryDirectory(item) }
            .onFailure { onMessage(it.message ?: "无法打开仓库目录") }
    }
    Card(
        modifier = Modifier.fillMaxWidth().height(80.dp)
            .onPointerEvent(PointerEventType.Press) {
                it.changes.firstOrNull()?.position?.let { position ->
                    menuPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt())
                }
                if (it.buttons.isSecondaryPressed) menuExpanded = true
            }
            .combinedClickable(
                onClick = {},
                onDoubleClick = openDirectory,
            ),
        elevation = 1.dp,
    ) {
        Box {
            Row(Modifier.fillMaxSize().padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.width(52.dp)) {
                    Text(
                        if (item.favorite) "♥" else "♡",
                        modifier = Modifier.semantics { contentDescription =
                            if (item.favorite) "取消收藏 ${item.repository.name}" else "收藏 ${item.repository.name}" },
                        color = if (item.favorite) {
                            if (MaterialTheme.colors.isLight) Color(0xFFC62828) else Color(0xFFFF6B81)
                        } else Color.Gray,
                        style = MaterialTheme.typography.h5,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(item.title, style = MaterialTheme.typography.subtitle1,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.displayName != null) {
                        Text(item.repository.name, style = MaterialTheme.typography.body2, color = Color.Gray,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (minimalButtons) {
                    IconButton(onClick = openReadme, enabled = metadata.readme,
                        modifier = Modifier.size(36.dp).semantics { contentDescription = "打开 README.md" }) {
                        Text("R", style = MaterialTheme.typography.subtitle2)
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = openAgents, enabled = metadata.agents,
                        modifier = Modifier.size(36.dp).semantics { contentDescription = "打开 AGENTS.md" }) {
                        Text("A", style = MaterialTheme.typography.subtitle2)
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = openGitHub, enabled = githubUrl != null,
                        modifier = Modifier.size(36.dp).semantics { contentDescription = "打开 GitHub 仓库网页" }) {
                        Icon(GitHubMark, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = openVsCode,
                        modifier = Modifier.size(36.dp).semantics { contentDescription = "用 VS Code 打开仓库" }) {
                        Icon(VsCodeMark, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Unspecified)
                    }
                } else {
                    OutlinedButton(enabled = metadata.readme, onClick = openReadme) { Text("README") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = metadata.agents, onClick = openAgents) { Text("AGENTS") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = githubUrl != null, onClick = openGitHub) {
                        Icon(GitHubMark, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("GITHUB")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = openVsCode) {
                        Icon(VsCodeMark, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Unspecified)
                        Spacer(Modifier.width(6.dp))
                        Text("VS Code")
                    }
                }
            }
            Box(Modifier.offset { menuPosition }.size(1.dp)) {
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        openDirectory()
                    }) { Text("打开") }
                    DropdownMenuItem(onClick = {
                        menuExpanded = false
                        runCatching { openRepositoryParentDirectory(item) }
                            .onFailure { onMessage(it.message ?: "无法打开仓库的父目录") }
                    }) { Text("打开所在目录") }
                    DropdownMenuItem(onClick = { menuExpanded = false; onEdit() }) { Text("编辑") }
                    DropdownMenuItem(onClick = { menuExpanded = false; onGitStatus() }) { Text("Git 状态") }
                }
            }
        }
    }
}
