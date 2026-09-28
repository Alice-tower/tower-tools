package dev.towertools.launcher.tabs.repositories

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal fun findVsCodeExecutable(
    pathValue: String? = System.getenv("PATH"),
    localAppData: String? = System.getenv("LOCALAPPDATA"),
    programFiles: String? = System.getenv("ProgramFiles"),
    programFilesX86: String? = System.getenv("ProgramFiles(x86)"),
): Path? {
    val pathCandidates = pathValue.orEmpty().split(';').asSequence().mapNotNull { raw ->
        runCatching { Path.of(raw.trim().trim('"')) }.getOrNull()?.takeIf { raw.isNotBlank() }
    }.flatMap { directory ->
        sequence {
            yield(directory.resolve("Code.exe"))
            if (Files.isRegularFile(directory.resolve("code.cmd"))) {
                directory.parent?.let { yield(it.resolve("Code.exe")) }
            }
        }
    }
    val installCandidates = listOfNotNull(
        localAppData?.let { Path.of(it).resolve("Programs/Microsoft VS Code/Code.exe") },
        programFiles?.let { Path.of(it).resolve("Microsoft VS Code/Code.exe") },
        programFilesX86?.let { Path.of(it).resolve("Microsoft VS Code/Code.exe") },
    )
    return (pathCandidates + installCandidates.asSequence()).firstOrNull(Files::isRegularFile)
}

internal fun openRepositoryInVsCode(executable: Path, directory: Path) {
    require(Files.isRegularFile(executable)) { "找不到 VS Code：$executable" }
    require(Files.isDirectory(directory)) { "找不到仓库目录：$directory" }
    ProcessBuilder(executable.toString(), "--new-window", directory.toString())
        .directory(executable.parent.toFile())
        .redirectInput(ProcessBuilder.Redirect.from(File("NUL")))
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
}
