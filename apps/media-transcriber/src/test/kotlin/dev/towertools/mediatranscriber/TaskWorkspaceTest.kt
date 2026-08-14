package dev.towertools.mediatranscriber

import kotlin.test.*
import java.nio.file.Files

class TaskWorkspaceTest {
    @Test fun createsBelowRootAndCleansForEveryOutcome() {
        val root=Files.createTempDirectory("app-data").resolve("temp"); val manager=TaskWorkspace(root)
        listOf("success","failure","cancel").forEach { name -> val task=manager.create(name); Files.writeString(task.resolve("x"),"x"); assertTrue(task.startsWith(root)); manager.cleanup(task); assertFalse(Files.exists(task)) }
    }
    @Test fun refusesOutsideAndRootCleanup() { val root=Files.createTempDirectory("safe-temp"); val manager=TaskWorkspace(root); assertFailsWith<IllegalArgumentException>{manager.cleanup(root)}; assertFailsWith<IllegalArgumentException>{manager.cleanup(root.parent.resolve("outside"))} }
}
