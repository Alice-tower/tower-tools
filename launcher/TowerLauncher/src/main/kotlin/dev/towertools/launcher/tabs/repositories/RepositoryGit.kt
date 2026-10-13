package dev.towertools.launcher.tabs.repositories

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class GitTarget(val remote: String, val branch: String)
internal data class GitSource(val branch: String, val head: String)
internal data class GitCommit(val hash: String, val subject: String, val author: String, val time: Instant)
internal data class GitCommitPage(val commits: List<GitCommit>, val hasMore: Boolean, val head: String)
internal data class GitFileChange(val path: String, val status: String, val originalPath: String? = null)
internal data class GitRemote(val name: String, val url: String, val pushUrl: String? = null)
internal data class GitSnapshot(
    val branch: String?,
    val hasCommits: Boolean,
    val upstream: GitTarget?,
    val remotes: List<GitRemote>,
    val remoteBranches: List<GitTarget>,
    val commits: List<GitCommit>,
    val staged: List<GitFileChange>,
    val unstaged: List<GitFileChange>,
    val untracked: List<GitFileChange>,
    val comparisonTarget: GitTarget?,
    val ahead: Int?,
    val behind: Int?,
    val historyHead: String? = null,
    val hasMoreCommits: Boolean = false,
)
internal data class GitOperationResult(val output: String)

internal interface RepositoryGitClient {
    fun load(path: Path, target: GitTarget? = null): GitSnapshot
    fun fetch(path: Path): GitOperationResult
    fun pull(path: Path, target: GitTarget): GitOperationResult
    fun push(path: Path, target: GitTarget, source: GitSource): GitOperationResult
    fun commits(path: Path, head: String, skip: Int, limit: Int = 10): GitCommitPage
}

