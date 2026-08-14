package dev.towertools.mediatranscriber

import java.nio.file.Path

class FfmpegService(private val runner: ProcessRunner) {
    fun mp3Command(ffmpeg: Path, input: Path, streamIndex: Int, output: Path) = listOf(
        ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", input.toString(), "-map", "0:$streamIndex",
        "-vn", "-ac", "2", "-c:a", "libmp3lame", "-q:a", "2", "-progress", "pipe:1", "-nostats", output.toString()
    )

    fun wavCommand(ffmpeg: Path, input: Path, streamIndex: Int, output: Path) = listOf(
        ffmpeg.toString(), "-hide_banner", "-nostdin", "-i", input.toString(), "-map", "0:$streamIndex",
        "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le", "-f", "wav",
        "-progress", "pipe:1", "-nostats", output.toString()
    )

    fun run(command: List<String>, workspace: Path, durationMs: Long?, cancellation: CancellationHandle, onProgress: (Int?) -> Unit) {
        val result = runner.run(ProcessRequest(command, workspace), cancellation, onStdoutLine = { line ->
            parseProgress(line, durationMs)?.let { onProgress(it) }
        })
        if (result.cancelled) error("任务已取消")
        if (result.exitCode != 0) {
            val detail = result.stderr.lineSequence().lastOrNull().orEmpty()
            if (result.stderr.contains("Unknown encoder 'libmp3lame'", true)) error("当前 FFmpeg 不包含 libmp3lame")
            error("FFmpeg 处理失败：$detail")
        }
    }

    fun parseProgress(line: String, durationMs: Long?): Int? {
        if (durationMs == null || durationMs <= 0) return null
        val parts = line.split('=', limit = 2)
        if (parts.size != 2 || parts[0] !in setOf("out_time_ms", "out_time_us")) return null
        val micros = parts[1].toLongOrNull() ?: return null
        return ((micros / 1000.0) / durationMs * 100).toInt().coerceIn(0, 100)
    }
}
