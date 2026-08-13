package dev.towertools.imageprocessor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.FileDialog
import java.awt.Frame
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.FilenameFilter
import javax.imageio.ImageIO
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable
fun ImageProcessorApp(controller: ImageProcessorController, owner: Frame) {
    var outputDraft by remember { mutableStateOf(controller.outputDirectory) }
    var isDraggingOver by remember { mutableStateOf(false) }
    val loaded = controller.loadedImage
    val enabled = loaded != null && !controller.isLoading && !controller.isExporting
    val dropTarget = remember(controller) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                isDraggingOver = true
            }

            override fun onExited(event: DragAndDropEvent) {
                isDraggingOver = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDraggingOver = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDraggingOver = false
                val files = (event.dragData() as? DragData.FilesList)?.readFiles() ?: return false
                val paths = DroppedFiles.parse(files)
                if (paths.isEmpty()) return false
                controller.loadFiles(paths)
                return true
            }
        }
    }
    val dragBorder = if (isDraggingOver) {
        Modifier.border(3.dp, MaterialTheme.colors.primary, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(dragBorder)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event -> event.dragData() is DragData.FilesList },
                target = dropTarget,
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(AppMetadata.displayName, style = MaterialTheme.typography.h5, fontWeight = FontWeight.SemiBold)
                Text(
                    "支持 JPG/JPEG、PNG、BMP、GIF、WebP、TIFF/TIF；仅支持静态 GIF/WebP 和单页 TIFF。",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f),
                )
            }
            OutlinedButton(
                onClick = { chooseImage(owner)?.let { controller.loadFiles(listOf(it)) } },
                enabled = !controller.isExporting,
            ) {
                Text("选择图片")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PreviewPanel(
                title = "原图",
                image = loaded?.originalPreview,
                subtitle = loaded?.descriptor?.let {
                    "${it.fileName}  ·  ${it.format}  ·  ${it.orientedWidth}×${it.orientedHeight}  ·  ${formatFileSize(it.fileSize)}"
                } ?: "拖入一张图片，或点击上方“选择图片”",
                loading = controller.isLoading,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            val outputSize = controller.outputDimensions()
            PreviewPanel(
                title = "产出预览",
                image = controller.processedPreview,
                subtitle = outputSize?.let {
                    "${it.first}×${it.second}  ·  ${controller.splitMode.columns * controller.splitMode.rows} 张产物"
                } ?: "旋转、裁剪和分割的共同结果",
                loading = false,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 270.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OptionSection("旋转") {
                Rotation.entries.forEach { option ->
                    OptionButton(
                        selected = controller.rotation == option,
                        enabled = enabled,
                        onClick = { controller.selectRotation(option) },
                    ) {
                        Text(
                            when (option) {
                                Rotation.ORIGINAL -> "⟲"
                                Rotation.CLOCKWISE_90 -> "↻"
                                Rotation.DEGREES_180 -> "↻↻"
                                Rotation.COUNTERCLOCKWISE_90 -> "↺"
                            },
                            fontSize = 18.sp,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(option.label)
                    }
                }
            }

            OptionSection("裁剪") {
                CropOptionButton(CropMode.Original, controller.cropMode, enabled) {
                    controller.selectCrop(CropMode.Original)
                }
                cropPresets.forEach { option ->
                    CropOptionButton(option, controller.cropMode, enabled) { controller.selectCrop(option) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = controller.customWidthText,
                        onValueChange = { controller.customWidthText = it },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text("宽") },
                        modifier = Modifier.width(72.dp),
                    )
                    Text(":", modifier = Modifier.padding(horizontal = 4.dp))
                    OutlinedTextField(
                        value = controller.customHeightText,
                        onValueChange = { controller.customHeightText = it },
                        enabled = enabled,
                        singleLine = true,
                        label = { Text("高") },
                        modifier = Modifier.width(72.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    OptionButton(
                        selected = (controller.cropMode as? CropMode.Ratio)?.custom == true,
                        enabled = enabled,
                        onClick = controller::selectCustomCrop,
                    ) {
                        AspectIcon(1.6)
                        Spacer(Modifier.width(6.dp))
                        Text("自定义")
                    }
                }
            }

            OptionSection("分割") {
                SplitMode.entries.forEach { option ->
                    OptionButton(
                        selected = controller.splitMode == option,
                        enabled = enabled,
                        onClick = { controller.selectSplit(option) },
                    ) {
                        GridIcon(option.columns, option.rows)
                        Spacer(Modifier.width(6.dp))
                        Text(option.label)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth(), elevation = 2.dp, shape = RoundedCornerShape(8.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = outputDraft,
                        onValueChange = { outputDraft = it },
                        singleLine = true,
                        label = { Text("输出目录（粘贴完整路径）") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        controller.saveOutputDirectory(outputDraft)
                        outputDraft = outputDraft.trim().removeSurrounding("\"")
                    }) {
                        Text("保存目录")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { controller.openOutputDirectory(controller.outputDirectory) }) {
                        Text("打开输出目录")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { controller.export(controller.outputDirectory) },
                        enabled = !controller.isExporting,
                    ) {
                        if (controller.isExporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colors.onPrimary,
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(if (controller.isExporting) controller.progressText else "输出图片")
                    }
                }
                if (controller.outputDirectory.isBlank()) {
                    Text(
                        "首次使用必须先填写并保存输出目录。",
                        color = MaterialTheme.colors.error,
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
                Text(
                    controller.message,
                    color = if (controller.messageIsError) MaterialTheme.colors.error else MaterialTheme.colors.primary,
                    style = MaterialTheme.typography.caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewPanel(
    title: String,
    image: BufferedImage?,
    subtitle: String,
    loading: Boolean,
    modifier: Modifier,
) {
    Card(modifier = modifier, elevation = 2.dp, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.subtitle1, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxSize()
                    .checkerboard()
                    .padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> CircularProgressIndicator()
                    image != null -> {
                        val bitmap = remember(image) { image.asComposeImageBitmap() }
                        Image(
                            bitmap = bitmap,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    else -> Text(
                        "将图片拖到窗口中",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.52f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionSection(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), elevation = 1.dp, shape = RoundedCornerShape(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Top) {
            Text(title, fontWeight = FontWeight.Medium, modifier = Modifier.width(52.dp).padding(top = 10.dp))
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
                content = { content() },
            )
        }
    }
}

@Composable
private fun OptionButton(
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, content = content)
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, content = content)
    }
}

@Composable
private fun CropOptionButton(option: CropMode, selected: CropMode, enabled: Boolean, onClick: () -> Unit) {
    val ratio = (option as? CropMode.Ratio)?.let { it.widthRatio / it.heightRatio } ?: 1.25
    OptionButton(selected = option == selected, enabled = enabled, onClick = onClick) {
        AspectIcon(ratio)
        Spacer(Modifier.width(6.dp))
        Text(option.label)
    }
}

@Composable
private fun AspectIcon(ratio: Double) {
    val color = MaterialTheme.colors.onSurface
    Canvas(Modifier.size(30.dp, 21.dp)) {
        val normalized = ratio.coerceIn(0.45, 2.4)
        val maxWidth = size.width - 2f
        val maxHeight = size.height - 2f
        val width: Float
        val height: Float
        if (normalized >= maxWidth / maxHeight) {
            width = maxWidth
            height = (maxWidth / normalized).toFloat()
        } else {
            height = maxHeight
            width = (maxHeight * normalized).toFloat()
        }
        drawRect(
            color = color,
            topLeft = Offset((size.width - width) / 2, (size.height - height) / 2),
            size = Size(width, height),
            style = Stroke(width = 1.6f),
        )
    }
}

@Composable
private fun GridIcon(columns: Int, rows: Int) {
    val color = MaterialTheme.colors.onSurface
    Canvas(Modifier.size(25.dp, 21.dp)) {
        drawRect(color, style = Stroke(width = 1.5f))
        for (column in 1 until columns) {
            val x = size.width * column / columns
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.3f, cap = StrokeCap.Square)
        }
        for (row in 1 until rows) {
            val y = size.height * row / rows
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.3f, cap = StrokeCap.Square)
        }
    }
}

private fun Modifier.checkerboard(): Modifier = drawBehind {
    val tile = 12.dp.toPx()
    val light = Color(0xFFE6E8EC)
    val dark = Color(0xFFCED2D8)
    var row = 0
    var y = 0f
    while (y < size.height) {
        var column = 0
        var x = 0f
        while (x < size.width) {
            drawRect(if ((row + column) % 2 == 0) light else dark, Offset(x, y), Size(tile, tile))
            x += tile
            column++
        }
        y += tile
        row++
    }
}

private fun BufferedImage.asComposeImageBitmap(): ImageBitmap {
    val bytes = ByteArrayOutputStream().use { output ->
        ImageIO.write(this, "png", output)
        output.toByteArray()
    }
    return org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

private fun chooseImage(owner: Frame): java.nio.file.Path? {
    val dialog = FileDialog(owner, "选择图片", FileDialog.LOAD)
    val supported = setOf("jpg", "jpeg", "png", "bmp", "gif", "webp", "tif", "tiff")
    dialog.filenameFilter = FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in supported }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return java.io.File(dialog.directory, file).toPath()
}

private fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024) return "%.1f KB".format(kib)
    return "%.1f MB".format(kib / 1024.0)
}