/** Blocking operations; callers use an interruptible background scope, never the UI thread. */
internal class RepositoryGit(
    private val executable: String = findGitExecutable(),
    private val timeoutSeconds: Long = 120,
) : RepositoryGitClient {
    override fun load(path: Path, target: GitTarget?): GitSnapshot {
        val status = parseGitStatus(command(path, listOf(
            "status", "--porcelain=v2", "--branch", "--ahead-behind", "-z",
            "--untracked-files=all", "--ignore-submodules=none",
        )).stdout)
        val remotes = readRemotes(path)
        val upstream = status.branch?.let { branch ->
            val remote = optionalConfig(path, "branch.$branch.remote")
            val reference = optionalConfig(path, "branch.$branch.merge")
            if (remote != null && reference?.startsWith("refs/heads/") == true) {
                GitTarget(remote, reference.removePrefix("refs/heads/"))
            } else null
        }
        val remoteBranches = readRemoteBranches(path, remotes)
        val comparison = target ?: upstream ?: status.branch?.takeIf {
            remotes.any { remote -> remote.name == "origin" }
        }?.let { GitTarget("origin", it) }
        val counts = if (status.hasCommits && comparison != null && remotes.any { it.name == comparison.remote }) {
            val reference = "refs/remotes/${comparison.remote}/${comparison.branch}"
            val present = command(path, listOf("rev-parse", "--verify", "--quiet", "$reference^{commit}"), setOf(0, 1))
            if (present.exitCode == 0) {
                command(path, listOf("rev-list", "--left-right", "--count", "HEAD...$reference", "--"))
                    .stdout.trim().split(Regex("\\s+")).let { values ->
                        require(values.size == 2) { "Git 返回的分支比较结果无效" }
                        values[0].toInt() to values[1].toInt()
                    }
            } else null
        } else null
        val history = if (status.hasCommits) {
            val reference = status.branch?.let { "refs/heads/$it" } ?: "HEAD"
            val head = command(path, listOf("rev-parse", "--verify", "$reference^{commit}")).stdout.trim()
            commits(path, head, 0)
        } else null
        return GitSnapshot(
            status.branch, status.hasCommits, upstream, remotes, remoteBranches, history?.commits.orEmpty(),
            status.staged, status.unstaged, status.untracked, comparison, counts?.first, counts?.second,
            history?.head, history?.hasMore ?: false,
        )
    }

    override fun commits(path: Path, head: String, skip: Int, limit: Int): GitCommitPage {
        require(Regex("[0-9a-fA-F]{40,64}").matches(head)) { "提交历史起点无效，请刷新 Git 状态" }
        require(skip >= 0 && limit in 1..100) { "提交分页参数无效" }
        val commits = parseGitCommits(command(path, listOf(
            "log", "-${limit + 1}", "--skip=$skip",
            "--no-show-signature", "--no-notes", "--no-decorate", "--no-color", "--no-patch",
            "--format=%H%x00%s%x00%an%x00%aI", "-z", head, "--",
        )).stdout)
        return GitCommitPage(commits.take(limit), commits.size > limit, head)
    }

    override fun fetch(path: Path): GitOperationResult {
        val remotes = readRemotes(path)
        if (remotes.isEmpty()) return GitOperationResult("没有远端仓库")
        val outputs = remotes.map { remote ->
            operation(path, listOf(
                "fetch", "--prune", "--no-prune-tags", "--no-tags", "--no-recurse-submodules", "--refmap=",
                "--", remote.name, "+refs/heads/*:refs/remotes/${remote.name}/*",
            )).output
        }.filter(String::isNotBlank)
        return GitOperationResult(outputs.joinToString("\n").ifBlank { "远端状态已更新" })
    }

    override fun pull(path: Path, target: GitTarget): GitOperationResult {
        validateTarget(path, target)
        requireCurrentBranch(path)
        return operation(path, listOf(
            "-c", "fetch.pruneTags=false", "-c", "remote.${target.remote}.pruneTags=false",
            "pull", "--ff-only", "--no-rebase", "--no-autostash", "--no-squash", "--no-tags", "--no-prune", "--refmap=",
            "--no-recurse-submodules", "--", target.remote,
            "+refs/heads/${target.branch}:refs/remotes/${target.remote}/${target.branch}",
        ))
    }

    override fun push(path: Path, target: GitTarget, source: GitSource): GitOperationResult {
        validateTarget(path, target)
        requireSource(path, source)
        val pushUrls = command(path, listOf("remote", "get-url", "--push", "--all", "--", target.remote))
            .stdout.lineSequence().filter(String::isNotBlank).distinct().toList()
        require(pushUrls.size == 1) { "远端配置了多个推送地址，无法安全确认目标分支，请在 Git 中操作" }
        val pushUrl = pushUrls.single()
        // Keep the named remote's transport settings while pinning its actual push destination.
        val remoteOptions = listOf(
            "-c", "remote.${target.remote}.url=", "-c", "remote.${target.remote}.url=$pushUrl",
            "-c", "remote.${target.remote}.pushurl=", "-c", "remote.${target.remote}.pushurl=$pushUrl",
        )
        val targetRef = "refs/heads/${target.branch}"
        val remoteReference = command(path, remoteOptions + listOf("ls-remote", "--heads", "--", target.remote, targetRef))
            .stdout.lineSequence().filter(String::isNotBlank).map { it.split('\t', limit = 2) }
            .singleOrNull { it.size == 2 && it[1] == targetRef }
        require(remoteReference != null) { "远端分支 ${target.remote}/${target.branch} 不存在，未推送" }
        val remoteHead = remoteReference[0]
        require(Regex("[0-9a-fA-F]{40,64}").matches(remoteHead)) { "Git 返回的远端提交无效" }

        // Read the actual push destination, which can differ from the configured fetch URL.
        command(path, remoteOptions + listOf(
            "fetch", "--no-write-fetch-head", "--no-tags", "--no-prune", "--no-recurse-submodules", "--refmap=",
            "--", target.remote, targetRef,
        ))
        val ancestry = command(path, listOf("merge-base", "--is-ancestor", remoteHead, source.head), setOf(0, 1))
        check(ancestry.exitCode == 0) { "推送需要快进更新；当前分支落后或已分叉，请先更新并解决分叉" }

        // Pin both object IDs: the lease prevents recreation if the remote branch is deleted
        // or changes after checking, while the ancestry check above forbids rewriting history.
        requireSource(path, source)
        val result = operation(path, remoteOptions + listOf(
            "-c", "remote.${target.remote}.mirror=false",
            "-c", "push.followTags=false", "-c", "push.autoSetupRemote=false",
            "push", "--porcelain", "--no-force", "--force-with-lease=$targetRef:$remoteHead", "--no-force-if-includes",
            "--no-mirror", "--no-follow-tags", "--recurse-submodules=no",
            "--", target.remote, "${source.head}:$targetRef",
        ))
        return result
    }

    private fun requireSource(path: Path, source: GitSource) {
        require(Regex("[0-9a-fA-F]{40,64}").matches(source.head)) { "推送源提交无效，请刷新 Git 状态" }
        require(requireCurrentBranch(path) == source.branch) { "当前分支已变化，请刷新 Git 状态后重试" }
        val head = command(path, listOf("rev-parse", "--verify", "refs/heads/${source.branch}^{commit}")).stdout.trim()
        require(head == source.head) { "当前分支提交已变化，请刷新 Git 状态后重试" }
    }

    private fun requireCurrentBranch(path: Path): String {
        val branch = command(path, listOf("symbolic-ref", "--quiet", "HEAD"), setOf(0, 1))
        require(branch.exitCode == 0) { "当前处于游离 HEAD，无法执行 Pull / Push" }
        val reference = branch.stdout.trim()
        require(reference.startsWith("refs/heads/")) { "找不到当前分支" }
        return reference.removePrefix("refs/heads/")
    }

    private fun validateTarget(path: Path, target: GitTarget) {
        require(readRemotes(path).any { it.name == target.remote }) { "远端仓库已不存在，请刷新 Git 状态" }
        val valid = command(path, listOf("check-ref-format", "refs/heads/${target.branch}"), setOf(0, 1))
        require(target.branch.isNotBlank() && valid.exitCode == 0) { "远端分支名称无效" }
    }

    private fun optionalConfig(path: Path, key: String): String? =
        command(path, listOf("config", "--null", "--get", key), setOf(0, 1)).let { result ->
            if (result.exitCode == 0) result.stdout.removeSuffix("\u0000").takeIf(String::isNotBlank) else null
        }

    private fun readRemotes(path: Path): List<GitRemote> =
        command(path, listOf("remote")).stdout.lineSequence().filter(String::isNotBlank).map { name ->
            val url = command(path, listOf("remote", "get-url", "--", name)).stdout.trimEnd('\r', '\n')
            val pushUrl = command(path, listOf("remote", "get-url", "--push", "--", name)).stdout.trimEnd('\r', '\n')
            GitRemote(name, url, pushUrl.takeIf { it != url })
        }.toList()

    private fun readRemoteBranches(path: Path, remotes: List<GitRemote>): List<GitTarget> {
        val names = remotes.map(GitRemote::name).sortedByDescending(String::length)
        return command(path, listOf("for-each-ref", "--format=%(refname)%00%(symref)", "refs/remotes/"))
            .stdout.lineSequence().filter(String::isNotBlank).mapNotNull { line ->
                val values = line.split('\u0000', limit = 2)
                require(values.size == 2) { "Git 返回的远端分支列表无效" }
                if (values[1].isNotEmpty()) return@mapNotNull null
                val remote = names.firstOrNull { values[0].startsWith("refs/remotes/$it/") } ?: return@mapNotNull null
                GitTarget(remote, values[0].removePrefix("refs/remotes/$remote/"))
            }.distinct().sortedWith(compareBy({ it.remote }, { it.branch })).toList()
    }

    private fun operation(path: Path, arguments: List<String>): GitOperationResult =
        command(path, arguments, requireCompleteOutput = false).let { result ->
            val output = listOf(result.stdout.trim(), result.stderr.trim()).filter(String::isNotEmpty).joinToString("\n")
            GitOperationResult(output + if (result.truncated) "\n（输出过长，已截断）" else "")
        }

    private fun command(
        path: Path,
        arguments: List<String>,
        acceptedExitCodes: Set<Int> = setOf(0),
        requireCompleteOutput: Boolean = true,
    ): GitCommandOutput {
        require(Files.isDirectory(path)) { "找不到仓库目录：$path" }
        val process = try {
            ProcessBuilder(listOf(executable, "-c", "core.quotepath=false", "-c", "i18n.logOutputEncoding=UTF-8") + arguments)
                .directory(path.toFile()).apply {
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    environment()["GIT_OPTIONAL_LOCKS"] = "0"
                }.start().also { it.outputStream.close() }
        } catch (error: IOException) {
            throw IllegalStateException("无法启动 Git。请安装 Git 并确保它位于 PATH 中。", error)
        }
        val stdout = GitOutputCollector(process.inputStream)
        val stderr = GitOutputCollector(process.errorStream)
        val outThread = Thread(stdout, "repository-git-output").apply { isDaemon = true; start() }
        val errThread = Thread(stderr, "repository-git-error").apply { isDaemon = true; start() }
        try {
            check(process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { "Git 操作超时（$timeoutSeconds 秒），已停止进程" }
            outThread.join(2_000)
            errThread.join(2_000)
            check(!outThread.isAlive && !errThread.isAlive) { "读取 Git 输出超时" }
            stdout.failure.get()?.let { throw IllegalStateException("读取 Git 输出失败", it) }
            stderr.failure.get()?.let { throw IllegalStateException("读取 Git 错误信息失败", it) }
            val result = GitCommandOutput(process.exitValue(), stdout.text(), stderr.text(), stdout.truncated || stderr.truncated)
            check(result.exitCode in acceptedExitCodes) {
                "Git 操作失败（退出码 ${result.exitCode}）：\n" +
                    listOf(result.stderr.trim(), result.stdout.trim()).filter(String::isNotEmpty).joinToString("\n")
            }
            check(!requireCompleteOutput || !result.truncated) { "Git 输出过长，无法完整读取仓库状态" }
            return result
        } finally {
            // Credential helpers and SSH can outlive Git. Stop the complete child tree on timeout/cancellation.
            process.descendants().use { children -> children.forEach { if (it.isAlive) it.destroyForcibly() } }
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }
}

private data class GitCommandOutput(val exitCode: Int, val stdout: String, val stderr: String, val truncated: Boolean)

private class GitOutputCollector(private val input: InputStream) : Runnable {
    private val bytes = ByteArrayOutputStream()
    val failure = AtomicReference<Throwable?>()
    var truncated = false
        private set

    override fun run() {
        try {
            input.use { stream ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    val remaining = MAX_GIT_OUTPUT_BYTES - bytes.size()
                    if (remaining > 0) bytes.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) truncated = true
                }
            }
        } catch (error: IOException) {
            failure.set(error)
        }
    }

    fun text(): String = bytes.toString(StandardCharsets.UTF_8)
}

