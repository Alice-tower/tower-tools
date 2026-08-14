package dev.towertools.mediatranscriber

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import javax.swing.JFileChooser

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MediaTranscriberApp(controller: MediaTranscriberController, owner: Frame) {
    val state = controller.state
    var settingsOpen by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val dropTarget = remember(controller) { object : DragAndDropTarget {
        override fun onEntered(event: DragAndDropEvent) { dragging = true }
        override fun onExited(event: DragAndDropEvent) { dragging = false }
        override fun onEnded(event: DragAndDropEvent) { dragging = false }
        override fun onDrop(event: DragAndDropEvent): Boolean {
            dragging = false
            val files = (event.dragData() as? DragData.FilesList)?.readFiles() ?: return false
            controller.loadFiles(DroppedFiles.parse(files)); return true
        }
    } }

    LaunchedEffect(Unit) { controller.startupCheck() }

    Column(
        Modifier.fillMaxSize()
            .then(if (dragging) Modifier.border(3.dp, MaterialTheme.colors.primary) else Modifier)
            .dragAndDropTarget({ it.dragData() is DragData.FilesList }, dropTarget)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderBar(controller, onSettings = { settingsOpen = true })

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val compact = maxWidth < 920.dp
            val rowModifier = if (compact) Modifier.fillMaxSize().horizontalScroll(rememberScrollState()) else Modifier.fillMaxSize()
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val cardModifier = if (compact) Modifier.width(286.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight()
                VideoStage(state, owner, controller, cardModifier)
                Text("→", style = MaterialTheme.typography.h5)
                AudioStage(state, controller, cardModifier)
                Text("→", style = MaterialTheme.typography.h5)
                TextStage(state, controller, cardModifier)
            }
        }

        Card(Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 190.dp).height(170.dp), elevation = 2.dp) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.progressText, fontWeight = FontWeight.Medium)
                        if (state.isBusy) { if (state.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(state.progress / 100f, Modifier.fillMaxWidth()) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { controller.cancel() }, enabled = state.canCancel) { Text("取消任务") }
                    TextButton(onClick = { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(controller.copyableLogs()), null) }) { Text("复制") }
                    TextButton(onClick = controller::clearUiLogs) { Text("清空显示") }
                }
                Divider()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    state.logs.forEach { entry ->
                        Row {
                            Text("[${entry.timestamp.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] [${entry.level.label}] ${entry.message}", style = MaterialTheme.typography.caption)
                            entry.link?.let { link -> TextButton(onClick = { runCatching { Desktop.getDesktop().browse(java.net.URI(link)) } }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("打开官网", style = MaterialTheme.typography.caption) } }
                        }
                    }
                }
            }
        }
    }
    if (settingsOpen) SettingsDialog(controller, owner) { settingsOpen = false }
}

@Composable
private fun HeaderBar(controller: MediaTranscriberController, onSettings: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 850.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column {
                    Text("影音转写", style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
                    Text("从视频提取默认音轨，或用本地 CUDA Whisper 转成文字", style = MaterialTheme.typography.caption)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutputDirectoryLabel(controller.settings.outputDirectory, Modifier.weight(1f))
                    Spacer(Modifier.width(6.dp))
                    OpenOutputDirectoryButton(controller)
                    Spacer(Modifier.width(6.dp))
                    OutlinedButton(onClick = onSettings) { Text("设置 ⚙") }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("影音转写", style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
                    Text("从视频提取默认音轨，或用本地 CUDA Whisper 转成文字", style = MaterialTheme.typography.caption)
                }
                OutputDirectoryLabel(controller.settings.outputDirectory, Modifier.widthIn(max = 360.dp))
                Spacer(Modifier.width(8.dp))
                OpenOutputDirectoryButton(controller)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onSettings) { Text("设置 ⚙") }
            }
        }
    }
}

