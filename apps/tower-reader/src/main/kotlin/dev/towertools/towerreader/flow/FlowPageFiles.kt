package dev.towertools.towerreader.flow

import dev.towertools.towerreader.ReaderSettings
import java.nio.file.Files
import java.nio.file.Path

/** Small, disposable HTML shells. Image bytes stay in the book cache, outside the HTML heap. */
internal class FlowPageFiles(private val parent: Path) : AutoCloseable {
    private var directory: Path? = null
    fun write(body: String, settings: ReaderSettings): Path {
        val root = directory ?: Files.createTempDirectory(Files.createDirectories(parent), "view-").also { directory = it }
        return Files.createTempFile(root, "page-", ".html").also { Files.writeString(it, FlowPane.document(body, settings)) }
    }
    fun remove(path: Path) { Files.deleteIfExists(path) }
    override fun close() {
        directory?.let { root ->
            Files.list(root).use { files -> files.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(root)
        }
        directory = null
    }
}
