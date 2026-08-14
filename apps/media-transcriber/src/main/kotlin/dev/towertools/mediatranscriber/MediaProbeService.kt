package dev.towertools.mediatranscriber

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path

class MediaProbeParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(input: Path, raw: String): MediaInfo {
        val root = json.parseToJsonElement(raw).jsonObject
        val streams = root["streams"]?.let { it as? kotlinx.serialization.json.JsonArray }.orEmpty()
            .mapNotNull { it as? JsonObject }
        val videoStreams = streams.filter { it.string("codec_type") == "video" && it.disposition("attached_pic") != 1 }
        val audioTracks = streams.filter { it.string("codec_type") == "audio" }.map { stream ->
            AudioTrack(
                streamIndex = stream.int("index") ?: error("音频流缺少索引"),
                codec = stream.string("codec_name") ?: "未知",
                channels = stream.int("channels"),
                sampleRate = stream.string("sample_rate")?.toIntOrNull(),
                bitrate = stream.string("bit_rate")?.toLongOrNull(),
                isDefault = stream.disposition("default") == 1,
            )
        }.sortedBy(AudioTrack::streamIndex)
        if (videoStreams.isEmpty() && audioTracks.isEmpty()) error("文件没有可用音视频流")
        val kind = if (videoStreams.isNotEmpty()) MediaKind.VIDEO else MediaKind.AUDIO
        val format = root["format"]?.jsonObject
        val video = videoStreams.firstOrNull()
        return MediaInfo(
            path = input,
            kind = kind,
            format = format?.string("format_long_name") ?: format?.string("format_name") ?: "未知",
            durationMs = format?.string("duration")?.toDoubleOrNull()?.times(1000)?.toLong(),
            fileSize = format?.string("size")?.toLongOrNull() ?: runCatching { Files.size(input) }.getOrDefault(0L),
            width = video?.int("width"), height = video?.int("height"), videoCodec = video?.string("codec_name"),
            audioTracks = audioTracks,
            selectedAudio = audioTracks.firstOrNull(AudioTrack::isDefault) ?: audioTracks.firstOrNull(),
        )
    }

    private fun JsonObject.string(name: String) = get(name)?.jsonPrimitive?.content
    private fun JsonObject.int(name: String) = get(name)?.jsonPrimitive?.intOrNull
    private fun JsonObject.disposition(name: String) = get("disposition")?.jsonObject?.get(name)?.jsonPrimitive?.intOrNull
}

class MediaProbeService(private val runner: ProcessRunner, private val parser: MediaProbeParser = MediaProbeParser()) {
    fun command(ffprobe: Path, input: Path) = listOf(
        ffprobe.toString(), "-v", "error", "-print_format", "json", "-show_format", "-show_streams", input.toString()
    )

    fun probe(ffprobe: Path, input: Path, workspace: Path, cancellation: CancellationHandle): MediaInfo {
        val result = runner.run(ProcessRequest(command(ffprobe, input), workspace), cancellation)
        if (result.cancelled) error("任务已取消")
        if (result.exitCode != 0) error("ffprobe 读取媒体失败：${result.stderr.lineSequence().lastOrNull().orEmpty()}")
        return parser.parse(input, result.stdout)
    }
}