@Composable private fun OutputDirectoryLabel(path: String, modifier: Modifier) = Text(path.ifBlank { "未设置输出目录" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
@Composable private fun OpenOutputDirectoryButton(controller: MediaTranscriberController) = OutlinedButton(
    onClick = controller::openOutputDirectory,
    enabled = controller.state.dependencies.outputAvailable,
) { Text("打开目录", maxLines = 1) }

@Composable
private fun VideoStage(state: AppState, owner: Frame, controller: MediaTranscriberController, modifier: Modifier) {
    StageCard("视频", modifier) {
        val media = state.media?.takeIf { it.kind == MediaKind.VIDEO }
        if (media == null) {
            EmptyMedia("拖入一个视频，或选择文件", state.isBusy) { chooseMedia(owner)?.let(controller::loadFile) }
        } else {
            ParameterPane {
                MediaLines(media.path.fileName.toString(), media.format, formatDuration(media.durationMs), formatSize(media.fileSize),
                    "${media.width ?: "?"} × ${media.height ?: "?"}", "视频编码：${media.videoCodec ?: "未知"}", "音轨数量：${media.audioTracks.size}")
            }
        }
    }
}

@Composable
private fun AudioStage(state: AppState, controller: MediaTranscriberController, modifier: Modifier) {
    StageCard("音频", modifier) {
        val media = state.media
        val audio = media?.selectedAudio
        when {
            media == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("等待媒体文件", color = MaterialTheme.colors.onSurface.copy(alpha = .55f)) }
            audio == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("未检测到音轨", color = MaterialTheme.colors.error) }
            else -> {
                ParameterPane {
                    MediaLines(if (media.kind == MediaKind.VIDEO) "来自视频的默认音轨" else media.path.fileName.toString(),
                        *(if (media.kind == MediaKind.AUDIO) arrayOf("格式：${media.format}", formatDuration(media.durationMs), formatSize(media.fileSize)) else emptyArray()),
                        "编码：${audio.codec}", "声道：${audio.channels ?: "未知"}", "采样率：${audio.sampleRate?.let { "$it Hz" } ?: "未知"}",
                        "码率：${audio.bitrate?.let(::formatBitrate) ?: "未知"}")
                }
                Divider()
                state.mp3Output?.let { Text("✓ ${it.fileName}", color = MaterialTheme.colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (media.kind == MediaKind.VIDEO) Button(onClick = controller::exportMp3, enabled = state.canExportMp3, modifier = Modifier.weight(1f)) { Text("导出 MP3", maxLines = 1) }
                    Button(onClick = controller::transcribe, enabled = state.canTranscribe, modifier = Modifier.weight(1f)) { Text("转成文字", maxLines = 1) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(controller.settings.includeTimestamps, { controller.saveSettings(controller.settings.copy(includeTimestamps = it)) }, enabled = !state.isBusy)
                    Text("每行添加累计分钟 [mm:ss] 时间", style = MaterialTheme.typography.caption, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun TextStage(state: AppState, controller: MediaTranscriberController, modifier: Modifier) {
    StageCard("文字", modifier) {
        val text = state.textOutput
        Box(Modifier.fillMaxWidth().weight(1f).pointerInput(text) { detectTapGestures(onDoubleTap = { if (text != null) controller.openText() }) }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("▤", style = MaterialTheme.typography.h2, color = if (text == null) Color.Gray else MaterialTheme.colors.primary)
                Text(text?.fileName?.toString() ?: "文字文档", maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (text == null) "转写完成后双击文档图标打开" else "已完成 · ${formatSize(runCatching { java.nio.file.Files.size(text) }.getOrDefault(0))}", style = MaterialTheme.typography.caption)
            }
        }
        if (text != null) OutlinedButton(onClick = controller::openOutputDirectory, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("打开所在目录") }
    }
}

@Composable private fun StageCard(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) = Card(modifier, elevation = 2.dp, shape = RoundedCornerShape(9.dp)) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(title, style = MaterialTheme.typography.h6); Divider(); content() }
}

@Composable private fun EmptyMedia(text: String, busy: Boolean, choose: () -> Unit) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(text); Spacer(Modifier.height(8.dp)); Button(choose, enabled = !busy) { Text("选择媒体") } } } }
@Composable private fun ColumnScope.ParameterPane(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp), content = content) }
@Composable private fun MediaLines(vararg lines: String) { lines.forEach { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } }

@Composable private fun SettingsDialog(controller: MediaTranscriberController, owner: Frame, close: () -> Unit) {
    var draft by remember(controller.settings) { mutableStateOf(controller.settings) }
    var dependencyHelpOpen by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("设置") }, text = {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingField("输出目录", draft.outputDirectory, { draft = draft.copy(outputDirectory = it) }) { chooseDirectory(owner)?.let { draft = draft.copy(outputDirectory = it.toString()) } }
            SettingField("FFmpeg 目录（留空使用 PATH）", draft.ffmpegDirectory, { draft = draft.copy(ffmpegDirectory = it) }) { chooseDirectory(owner)?.let { draft = draft.copy(ffmpegDirectory = it.toString()) } }
            SettingField("CUDA whisper-cli.exe", draft.whisperCliPath, { draft = draft.copy(whisperCliPath = it) }) { chooseFile(owner, "选择 whisper-cli.exe", setOf("exe"))?.let { draft = draft.copy(whisperCliPath = it.toString()) } }
            SettingField("Whisper 模型", draft.whisperModelPath, { draft = draft.copy(whisperModelPath = it) }) { chooseFile(owner, "选择 Whisper 模型", setOf("bin"))?.let { draft = draft.copy(whisperModelPath = it.toString()) } }
            Text("保存后重新自检；模型路径变化时会实际加载一秒静音音频验证。不会下载或复制任何外部资产。", style = MaterialTheme.typography.caption)
        }
    }, buttons = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { dependencyHelpOpen = true }) { Text("依赖说明") }
            Spacer(Modifier.weight(1f))
            TextButton(close) { Text("取消") }
            Spacer(Modifier.width(6.dp))
            Button(onClick = { val changed = draft.whisperModelPath != controller.settings.whisperModelPath || draft.whisperCliPath != controller.settings.whisperCliPath; controller.saveSettings(draft, changed); close() }) { Text("保存并检测") }
        }
    })
    if (dependencyHelpOpen) DependencyGuidanceDialog { dependencyHelpOpen = false }
}

