package dev.towertools.mediatranscriber

enum class TaskPhase {
    IDLE, PROBING, READY_VIDEO, READY_AUDIO, NO_AUDIO, EXTRACTING_MP3, PREPARING_AUDIO,
    TRANSCRIBING, COMPLETED, CANCELLING, ERROR
}

enum class DependencyCheckPhase { NOT_STARTED, CHECKING, COMPLETE }

data class AppState(
    val phase: TaskPhase = TaskPhase.IDLE,
    val media: MediaInfo? = null,
    val mp3Output: java.nio.file.Path? = null,
    val textOutput: java.nio.file.Path? = null,
    val progress: Int? = null,
    val progressText: String = "就绪",
    val dependencyCheckPhase: DependencyCheckPhase = DependencyCheckPhase.NOT_STARTED,
    val dependencies: DependencyStatus = DependencyStatus(),
    val logs: List<UiLogEntry> = emptyList(),
) {
    val dependenciesReady get() = dependencyCheckPhase == DependencyCheckPhase.COMPLETE
    val isBusy get() = phase in setOf(TaskPhase.PROBING, TaskPhase.EXTRACTING_MP3, TaskPhase.PREPARING_AUDIO, TaskPhase.TRANSCRIBING, TaskPhase.CANCELLING)
    val canCancel get() = phase in setOf(TaskPhase.PROBING, TaskPhase.EXTRACTING_MP3, TaskPhase.PREPARING_AUDIO, TaskPhase.TRANSCRIBING)
    val canExportMp3 get() = dependenciesReady && media?.kind == MediaKind.VIDEO && media.selectedAudio != null && dependencies.mediaAvailable && dependencies.outputAvailable && !isBusy
    val canTranscribe get() = dependenciesReady && media?.selectedAudio != null && dependencies.mediaAvailable && dependencies.outputAvailable && dependencies.cudaAvailable && dependencies.modelExists && !isBusy
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

    fun taskStarted(phase: TaskPhase) {
        require(phase in setOf(TaskPhase.EXTRACTING_MP3, TaskPhase.PREPARING_AUDIO, TaskPhase.TRANSCRIBING))
        val progressText = when (phase) {
            TaskPhase.EXTRACTING_MP3 -> "正在导出 MP3"
            TaskPhase.PREPARING_AUDIO -> "正在准备 16 kHz 音频"
            TaskPhase.TRANSCRIBING -> "正在转写"
            else -> error("不支持的任务阶段：$phase")
        }
        state = state.copy(phase = phase, progress = null, progressText = progressText)
    }
    fun cancelComplete() { state = state.copy(phase = readyPhase(), progress = null, progressText = "任务已取消") }
    fun fail(message: String) { state = state.copy(phase = TaskPhase.ERROR, progress = null, progressText = message) }
    fun beginDependencyCheck() {
        state = state.copy(
            dependencyCheckPhase = DependencyCheckPhase.CHECKING,
            dependencies = DependencyStatus(),
            progressText = if (state.isBusy) state.progressText else "正在检测运行环境",
        )
    }
    fun completeDependencyCheck(value: DependencyStatus) {
        state = state.copy(
            dependencyCheckPhase = DependencyCheckPhase.COMPLETE,
            dependencies = value,
            progressText = if (state.isBusy || state.phase == TaskPhase.ERROR) state.progressText else "就绪",
        )
    }
    fun update(value: AppState) { state = value }
    fun readyPhase(): TaskPhase = when { state.media?.selectedAudio == null -> if (state.media == null) TaskPhase.IDLE else TaskPhase.NO_AUDIO; state.media?.kind == MediaKind.VIDEO -> TaskPhase.READY_VIDEO; else -> TaskPhase.READY_AUDIO }
}
