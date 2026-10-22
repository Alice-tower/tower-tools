@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.towertools.launcher.tabs.repositories

import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.Window as AwtWindow
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryGitDialogTest {
    @Test
    fun dialogShowsIndependentPanelsAndSupportsPagingAndDirectoryActionDuringUpdates() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = DisplayRepository(
            DiscoveredRepository("test", "repository", Path.of("C:/test-repository/" + "很长的仓库目录/".repeat(18))),
            "未分类", 0, false,
        )
        val target = GitTarget("origin", "main")
        val now = Instant.parse("2026-10-14T08:00:00Z")
        val head = "b".repeat(40)
        fun commit(number: Int) = GitCommit(number.toString(16).padStart(40, 'a'), "提交 $number", "Alice", now)
        val pagingStarted = AtomicInteger()
        val releasePaging = CountDownLatch(1)
        val openedDirectory = AtomicReference<Path?>(null)
        val dismissCount = AtomicInteger()
        val git = object : RepositoryGitClient {
            override fun load(path: Path, target: GitTarget?): GitSnapshot = error("UI smoke test must not read Git")
            override fun commits(path: Path, head: String, skip: Int, limit: Int): GitCommitPage {
                assertEquals(repository.repository.path, path)
                assertEquals("b".repeat(40), head)
                assertEquals(10, skip)
                assertEquals(10, limit)
                pagingStarted.incrementAndGet()
                check(releasePaging.await(3, TimeUnit.SECONDS)) { "UI test must release the fake page reader" }
                return GitCommitPage((11..20).map(::commit), false, head)
            }
            override fun fetch(path: Path): GitOperationResult = error("UI smoke test must not fetch")
            override fun pull(path: Path, target: GitTarget): GitOperationResult = error("UI smoke test must not pull")
            override fun push(path: Path, target: GitTarget, source: GitSource): GitOperationResult = error("UI smoke test must not push")
        }
        val state = RepositoryGitDialogState(repository, scope, git, openDirectoryAction = openedDirectory::set)
        val local = GitSnapshot(
            branch = "main",
            hasCommits = true,
            upstream = target,
            remotes = listOf(GitRemote("origin", "https://example.invalid/repository.git")),
            remoteBranches = listOf(target),
            commits = (1..10).map(::commit),
            staged = listOf(GitFileChange("src/已暂存.kt", "新增")),
            unstaged = listOf(GitFileChange("src/未暂存.kt", "修改")),
            untracked = listOf(GitFileChange("未跟踪.txt", "未跟踪")),
            comparisonTarget = target,
            ahead = 0,
            behind = 0,
            historyHead = head,
            hasMoreCommits = true,
        )
        state.snapshot.value = local
        state.message.value = "本地信息已加载"
        val failure = AtomicReference<Throwable?>(null)
        val exit = AtomicReference<() -> Unit>({})
        val windowReference = AtomicReference<ComposeWindow?>(null)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        val timeout = watchdog.schedule({
            EventQueue.invokeLater {
                failure.compareAndSet(null, AssertionError("Git dialog smoke test timed out\n${windowDiagnostics()}"))
                exit.get().invoke()
            }
        }, 12, TimeUnit.SECONDS)

        try {
            application(exitProcessOnExit = false) {
                exit.set { exitApplication() }
                Window(
                    onCloseRequest = { exitApplication() },
                    title = "Repository Git dialog test",
                    state = rememberWindowState(
                        position = WindowPosition(-5000.dp, -5000.dp),
                        width = 1250.dp,
                        height = 800.dp,
                    ),
                ) {
                    MaterialTheme {
                        RepositoryGitDialog(state, onDismiss = { dismissCount.incrementAndGet() })
                    }
                    LaunchedEffect(Unit) {
                        windowReference.set(window)
                        try {
                            withTimeout(10_000) {
                                // Compose Dialog can render in a scene layer, without a separate AWT Dialog.
                                while (!window.isVisible || gitDialogNode(window) == null) delay(20)
                                delay(150)
                                assertUsableDialog(window)
                                assertCompactHeader(window)
                                assertControlDisabled(window, "Git Push", false)
                                assertText(window, "src/已暂存.kt")
                                assertText(window, "src/未暂存.kt")
                                assertText(window, "未跟踪.txt")
                                assertText(window, "已暂存 1 · 未暂存 1 · 未跟踪 1")
                                assertText(window, "提交 1")

                                state.snapshot.value = local.copy(staged = emptyList(), untracked = emptyList())
                                delay(100)
                                assertText(window, "已暂存 0 · 未暂存 1 · 未跟踪 0")
                                assertText(window, "未暂存 · 1 个文件")
                                assertText(window, "src/未暂存.kt")
                                assertNoText(window, "已暂存 · 0 个文件")
                                assertNoText(window, "未跟踪 · 0 个文件")
                                assertNoText(window, "无")

                                state.snapshot.value = local.copy(staged = emptyList(), unstaged = emptyList(), untracked = emptyList())
                                delay(100)
                                assertText(window, "已暂存 0 · 未暂存 0 · 未跟踪 0")
                                assertText(window, "工作区没有文件改动")
                                assertNoText(window, "已暂存 · 0 个文件")
                                assertNoText(window, "未暂存 · 0 个文件")
                                assertNoText(window, "未跟踪 · 0 个文件")
                                assertNoText(window, "无")
                                assertUsableDialog(window)

                                state.snapshot.value = local
                                delay(100)
                                scrollCommitsTo(window, 11)
                                delay(150)
                                click(window, "加载更多")
                                delay(100)
                                assertTrue(state.isLoadingCommits.value)
                                assertControlDisabled(window, "Git Pull", true)
                                assertControlDisabled(window, "Git Push", true)
                                assertControlDisabled(window, "刷新", true)
                                assertControlDisabled(window, "加载中…", true)
                                assertControlDisabled(window, "打开目标目录", false)
                                click(window, "打开目标目录")
                                while (openedDirectory.get() == null) delay(20)
                                assertEquals(repository.repository.path, openedDirectory.get(), "Directory action must open the repository itself")
                                releasePaging.countDown()
                                while (state.isLoadingCommits.value) delay(20)
                                delay(150)
                                assertEquals(1, pagingStarted.get())
                                assertEquals(20, state.snapshot.value?.commits?.size)
                                scrollCommitsTo(window, 21)
                                delay(150)
                                assertText(window, "已全部加载")
                                assertUsableDialog(window)

                                state.isBusy.value = true
                                state.message.value = "本地信息已加载 · 正在更新远端信息…"
                                delay(100)
                                assertUsableDialog(window)
                                assertControlDisabled(window, "Git Push", true)
                                assertControlDisabled(window, "打开目标目录", false)

                                state.snapshot.value = local.copy(
                                    branch = "feature/new",
                                    upstream = null,
                                    remotes = emptyList(),
                                    remoteBranches = emptyList(),
                                    comparisonTarget = null,
                                    ahead = null,
                                    behind = null,
                                )
                                state.isBusy.value = false
                                state.message.value = "本地信息已加载 · 没有远端仓库"
                                delay(150)
                                assertUsableDialog(window)
                                assertControlDisabled(window, "Git Pull", true)
                                assertControlDisabled(window, "Git Push", true)

                                // A missing remote branch is reported by the operation rather than disabling its button.
                                state.snapshot.value = local.copy(upstream = null, remoteBranches = emptyList(), ahead = null, behind = null)
                                delay(100)
                                assertControlDisabled(window, "Git Push", false)

                                state.snapshot.value = local.copy(ahead = 2, behind = 1)
                                state.updatedAt.value = now
                                state.output.value = "[2026-10-14 16:00:00] 远端信息更新完成\n测试操作结果"
                                state.message.value = "远端信息更新完成"
                                delay(150)
                                click(window, "操作结果")
                                delay(150)
                                assertText(window, state.output.value)
                                assertUsableDialog(window)

                                // Smaller host sizes must constrain the surface and keep both panels and footer visible.
                                state.snapshot.value = local.copy(
                                    branch = "feature/" + "long-branch-name/".repeat(8),
                                    upstream = GitTarget("origin", "feature/" + "long-branch-name/".repeat(8)),
                                )
                                window.setSize(980, 650)
                                delay(150)
                                assertUsableDialog(window)

                                state.isBusy.value = true
                                delay(100)
                                assertControlDisabled(window, "刷新", true)
                                assertControlDisabled(window, "关闭 Git 状态", false)
                                click(window, "关闭 Git 状态")
                                assertEquals(1, dismissCount.get(), "Top-right close button must dismiss even during an operation")
                                assertTrue(state.isBusy.value, "Closing must not cancel the repository operation")
                            }
                        } catch (error: Throwable) {
                            failure.compareAndSet(null, AssertionError("Git dialog smoke test failed\n${windowDiagnostics()}", error))
                        } finally {
                            releasePaging.countDown()
                            exitApplication()
                        }
                    }
                }
            }
        } finally {
            releasePaging.countDown()
            timeout.cancel(false)
            watchdog.shutdownNow()
            scope.cancel()
        }
        failure.get()?.let { throw it }
        assertFalse(checkNotNull(windowReference.get()).isVisible, "Test window must close when the test exits")
    }

    private fun assertUsableDialog(window: ComposeWindow) {
        assertTrue(window.isVisible && window.isDisplayable, "Test window must remain visible during updates")
        val dialog = checkNotNull(gitDialogNode(window)) { "Rendered Git dialog must remain present during updates" }
        val allNodes = nodes(dialog).toList()
        val texts = allNodes.flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }.toSet()
        listOf("Git 状态 · repository", "最近提交", "文件改动", "远端", "操作结果", "打开目标目录", "Git Pull", "Git Push").forEach { text ->
            assertTrue(text in texts, "Git dialog must render $text")
        }
        assertFalse("目标分支（可输入）" in texts || "比较" in texts, "Target selection controls must be removed")
        assertTrue(allNodes.none { it.config.getOrNull(SemanticsProperties.EditableText) != null }, "Git dialog must contain no editable target fields")
        val history = taggedNode(dialog, "git-history-pane").boundsInWindow
        val details = taggedNode(dialog, "git-details-pane").boundsInWindow
        assertTrue(history.right < details.left, "Commit history must remain to the left of the detail tabs")
        assertTrue(history.height > 100f && details.height > 100f, "Both panels must retain usable scroll space")
        val surface = taggedNode(dialog, "git-dialog-surface").boundsInWindow
        val actions = taggedNode(dialog, "git-header-actions").boundsInWindow
        val header = taggedNode(dialog, "git-dialog-header").boundsInWindow
        val summary = taggedNode(dialog, "git-sync-summary").boundsInWindow
        val close = actionNode(dialog, "关闭 Git 状态").boundsInWindow
        val refresh = actionNode(dialog, "刷新").boundsInWindow
        assertTrue(kotlin.math.abs(close.right - header.right) <= 1f, "Close button must stay at the far right of the header")
        assertTrue(kotlin.math.abs(close.top - header.top) <= 1f, "Close button must stay at the top of the header")
        assertTrue(refresh.right < close.left, "Refresh must precede the separate close control")
        assertTrue(actions.bottom <= summary.top, "Sync information must remain below the header actions")
        assertTrue(kotlin.math.abs(summary.right - actions.right) <= 1f, "Sync information must use the full right column width")
        val bounds = dialog.boundsInWindow
        val transform = window.graphicsConfiguration.defaultTransform
        val logicalRight = bounds.right / transform.scaleX
        val logicalBottom = bounds.bottom / transform.scaleY
        assertTrue(surface.width > surface.height * 1.4f, "Git dialog must use a wide, compact layout")
        assertTrue(surface.height / transform.scaleY <= 641, "Git dialog surface height must not exceed 640dp")
        assertTrue(
            bounds.left >= -1f && bounds.top >= -1f &&
                logicalRight <= window.width + 1 && logicalBottom <= window.height + 1,
            "Git dialog must fit its host; rendered=$bounds, " +
                "host=${window.width}x${window.height}, scale=${transform.scaleX}x${transform.scaleY}",
        )
        val directoryButton = actionNode(dialog, "打开目标目录").boundsInWindow
        val pullButton = actionNode(dialog, "Git Pull").boundsInWindow
        assertTrue(directoryButton.right < pullButton.left, "Directory action must remain at the left of the footer")
        assertTrue(directoryButton.bottom <= surface.bottom + 1f, "Footer must stay within the compact surface")
    }

    private fun assertText(window: ComposeWindow, text: String) {
        assertTrue(nodes(checkNotNull(gitDialogNode(window))).any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }, "Git dialog must show $text")
    }

    private fun assertNoText(window: ComposeWindow, text: String) {
        assertFalse(nodes(checkNotNull(gitDialogNode(window))).any { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == text }
        }, "Git dialog must omit $text")
    }

    private fun assertCompactHeader(window: ComposeWindow) {
        val dialog = checkNotNull(gitDialogNode(window))
        val surface = taggedNode(dialog, "git-dialog-surface").boundsInWindow
        val header = taggedNode(dialog, "git-dialog-header").boundsInWindow
        val identity = taggedNode(dialog, "git-repository-identity").boundsInWindow
        val summaryNode = taggedNode(dialog, "git-sync-summary")
        val summary = summaryNode.boundsInWindow
        val history = taggedNode(dialog, "git-history-pane").boundsInWindow
        val details = taggedNode(dialog, "git-details-pane").boundsInWindow
        val scaleY = window.graphicsConfiguration.defaultTransform.scaleY
        assertTrue(identity.right <= summary.left + 1f, "Repository identity and sync summary must share separate header columns")
        assertTrue(identity.top < header.bottom && summary.top < header.bottom, "Both header columns must remain in the compact header")
        assertTrue(header.height / scaleY <= 110, "Normal sync information must fit a compact header")
        assertTrue((history.top - surface.top) / scaleY <= 160, "Compact header must move commit history up")
        assertTrue((details.top - surface.top) / scaleY <= 160, "Compact header must move detail tabs up")
        assertTrue(kotlin.math.abs(history.top - details.top) <= 1f, "Both body panels must begin at the same height")
        val summaryText = nodes(summaryNode).flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().asSequence().map { it.text }
        }.joinToString(" ")
        assertTrue("main" in summaryText && "origin/main" in summaryText, "Compact summary must retain the current branch and comparison target")
        taggedNode(dialog, "git-file-counts")
    }

    private fun assertControlDisabled(window: ComposeWindow, label: String, disabled: Boolean) {
        val node = actionNode(checkNotNull(gitDialogNode(window)), label)
        assertEquals(disabled, node.config.getOrNull(SemanticsProperties.Disabled) != null, "$label disabled state")
    }

    private fun click(window: ComposeWindow, label: String) {
        val node = actionNode(checkNotNull(gitDialogNode(window)), label)
        assertTrue(checkNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke(), "$label must respond to its click")
    }

    private fun scrollCommitsTo(window: ComposeWindow, index: Int) {
        val list = taggedNode(checkNotNull(gitDialogNode(window)), "git-commit-list")
        assertTrue(checkNotNull(list.config.getOrNull(SemanticsActions.ScrollToIndex)?.action).invoke(index), "Commit history must scroll to its load-more footer")
    }

    private fun taggedNode(dialog: SemanticsNode, tag: String): SemanticsNode =
        checkNotNull(nodes(dialog).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }) { "Missing $tag" }

    private fun actionNode(dialog: SemanticsNode, label: String): SemanticsNode =
        checkNotNull(nodes(dialog).firstOrNull { node ->
            (node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
                label in node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()) &&
                node.config.getOrNull(SemanticsActions.OnClick)?.action != null
        }) { "Missing clickable $label" }

    private fun gitDialogNode(window: ComposeWindow): SemanticsNode? =
        window.semanticsOwners.asSequence().flatMap { nodes(it.rootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.IsDialog) != null }

    private fun nodes(node: SemanticsNode): Sequence<SemanticsNode> =
        sequenceOf(node) + node.children.asSequence().flatMap(::nodes)

    private fun windowDiagnostics(): String = AwtWindow.getWindows().joinToString("\n") { candidate ->
        "class=${candidate.javaClass.name}, owner=${candidate.owner?.javaClass?.name}, " +
            "visible=${candidate.isVisible}, displayable=${candidate.isDisplayable}, bounds=${candidate.bounds}" +
            if (candidate is ComposeWindow) ", semanticsOwners=${candidate.semanticsOwners.size}" else ""
    }
}
