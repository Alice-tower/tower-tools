package dev.towertools.launcher.tabs.repositories

import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryGitDialogStateTest {
    private val target = GitTarget("origin", "main")
    private val repository = DisplayRepository(
        DiscoveredRepository("test", "repository", Path.of("C:/test-repository")),
        "未分类", 0, false,
    )

    @Test
    fun publishesLocalStateBeforeFetchAndThenShowsUpdatedComparisonAndTime() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val loads = AtomicInteger()
        val git = FakeGit(
            read = { _, _ -> snapshot(behind = if (loads.incrementAndGet() == 1) 0 else 2) },
            fetchAction = {
                fetchStarted.countDown()
                assertTrue(releaseFetch.await(5, TimeUnit.SECONDS))
                GitOperationResult("fetch result")
            },
        )
        val now = Instant.parse("2026-10-14T08:00:00Z")
        val state = RepositoryGitDialogState(repository, scope, git, Clock.fixed(now, ZoneOffset.UTC))
        try {
            state.refresh()
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
            assertEquals(0, state.snapshot.value?.behind)
            assertTrue(state.isBusy.value)
            assertTrue(state.message.value.contains("正在更新远端"))
            assertNull(state.updatedAt.value)
            // Reopening or clicking refresh during an operation must not start another fetch.
            state.refresh()
            assertEquals(1, git.fetches.get())
            releaseFetch.countDown()
            awaitIdle(state)
            assertEquals(2, state.snapshot.value?.behind)
            assertEquals(now, state.updatedAt.value)
            assertFalse(state.isError.value)
            assertTrue(state.message.value.contains("更新完成"))
            assertTrue(state.output.value.startsWith("[2026-10-14 08:00:00] 远端信息更新完成"))
        } finally {
            releaseFetch.countDown()
            scope.cancel()
        }
    }

    @Test
    fun failedFetchKeepsLocalInformationAndShowsFailureWithoutSuccessfulTimestamp() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val state = RepositoryGitDialogState(repository, scope, FakeGit(fetchAction = { error("远端不可达") }))
        try {
            state.refresh()
            awaitIdle(state)
            assertNotNull(state.snapshot.value)
            assertTrue(state.isError.value)
            assertTrue(state.message.value.contains("远端不可达"))
            assertTrue(state.output.value.contains("远端不可达"))
            assertNull(state.updatedAt.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun localRepositoryWithoutRemotesDoesNotFetch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val git = FakeGit(read = { _, _ -> snapshot().copy(remotes = emptyList(), upstream = null) })
        val state = RepositoryGitDialogState(repository, scope, git)
        try {
            state.refresh()
            awaitIdle(state)
            assertEquals(0, git.fetches.get())
            assertTrue(state.message.value.contains("没有远端"))
            assertFalse(state.isError.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun branchChangeAfterDisplayStopsPushBeforeAnyWrite() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val git = FakeGit(read = { _, _ -> snapshot().copy(branch = "other") })
        val state = RepositoryGitDialogState(repository, scope, git)
        state.snapshot.value = snapshot()
        try {
            state.push()
            awaitIdle(state)
            assertEquals(0, git.pushes.get())
            assertTrue(state.isError.value)
            assertTrue(state.message.value.contains("分支已改变"))
            assertEquals("other", state.snapshot.value?.branch)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pushPassesTheFreshlyCheckedBranchAndCommitToTheBackend() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val checkedHead = "b".repeat(40)
        var pushedSource: GitSource? = null
        val git = FakeGit(
            read = { _, _ -> snapshot().copy(historyHead = checkedHead) },
            pushAction = { _, requested, source ->
                assertEquals(target, requested)
                pushedSource = source
                GitOperationResult("push success")
            },
        )
        val state = RepositoryGitDialogState(repository, scope, git)
        state.snapshot.value = snapshot().copy(historyHead = "a".repeat(40))
        try {
            state.push()
            awaitIdle(state)
            assertEquals(GitSource("main", checkedHead), pushedSource)
            assertFalse(state.isError.value)
        } finally {
            scope.cancel()
        }
    }
    @Test
    fun successfulPushIsNotReportedAsFailedWhenRefreshFails() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var pushedTarget: GitTarget? = null
        val git = FakeGit(
            read = { _, _ -> snapshot().copy(upstream = null) },
            fetchAction = { error("刷新网络失败") },
            pushAction = { _, requested, _ -> pushedTarget = requested; GitOperationResult("push success") },
        )
        val state = RepositoryGitDialogState(repository, scope, git)
        state.snapshot.value = snapshot().copy(upstream = null)
        try {
            state.push()
            awaitIdle(state)
            assertEquals(target, pushedTarget)
            assertTrue(state.isError.value)
            assertTrue(state.message.value.startsWith("Push 已完成"))
            assertTrue(state.output.value.contains("push success"))
            assertFalse(state.message.value.startsWith("Push 失败"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun switchingBranchResetsTheCachedComparisonTargetToItsNewUpstream() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newTarget = GitTarget("origin", "other")
        val git = FakeGit(read = { _, requested ->
            snapshot().copy(branch = "other", upstream = newTarget, comparisonTarget = requested ?: newTarget)
        })
        val state = RepositoryGitDialogState(repository, scope, git)
        state.snapshot.value = snapshot()
        try {
            state.refresh()
            awaitIdle(state)
            assertEquals(newTarget, state.snapshot.value?.comparisonTarget)
            assertEquals("other", state.snapshot.value?.branch)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadingMoreAppendsTenCommitsAndStopsAtTheEnd() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val head = "a".repeat(40)
        val history = (0 until 20).map { GitCommit(it.toString().padStart(40, '0'), "commit $it", "author", Instant.EPOCH) }
        val git = FakeGit(readCommits = { _, requestedHead, skip, limit ->
            assertEquals(head, requestedHead)
            assertEquals(10, skip)
            assertEquals(10, limit)
            GitCommitPage(history.drop(10), false, head)
        })
        val state = RepositoryGitDialogState(repository, scope, git)
        state.snapshot.value = snapshot().copy(commits = history.take(10), historyHead = head, hasMoreCommits = true)
        try {
            state.loadMoreCommits()
            awaitIdle(state)
            assertEquals(history, state.snapshot.value?.commits)
            assertFalse(state.snapshot.value!!.hasMoreCommits)
            assertNull(state.commitLoadError.value)
            state.loadMoreCommits()
            assertEquals(1, git.pages.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedPagePreservesHistoryAndCanBeRetried() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val head = "a".repeat(40)
        val first = (0 until 10).map { GitCommit(it.toString(), "commit $it", "author", Instant.EPOCH) }
        val attempt = AtomicInteger()
        val git = FakeGit(readCommits = { _, _, _, _ ->
            if (attempt.incrementAndGet() == 1) error("无法读取提交")
            GitCommitPage(listOf(GitCommit("last", "last", "author", Instant.EPOCH)), false, head)
        })
        val state = RepositoryGitDialogState(repository, scope, git)
        state.snapshot.value = snapshot().copy(commits = first, historyHead = head, hasMoreCommits = true)
        try {
            state.loadMoreCommits()
            awaitIdle(state)
            assertEquals(first, state.snapshot.value?.commits)
            assertTrue(state.snapshot.value!!.hasMoreCommits)
            assertTrue(state.commitLoadError.value!!.contains("无法读取提交"))
            state.loadMoreCommits()
            awaitIdle(state)
            assertEquals(11, state.snapshot.value?.commits?.size)
            assertNull(state.commitLoadError.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun changedUpstreamAfterDisplayStopsPushBeforeAnyWrite() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val changedTarget = GitTarget("origin", "other")
        val git = FakeGit(read = { _, _ -> snapshot().copy(upstream = changedTarget, comparisonTarget = changedTarget) })
        val state = RepositoryGitDialogState(repository, scope, git)
        state.snapshot.value = snapshot()
        try {
            state.push()
            awaitIdle(state)
            assertEquals(0, git.pushes.get())
            assertTrue(state.message.value.contains("同步目标已改变"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun openingTheRepositoryDirectoryRemainsAvailableDuringFetch() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val directoryOpened = CountDownLatch(1)
        val git = FakeGit(fetchAction = {
            fetchStarted.countDown()
            assertTrue(releaseFetch.await(5, TimeUnit.SECONDS))
            GitOperationResult("")
        })
        val state = RepositoryGitDialogState(repository, scope, git, openDirectoryAction = { path ->
            assertEquals(repository.repository.path, path)
            directoryOpened.countDown()
        })
        try {
            state.refresh()
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
            state.openDirectory()
            assertTrue(directoryOpened.await(5, TimeUnit.SECONDS))
            assertTrue(state.isBusy.value)
            releaseFetch.countDown()
            awaitIdle(state)
        } finally {
            releaseFetch.countDown()
            scope.cancel()
        }
    }

    @Test
    fun longOperationOutputKeepsItsTimestampAndHasABoundedSize() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val now = Instant.parse("2026-10-14T08:00:00Z")
        val git = FakeGit(fetchAction = { GitOperationResult("x".repeat(100_000)) })
        val state = RepositoryGitDialogState(repository, scope, git, Clock.fixed(now, ZoneOffset.UTC))
        try {
            state.refresh()
            awaitIdle(state)
            assertTrue(state.output.value.startsWith("[2026-10-14 08:00:00]"))
            assertTrue(state.output.value.contains("已截断"))
            assertTrue(state.output.value.length < 65_536)
        } finally {
            scope.cancel()
        }
    }

    private fun snapshot(behind: Int = 0) = GitSnapshot(
        branch = "main", hasCommits = true, upstream = target,
        remotes = listOf(GitRemote("origin", "file:///test-remote")),
        remoteBranches = listOf(target), commits = emptyList(),
        staged = emptyList(), unstaged = emptyList(), untracked = emptyList(),
        comparisonTarget = target, ahead = 0, behind = behind, historyHead = "b".repeat(40),
    )

    private fun awaitIdle(state: RepositoryGitDialogState) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while ((state.isBusy.value || state.isLoadingCommits.value) && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse(state.isBusy.value || state.isLoadingCommits.value, "Git task did not finish")
    }

    private inner class FakeGit(
        private val read: (Path, GitTarget?) -> GitSnapshot = { _, _ -> snapshot() },
        private val fetchAction: () -> GitOperationResult = { GitOperationResult("") },
        private val pushAction: (Path, GitTarget, GitSource) -> GitOperationResult = { _, _, _ -> GitOperationResult("") },
        private val readCommits: (Path, String, Int, Int) -> GitCommitPage = { _, head, _, _ -> GitCommitPage(emptyList(), false, head) },
    ) : RepositoryGitClient {
        val fetches = AtomicInteger()
        val pushes = AtomicInteger()
        val pages = AtomicInteger()
        override fun load(path: Path, target: GitTarget?) = read(path, target)
        override fun fetch(path: Path): GitOperationResult {
            fetches.incrementAndGet()
            return fetchAction()
        }
        override fun pull(path: Path, target: GitTarget) = GitOperationResult("")
        override fun push(path: Path, target: GitTarget, source: GitSource): GitOperationResult {
            pushes.incrementAndGet()
            return pushAction(path, target, source)
        }
        override fun commits(path: Path, head: String, skip: Int, limit: Int): GitCommitPage {
            pages.incrementAndGet()
            return readCommits(path, head, skip, limit)
        }
    }
}
