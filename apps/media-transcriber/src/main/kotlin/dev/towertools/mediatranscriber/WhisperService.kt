package dev.towertools.mediatranscriber

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

class WhisperService(private val runner: ProcessRunner) {
    private val json = Json { ignoreUnknownKeys = true }

    fun command(cli: Path, model: Path, wav: Path, outputBase: Path) = listOf(
        cli.toString(), "--model", model.toString(), "--file", wav.toString(), "--language", "auto",
        "--output-json", "--output-file", outputBase.toString(), "--print-progress"
    )

    fun quickCheck(cli: Path, workspace: Path): Pair<Boolean, String> {
        if (!Files.isRegularFile(cli)) return false to "whisper-cli 路径无效"
        val result = runCatching { runner.run(ProcessRequest(listOf(cli.toString(), "--version"), workspace)) }
            .getOrElse { return false to "whisper-cli 无法启动：${it.message}" }
        val output = result.stdout + "\n" + result.stderr
        val lower = output.lowercase()
        val cuda = lower.contains("cuda") && (lower.contains("nvidia") || Regex("(?:found|device)[^\n]*[1-9]").containsMatchIn(lower))
        return if (result.exitCode == 0 && cuda) true to output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        else false to "未确认 CUDA 后端和 NVIDIA 设备；禁止 CPU 回退"
    }

    fun validateModel(cli: Path, model: Path, workspace: Path, cancellation: CancellationHandle): TranscriptResult {
        val wav = workspace.resolve("model-check.wav")
        writeSilentWav(wav)
        return transcribe(cli, model, wav, workspace.resolve("model-check"), workspace, cancellation, {}, requireSegments = false)
    }

    fun transcribe(
        cli: Path, model: Path, wav: Path, outputBase: Path, workspace: Path,
        cancellation: CancellationHandle, onProgress: (Int?) -> Unit, requireSegments: Boolean = true,
    ): TranscriptResult {
        val result = runner.run(ProcessRequest(command(cli, model, wav, outputBase), workspace), cancellation,
            onStderrLine = { parseProgress(it)?.let(onProgress) }, onStdoutLine = { parseProgress(it)?.let(onProgress) })
        if (result.cancelled) error("任务已取消")
        if (result.exitCode != 0) error("Whisper 转写失败：${result.stderr.lineSequence().lastOrNull().orEmpty()}")
        val output = outputBase.resolveSibling("${outputBase.fileName}.json")
        if (!Files.isRegularFile(output)) error("Whisper 没有生成 JSON")
        return parseJson(Files.readString(output), requireSegments)
    }

    fun parseProgress(line: String): Int? = Regex("(?<!\\d)(100|[1-9]?\\d)%").find(line)?.groupValues?.get(1)?.toInt()

    fun parseJson(raw: String, requireSegments: Boolean = true): TranscriptResult {
        val root = json.parseToJsonElement(raw).jsonObject
        val language = root["result"]?.let { it as? JsonObject }?.get("language")?.jsonPrimitive?.content
        val segments = (root["transcription"] as? JsonArray).orEmpty().mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val text = obj["text"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val fromElement = obj["offsets"]?.let { it as? JsonObject }?.get("from")?.jsonPrimitive
            val from = fromElement?.longOrNull ?: fromElement?.content?.toLongOrNull() ?: 0L
            TranscriptSegment(from, text)
        }.filter { it.text.isNotBlank() }
        if (requireSegments && segments.isEmpty()) error("Whisper JSON 没有有效片段")
        return TranscriptResult(language, segments)
    }

    private fun writeSilentWav(path: Path) {
        val dataSize = 16_000 * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
        buffer.putShort(1).putShort(1).putInt(16_000).putInt(32_000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize).put(ByteArray(dataSize))
        Files.write(path, buffer.array())
    }
}
