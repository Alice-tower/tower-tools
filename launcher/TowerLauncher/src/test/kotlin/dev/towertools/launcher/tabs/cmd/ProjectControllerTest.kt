package dev.towertools.launcher.tabs.cmd

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectControllerTest {
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun existingProjectsPropertiesLoadWithoutConversion() {
        val root = createTempDirectory("local-project-existing-")
        try {
            val projectsFile = root.resolve("projects.properties")
            val script = root.resolve("existing.cmd")
            Files.writeString(script, "@echo off")
            Files.writeString(
                projectsFile,
                """
                count=1
                project.0.id=existing-project
                project.0.name=\u73B0\u6709\u9879\u76EE
                project.0.scriptPath=${script.toString().replace(java.io.File.separatorChar, '/')}
                project.0.webPort=4173
                """.trimIndent(),
            )

            val projects = ProjectRegistry(projectsFile).load()
            assertEquals(listOf("existing-project"), projects.map(LocalProject::id))
            assertEquals("现有项目", projects.single().name)
            assertEquals(script, projects.single().scriptPath)
            assertEquals(4173, projects.single().webPort)
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun projectsCanBeAddedEditedAndDeletedWithoutDeletingScripts() {
        val root = createTempDirectory("local-project-crud-")
        try {
            val script = root.resolve("my project.cmd")
            Files.writeString(script, "@echo off")
            val controller = ProjectController(ProjectRegistry(root.resolve("projects.properties")))

            val added = controller.saveProject(null, "  我的项目  ", script, 8080).single()
            assertEquals("我的项目", added.name)
            assertEquals(8080, added.webPort)
            val edited = controller.saveProject(added.id, "新名称", script, null).single()
            assertEquals(added.id, edited.id)
            assertEquals(null, edited.webPort)
            assertEquals(emptyList(), controller.deleteProject(added.id))
            assertTrue(Files.exists(script))
        } finally {
            root.deleteRecursively()
        }
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun invalidProjectFileIsNotOverwrittenByAnEdit() {
        val root = createTempDirectory("local-project-invalid-")
        try {
            val projectsFile = root.resolve("projects.properties")
            val script = root.resolve("start.cmd")
            val invalid = "count=not-a-number"
            Files.writeString(projectsFile, invalid)
            Files.writeString(script, "@echo off")
            val controller = ProjectController(ProjectRegistry(projectsFile))

            assertFailsWith<IllegalStateException> {
                controller.saveProject(null, "新项目", script, null)
            }
            assertEquals(invalid, Files.readString(projectsFile))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun launchCommandQuotesPathsWithSpaces() {
        val script = Paths.get("C:\\Example Folder\\start.cmd")
        val command = ProjectController.buildLaunchCommand(script)
        assertTrue(command.contains("/D \"C:\\Example Folder\""))
        assertTrue(command.endsWith("call \"C:\\Example Folder\\start.cmd\""))
    }

    @Test
    fun portCheckReportsOccupiedPort() {
        ServerSocket().use { socket ->
            socket.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0))
            assertEquals(PortStatus.IN_USE, ProjectController.portStatus(socket.localPort))
        }
    }

    @Test
    fun portCheckReportsAvailablePortAfterListenerCloses() {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(PortStatus.AVAILABLE, ProjectController.portStatus(port))
    }
}
