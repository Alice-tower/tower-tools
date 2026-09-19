package dev.towertools.naibox

import java.nio.file.Files
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

object AppLog {
    val logger: Logger = Logger.getLogger(AppMetadata.id).apply {
        level = Level.INFO
        useParentHandlers = true

        runCatching {
            Files.createDirectories(AppPaths.logDirectory)
            val handler = FileHandler(
                AppPaths.logDirectory.resolve("application.log").toString(),
                1_000_000,
                3,
                true,
            )
            handler.formatter = SimpleFormatter()
            addHandler(handler)
        }
    }
}
