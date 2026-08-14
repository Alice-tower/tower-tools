package dev.towertools.mediatranscriber

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

data class DependencyStatus(
    val ffmpeg: Path? = null,
    val ffprobe: Path? = null,
    val mediaAvailable: Boolean = false,
    val cudaAvailable: Boolean = false,
    val modelExists: Boolean = false,
    val outputAvailable: Boolean = false,
)

class DependencyChecker(private val runner: ProcessRunner, private val whisper: WhisperService) {
    fun check(settings: AppSettings, workspace: Path, log: (UiLogLevel, String, String?) -> Unit): DependencyStatus {
        val output = settings.outputDirectory.takeIf(String::isNotBlank)?.let { runCatching { Paths.get(it) }.getOrNull() }
        val writable = output?.let(::validateOutputDirectory) == true
        log(UiLogLevel.SELF_CHECK, if (writable) "输出目录可写：$output" else "输出目录尚未设置或不可写", null)

        val ffmpeg = ExecutableResolver.resolve("ffmpeg.exe", settings.ffmpegDirectory)
        val ffprobe = ExecutableResolver.resolve("ffprobe.exe", settings.ffmpegDirectory)
        val ffmpegResult = ffmpeg?.let { runVersion(it, workspace) }
        val ffprobeResult = ffprobe?.let { runVersion(it, workspace) }
        val media = ffmpegResult != null && ffprobeResult != null
        log(if (media) UiLogLevel.SELF_CHECK else UiLogLevel.WARNING,
            if (media) "FFmpeg 与 ffprobe 可用：$ffmpegResult / $ffprobeResult" else "FFmpeg/ffprobe 不完整，媒体功能已禁用",
            if (media) null else "https://ffmpeg.org/download.html")

        val cli = settings.whisperCliPath.takeIf(String::isNotBlank)?.let { runCatching { Paths.get(it) }.getOrNull() }
        val cudaResult = cli?.let { whisper.quickCheck(it, workspace) }
        val cuda = cudaResult?.first == true
        log(if (cuda) UiLogLevel.SELF_CHECK else UiLogLevel.WARNING,
            cudaResult?.second ?: "尚未设置 CUDA 版 whisper-cli.exe", null)
        val model = settings.whisperModelPath.takeIf(String::isNotBlank)?.let { runCatching { Paths.get(it) }.getOrNull() }?.let(Files::isRegularFile) == true
        log(if (model) UiLogLevel.SELF_CHECK else UiLogLevel.WARNING,
            if (model) "Whisper 模型路径存在" else "Whisper 模型路径尚未设置或不存在", null)
        return DependencyStatus(ffmpeg, ffprobe, media, cuda, model, writable)
    }

    private fun runVersion(executable: Path, workspace: Path): String? = runCatching {
        val result = runner.run(ProcessRequest(listOf(executable.toString(), "-version"), workspace))
        result.takeIf { it.exitCode == 0 }?.stdout?.lineSequence()?.firstOrNull { line -> line.isNotBlank() }
    }.getOrNull()

    companion object {
        fun validateOutputDirectory(path: Path): Boolean = runCatching {
            val directory = path.toAbsolutePath().normalize()
            if (!Files.isDirectory(directory)) return false
            val probe = directory.resolve(".media-transcriber-write-test-${java.util.UUID.randomUUID()}")
            require(probe.normalize().parent == directory)
            Files.createFile(probe)
            Files.delete(probe)
            true
        }.getOrDefault(false)
    }
}