private const val MAX_GIT_OUTPUT_BYTES = 8 * 1024 * 1024

private fun findGitExecutable(): String {
    val candidates = System.getenv("PATH").orEmpty().split(';').asSequence().mapNotNull { value ->
        runCatching { Path.of(value.trim().trim('"')).resolve("git.exe") }.getOrNull()
    } + listOfNotNull(System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA")).asSequence().map {
        Path.of(it).resolve(if (it == System.getenv("LOCALAPPDATA")) "Programs/Git/cmd/git.exe" else "Git/cmd/git.exe")
    }
    return candidates.firstOrNull(Files::isRegularFile)?.toString() ?: "git"
}

internal data class GitWorkingStatus(
    val branch: String?,
    val hasCommits: Boolean,
    val staged: List<GitFileChange>,
    val unstaged: List<GitFileChange>,
    val untracked: List<GitFileChange>,
)

/** NUL delimiters preserve Chinese names, spaces, tabs, newlines and both paths of a rename. */
internal fun parseGitStatus(output: String): GitWorkingStatus {
    var branch: String? = null
    var hasCommits = false
    var hasBranchHeader = false
    var hasCommitHeader = false
    val staged = mutableListOf<GitFileChange>()
    val unstaged = mutableListOf<GitFileChange>()
    val untracked = mutableListOf<GitFileChange>()
    val records = output.split('\u0000')
    var index = 0
    while (index < records.size) {
        val record = records[index++]
        when {
            record.startsWith("# branch.head ") -> {
                hasBranchHeader = true
                branch = record.removePrefix("# branch.head ").takeUnless { it == "(detached)" }
            }
            record.startsWith("# branch.oid ") -> {
                hasCommitHeader = true
                hasCommits = record.removePrefix("# branch.oid ") != "(initial)"
            }
            record.startsWith("1 ") || record.startsWith("2 ") -> {
                val rename = record.startsWith("2 ")
                val fields = record.split(' ', limit = if (rename) 10 else 9)
                require(fields.size == if (rename) 10 else 9) { "Git 返回的文件状态无效" }
                val xy = fields[1]
                require(xy.length == 2) { "Git 返回的文件状态标记无效" }
                val original = if (rename) {
                    require(index < records.lastIndex) { "Git 返回的重命名路径不完整" }
                    records[index++]
                } else null
                val path = fields.last()
                if (xy[0] != '.') staged += GitFileChange(path, gitChangeLabel(xy[0]), original)
                if (xy[1] != '.') unstaged += GitFileChange(path, gitChangeLabel(xy[1]), original)
            }
            record.startsWith("u ") -> {
                val fields = record.split(' ', limit = 11)
                require(fields.size == 11) { "Git 返回的冲突状态无效" }
                unstaged += GitFileChange(fields.last(), "冲突")
            }
            record.startsWith("? ") -> untracked += GitFileChange(record.removePrefix("? "), "未跟踪")
            record.isEmpty() || record.startsWith("# ") || record.startsWith("! ") -> Unit
            else -> error("无法识别 Git 文件状态")
        }
    }
    require(hasBranchHeader && hasCommitHeader) { "Git 返回的分支状态不完整" }
    return GitWorkingStatus(branch, hasCommits, staged, unstaged, untracked)
}

private fun gitChangeLabel(status: Char): String = when (status) {
    'M' -> "修改"
    'A' -> "新增"
    'D' -> "删除"
    'R' -> "重命名"
    'C' -> "复制"
    'T' -> "类型变更"
    'U' -> "冲突"
    else -> error("无法识别 Git 文件状态：$status")
}

internal fun parseGitCommits(output: String): List<GitCommit> {
    if (output.isEmpty()) return emptyList()
    val fields = output.removeSuffix("\u0000").split('\u0000')
    require(fields.size % 4 == 0) { "Git 返回的提交记录不完整" }
    return fields.chunked(4).map { values ->
        GitCommit(values[0], values[1], values[2], OffsetDateTime.parse(values[3]).toInstant())
    }
}
