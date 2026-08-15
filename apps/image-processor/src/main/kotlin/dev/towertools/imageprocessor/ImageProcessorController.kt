package dev.towertools.imageprocessor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Desktop
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ImageProcessorController : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "image-processor-worker").apply { isDaemon = true }
    }
    private val loadGeneration = AtomicInteger()

    var loadedImage by mutableStateOf<LoadedImage?>(null)
        private set
    var processedPreview by mutableStateOf<java.awt.image.BufferedImage?>(null)
        private set
    var rotation by mutableStateOf(Rotation.ORIGINAL)
        private set
    var cropMode by mutableStateOf<CropMode>(CropMode.Original)
        private set
    var splitMode by mutableStateOf(SplitMode.ORIGINAL)
        private set
    var outputDirectory by mutableStateOf(SettingsStore.load().outputDirectory)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isExporting by mutableStateOf(false)
        private set
    var progressText by mutableStateOf("")
        private set
    var message by mutableStateOf("拖入一张图片，或点击“选择图片”开始。")
        private set
    var messageIsError by mutableStateOf(false)
        private set

    fun loadFiles(paths: List<Path>) {
        if (isExporting) {
            showError("正在输出图片，请稍后再加载新图片。")
            return
        }
        if (paths.size != 1) {
            showError("一次只能处理一张图片。")
            return
        }
        load(paths.single())
    }

    fun selectRotation(value: Rotation) {
        if (!canChangeOptions()) return
        if (rotation != value && cropMode is CropMode.Fine) cropMode = CropMode.Original
        rotation = value
        rebuildPreview()
    }

    fun selectCrop(value: CropMode) {
        if (!canChangeOptions()) return
        cropMode = value
        rebuildPreview()
    }

    fun confirmFineCrop(rect: ImageRect) {
        if (!canChangeOptions()) return
        val descriptor = loadedImage?.descriptor ?: return
        val (width, height) = ImageGeometry.rotatedSize(descriptor.orientedWidth, descriptor.orientedHeight, rotation)
        runCatching { ImageGeometry.cropRect(width, height, CropMode.Fine(rect)) }
            .onSuccess {
                cropMode = CropMode.Fine(rect)
                rebuildPreview()
            }
            .onFailure(::handleFailure)
    }

    fun selectSplit(value: SplitMode) {
        if (!canChangeOptions()) return
        splitMode = value
        rebuildPreview()
    }

    fun saveOutputDirectory(rawValue: String) {
        runCatching {
            val path = PathValidator.requireWritable(rawValue)
            SettingsStore.save(AppSettings(path.toString()))
            path
        }.onSuccess { path ->
            outputDirectory = path.toString()
            showInfo("输出目录已保存。")
        }.onFailure(::handleFailure)
    }

    fun openOutputDirectory(rawValue: String) {
        runCatching {
            val path = PathValidator.normalize(rawValue)
            if (!Desktop.isDesktopSupported()) throw UserFacingException("当前系统无法打开文件夹。")
            Desktop.getDesktop().open(path.toFile())
            path
        }.onSuccess { showInfo("已打开输出目录。") }
            .onFailure(::handleFailure)
    }

    fun export(rawOutputDirectory: String) {
        if (isExporting) return
        if (isLoading) {
            showError("图片仍在读取，请稍后再输出。")
            return
        }
        val loaded = loadedImage
        if (loaded == null) {
            showError("请先拖入一张图片。")
            return
        }
        isExporting = true
        progressText = "正在准备输出…"
        showInfo("正在输出，请稍候。")
        executor.submit {
            runCatching {
                val directory = PathValidator.requireWritable(rawOutputDirectory)
                OutputExporter.export(loaded, rotation, cropMode, splitMode, directory) { current, total ->
                    EventQueue.invokeLater { progressText = "正在生成 $current/$total" }
                }
            }.onSuccess { summary ->
                EventQueue.invokeLater {
                    isExporting = false
                    progressText = ""
                    showInfo("输出完成，共生成 ${summary.files.size} 张图片。")
                }
            }.onFailure { error ->
                EventQueue.invokeLater {
                    isExporting = false
                    progressText = ""
                    handleFailure(error)
                }
            }
        }
    }

    fun outputDimensions(): Pair<Int, Int>? {
        val descriptor = loadedImage?.descriptor ?: return null
        return ImageGeometry.outputSize(
            descriptor.orientedWidth,
            descriptor.orientedHeight,
            rotation,
            cropMode,
        )
    }

    private fun load(path: Path) {
        val generation = loadGeneration.incrementAndGet()
        isLoading = true
        showInfo("正在读取图片…")
        executor.submit {
            runCatching { ImageIOService.load(path) }
                .onSuccess { loaded ->
                    EventQueue.invokeLater {
                        if (generation != loadGeneration.get()) return@invokeLater
                        loadedImage = loaded
                        rotation = Rotation.ORIGINAL
                        cropMode = CropMode.Original
                        splitMode = SplitMode.ORIGINAL
                        isLoading = false
                        rebuildPreview()
                        showInfo("已加载 ${loaded.descriptor.fileName}。")
                    }
                }
                .onFailure { error ->
                    EventQueue.invokeLater {
                        if (generation != loadGeneration.get()) return@invokeLater
                        isLoading = false
                        handleFailure(error)
                    }
                }
        }
    }

    private fun rebuildPreview() {
        val source = loadedImage?.originalPreview ?: run {
            processedPreview = null
            return
        }
        runCatching {
            val descriptor = loadedImage?.descriptor ?: return
            ImageTransforms.splitPreview(
                ImageTransforms.processPreview(
                    source,
                    descriptor.orientedWidth,
                    descriptor.orientedHeight,
                    rotation,
                    cropMode,
                ),
                splitMode,
            )
        }.onSuccess {
            processedPreview = it
            messageIsError = false
        }.onFailure(::handleFailure)
    }

    private fun canChangeOptions(): Boolean {
        if (isExporting) {
            showError("正在输出图片，请稍后再调整选项。")
            return false
        }
        return true
    }

    private fun handleFailure(error: Throwable) {
        val message = when (error) {
            is OutputCollisionException -> {
                val preview = error.collisions.take(5).joinToString("、") { it.fileName.toString() }
                val more = if (error.collisions.size > 5) " 等 ${error.collisions.size} 个文件" else ""
                "检测到同名文件：$preview$more。已拒绝整批输出。"
            }
            is UserFacingException -> error.message ?: "操作失败。"
            else -> error.message ?: error.javaClass.simpleName
        }
        AppLog.logger.warning("Image operation failed: ${error.message}")
        showError(message)
    }

    private fun showInfo(value: String) {
        message = value
        messageIsError = false
    }

    private fun showError(value: String) {
        message = value
        messageIsError = true
    }

    override fun close() {
        loadGeneration.incrementAndGet()
        executor.shutdownNow()
    }
}
