package dev.towertools.launcher.tabs.repositories

import androidx.compose.runtime.mutableStateOf
import dev.towertools.launcher.AppLog
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.logging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Owned by the repository Tab, so operations survive closing the dialog or switching Tabs. */
internal class RepositoryGitDialogState(
    var repository: DisplayRepository,
    private val scope: CoroutineScope,
    private val git: RepositoryGitClient = RepositoryGit(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val openDirectoryAction: (Path) -> Unit = { directory ->
        require(Files.isDirectory(directory)) { "找不到仓库目录：$directory" }
        Desktop.getDesktop().open(directory.toFile())
    },
) {
    val snapshot = mutableStateOf<GitSnapshot?>(null)
    val isBusy = mutableStateOf(false)
    val isLoadingCommits = mutableStateOf(false)
    val commitLoadError = mutableStateOf<String?>(null)
    val message = mutableStateOf("正在读取本地 Git 信息…")
    val isError = mutableStateOf(false)
    val output = mutableStateOf("")
    val updatedAt = mutableStateOf<Instant?>(null)
    private val path: Path get() = repository.repository.path
    private val outputEntries = ArrayDeque<String>()
    private var outputCharacters = 0

    fun refresh() {
        if (isBusy.value || isLoadingCommits.value) return
        begin("正在读取本地 Git 信息…")
        scope.launch {
            try {
                loadLocal()
                if (snapshot.value!!.remotes.isEmpty()) {
                    message.value = "本地信息已加载 · 没有远端仓库"
                    return@launch
                }
                updateRemote("本地信息已加载 · 正在更新远端信息…")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(if (snapshot.value == null) "读取本地 Git 信息失败" else "更新信息失败", error)
            } finally {
                isBusy.value = false
            }
        }
    }

    fun loadMoreCommits() {
        val current = snapshot.value ?: return
        val head = current.historyHead ?: return
        if (isBusy.value || isLoadingCommits.value || !current.hasMoreCommits) return
        isLoadingCommits.value = true
        commitLoadError.value = null
        scope.launch {
            try {
                val page = runInterruptible(Dispatchers.IO) {
                    git.commits(path, head, current.commits.size, 10)
                }
                check(page.head == head) { "提交历史已改变，请刷新后重试" }
                snapshot.value = current.copy(
                    commits = current.commits + page.commits,
                    hasMoreCommits = page.hasMore,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                commitLoadError.value = "加载提交失败：${error.message ?: "未知错误"}"
                appendOutput("加载提交失败", error.message.orEmpty())
                AppLog.logger.log(Level.WARNING, "Unable to load Git commits: $path", error)
            } finally {
                isLoadingCommits.value = false
            }
        }
    }

    fun openDirectory() {
        scope.launch {
            try {
                runInterruptible(Dispatchers.IO) {
                    openDirectoryAction(path)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail("打开仓库目录失败", error)
            }
        }
    }

    fun pull() = operate(push = false)

    fun push() = operate(push = true)

    private fun operate(push: Boolean) {
        if (isBusy.value || isLoadingCommits.value) return
        val displayed = snapshot.value
        val action = if (push) "Push" else "Pull"
        begin("正在执行 $action…")
        scope.launch {
            var operationCompleted = false
            try {
                val current = runInterruptible(Dispatchers.IO) { git.load(path) }
                require(current.branch != null && current.hasCommits) { "当前仓库没有可操作的分支提交" }
                require(current.branch == displayed?.branch) { "当前分支已改变，请刷新后重新操作" }
                val target = current.comparisonTarget
                    ?: error("当前分支没有跟踪目标，且未找到 origin 远端")
                require(current.comparisonTarget == displayed?.comparisonTarget) { "同步目标已改变，请刷新后重新操作" }
                require(current.remotes.any { it.name == target.remote }) { "同步目标的远端已不存在，请刷新 Git 状态" }
                message.value = "正在执行 $action：${target.remote}/${target.branch}…"
                val result = runInterruptible(Dispatchers.IO) {
                    if (push) {
                        val head = requireNotNull(current.historyHead) { "无法确定当前分支提交，请刷新后重试" }
                        git.push(path, target, GitSource(current.branch, head))
                    } else {
                        git.pull(path, target)
                    }
                }
                operationCompleted = true
                appendOutput("$action ${target.remote}/${target.branch} 已完成", result.output)
                loadLocal()
                updateRemote("$action 已完成 · 正在更新远端信息…", "$action 已完成 · 远端信息更新完成")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail(if (operationCompleted) "$action 已完成，但刷新状态失败" else "$action 失败", error)
                // A failed pull can still fetch refs; expose those and any conflict state immediately.
                try {
                    loadLocal()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refreshError: Exception) {
                    appendOutput("重新读取本地状态失败", refreshError.message.orEmpty())
                }
            } finally {
                isBusy.value = false
            }
        }
    }

    private suspend fun loadLocal() {
        snapshot.value = runInterruptible(Dispatchers.IO) { git.load(path) }
        commitLoadError.value = null
    }

    private suspend fun updateRemote(
        progress: String,
        completed: String = "远端信息更新完成",
    ) {
        message.value = progress
        val result = runInterruptible(Dispatchers.IO) { git.fetch(path) }
        loadLocal()
        updatedAt.value = clock.instant()
        message.value = completed
        appendOutput(completed, result.output)
    }

    private fun begin(text: String) {
        isBusy.value = true
        isError.value = false
        message.value = text
    }

    private fun fail(label: String, error: Exception) {
        isError.value = true
        message.value = "$label：${error.message ?: "未知错误"}"
        appendOutput(label, error.message.orEmpty())
        AppLog.logger.log(Level.WARNING, "$label: $path", error)
    }

    private fun appendOutput(label: String, detail: String) {
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(clock.instant().atZone(clock.zone))
        val fullDetail = detail.ifBlank { "操作成功。" }
        val limitedDetail = if (fullDetail.length > 16_384) fullDetail.take(16_384) + "\n（输出过长，已截断）" else fullDetail
        val entry = "[$time] $label\n$limitedDetail"
        outputEntries.addLast(entry)
        outputCharacters += entry.length + 2
        while (outputCharacters > 65_536 && outputEntries.size > 1) {
            outputCharacters -= outputEntries.removeFirst().length + 2
        }
        output.value = outputEntries.joinToString("\n\n")
    }
}
