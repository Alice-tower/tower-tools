package dev.towertools.launcher.tabs.repositories

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val gitDialogTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@Composable
internal fun RepositoryGitDialog(state: RepositoryGitDialogState, onDismiss: () -> Unit) {
    val snapshot by state.snapshot
    val isBusy by state.isBusy
    val output by state.output
    val isLoadingCommits by state.isLoadingCommits
    val commitLoadError by state.commitLoadError
    var selectedPage by remember(state.repository.key) { mutableStateOf(0) }
    val current = snapshot
    val working = isBusy || isLoadingCommits
    val operationReason = when {
        current == null -> "正在读取 Git 状态…"
        current.remotes.isEmpty() -> "没有远端仓库，Pull 和 Push 暂不可用。"
        current.branch == null -> "当前处于分离 HEAD 状态，请先切换到本地分支。"
        !current.hasCommits -> "当前分支还没有提交，Pull 和 Push 暂不可用。"
        current.upstream == null -> current.comparisonTarget?.let {
            "未设置跟踪目标，使用 ${it.remote}/${it.branch}；目标分支不存在时会报错。Pull 仅快进更新。"
        } ?: "未设置跟踪目标，且未找到 origin 远端；Pull / Push 会提示无法同步。"
        else -> "Pull 仅快进更新；Push 将当前分支推送至 ${current.upstream.remote}/${current.upstream.branch}。"
    }
    val operationEnabled = !working && current != null && current.branch != null &&
        current.hasCommits && current.remotes.isNotEmpty()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.padding(12.dp)) {
            Surface(
                modifier = Modifier.width(maxWidth.coerceAtMost(1100.dp))
                    .height(maxHeight.coerceAtMost(640.dp)).testTag("git-dialog-surface"),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colors.surface,
                elevation = 24.dp,
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GitDialogHeader(state, current, working)
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            Modifier.weight(1f).fillMaxHeight().testTag("git-history-pane"),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("最近提交", style = MaterialTheme.typography.subtitle1)
                            GitCommitList(current, !working, isLoadingCommits, commitLoadError, state::loadMoreCommits)
                        }
                        Divider(Modifier.width(1.dp).fillMaxHeight())
                        Column(
                            Modifier.weight(1f).fillMaxHeight().testTag("git-details-pane"),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TabRow(selectedTabIndex = selectedPage) {
                                listOf("文件改动", "远端", "操作结果").forEachIndexed { index, title ->
                                    Tab(selected = selectedPage == index, onClick = { selectedPage = index }, text = { Text(title) })
                                }
                            }
                            Box(Modifier.fillMaxWidth().weight(1f)) {
                                when (selectedPage) {
                                    0 -> GitFileLists(current)
                                    1 -> GitRemoteList(current)
                                    else -> GitOperationOutput(output)
                                }
                            }
                        }
                    }
                    Divider()
                    Text(operationReason, style = MaterialTheme.typography.caption, color = Color.Gray, maxLines = 2)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = state::openDirectory) { Text("打开目标目录") }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(
                            enabled = operationEnabled,
                            onClick = { state.pull(); selectedPage = 2 },
                        ) { Text("Git Pull") }
                        Button(
                            enabled = operationEnabled,
                            onClick = { state.push(); selectedPage = 2 },
                        ) { Text("Git Push") }
                        TextButton(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitDialogHeader(
    state: RepositoryGitDialogState,
    snapshot: GitSnapshot?,
    working: Boolean,
) {
    val message by state.message
    val isError by state.isError
    val isBusy by state.isBusy
    val updatedAt by state.updatedAt
    Row(
        Modifier.fillMaxWidth().testTag("git-dialog-header"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f).testTag("git-repository-identity"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Git 状态 · ${state.repository.title}",
                style = MaterialTheme.typography.h6,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SelectionContainer {
                Text(
                    state.repository.repository.path.toString(),
                    style = MaterialTheme.typography.caption,
                    color = Color.Gray,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                Modifier.weight(1f).testTag("git-sync-summary"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (snapshot != null) GitBranchSummary(snapshot)
                Text(
                    message.ifBlank { "正在读取 Git 状态…" },
                    style = MaterialTheme.typography.caption,
                    color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val updateText = updatedAt?.let { "远端更新：${formatGitDialogTime(it)}" }
                    ?: if (snapshot?.remotes?.isNotEmpty() == true) "尚未完成远端更新，比较结果来自本地记录。" else null
                updateText?.let {
                    Text(it, style = MaterialTheme.typography.caption, color = Color.Gray, maxLines = 2)
                }
                if (isBusy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            }
            TextButton(onClick = state::refresh, enabled = !working) { Text("刷新") }
        }
    }
}

@Composable
private fun GitBranchSummary(snapshot: GitSnapshot) {
    val target = snapshot.comparisonTarget
    val comparison = when {
        !snapshot.hasCommits -> "暂无提交"
        snapshot.remotes.isEmpty() -> "没有远端仓库"
        snapshot.branch == null -> "暂不可同步"
        target == null -> "未找到同步目标"
        snapshot.remotes.none { it.name == target.remote } -> "跟踪远端已不存在"
        snapshot.ahead == null || snapshot.behind == null -> "远端分支尚无记录"
        snapshot.ahead == 0 && snapshot.behind == 0 -> "与远端一致"
        snapshot.ahead > 0 && snapshot.behind > 0 -> "领先 ${snapshot.ahead} · 落后 ${snapshot.behind}"
        snapshot.ahead > 0 -> "领先 ${snapshot.ahead} 个提交"
        else -> "落后 ${snapshot.behind} 个提交"
    }
    val branch = snapshot.branch ?: "分离 HEAD"
    val reference = if (target != null && snapshot.remotes.isNotEmpty()) {
        "$branch → ${target.remote}/${target.branch}"
    } else {
        branch
    }
    SelectionContainer {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                reference, Modifier.weight(1f),
                style = MaterialTheme.typography.body2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "· $comparison", Modifier.widthIn(max = 175.dp),
                style = MaterialTheme.typography.body2,
                maxLines = 2,
                color = if ((snapshot.ahead ?: 0) > 0 && (snapshot.behind ?: 0) > 0) MaterialTheme.colors.error
                    else MaterialTheme.colors.primary,
            )
        }
    }
}

@Composable
private fun GitCommitList(
    snapshot: GitSnapshot?,
    enabled: Boolean,
    isLoading: Boolean,
    loadError: String?,
    onLoadMore: () -> Unit,
) {
    when {
        snapshot == null -> GitEmptyMessage("正在读取最近提交…")
        snapshot.commits.isEmpty() -> GitEmptyMessage("当前分支还没有提交。")
        else -> LazyColumn(
            Modifier.fillMaxSize().testTag("git-commit-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("当前分支最近 ${snapshot.commits.size} 次提交", style = MaterialTheme.typography.caption, color = Color.Gray) }
            items(snapshot.commits, key = { it.hash }) { commit ->
                SelectionContainer {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(commit.hash.take(8), color = MaterialTheme.colors.primary, fontFamily = FontFamily.Monospace)
                            Text(commit.subject, Modifier.weight(1f), style = MaterialTheme.typography.body2)
                        }
                        Text("${commit.author} · ${formatGitDialogTime(commit.time)}", style = MaterialTheme.typography.caption, color = Color.Gray)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Divider()
            }
            item(key = "load-more") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    loadError?.let {
                        Text(it, style = MaterialTheme.typography.caption, color = MaterialTheme.colors.error)
                    }
                    if (snapshot.hasMoreCommits || isLoading || loadError != null) {
                        OutlinedButton(onClick = onLoadMore, enabled = enabled && !isLoading) {
                            Text(if (isLoading) "加载中…" else if (loadError != null) "重试加载更多" else "加载更多")
                        }
                    } else {
                        Text("已全部加载", style = MaterialTheme.typography.caption, color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
private fun GitFileLists(snapshot: GitSnapshot?) {
    if (snapshot == null) {
        GitEmptyMessage("正在读取文件状态…")
        return
    }
    val groups = listOf("已暂存" to snapshot.staged, "未暂存" to snapshot.unstaged, "未跟踪" to snapshot.untracked)
        .filter { (_, files) -> files.isNotEmpty() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "已暂存 ${snapshot.staged.size} · 未暂存 ${snapshot.unstaged.size} · 未跟踪 ${snapshot.untracked.size}",
            Modifier.fillMaxWidth().testTag("git-file-counts"),
            style = MaterialTheme.typography.caption,
            color = Color.Gray,
        )
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                GitEmptyMessage("工作区没有文件改动")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEachIndexed { index, (title, files) ->
                    item(key = "section-$title") {
                        Text("$title · ${files.size} 个文件", style = MaterialTheme.typography.subtitle2)
                    }
                    items(files, key = { "$title-${it.path}" }) { file ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(file.status, Modifier.width(72.dp), style = MaterialTheme.typography.caption, color = MaterialTheme.colors.primary)
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(
                                    file.originalPath?.let { "$it → ${file.path}" } ?: file.path,
                                    style = MaterialTheme.typography.body2,
                                )
                            }
                        }
                    }
                    if (index < groups.lastIndex) {
                        item(key = "divider-$title") { Divider(Modifier.padding(vertical = 4.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GitRemoteList(snapshot: GitSnapshot?) {
    when {
        snapshot == null -> GitEmptyMessage("正在读取远端信息…")
        snapshot.remotes.isEmpty() -> GitEmptyMessage("这个仓库没有配置远端。")
        else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(snapshot.remotes, key = { it.name }) { remote ->
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(remote.name, style = MaterialTheme.typography.subtitle2)
                        Text("获取地址：${remote.url}", style = MaterialTheme.typography.body2)
                        remote.pushUrl?.takeIf { it != remote.url }?.let { url ->
                            Text("推送地址：$url", style = MaterialTheme.typography.body2)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Divider()
            }
        }
    }
}

@Composable
private fun GitOperationOutput(output: String) {
    if (output.isBlank()) {
        GitEmptyMessage("远端更新以及 Pull / Push 的结果会显示在这里。")
    } else {
        SelectionContainer(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(output, style = MaterialTheme.typography.body2, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun GitEmptyMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.Gray, style = MaterialTheme.typography.body2)
    }
}

private fun formatGitDialogTime(time: Instant): String = gitDialogTimeFormat.format(time.atZone(ZoneId.systemDefault()))
