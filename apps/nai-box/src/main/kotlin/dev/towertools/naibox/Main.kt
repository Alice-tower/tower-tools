package dev.towertools.naibox

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val instance = runCatching { SingleInstance.acquire(AppMetadata.id) }
        .onFailure { AppLog.logger.severe("单实例控制启动失败：${it.message}") }
        .getOrNull() ?: return
    application {
        val state = rememberWindowState(width = 1180.dp, height = 760.dp)
        Window(onCloseRequest = ::exitApplication, state = state, title = AppMetadata.displayName) {
            var parsed by remember { mutableStateOf<ImageInfo?>(null) }
            var currentBytes by remember { mutableStateOf<ByteArray?>(null) }
            var status by remember { mutableStateOf("选择、拖入图片，或按 Ctrl+V 粘贴") }
            var selectionNotice by remember { mutableStateOf<String?>(null) }
            var hasError by remember { mutableStateOf(false) }
            var selectedTab by remember { mutableStateOf("参数") }
            val scope = rememberCoroutineScope()
            val loadId = remember { AtomicInteger() }
            fun open(bytes: ByteArray, name: String, request: Int = loadId.incrementAndGet()) {
                parsed = null; currentBytes = null
                status = "正在读取 $name …"; hasError = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { ImageParser.parse(bytes, name) } }
                        .onSuccess {
                            if (request != loadId.get()) return@onSuccess
                            if (it.format == "unknown") { hasError = true; status = it.warnings.firstOrNull() ?: "无法识别的图片格式" }
                            else { parsed = it; currentBytes = bytes; selectedTab = "参数"; status = "已解析 ${it.fileName}" }
                        }
                        .onFailure { e -> if (request == loadId.get()) { hasError = true; status = "解析失败：${e.message}"; AppLog.logger.warning(status) } }
                }
            }
            fun openFile(file: File, notice: String? = null) {
                val request = loadId.incrementAndGet()
                selectionNotice = notice
                parsed = null; currentBytes = null
                status = "正在读取 ${file.name} …"; hasError = false
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Files.readAllBytes(file.toPath()) } }
                        .onSuccess { if (request == loadId.get()) open(it, file.name, request) }
                        .onFailure { e -> if (request == loadId.get()) { hasError = true; status = "读取失败：${e.message}" } }
                }
            }
            fun choose() {
                FileDialog(window, "选择图片", FileDialog.LOAD).apply {
                    isVisible = true
                    file?.let { openFile(File(directory, it)) }
                }
            }
            fun paste() {
                runCatching {
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    val files = if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor))
                        (clipboard.getData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>()
                    else emptyList()
                    val selectedFile = ImageInput.firstImage(files)
                    when {
                        selectedFile != null -> {
                            openFile(selectedFile, if (files.size > 1) "一次只处理一张，已选用 ${selectedFile.name}" else null)
                        }
                        clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) -> {
                            val image = clipboard.getData(DataFlavor.imageFlavor) as java.awt.Image
                            val bitmap = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
                            val graphics = bitmap.createGraphics()
                            try { graphics.drawImage(image, 0, 0, null) } finally { graphics.dispose() }
                            val buffer = ByteArrayOutputStream()
                            ImageIO.write(bitmap, "png", buffer)
                            selectionNotice = null
                            open(buffer.toByteArray(), "剪贴板图片.png")
                        }
                        else -> throw IllegalStateException("剪贴板里没有支持的图片")
                    }
                }.onFailure { hasError = true; status = it.message ?: "无法读取剪贴板" }
            }
            val dropTarget = remember { object : DragAndDropTarget {
                override fun onDrop(event: DragAndDropEvent): Boolean {
                    val files = (event.dragData() as? DragData.FilesList)?.readFiles() ?: return false
                    val file = ImageInput.firstImage(ImageInput.droppedFiles(files))
                    if (file == null) { selectionNotice = null; hasError = true; status = "请拖入图片文件（PNG / JPEG / WebP 等）"; return true }
                    openFile(file, if (files.size > 1) "一次只处理一张，已选用 ${file.name}" else null)
                    return true
                }
            } }
            DisposableEffect(window) {
                instance.onActivate {
                    EventQueue.invokeLater { window.extendedState = Frame.NORMAL; window.isVisible = true; window.toFront(); window.requestFocus() }
                }
                onDispose(instance::close)
            }
            MaterialTheme(colors = if (isSystemInDarkTheme()) darkColors() else lightColors()) {
                Surface(Modifier.fillMaxSize().dragAndDropTarget({ it.dragData() is DragData.FilesList }, dropTarget).onPreviewKeyEvent {
                    if (it.isCtrlPressed && it.key == Key.V) { paste(); true } else false
                }) {
                    Column(Modifier.fillMaxSize().padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("图片元数据解析器", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
                                Text("NovelAI · A1111 / Forge · 角色卡 · PNG / JPEG / WebP", color = MaterialTheme.colors.onSurface.copy(alpha = .65f))
                            }
                            Text("全程本地解析", color = MaterialTheme.colors.primary)
                            Spacer(Modifier.width(20.dp))
                            OutlinedButton(onClick = ::paste) { Text("粘贴图片") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = ::choose) { Text("打开图片") }
                        }
                        Spacer(Modifier.height(16.dp))
                        Surface(color = if (hasError) MaterialTheme.colors.error.copy(alpha = .12f) else MaterialTheme.colors.primary.copy(alpha = .09f), shape = RoundedCornerShape(8.dp)) {
                            Text(status, Modifier.fillMaxWidth().padding(12.dp), color = if (hasError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface)
                        }
                        selectionNotice?.let { Text(it, Modifier.padding(top = 5.dp), color = MaterialTheme.colors.primary) }
                        Spacer(Modifier.height(16.dp))
                        val info = parsed
                        if (info == null) {
                            Box(Modifier.fillMaxSize().border(1.dp, MaterialTheme.colors.primary.copy(alpha = .4f), RoundedCornerShape(12.dp)).clickable(onClick = ::choose), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("把图片拖到这里", style = MaterialTheme.typography.h5)
                                    Spacer(Modifier.height(8.dp))
                                    Text("也可以点击选择文件，或按 Ctrl+V 粘贴")
                                }
                            }
                        } else {
                            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Column(Modifier.width(290.dp).verticalScroll(rememberScrollState())) {
                                    ImagePreview(currentBytes, Modifier.fillMaxWidth().height(260.dp))
                                    Spacer(Modifier.height(12.dp))
                                    SectionTitle("文件信息")
                                    KeyValues(listOf(
                                        "文件名" to info.fileName, "文件大小" to formatBytes(info.fileSize),
                                        "格式" to info.format.uppercase(), "尺寸" to "${info.width ?: "?"} × ${info.height ?: "?"}",
                                        "文件头" to info.head,
                                    ) + info.header.toList())
                                }
                                Column(Modifier.weight(1f)) {
                                    val tabs = buildList {
                                        add("参数")
                                        if (info.novelAi.characters.isNotEmpty()) add("角色")
                                        if (info.card != null && info.novelAi.isNai) add("角色卡")
                                        if (info.container != null) add("容器")
                                        add("原始元数据")
                                        if (info.novelAi.commentRaw.isNotBlank()) add("Comment JSON")
                                        if (info.novelAi.requestBody != null) add("还原请求体")
                                    }
                                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                                        tabs.forEach { tab -> TextButton(onClick = { selectedTab = tab }) { Text(tab, fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal) } }
                                    }
                                    Divider()
                                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, end = 8.dp)) {
                                        when (selectedTab) {
                                            "参数" -> ParamsPanel(info)
                                            "角色" -> CharactersPanel(info)
                                            "角色卡" -> info.card?.let { CardPanel(it) }
                                            "容器" -> info.container?.let { ContainerPanel(it, currentBytes) }
                                            "原始元数据" -> RawPanel(info, window)
                                            "Comment JSON" -> CopyBlock("Comment JSON", parseJson(info.novelAi.commentRaw)?.pretty() ?: info.novelAi.commentRaw)
                                            "还原请求体" -> { Notice("参考图、img2img 原图和遮罩不会存于元数据，需手动补齐。", true); CopyBlock("/ai/generate-image 请求体", info.novelAi.requestBody.orEmpty()) }
                                        }
                                        Spacer(Modifier.height(30.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
