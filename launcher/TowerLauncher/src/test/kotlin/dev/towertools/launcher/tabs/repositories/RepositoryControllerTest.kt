package dev.towertools.launcher.tabs.repositories

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryControllerTest {
    @OptIn(ExperimentalPathApi::class)
    @Test
    fun tabRefreshesAllPathsAndCombinesTheirRepositories() {
        val root = createTempDirectory("repository-tab-refresh-")
        try {
            val first = Files.createDirectory(root.resolve("first"))
            val second = Files.createDirectory(root.resolve("second"))
            Files.createDirectory(Files.createDirectory(first.resolve("project")).resolve(".git"))
            Files.createDirectory(Files.createDirectory(second.resolve("project")).resolve(".git"))
            val controller = RepositoryController(RepositoryRegistry(root.resolve("locations.properties")))
            controller.add(first)
            controller.add(second)
            val state = RepositoryTabState(controller)
            try {
                state.refreshAll()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (state.isRefreshing.value && System.nanoTime() < deadline) {
                    Thread.sleep(10)
                }
                assertEquals(false, state.isRefreshing.value, "扫描任务未完成")
                assertEquals(
                    listOf(first.resolve("project"), second.resolve("project")),
                    discoveredRepositories(state.locations.value).map(DiscoveredRepository::path),
                )
                assertEquals(false, state.isError.value)
            } finally {
                state.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun scansOnlyDirectChildDirectoriesAndKeepsResultAfterReload() {
        val root = createTempDirectory("repository-scan-")
        try {
            Files.createDirectory(root.resolve(".git"))
            val ordinary = Files.createDirectory(root.resolve("ordinary"))
            Files.createDirectory(ordinary.resolve(".git"))
            val worktree = Files.createDirectory(root.resolve("worktree"))
            Files.writeString(worktree.resolve(".git"), "gitdir: elsewhere")
            val nested = Files.createDirectory(root.resolve("nested"))
            val nestedRepository = Files.createDirectory(nested.resolve("deep"))
            Files.createDirectory(nestedRepository.resolve(".git"))
            Files.writeString(root.resolve("not-a-directory"), "x")

            val settings = root.resolve("locations.properties")
            val clock = Clock.fixed(Instant.parse("2026-09-28T08:00:00Z"), ZoneOffset.UTC)
            val controller = RepositoryController(RepositoryRegistry(settings), clock)
            val added = controller.add(root).single()
            assertNull(added.scan)

            val scanned = controller.refresh(added.id).single()
            assertEquals(4, scanned.scan?.directoryCount)
            assertEquals(listOf("ordinary"), scanned.scan?.repositoryNames)
            assertEquals(clock.instant(), scanned.scan?.scannedAt)
            assertEquals(listOf(scanned), RepositoryController(RepositoryRegistry(settings)).locations())
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun avoidsDuplicatePathsAndRemovingRegistrationLeavesDirectory() {
        val root = createTempDirectory("repository-locations-")
        try {
            val settings = root.resolve("locations.properties")
            val directory = Files.createDirectory(root.resolve("projects"))
            val controller = RepositoryController(RepositoryRegistry(settings))
            val added = controller.add(directory).single()
            assertFailsWith<IllegalArgumentException> { controller.add(directory.resolve("..").resolve("projects")) }
            assertEquals(emptyList(), controller.remove(added.id))
            assertTrue(Files.isDirectory(directory))
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun invalidSettingsAndFailedScanDoNotOverwritePreviousData() {
        val root = createTempDirectory("repository-failure-")
        try {
            val settings = root.resolve("locations.properties")
            val invalid = "format=1\ncount=broken\n"
            Files.writeString(settings, invalid)
            val controller = RepositoryController(RepositoryRegistry(settings))
            assertFailsWith<IllegalStateException> { controller.add(root) }
            assertEquals(invalid, Files.readString(settings))

            Files.delete(settings)
            val unavailable = Files.createDirectory(root.resolve("unavailable"))
            Files.createDirectory(unavailable.resolve(".git"))
            val registered = controller.add(unavailable).single()
            val scanned = controller.refresh(registered.id).single()
            Files.delete(unavailable.resolve(".git"))
            Files.delete(unavailable)
            assertFailsWith<IllegalArgumentException> { controller.refresh(registered.id) }
            assertEquals(listOf(scanned), controller.locations())
        } finally {
            root.deleteRecursively()
        }
    }
}