@Composable
private fun DependencyGuidanceDialog(close: () -> Unit) {
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.width(720.dp).height(500.dp),
            shape = RoundedCornerShape(12.dp),
            elevation = 12.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Text("依赖说明与推荐", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DependencySection(
                        title = "FFmpeg",
                        explanation = "用于读取媒体信息、提取 MP3，并为 Whisper 生成 16 kHz 单声道 WAV。",
                        recommendation = "推荐下载 release essentials ZIP；解压后在设置中选择包含 ffmpeg.exe 和 ffprobe.exe 的 bin 目录。",
                        url = DependencyGuidance.ffmpegDownloadUrl,
                    )
                    DependencySection(
                        title = "whisper-cli（CUDA 版）",
                        explanation = "负责调用 NVIDIA GPU 和所选 Whisper 模型执行离线语音转写。",
                        recommendation = "在 Release 的 Assets 中下载 whisper-cublas-12.x.x-bin-x64.zip；较旧驱动可选 11.8 包。不要选择 whisper-bin、BLAS 或其他 CPU 包。",
                        url = DependencyGuidance.whisperCliDownloadUrl,
                    )
                    DependencySection(
                        title = "Whisper 模型",
                        explanation = "模型决定语言识别能力、准确率、速度以及显存占用。",
                        recommendation = "中文、日语和中英混合请选择不带 .en 的多语言 .bin 文件；通常优先 Large V3 Turbo，低显存可从 Small 开始。",
                        url = DependencyGuidance.modelDownloadUrl,
                    )
                    Text("模型选择参考", style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold)
                    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        Column(Modifier.width(760.dp)) {
                            ModelTableRow("模型", "文件", "速度", "质量", "建议显存", "适用场景", header = true)
                            DependencyGuidance.models.forEach { model ->
                                Divider()
                                ModelTableRow(model.model, model.fileSize, model.speed, model.quality, model.suggestedVram, model.suitableFor)
                            }
                        }
                    }
                    Text(
                        "显存数字是便于选型的保守建议，并非硬性门槛。显存不足时可尝试名称带 q5_0 的量化模型：文件和显存占用更低，但准确率可能略有下降。",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = .72f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Divider()
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = close) { Text("关闭") }
                }
            }
        }
    }
}

@Composable
private fun DependencySection(title: String, explanation: String, recommendation: String, url: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { openWebPage(url) }) { Text("打开下载页") }
        }
        Text(explanation)
        Text("推荐：$recommendation", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface.copy(alpha = .75f))
    }
}

@Composable
private fun ModelTableRow(model: String, size: String, speed: String, quality: String, vram: String, suitable: String, header: Boolean = false) {
    val weight = if (header) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(model, Modifier.width(130.dp), fontWeight = weight)
        Text(size, Modifier.width(85.dp), fontWeight = weight)
        Text(speed, Modifier.width(70.dp), fontWeight = weight)
        Text(quality, Modifier.width(70.dp), fontWeight = weight)
        Text(vram, Modifier.width(90.dp), fontWeight = weight)
        Text(suitable, Modifier.weight(1f), fontWeight = weight)
    }
}

@Composable private fun SettingField(label: String, value: String, change: (String) -> Unit, browse: () -> Unit) { Row(verticalAlignment = Alignment.CenterVertically) { OutlinedTextField(value, change, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f)); Spacer(Modifier.width(6.dp)); OutlinedButton(browse) { Text("选择") } } }

private fun chooseDirectory(owner: Frame): Path? = JFileChooser().apply { dialogTitle = "选择输出目录"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; isAcceptAllFileFilterUsed = false }.let { if (it.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) it.selectedFile.toPath() else null }
private fun chooseMedia(owner: Frame) = chooseFile(owner, "选择媒体文件", setOf("mp4","mkv","mov","avi","webm","mp3","wav","m4a","aac","flac","ogg"))
private fun chooseFile(owner: Frame, title: String, extensions: Set<String>): Path? { val dialog = FileDialog(owner, title, FileDialog.LOAD); dialog.filenameFilter = java.io.FilenameFilter { _, n -> n.substringAfterLast('.', "").lowercase() in extensions }; dialog.isVisible = true; return dialog.file?.let { File(dialog.directory, it).toPath() } }
private fun openWebPage(url: String) { runCatching { Desktop.getDesktop().browse(java.net.URI(url)) }.onFailure { AppLog.logger.warning("无法打开下载页面 $url：${it.message}") } }
private fun formatDuration(ms: Long?) = ms?.let { "%02d:%02d:%02d".format(it/3_600_000, it/60_000%60, it/1000%60) } ?: "时长未知"
private fun formatSize(bytes: Long): String = if (bytes < 1024*1024) "%.1f KB".format(bytes/1024.0) else "%.1f MB".format(bytes/1024.0/1024.0)
private fun formatBitrate(value: Long) = "%.0f kbps".format(value/1000.0)
