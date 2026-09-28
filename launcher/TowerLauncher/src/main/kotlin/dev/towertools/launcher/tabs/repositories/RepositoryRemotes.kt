package dev.towertools.launcher.tabs.repositories

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

internal data class RepositoryRemotes(
    val hasRemote: Boolean,
    val githubUrl: String? = null,
)

/** Reads the repository's local Git configuration without contacting a remote or requiring Git on PATH. */
internal fun readRepositoryRemotes(repository: Path): RepositoryRemotes {
    val config = repository.resolve(".git").resolve("config")
    if (!Files.isRegularFile(config)) error("找不到 Git 配置：$config")
    val urls = linkedMapOf<String, MutableList<String>>()
    var remote: String? = null
    Files.newBufferedReader(config).useLines { lines ->
        lines.forEach { line ->
            val trimmed = withoutGitComment(line).trim()
            if (trimmed.startsWith("[")) {
                remote = REMOTE_SECTION.matchEntire(trimmed)?.groupValues?.get(1)
            } else if (remote != null) {
                val match = URL_ENTRY.matchEntire(trimmed)
                if (match != null) {
                    val url = match.groupValues[1].trim().removeSurrounding("\"")
                    if (url.isNotEmpty()) urls.getOrPut(checkNotNull(remote)) { mutableListOf() } += url
                }
            }
        }
    }
    val ordered = urls.entries.sortedWith(compareBy<Map.Entry<String, MutableList<String>>> { it.key != "origin" })
        .flatMap { it.value }
    return RepositoryRemotes(
        hasRemote = ordered.isNotEmpty(),
        githubUrl = ordered.firstNotNullOfOrNull(::githubWebUrl),
    )
}

private val REMOTE_SECTION = Regex("""\[\s*remote\s+"([^"]+)"\s*]""", RegexOption.IGNORE_CASE)
private val URL_ENTRY = Regex("""url\s*=\s*(.+)""", RegexOption.IGNORE_CASE)
private val SCP_GITHUB = Regex("""(?:[^@/\s:]+@)?github\.com:([^\s?#]+)""", RegexOption.IGNORE_CASE)
private val OWNER = Regex("[A-Za-z0-9-]+")
private val REPOSITORY = Regex("[A-Za-z0-9._-]+")

/** Git treats # and ; outside quoted values as the start of a comment. */
private fun withoutGitComment(line: String): String {
    var quoted = false
    var escaped = false
    line.forEachIndexed { index, character ->
        when {
            escaped -> escaped = false
            character == '\\' -> escaped = true
            character == '"' -> quoted = !quoted
            !quoted && (character == '#' || character == ';') -> return line.substring(0, index)
        }
    }
    require(!quoted) { "Git 配置中的引号未闭合" }
    return line
}

internal fun githubWebUrl(remoteUrl: String): String? {
    val path = SCP_GITHUB.matchEntire(remoteUrl.trim())?.groupValues?.get(1) ?: run {
        val uri = runCatching { URI(remoteUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https", "ssh", "git") ||
            !uri.host.equals("github.com", ignoreCase = true) || uri.query != null || uri.fragment != null
        ) return null
        uri.path ?: return null
    }
    val parts = path.trim('/').removeSuffix(".git").split('/')
    if (parts.size != 2 || !OWNER.matches(parts[0]) || !REPOSITORY.matches(parts[1])) return null
    return "https://github.com/${parts[0]}/${parts[1]}"
}
