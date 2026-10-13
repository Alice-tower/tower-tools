package dev.towertools.launcher.tabs.repositories

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.attribute.DosFileAttributeView
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryGitTest {
    @Test
    fun parsesNullDelimitedStatusWithoutDamagingPaths() {
        val output = listOf(
            "# branch.oid abc123", "# branch.head feature/中文", "# extra.header value",
            "1 MM N... 100644 100644 100644 a b 中文 空格.txt",
            "2 R. N... 100644 100644 100644 a b R100 新 名称.txt", "原 名称.txt",
            "1 .D N... 100644 100644 000000 a b 删除.txt",
            "u UU N... 100644 100644 100644 100644 a b c 冲突.txt",
            "? folder/名字\t带换行\n.txt", "! ignored.txt", "",
        ).joinToString("\u0000")
        val result = parseGitStatus(output)
        assertEquals("feature/中文", result.branch)
        assertTrue(result.hasCommits)
        assertEquals(listOf(
            GitFileChange("中文 空格.txt", "修改"),
            GitFileChange("新 名称.txt", "重命名", "原 名称.txt"),
        ), result.staged)
        assertEquals(listOf(
            GitFileChange("中文 空格.txt", "修改"), GitFileChange("删除.txt", "删除"), GitFileChange("冲突.txt", "冲突"),
        ), result.unstaged)
        assertEquals(listOf(GitFileChange("folder/名字\t带换行\n.txt", "未跟踪")), result.untracked)
        assertFailsWith<IllegalArgumentException> { parseGitStatus("1 .M invalid\u0000") }
    }

    @Test
    fun parsesCommitTimesAndUnbornOrDetachedHeaders() {
        val commit = parseGitCommits("abc\u0000修复 中文\u0000测试者\u00002026-10-14T08:00:00+08:00\u0000").single()
        assertEquals(GitCommit("abc", "修复 中文", "测试者", Instant.parse("2026-10-14T00:00:00Z")), commit)
        val unborn = parseGitStatus("# branch.oid (initial)\u0000# branch.head main\u0000")
        assertFalse(unborn.hasCommits)
        assertEquals("main", unborn.branch)
        val detached = parseGitStatus("# branch.oid abc\u0000# branch.head (detached)\u0000")
        assertTrue(detached.hasCommits)
        assertNull(detached.branch)
    }

    @Test
    fun loadsActualStagedUnstagedUntrackedAndRenamedFiles() = withRepositories { fixture ->
        val repository = fixture.work
        fixture.write(repository, "修改 空格.txt", "original\n")
        fixture.write(repository, "原 名称.txt", "rename\n")
        fixture.write(repository, "删除.txt", "delete\n")
        fixture.write(repository, ".gitignore", "ignored/\n")
        fixture.commit(repository, "初始 中文提交")
        fixture.git(repository, "push", "--set-upstream", "origin", "main")
        fixture.write(repository, "修改 空格.txt", "staged\n")
        fixture.git(repository, "add", "修改 空格.txt")
        fixture.write(repository, "修改 空格.txt", "unstaged after staging\n")
        fixture.git(repository, "mv", "原 名称.txt", "新 名称.txt")
        Files.delete(repository.resolve("删除.txt"))
        fixture.write(repository, "子目录/未跟踪 文件.txt", "untracked\n")
        fixture.write(repository, "ignored/hidden.txt", "ignored\n")

        val snapshot = RepositoryGit().load(repository)
        assertEquals("main", snapshot.branch)
        assertEquals(GitTarget("origin", "main"), snapshot.upstream)
        assertEquals(0, snapshot.ahead)
        assertEquals(0, snapshot.behind)
        assertEquals("初始 中文提交", snapshot.commits.single().subject)
        assertTrue(snapshot.commits.single().hash.length >= 40)
        assertTrue(GitFileChange("修改 空格.txt", "修改") in snapshot.staged)
        assertTrue(GitFileChange("新 名称.txt", "重命名", "原 名称.txt") in snapshot.staged)
        assertTrue(GitFileChange("修改 空格.txt", "修改") in snapshot.unstaged)
        assertTrue(GitFileChange("删除.txt", "删除") in snapshot.unstaged)
        assertEquals(listOf(GitFileChange("子目录/未跟踪 文件.txt", "未跟踪")), snapshot.untracked)
    }

    @Test
    fun fetchRefreshesCachedComparisonAndPullFastForwards() = withRepositories { fixture ->
        fixture.seed()
        val peer = fixture.clonePeer()
        fixture.write(peer, "remote.txt", "remote change\n")
        fixture.commit(peer, "远端提交")
        fixture.git(peer, "push", "origin", "main")
        val service = RepositoryGit()
        assertEquals(0, service.load(fixture.work).behind)
        service.fetch(fixture.work)
        assertEquals(1, service.load(fixture.work).behind)
        service.pull(fixture.work, GitTarget("origin", "main"))
        assertEquals("remote change\n", Files.readString(fixture.work.resolve("remote.txt")))
        assertEquals(0, service.load(fixture.work).behind)
        fixture.write(fixture.work, "local.txt", "local change\n")
        fixture.commit(fixture.work, "本地提交")
        assertEquals(1, service.load(fixture.work).ahead)
        service.push(fixture.work, GitTarget("origin", "main"), fixture.source())
        service.fetch(fixture.work)
        assertEquals(0, service.load(fixture.work).ahead)
    }

    @Test
    fun divergentPullRejectsRebaseAndAutostashConfiguration() = withRepositories { fixture ->
        fixture.seed()
        val peer = fixture.clonePeer()
        fixture.write(fixture.work, "local.txt", "local\n")
        fixture.commit(fixture.work, "local divergence")
        fixture.write(peer, "remote.txt", "remote\n")
        fixture.commit(peer, "remote divergence")
        fixture.git(peer, "push", "origin", "main")
        fixture.git(fixture.work, "config", "pull.rebase", "true")
        fixture.git(fixture.work, "config", "pull.ff", "false")
        fixture.git(fixture.work, "config", "rebase.autoStash", "true")
        fixture.git(fixture.work, "config", "merge.autoStash", "true")
        fixture.git(fixture.work, "config", "remote.origin.fetch", "+refs/heads/*:refs/heads/*")
        fixture.write(fixture.work, "seed.txt", "uncommitted\n")
        val originalHead = fixture.git(fixture.work, "rev-parse", "HEAD").trim()
        val service = RepositoryGit()
        service.fetch(fixture.work)
        val snapshot = service.load(fixture.work)
        assertEquals(1, snapshot.ahead)
        assertEquals(1, snapshot.behind)
        assertFailsWith<IllegalStateException> { service.pull(fixture.work, GitTarget("origin", "main")) }
        assertEquals(originalHead, fixture.git(fixture.work, "rev-parse", "HEAD").trim())
        assertEquals("uncommitted\n", Files.readString(fixture.work.resolve("seed.txt")))
        assertEquals("", fixture.git(fixture.work, "stash", "list").trim())
        assertFalse(Files.exists(fixture.work.resolve(".git/rebase-merge")))
        assertFalse(Files.exists(fixture.work.resolve(".git/rebase-apply")))
    }

    @Test
    fun pushUpdatesOnlyCurrentBranchDespiteMirrorMatchingAndTagSettings() = withRepositories { fixture ->
        fixture.seed()
        val initialHead = fixture.git(fixture.work, "rev-parse", "HEAD").trim()
        fixture.git(fixture.remote, "update-ref", "refs/heads/review", initialHead)
        fixture.git(fixture.work, "branch", "side")
        fixture.write(fixture.work, "main.txt", "new main\n")
        fixture.commit(fixture.work, "current branch change")
        val currentHead = fixture.git(fixture.work, "rev-parse", "HEAD").trim()
        fixture.git(fixture.work, "tag", "-a", "v-test", "-m", "annotated tag")
        fixture.git(fixture.work, "tag", "main") // Same-name tag must not make the current branch ambiguous.
        fixture.git(fixture.work, "config", "remote.origin.mirror", "true")
        fixture.git(fixture.work, "config", "remote.origin.push", "refs/heads/*:refs/heads/*")
        fixture.git(fixture.work, "config", "push.default", "matching")
        fixture.git(fixture.work, "config", "push.followTags", "true")
        fixture.git(fixture.work, "config", "push.autoSetupRemote", "true")
        val service = RepositoryGit()
        service.push(fixture.work, GitTarget("origin", "review"), fixture.source())
        assertEquals(initialHead, fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim())
        assertEquals(currentHead, fixture.git(fixture.remote, "rev-parse", "refs/heads/review").trim())
        assertEquals("", fixture.git(fixture.remote, "for-each-ref", "--format=%(refname)", "refs/heads/side", "refs/tags/").trim())
        assertEquals(GitTarget("origin", "main"), service.load(fixture.work).upstream)
    }

    @Test
    fun supportsNoUpstreamMultipleRemotesUnbornAndDetachedStates() = withRepositories { fixture ->
        val service = RepositoryGit()
        var snapshot = service.load(fixture.work)
        assertEquals("main", snapshot.branch)
        assertFalse(snapshot.hasCommits)
        assertNull(snapshot.upstream)
        assertNull(snapshot.ahead)
        assertTrue(snapshot.commits.isEmpty())
        fixture.seed()
        fixture.git(fixture.work, "branch", "--unset-upstream")
        fixture.git(fixture.work, "remote", "add", "backup", fixture.remote.toString())
        service.fetch(fixture.work)
        snapshot = service.load(fixture.work)
        assertNull(snapshot.upstream)
        assertEquals(GitTarget("origin", "main"), snapshot.comparisonTarget)
        assertEquals(0, snapshot.ahead)
        assertEquals(0, snapshot.behind)
        assertEquals(setOf("origin", "backup"), snapshot.remotes.map(GitRemote::name).toSet())
        assertTrue(GitTarget("backup", "main") in snapshot.remoteBranches)
        snapshot = service.load(fixture.work, GitTarget("backup", "main"))
        assertEquals(0, snapshot.ahead)
        assertEquals(0, snapshot.behind)
        assertNull(service.load(fixture.work, GitTarget("backup", "absent")).ahead)
        fixture.git(fixture.work, "checkout", "--detach")
        snapshot = service.load(fixture.work)
        assertNull(snapshot.branch)
        assertTrue(snapshot.hasCommits)
        assertFailsWith<IllegalArgumentException> { service.push(fixture.work, GitTarget("origin", "main"), fixture.source()) }
        assertFailsWith<IllegalArgumentException> { service.pull(fixture.work, GitTarget("origin", "main")) }
    }

    @Test
    fun returnsOnlyLatestTenCommitsAndHandlesNoRemote() = withRepositories { fixture ->
        fixture.git(fixture.work, "remote", "remove", "origin")
        repeat(12) { index ->
            fixture.write(fixture.work, "history.txt", "commit $index\n")
            fixture.commit(fixture.work, "commit $index")
        }
        fixture.git(fixture.work, "notes", "add", "-m", "attached note")
        fixture.git(fixture.work, "config", "log.showSignature", "true")
        fixture.git(fixture.work, "config", "log.showNotes", "true")
        fixture.git(fixture.work, "config", "log.decorate", "full")
        fixture.git(fixture.work, "config", "color.ui", "always")
        val service = RepositoryGit()
        val snapshot = service.load(fixture.work)
        assertTrue(snapshot.remotes.isEmpty())
        assertTrue(snapshot.commits.all { Regex("[0-9a-f]{40,64}").matches(it.hash) })
        assertEquals(10, snapshot.commits.size)
        assertEquals("commit 11", snapshot.commits.first().subject)
        assertEquals("commit 2", snapshot.commits.last().subject)
        assertTrue(snapshot.hasMoreCommits)
        assertEquals(snapshot.commits.first().hash, snapshot.historyHead)
        val remainder = service.commits(fixture.work, snapshot.historyHead!!, 10)
        assertEquals(listOf("commit 1", "commit 0"), remainder.commits.map(GitCommit::subject))
        assertFalse(remainder.hasMore)
        assertEquals("没有远端仓库", service.fetch(fixture.work).output)
    }

    @Test
    fun fetchPrunesDeletedRemoteBranchesWithoutDeletingTagsOrLocalBranches() = withRepositories { fixture ->
        fixture.seed()
        fixture.git(fixture.work, "branch", "local-only")
        fixture.git(fixture.work, "tag", "local-tag")
        fixture.git(fixture.work, "config", "fetch.pruneTags", "true")
        fixture.git(fixture.work, "config", "remote.origin.pruneTags", "true")
        fixture.git(fixture.work, "config", "--add", "remote.origin.fetch", "+refs/tags/*:refs/tags/*")
        fixture.git(fixture.remote, "update-ref", "-d", "refs/heads/main")
        val service = RepositoryGit()
        assertEquals(0, service.load(fixture.work).behind)
        service.fetch(fixture.work)
        val snapshot = service.load(fixture.work)
        assertNull(snapshot.ahead)
        assertNull(snapshot.behind)
        assertFalse(GitTarget("origin", "main") in snapshot.remoteBranches)
        assertTrue(fixture.git(fixture.work, "rev-parse", "refs/heads/local-only").isNotBlank())
        assertTrue(fixture.git(fixture.work, "rev-parse", "refs/tags/local-tag").isNotBlank())
    }

    @Test
    fun timesOutBlockedSshHelperWithoutContactingNetwork() = withRepositories { fixture ->
        if (!System.getProperty("os.name").startsWith("Windows")) return@withRepositories
        val helper = fixture.root.resolve("wait-helper.ps1")
        Files.writeString(helper, "Start-Sleep -Seconds 30\n")
        fixture.git(fixture.work, "remote", "set-url", "origin", "ssh://example.invalid/repository.git")
        fixture.git(fixture.work, "config", "ssh.variant", "ssh")
        fixture.git(fixture.work, "config", "core.sshCommand", "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"${helper.toString().replace('\\', '/')}\"")
        val started = System.nanoTime()
        val error = assertFailsWith<IllegalStateException> { RepositoryGit(timeoutSeconds = 1).fetch(fixture.work) }
        assertTrue(error.message.orEmpty().contains("超时"))
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
    }

    @Test
    fun noUpstreamFallsBackOnlyToOriginAndRetainsMissingConfiguredTarget() = withRepositories { fixture ->
        fixture.seed()
        fixture.git(fixture.work, "branch", "--unset-upstream")
        fixture.git(fixture.work, "remote", "add", "backup", fixture.remote.toString())
        val service = RepositoryGit()
        service.fetch(fixture.work)
        assertEquals(GitTarget("origin", "main"), service.load(fixture.work).comparisonTarget)
        fixture.git(fixture.work, "remote", "remove", "origin")
        assertNull(service.load(fixture.work).comparisonTarget)
        fixture.git(fixture.work, "config", "branch.main.remote", "missing")
        fixture.git(fixture.work, "config", "branch.main.merge", "refs/heads/other")
        fixture.git(fixture.work, "remote", "add", "origin", fixture.remote.toString())
        val snapshot = service.load(fixture.work)
        assertEquals(GitTarget("missing", "other"), snapshot.upstream)
        assertEquals(GitTarget("missing", "other"), snapshot.comparisonTarget)
        assertNull(snapshot.ahead)
        assertFailsWith<IllegalArgumentException> { service.push(fixture.work, snapshot.comparisonTarget!!, fixture.source()) }
    }

    @Test
    fun safePushDoesNotSetUpstreamOrCreateMissingBranch() = withRepositories { fixture ->
        fixture.seed()
        fixture.git(fixture.work, "branch", "--unset-upstream")
        fixture.git(fixture.work, "config", "push.autoSetupRemote", "true")
        fixture.write(fixture.work, "local.txt", "local change\n")
        fixture.commit(fixture.work, "local change")
        val service = RepositoryGit()
        service.push(fixture.work, GitTarget("origin", "main"), fixture.source())
        assertNull(service.load(fixture.work).upstream)
        assertEquals(
            fixture.git(fixture.work, "rev-parse", "HEAD").trim(),
            fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim(),
        )
        val error = assertFailsWith<IllegalArgumentException> {
            service.push(fixture.work, GitTarget("origin", "absent"), fixture.source())
        }
        assertTrue(error.message.orEmpty().contains("不存在"))
        assertEquals("", fixture.git(fixture.remote, "for-each-ref", "--format=%(refname)", "refs/heads/absent").trim())
    }

    @Test
    fun pushRejectsDeletedRemoteBranchEvenWhenCachedReferenceRemains() = withRepositories { fixture ->
        fixture.seed()
        fixture.git(fixture.remote, "update-ref", "-d", "refs/heads/main")
        val service = RepositoryGit()
        assertEquals(0, service.load(fixture.work).ahead)
        assertFailsWith<IllegalArgumentException> { service.push(fixture.work, GitTarget("origin", "main"), fixture.source()) }
        assertEquals("", fixture.git(fixture.remote, "for-each-ref", "--format=%(refname)", "refs/heads/main").trim())
    }

    @Test
    fun pushRejectsNonFastForwardInsteadOfRewritingRemoteHistory() = withRepositories { fixture ->
        fixture.seed()
        val peer = fixture.clonePeer()
        fixture.write(fixture.work, "local.txt", "local\n")
        fixture.commit(fixture.work, "local divergence")
        fixture.write(peer, "remote.txt", "remote\n")
        fixture.commit(peer, "remote divergence")
        fixture.git(peer, "push", "origin", "main")
        val remoteHead = fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim()
        val error = assertFailsWith<IllegalStateException> {
            RepositoryGit().push(fixture.work, GitTarget("origin", "main"), fixture.source())
        }
        assertTrue(error.message.orEmpty().contains("快进"))
        assertEquals(remoteHead, fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim())
    }

    @Test
    fun pushChecksActualPushUrlAndRejectsMultiplePushDestinations() = withRepositories { fixture ->
        fixture.seed()
        val pushRemote = Files.createDirectory(fixture.root.resolve("push.git"))
        fixture.git(pushRemote, "init", "--bare", "--initial-branch=main")
        fixture.git(fixture.work, "remote", "set-url", "--push", "origin", pushRemote.toString())
        val service = RepositoryGit()
        assertFailsWith<IllegalArgumentException> { service.push(fixture.work, GitTarget("origin", "main"), fixture.source()) }
        assertEquals("", fixture.git(pushRemote, "for-each-ref", "--format=%(refname)", "refs/heads/main").trim())
        fixture.git(fixture.work, "push", pushRemote.toString(), "main")
        fixture.write(fixture.work, "local.txt", "local\n")
        fixture.commit(fixture.work, "local change")
        val originalFetchHead = fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim()
        service.push(fixture.work, GitTarget("origin", "main"), fixture.source())
        assertEquals(
            fixture.git(fixture.work, "rev-parse", "HEAD").trim(),
            fixture.git(pushRemote, "rev-parse", "refs/heads/main").trim(),
        )
        assertEquals(originalFetchHead, fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim())
        fixture.git(fixture.work, "remote", "set-url", "--add", "--push", "origin", fixture.remote.toString())
        val error = assertFailsWith<IllegalArgumentException> { service.push(fixture.work, GitTarget("origin", "main"), fixture.source()) }
        assertTrue(error.message.orEmpty().contains("多个推送地址"))
    }

    @Test
    fun pushLeaseDoesNotRecreateBranchDeletedWhilePushIsStarting() = withRepositories { fixture ->
        fixture.seed()
        fixture.write(fixture.work, "local.txt", "local\n")
        fixture.commit(fixture.work, "local change")
        fixture.beforePush("git --git-dir=${fixture.shellQuote(fixture.remote.toString())} update-ref -d refs/heads/main")
        assertFailsWith<IllegalStateException> { RepositoryGit().push(fixture.work, GitTarget("origin", "main"), fixture.source()) }
        assertEquals("", fixture.git(fixture.remote, "for-each-ref", "--format=%(refname)", "refs/heads/main").trim())
    }

    @Test
    fun pushLeaseRejectsRemoteChangeAfterFastForwardCheck() = withRepositories { fixture ->
        fixture.seed()
        val peer = fixture.clonePeer()
        fixture.write(peer, "peer.txt", "peer\n")
        fixture.commit(peer, "peer change")
        fixture.git(peer, "push", "origin", "main:other")
        val concurrentHead = fixture.git(peer, "rev-parse", "HEAD").trim()
        fixture.write(fixture.work, "local.txt", "local\n")
        fixture.commit(fixture.work, "local change")
        fixture.beforePush("git --git-dir=${fixture.shellQuote(fixture.remote.toString())} update-ref refs/heads/main $concurrentHead")
        assertFailsWith<IllegalStateException> { RepositoryGit().push(fixture.work, GitTarget("origin", "main"), fixture.source()) }
        assertEquals(concurrentHead, fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim())
    }

    @Test
    fun commitPagesStayOnOriginalHeadAndEndWithoutOverlapping() = withRepositories { fixture ->
        repeat(23) { index ->
            fixture.write(fixture.work, "history.txt", "commit $index\n")
            fixture.commit(fixture.work, "commit $index")
        }
        val service = RepositoryGit()
        val snapshot = service.load(fixture.work)
        val head = snapshot.historyHead!!
        assertEquals((22 downTo 13).map { "commit $it" }, snapshot.commits.map(GitCommit::subject))
        assertTrue(snapshot.hasMoreCommits)
        fixture.write(fixture.work, "history.txt", "new commit\n")
        fixture.commit(fixture.work, "new commit")
        val middle = service.commits(fixture.work, head, 10)
        assertEquals((12 downTo 3).map { "commit $it" }, middle.commits.map(GitCommit::subject))
        assertTrue(middle.hasMore)
        val last = service.commits(fixture.work, head, 20)
        assertEquals((2 downTo 0).map { "commit $it" }, last.commits.map(GitCommit::subject))
        assertFalse(last.hasMore)
        val exhausted = service.commits(fixture.work, head, 23)
        assertTrue(exhausted.commits.isEmpty())
        assertFalse(exhausted.hasMore)
        assertEquals(head, middle.head)
        assertEquals(head, last.head)
        assertEquals(23, (snapshot.commits + middle.commits + last.commits).map(GitCommit::hash).distinct().size)
        assertEquals("new commit", service.load(fixture.work).commits.first().subject)
        assertFailsWith<IllegalArgumentException> { service.commits(fixture.work, "HEAD", 10) }
        assertFailsWith<IllegalArgumentException> { service.commits(fixture.work, head, -1) }
    }
    @Test
    fun pullFastForwardsDespiteConfiguredSquash() = withRepositories { fixture ->
        fixture.seed()
        val peer = fixture.clonePeer()
        fixture.write(peer, "remote.txt", "remote change\n")
        fixture.commit(peer, "remote change")
        fixture.git(peer, "push", "origin", "main")
        fixture.git(fixture.work, "config", "branch.main.mergeOptions", "--squash")
        RepositoryGit().pull(fixture.work, GitTarget("origin", "main"))
        assertEquals(
            fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim(),
            fixture.git(fixture.work, "rev-parse", "HEAD").trim(),
        )
        assertEquals("", fixture.git(fixture.work, "status", "--porcelain").trim())
        assertEquals("--squash", fixture.git(fixture.work, "config", "branch.main.mergeOptions").trim())
    }

    @Test
    fun pushRejectsBranchSwitchAfterSourceWasChecked() = withRepositories { fixture ->
        fixture.seed()
        val source = fixture.source()
        fixture.git(fixture.work, "checkout", "-b", "feature")
        fixture.write(fixture.work, "feature.txt", "feature change\n")
        fixture.commit(fixture.work, "feature change")
        val error = assertFailsWith<IllegalArgumentException> {
            RepositoryGit().push(fixture.work, GitTarget("origin", "main"), source)
        }
        assertTrue(error.message.orEmpty().contains("当前分支已变化"))
        assertEquals(source.head, fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim())
    }

    @Test
    fun pushRejectsCommitChangeAfterSourceWasChecked() = withRepositories { fixture ->
        fixture.seed()
        val source = fixture.source()
        fixture.write(fixture.work, "local.txt", "local change\n")
        fixture.commit(fixture.work, "local change")
        val error = assertFailsWith<IllegalArgumentException> {
            RepositoryGit().push(fixture.work, GitTarget("origin", "main"), source)
        }
        assertTrue(error.message.orEmpty().contains("提交已变化"))
        assertEquals(source.head, fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim())
    }

    @Test
    fun pushKeepsCheckedSourceWhenHookSwitchesBranch() = withRepositories { fixture ->
        fixture.seed()
        fixture.git(fixture.work, "checkout", "-b", "feature")
        fixture.write(fixture.work, "feature.txt", "feature change\n")
        fixture.commit(fixture.work, "feature change")
        val featureHead = fixture.git(fixture.work, "rev-parse", "HEAD").trim()
        fixture.git(fixture.work, "checkout", "main")
        fixture.write(fixture.work, "main.txt", "main change\n")
        fixture.commit(fixture.work, "main change")
        val source = fixture.source()
        fixture.beforePush("git -C ${fixture.shellQuote(fixture.work.toString())} checkout feature")
        RepositoryGit().push(fixture.work, GitTarget("origin", "main"), source)
        assertEquals("feature", fixture.git(fixture.work, "branch", "--show-current").trim())
        assertEquals(featureHead, fixture.git(fixture.work, "rev-parse", "HEAD").trim())
        assertEquals(source.head, fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim())
    }

    @Test
    fun pushPreservesRemoteTransportConfiguration() = withRepositories { fixture ->
        fixture.seed()
        val initialHead = fixture.source().head
        fixture.write(fixture.work, "local.txt", "local change\n")
        fixture.commit(fixture.work, "local change")
        val originalUrl = fixture.git(fixture.work, "remote", "get-url", "origin").trim()
        val service = RepositoryGit()
        fixture.git(fixture.work, "config", "remote.origin.uploadpack", "false")
        assertFailsWith<IllegalStateException> {
            service.push(fixture.work, GitTarget("origin", "main"), fixture.source())
        }
        fixture.git(fixture.work, "config", "--unset", "remote.origin.uploadpack")
        fixture.git(fixture.work, "config", "remote.origin.receivepack", "false")
        assertFailsWith<IllegalStateException> {
            service.push(fixture.work, GitTarget("origin", "main"), fixture.source())
        }
        assertEquals(initialHead, fixture.git(fixture.remote, "rev-parse", "refs/heads/main").trim())
        assertEquals(originalUrl, fixture.git(fixture.work, "remote", "get-url", "origin").trim())
        assertEquals("", fixture.git(fixture.work, "config", "--get-regexp", "remote.origin.*").lineSequence()
            .filter { it.startsWith("remote.origin.pushurl ") }.joinToString("\n"))
    }

    @OptIn(ExperimentalPathApi::class)
    private fun withRepositories(block: (GitFixture) -> Unit) {
        val root = createTempDirectory("repository-git-")
        try {
            block(GitFixture(root))
        } finally {
            // Git makes object files read-only on Windows. Only clear attributes under this fixture's own root.
            val temporaryDirectory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
            check(root.toAbsolutePath().normalize().parent == temporaryDirectory)
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                    Files.getFileAttributeView(file, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
                        ?.setReadOnly(false)
                }
            }
            root.deleteRecursively()
        }
    }

    private class GitFixture(val root: Path) {
        val remote = Files.createDirectory(root.resolve("remote.git"))
        val work = Files.createDirectory(root.resolve("工作 仓库"))

        init {
            git(remote, "init", "--bare", "--initial-branch=main")
            git(work, "init", "--initial-branch=main")
            configure(work)
            git(work, "remote", "add", "origin", remote.toString())
        }

        fun shellQuote(value: String): String = "'" + value.replace('\\', '/').replace("'", "'\"'\"'") + "'"

        fun beforePush(command: String) {
            val hooks = Files.createDirectories(root.resolve("push-hooks"))
            val hook = hooks.resolve("pre-push")
            Files.writeString(hook, "#!/bin/sh\nset -e\n$command\n")
            check(hook.toFile().setExecutable(true))
            git(work, "config", "core.hooksPath", hooks.toString())
        }
        fun configure(repository: Path) {
            git(repository, "config", "user.name", "测试者")
            git(repository, "config", "user.email", "test@example.invalid")
            git(repository, "config", "commit.gpgSign", "false")
            git(repository, "config", "tag.gpgSign", "false")
            git(repository, "config", "core.autocrlf", "false")
            git(repository, "config", "core.hooksPath", root.resolve("unused-hooks").toString())
        }

        fun source(): GitSource = GitSource("main", git(work, "rev-parse", "refs/heads/main").trim())

        fun seed() {
            write(work, "seed.txt", "seed\n")
            commit(work, "seed")
            git(work, "push", "--set-upstream", "origin", "main")
        }

        fun clonePeer(): Path {
            val peer = root.resolve("peer")
            git(root, "clone", remote.toString(), peer.toString())
            configure(peer)
            return peer
        }

        fun write(repository: Path, name: String, content: String) {
            val file = repository.resolve(name)
            Files.createDirectories(file.parent)
            Files.writeString(file, content)
        }

        fun commit(repository: Path, message: String) {
            git(repository, "add", "--all")
            git(repository, "commit", "-m", message)
        }

        fun git(repository: Path, vararg arguments: String): String {
            val process = ProcessBuilder(listOf("git", "-c", "core.quotepath=false") + arguments)
                .directory(repository.toFile()).redirectErrorStream(true).apply {
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                }.start()
            process.outputStream.close()
            try {
                val output = process.inputStream.bufferedReader(Charsets.UTF_8).readText()
                check(process.waitFor(20, TimeUnit.SECONDS)) { "Test Git process timed out" }
                check(process.exitValue() == 0) { "Test Git failed: ${arguments.toList()}\n$output" }
                return output
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.inputStream.close()
            }
        }
    }
}
