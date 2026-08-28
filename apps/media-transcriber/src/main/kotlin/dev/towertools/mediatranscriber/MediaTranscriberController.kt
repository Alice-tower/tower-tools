package dev.towertools.mediatranscriber

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Desktop
import java.awt.EventQueue
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level

class MediaTranscriberController(
    private val settingsStore: SettingsStore = SettingsStore(onWarning = { AppLog.logger.warning(it) }),
    private val runner: ProcessRunner = ExternalProcessRunner { AppLog.logger.info(it) },
    private val workspaceManager: TaskWorkspace = TaskWorkspace(),
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "media-transcriber-worker").apply { isDaemon = true } }
    private val currentCancellation = AtomicReference<CancellationHandle?>()
    private val cancelCompletion = AtomicReference<Pair<CancellationHandle, () -> Unit>?>(null)
    private val probeParser = MediaProbeParser()
    private val probeService = MediaProbeService(runner, probeParser)
    private val ffmpegService = FfmpegService(runner)
    private val whisperService = WhisperService(runner)
    private val dependencyChecker = DependencyChecker(runner, whisperService)
    private val machine = ControllerStateMachine()
    private val mediaLoadGate = MediaLoadGate()
    private var dependencyCheckGeneration = 0L

    var state by mutableStateOf(machine.state)
        private set
    var settings by mutableStateOf(settingsStore.load())
        private set

    init {
        runCatching { workspaceManager.cleanupStale() }.onFailure { AppLog.logger.log(Level.WARNING, "清理残留临时目录失败", it) }
    }

    fun startupCheck() = onUi {
        val generation = beginDependencyCheck()
        submit("启动自检") { workspace, _ ->
            val checked = dependencyChecker.check(settings, workspace, ::appendLog)
            publishDependencyCheck(generation, checked)
        }
    }

    fun saveSettings(value: AppSettings, validateModel: Boolean = false) {
        fun absolute(path: String) = if (path.isBlank()) "" else runCatching { Paths.get(path.trim().removeSurrounding("\"")).toAbsolutePath().normalize().toString() }.getOrDefault(path)
        val normalized = value.copy(
            outputDirectory = absolute(value.outputDirectory), ffmpegDirectory = absolute(value.ffmpegDirectory),
            whisperCliPath = absolute(value.whisperCliPath), whisperModelPath = absolute(value.whisperModelPath),
        )
        if (normalized.outputDirectory.isNotBlank()) {
            val valid = runCatching { DependencyChecker.validateOutputDirectory(Paths.get(normalized.outputDirectory)) }.getOrDefault(false)
            if (!valid) { appendLog(UiLogLevel.ERROR, "输出目录不存在或不可写，设置未保存", null); return }
        }
        val previous = settings
        settings = normalized
        runCatching { settingsStore.save(normalized) }.onFailure { reportError("保存设置失败", it) }
        val generation = beginDependencyCheck()
        submit("重新检测环境") { workspace, cancellation ->
            val checked = dependencyChecker.check(normalized, workspace, ::appendLog)
            var final = checked
            if (validateModel && checked.cudaAvailable && checked.modelExists) {
                appendLog(UiLogLevel.INFO, "正在实际加载 Whisper 模型…", null)
                val cli = Paths.get(normalized.whisperCliPath)
                val model = Paths.get(normalized.whisperModelPath)
                runCatching { whisperService.validateModel(cli, model, workspace, cancellation) }
                    .onSuccess { appendLog(UiLogLevel.COMPLETE, "Whisper 模型加载验证通过", null) }
                    .onFailure {
                        final = final.copy(modelExists = false)
                        appendLog(UiLogLevel.ERROR, "Whisper 模型验证失败：${it.message}", null)
                        val reverted = normalized.copy(
                            whisperCliPath = previous.whisperCliPath,
                            whisperModelPath = previous.whisperModelPath,
                        )
                        final = dependencyChecker.check(reverted, workspace, ::appendLog)
                        onUi {
                            settings = reverted
                            runCatching { settingsStore.save(reverted) }
                                .onFailure { saveError -> AppLog.logger.log(Level.SEVERE, "回退无效 Whisper 设置失败", saveError) }
                            appendLog(UiLogLevel.WARNING, "已恢复上次的 Whisper 路径设置", null)
                        }
                    }
            }
            publishDependencyCheck(generation, final)
        }
    }

    fun loadDroppedFiles(paths: List<Path>) {
        AppLog.logger.info("拖放文件已解析：${paths.size} 个")
        loadFiles(paths)
    }

    fun loadFiles(paths: List<Path>) {
        if (paths.isEmpty()) { appendLog(UiLogLevel.WARNING, "没有识别到可读取的文件", null); return }
        if (paths.size > 1) { appendLog(UiLogLevel.WARNING, "一次只能处理一个文件", null); return }
        loadFile(paths.single())
    }

    fun loadFile(path: Path) {
        if (state.isBusy) { appendLog(UiLogLevel.WARNING, "任务进行中，请先取消当前任务", null); return }
        if (!Files.isRegularFile(path)) { appendLog(UiLogLevel.ERROR, "请选择一个存在的普通文件", null); return }
        val normalized = path.toAbsolutePath().normalize()
        when (mediaLoadGate.request(normalized, state.dependenciesReady)) {
            MediaLoadDecision.Queued -> {
                appendLog(UiLogLevel.INFO, "运行环境检测中，完成后将自动读取：${normalized.fileName}", null)
                return
            }
            MediaLoadDecision.Duplicate -> {
                AppLog.logger.info("忽略重复排队的媒体文件：${normalized.fileName}")
                return
            }
            MediaLoadDecision.Occupied -> {
                appendLog(UiLogLevel.WARNING, "已有文件等待环境检测完成，请稍后再试", null)
                return
            }
            MediaLoadDecision.Proceed -> Unit
        }
        if (!state.dependencies.mediaAvailable) { appendLog(UiLogLevel.ERROR, "FFmpeg/ffprobe 不可用，无法读取媒体", "https://ffmpeg.org/download.html"); return }
        machine.beginProbe(); publish()
        AppLog.logger.info("开始读取媒体信息：${normalized.fileName}")
        submit("读取媒体信息") { workspace, cancellation ->
            val ffprobe = state.dependencies.ffprobe ?: error("ffprobe 不可用")
            val startedAt = System.nanoTime()
            val info = probeService.probe(ffprobe, normalized, workspace, cancellation)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            AppLog.logger.info("媒体探测与解析完成：${path.fileName}，耗时 ${elapsedMs} ms")
            onUi {
                machine.mediaReady(info); publish()
                appendLog(if (info.selectedAudio == null) UiLogLevel.WARNING else UiLogLevel.COMPLETE,
                    if (info.selectedAudio == null) "视频没有音轨" else "已读取：${path.fileName}（${elapsedMs} ms）", null)
            }
        }
    }

    fun exportMp3() {
        val media = state.media ?: return
        val audio = media.selectedAudio ?: return
        val outputDirectory = validatedOutputDirectory() ?: return
        if (!state.canExportMp3) return
        machine.taskStarted(TaskPhase.EXTRACTING_MP3); publishProgress("正在导出 MP3", null)
        submit("导出 MP3") { workspace, cancellation ->
            val temporary = workspace.resolve("audio.mp3")
            ffmpegService.run(ffmpegService.mp3Command(state.dependencies.ffmpeg!!, media.path, audio.streamIndex, temporary),
                workspace, media.durationMs, cancellation) { publishProgress("正在导出 MP3", it) }
            val target = OutputNameResolver.resolve(outputDirectory, media.path, "mp3")
            publishNoOverwrite(temporary, target)
            onUi { machine.update(machine.state.copy(phase = TaskPhase.COMPLETED, mp3Output = target, progress = 100, progressText = "MP3 导出完成")); publish() }
            appendLog(UiLogLevel.COMPLETE, "已生成：${target.fileName}", null)
        }
    }

    fun transcribe() {
        val media = state.media ?: return
        val audio = media.selectedAudio ?: return
        val outputDirectory = validatedOutputDirectory() ?: return
        if (!state.canTranscribe) return
        machine.taskStarted(TaskPhase.PREPARING_AUDIO); publishProgress("正在准备 16 kHz 音频", null)
        submit("转写") { workspace, cancellation ->
            ensureTemporarySpace(media.durationMs, workspace)
            val wav = workspace.resolve("whisper-input.wav")
            ffmpegService.run(ffmpegService.wavCommand(state.dependencies.ffmpeg!!, media.path, audio.streamIndex, wav),
                workspace, media.durationMs, cancellation) { publishProgress("正在准备 16 kHz 音频", it) }
            onUi { machine.taskStarted(TaskPhase.TRANSCRIBING); publish() }
            val transcript = whisperService.transcribe(Paths.get(settings.whisperCliPath), Paths.get(settings.whisperModelPath), wav,
                workspace.resolve("transcript"), workspace, cancellation, onProgress = { publishProgress("正在转写", it) })
            val temporary = workspace.resolve("final.txt")
            TranscriptWriter.write(temporary, transcript.segments, settings.includeTimestamps)
            val target = OutputNameResolver.resolve(outputDirectory, media.path, "txt")
            publishNoOverwrite(temporary, target)
            onUi { machine.update(machine.state.copy(phase = TaskPhase.COMPLETED, textOutput = target, progress = 100, progressText = "转写完成")); publish() }
            appendLog(UiLogLevel.COMPLETE, "已生成：${target.fileName}${transcript.language?.let { "（语言：$it）" }.orEmpty()}", null)
        }
    }

    fun cancel(onComplete: (() -> Unit)? = null) {
        if (!state.canCancel) return
        val cancellation = currentCancellation.get()
        if (onComplete != null && cancellation != null) cancelCompletion.set(cancellation to onComplete)
        onUi { machine.update(machine.state.copy(phase = TaskPhase.CANCELLING, progressText = "正在取消")); publish() }
        currentCancellation.get()?.cancel()
    }

    fun openText() = state.textOutput?.let(::openPath)
    fun openOutputDirectory() = settings.outputDirectory.takeIf(String::isNotBlank)?.let(Paths::get)?.let(::openPath)

    fun clearUiLogs() { machine.update(machine.state.copy(logs = emptyList())); publish() }
    fun copyableLogs(): String = state.logs.joinToString("\r\n") { "[${it.timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] [${it.level.label}] ${it.message}" }

    private fun submit(label: String, work: (Path, CancellationHandle) -> Unit) {
        val cancellation = CancellationHandle()
        currentCancellation.set(cancellation)
        executor.submit {
            var workspace: Path? = null
            try {
                workspace = workspaceManager.create()
                work(workspace, cancellation)
            } catch (error: Throwable) {
                if (cancellation.isCancelled() || error.message == "任务已取消") {
                    onUi { machine.cancelComplete(); publish(); appendLog(UiLogLevel.INFO, "任务已取消", null) }
            } else reportError("${label}失败", error)
            } finally {
                workspace?.let { runCatching { workspaceManager.cleanup(it) }.onFailure { error -> AppLog.logger.log(Level.WARNING, "清理任务目录失败", error) } }
                currentCancellation.compareAndSet(cancellation, null)
                cancelCompletion.get()?.takeIf { it.first === cancellation }?.let { pair ->
                    if (cancelCompletion.compareAndSet(pair, null)) EventQueue.invokeLater(pair.second)
                }
            }
        }
    }

    private fun validatedOutputDirectory(): Path? {
        val path = settings.outputDirectory.takeIf(String::isNotBlank)?.let(Paths::get)
        if (path == null || !DependencyChecker.validateOutputDirectory(path)) {
            appendLog(UiLogLevel.ERROR, "输出目录未设置或不可写", null)
            return null
        }
        return path.toAbsolutePath().normalize()
    }

    private fun ensureTemporarySpace(durationMs: Long?, workspace: Path) {
        val expected = durationMs?.let { (it / 3_600_000.0 * 115 * 1024 * 1024).toLong() } ?: return
        if (Files.getFileStore(workspace).usableSpace < expected + 64L * 1024 * 1024) error("临时目录磁盘空间不足")
    }

    private fun publishNoOverwrite(source: Path, target: Path) {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(source, target) }
        catch (_: FileAlreadyExistsException) { error("输出文件已被其他程序创建，请重试") }
    }

    private fun openPath(path: Path) = runCatching { Desktop.getDesktop().open(path.toFile()) }
        .onFailure { appendLog(UiLogLevel.ERROR, "Windows 无法打开：$path", null); AppLog.logger.log(Level.WARNING, "打开文件失败", it) }

    private fun reportError(prefix: String, error: Throwable) {
        AppLog.logger.log(Level.SEVERE, prefix, error)
        onUi { machine.fail("$prefix：${error.message ?: "未知错误"}"); publish(); appendLog(UiLogLevel.ERROR, machine.state.progressText, null) }
    }

    private fun beginDependencyCheck(): Long {
        dependencyCheckGeneration += 1
        machine.beginDependencyCheck()
        publish()
        return dependencyCheckGeneration
    }

    private fun publishDependencyCheck(generation: Long, value: DependencyStatus) = onUi {
        if (generation != dependencyCheckGeneration) {
            AppLog.logger.info("忽略已过期的运行环境检测结果：$generation")
            return@onUi
        }
        machine.completeDependencyCheck(value)
        publish()
        AppLog.logger.info("运行环境检测结果已发布：媒体功能=${value.mediaAvailable}")
        when (val completion = mediaLoadGate.dependencyCheckCompleted(value.mediaAvailable)) {
            PendingMediaCompletion.None -> Unit
            is PendingMediaCompletion.Load -> {
                AppLog.logger.info("开始处理等待中的媒体文件：${completion.path.fileName}")
                loadFile(completion.path)
            }
            is PendingMediaCompletion.Reject -> appendLog(
                UiLogLevel.ERROR,
                "环境检测完成，但 FFmpeg/ffprobe 不可用，无法读取：${completion.path.fileName}",
                "https://ffmpeg.org/download.html",
            )
        }
    }

    private fun publishProgress(label: String, progress: Int?) = onUi {
        machine.update(machine.state.copy(progress = progress, progressText = if (progress == null) label else "$label：$progress%")); publish()
    }

    private fun appendLog(level: UiLogLevel, message: String, link: String?) = onUi {
        val logs = (machine.state.logs + UiLogEntry(level = level, message = message, link = link)).takeLast(500)
        machine.update(machine.state.copy(logs = logs)); publish()
        AppLog.logger.info("[${level.label}] $message")
    }

    private fun onUi(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) action() else EventQueue.invokeLater(action)
    }
    private fun publish() { state = machine.state }
    override fun close() { currentCancellation.get()?.cancel(); executor.shutdown() }
}
