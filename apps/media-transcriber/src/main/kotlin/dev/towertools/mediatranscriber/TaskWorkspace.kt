package dev.towertools.mediatranscriber

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID

class TaskWorkspace(private val tempRoot: Path = AppPaths.tempDirectory) {
    private val root = tempRoot.toAbsolutePath().normalize()

    fun create(taskId: String = UUID.randomUUID().toString()): Path {
        require(taskId.isNotBlank() && !taskId.contains('/') && !taskId.contains('\\'))
        Files.createDirectories(root)
        return Files.createDirectory(root.resolve(taskId).normalize())
    }

    fun cleanup(directory: Path) {
        val target = directory.toAbsolutePath().normalize()
        require(target != root && target.startsWith(root)) { "拒绝清理应用 temp 根目录之外的路径" }
        if (!Files.exists(target)) return
        Files.walk(target).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    fun cleanupStale() {
        Files.createDirectories(root)
        Files.list(root).use { children -> children.forEach(::cleanup) }
    }

    fun root(): Path = root
}
