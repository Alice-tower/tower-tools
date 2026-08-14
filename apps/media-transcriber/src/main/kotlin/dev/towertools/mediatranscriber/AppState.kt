package dev.towertools.mediatranscriber

enum class TaskPhase {
    IDLE, PROBING, READY_VIDEO, READY_AUDIO, NO_AUDIO, EXTRACTING_MP3, PREPARING_AUDIO,
    TRANSCRIBING, COMPLETED, CANCELLING, ERROR
}

data class AppState(
    val phase: TaskPhase = TaskPhase.IDLE,
    val media: MediaInfo? = null,
    val mp3Output: java.nio.file.Path? = null,
    val textOutput: java.nio.file.Path? = null,
    val progress: Int? = null,
    val progressText: String = "就绪",
    val dependencies: DependencyStatus = DependencyStatus(),
    val logs: List<UiLogEntry> = emptyList(),
) {
    val isBusy get() = phase in setOf(TaskPhase.PROBING, TaskPhase.EXTRACTING_MP3, TaskPhase.PREPARING_AUDIO, TaskPhase.TRANSCRIBING, TaskPhase.CANCELLING)
    val canCancel get() = phase in setOf(TaskPhase.PROBING, TaskPhase.EXTRACTING_MP3, TaskPhase.PREPARING_AUDIO, TaskPhase.TRANSCRIBING)
    val canExportMp3 get() = media?.kind == MediaKind.VIDEO && media.selectedAudio != null && dependencies.mediaAvailable && dependencies.outputAvailable && !isBusy
    val canTranscribe get() = media?.selectedAudio != null && dependencies.mediaAvailable && dependencies.outputAvailable && dependencies.cudaAvailable && dependencies.modelExists && !isBusy
}

class ControllerStateMachine(initial: AppState = AppState()) {
    var state: AppState = initial
        private set

    fun beginProbe(): Boolean {
        if (state.isBusy) return false
        state = state.copy(
            phase = TaskPhase.PROBING,
            media = null,
            mp3Output = null,
            textOutput = null,
            progress = null,
            progressText = "正在读取媒体信息",
        )
        return true
    }

    fun mediaReady(info: MediaInfo) {
        val phase = when { info.selectedAudio == null -> TaskPhase.NO_AUDIO; info.kind == MediaKind.VIDEO -> TaskPhase.READY_VIDEO; else -> TaskPhase.READY_AUDIO }
        state = state.copy(phase = phase, media = info, progress = null, progressText = "就绪")
    }

    fun taskStarted(phase: TaskPhase) { require(phase in setOf(TaskPhase.EXTRACTING_MP3, TaskPhase.PREPARING_AUDIO, TaskPhase.TRANSCRIBING)); state = state.copy(phase = phase) }
    fun cancelComplete() { state = state.copy(phase = readyPhase(), progress = null, progressText = "任务已取消") }
    fun fail(message: String) { state = state.copy(phase = TaskPhase.ERROR, progress = null, progressText = message) }
    fun updateDependencies(value: DependencyStatus) { state = state.copy(dependencies = value) }
    fun update(value: AppState) { state = value }
    fun readyPhase(): TaskPhase = when { state.media?.selectedAudio == null -> if (state.media == null) TaskPhase.IDLE else TaskPhase.NO_AUDIO; state.media?.kind == MediaKind.VIDEO -> TaskPhase.READY_VIDEO; else -> TaskPhase.READY_AUDIO }
}
