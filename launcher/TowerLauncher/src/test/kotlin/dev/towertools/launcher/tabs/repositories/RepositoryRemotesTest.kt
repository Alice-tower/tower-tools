package dev.towertools.launcher.tabs.repositories

import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RepositoryRemotesTest {
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun readsRemotePresenceAndPrefersOriginsGithubAddress() {
        val root = createTempDirectory("repository-remotes-")
        try {
            val git = Files.createDirectory(root.resolve(".git"))
            val config = git.resolve("config")
            Files.writeString(config, "[core]\n\tbare = false\n")
            assertEquals(RepositoryRemotes(false), readRepositoryRemotes(root))

            Files.writeString(config, """
                [remote "backup"]
                    url = https://gitlab.com/owner/project.git
                [remote "upstream"]
                    url = git@github.com:other/project.git
                [remote "origin"]
                    url = ssh://git@github.com/owner/project.git
            """.trimIndent())
            assertEquals(RepositoryRemotes(true, "https://github.com/owner/project"), readRepositoryRemotes(root))

            Files.writeString(config, "[remote \"origin\"]\nurl = https://gitlab.com/owner/project.git\n")
            assertEquals(RepositoryRemotes(true), readRepositoryRemotes(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recognizesOnlyGithubRepositoryUrls() {
        assertEquals("https://github.com/Alice/tower-tools", githubWebUrl("https://github.com/Alice/tower-tools.git"))
        assertEquals("https://github.com/Alice/tower-tools", githubWebUrl("git@github.com:Alice/tower-tools.git"))
        assertEquals("https://github.com/Alice/tower-tools", githubWebUrl("git://github.com/Alice/tower-tools"))
        assertEquals(null, githubWebUrl("https://github.com.evil.test/Alice/tower-tools.git"))
        assertEquals(null, githubWebUrl("https://gitlab.com/Alice/tower-tools.git"))
        assertEquals(null, githubWebUrl("https://github.com/Alice"))
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun acceptsGitCommentsAndQuotedRemoteUrls() {
        val root = createTempDirectory("repository-commented-remote-")
        try {
            val git = Files.createDirectory(root.resolve(".git"))
            Files.writeString(git.resolve("config"), """
                [core]
                    bare = false
                [remote "origin"] # primary remote
                    url = "https://github.com/owner/project.git" ; GitHub repository
            """.trimIndent())
            assertEquals(RepositoryRemotes(true, "https://github.com/owner/project"), readRepositoryRemotes(root))

            Files.writeString(git.resolve("config"), """
                [remote "origin"] ; primary remote
                    url = https://github.com/owner/project.git # repository
            """.trimIndent())
            assertEquals(RepositoryRemotes(true, "https://github.com/owner/project"), readRepositoryRemotes(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun missingConfigurationIsNotReportedAsNoRemote() {
        val root = createTempDirectory("repository-no-config-")
        try {
            Files.createDirectory(root.resolve(".git"))
            assertFailsWith<IllegalStateException> { readRepositoryRemotes(root) }
        } finally {
            root.deleteRecursively()
        }
    }
}
