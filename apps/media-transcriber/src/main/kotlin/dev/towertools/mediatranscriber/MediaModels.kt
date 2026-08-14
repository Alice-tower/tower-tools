package dev.towertools.mediatranscriber

import java.nio.file.Path

enum class MediaKind { VIDEO, AUDIO }

data class AudioTrack(
    val streamIndex: Int,
    val codec: String = "未知",
    val channels: Int? = null,
    val sampleRate: Int? = null,
    val bitrate: Long? = null,
    val isDefault: Boolean = false,
)

data class MediaInfo(
    val path: Path,
    val kind: MediaKind,
    val format: String,
    val durationMs: Long?,
    val fileSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val videoCodec: String? = null,
    val audioTracks: List<AudioTrack>,
    val selectedAudio: AudioTrack?,
)

data class TranscriptSegment(val startMilliseconds: Long, val text: String)
data class TranscriptResult(val language: String?, val segments: List<TranscriptSegment>)

enum class UiLogLevel(val label: String) {
    SELF_CHECK("自检"), INFO("信息"), WARNING("警告"), ERROR("错误"), COMPLETE("完成"), PROCESS("处理")
}

data class UiLogEntry(
    val timestamp: java.time.LocalTime = java.time.LocalTime.now(),
    val level: UiLogLevel,
    val message: String,
    val link: String? = null,
)
