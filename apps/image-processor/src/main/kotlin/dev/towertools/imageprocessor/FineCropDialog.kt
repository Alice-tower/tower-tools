package dev.towertools.imageprocessor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.awt.Dimension
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun FineCropDialog(
    previewImage: BufferedImage,
    sourceWidth: Int,
    sourceHeight: Int,
    onConfirm: (ImageRect) -> Unit,
    onCancel: () -> Unit,
) {
    val state = rememberDialogState(width = 1080.dp, height = 820.dp)
    DialogWindow(
        onCloseRequest = onCancel,
        state = state,
        title = "精细裁剪",
        modalityType = DialogModalityType.ApplicationModal,
    ) {
        DisposableEffect(window) {
            window.minimumSize = Dimension(900, 650)
            onDispose { }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            FineCropContent(
                previewImage = previewImage,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                onConfirm = onConfirm,
                onCancel = onCancel,
            )
        }
    }
}

@Composable
private fun FineCropContent(
    previewImage: BufferedImage,
    sourceWidth: Int,
    sourceHeight: Int,
    onConfirm: (ImageRect) -> Unit,
    onCancel: () -> Unit,
) {
    var cropRect by remember(sourceWidth, sourceHeight) {
        mutableStateOf(ImageRect(0, 0, sourceWidth, sourceHeight))
    }
    var widthText by remember(sourceWidth, sourceHeight) { mutableStateOf(sourceWidth.toString()) }
    var heightText by remember(sourceWidth, sourceHeight) { mutableStateOf(sourceHeight.toString()) }
    var selectedPreset by remember(sourceWidth, sourceHeight) { mutableStateOf<CropMode?>(CropMode.Original) }
    val bitmap = remember(previewImage) { previewImage.asComposeImageBitmap() }

    fun setCropRect(value: ImageRect, selection: CropMode? = null) {
        cropRect = value
        widthText = value.width.toString()
        heightText = value.height.toString()
        selectedPreset = selection
    }

    fun applyInput(widthValue: String, heightValue: String) {
        widthText = widthValue
        heightText = heightValue
        val width = widthValue.toIntOrNull()
        val height = heightValue.toIntOrNull()
        if (width != null && width in 1..sourceWidth && height != null && height in 1..sourceHeight) {
            cropRect = ImageGeometry.centeredRect(sourceWidth, sourceHeight, width, height)
            selectedPreset = null
        }
    }

    val widthValid = widthText.toIntOrNull()?.let { it in 1..sourceWidth } == true
    val heightValid = heightText.toIntOrNull()?.let { it in 1..sourceHeight } == true

    Column(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("精细裁剪", style = MaterialTheme.typography.h6, fontWeight = FontWeight.SemiBold)
                Text(
                    "当前图片分辨率：${sourceWidth} × ${sourceHeight}（已应用主窗口旋转）",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f),
                )
            }
            Text(
                "拖动裁剪框调整位置，拖拽边缘或角点调整大小",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f),
            )
        }

        Card(modifier = Modifier.fillMaxWidth().weight(1f), elevation = 2.dp) {
            FineCropCanvas(
                bitmap = bitmap,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                cropRect = cropRect,
                onCropRectChange = { setCropRect(it) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = widthText,
                onValueChange = { applyInput(it, heightText) },
                singleLine = true,
                isError = !widthValid,
                label = { Text("裁剪宽度（像素）") },
                modifier = Modifier.width(180.dp),
            )
            Text("×")
            OutlinedTextField(
                value = heightText,
                onValueChange = { applyInput(widthText, it) },
                singleLine = true,
                isError = !heightValid,
                label = { Text("裁剪高度（像素）") },
                modifier = Modifier.width(180.dp),
            )
            Column(modifier = Modifier.padding(start = 8.dp)) {
                Text("宽度占原图 ${formatPercent(cropRect.width, sourceWidth)}")
                Text("高度占原图 ${formatPercent(cropRect.height, sourceHeight)}")
            }
            if (!widthValid || !heightValid) {
                Text(
                    "请输入大于 0 且不超过原图的整数",
                    color = MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("裁剪比例", fontWeight = FontWeight.Medium)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FineCropPresetButton(
                    label = CropMode.Original.label,
                    selected = selectedPreset == CropMode.Original,
                    onClick = {
                        setCropRect(ImageRect(0, 0, sourceWidth, sourceHeight), CropMode.Original)
                    },
                )
                cropPresets.forEach { preset ->
                    FineCropPresetButton(
                        label = preset.label,
                        selected = selectedPreset == preset,
                        onClick = {
                            setCropRect(ImageGeometry.cropRect(sourceWidth, sourceHeight, preset), preset)
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onCancel) { Text("取消") }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { onConfirm(cropRect) },
                enabled = widthValid && heightValid,
            ) {
                Text("确认")
            }
        }
    }
}

@Composable
private fun FineCropPresetButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

@Composable
private fun FineCropCanvas(
    bitmap: ImageBitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    cropRect: ImageRect,
    onCropRectChange: (ImageRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val currentCropRect by rememberUpdatedState(cropRect)
    val currentOnCropRectChange by rememberUpdatedState(onCropRectChange)
    val selectionColor = MaterialTheme.colors.primary

    Box(modifier = modifier.background(Color(0xFF30343C))) {
        Canvas(
            modifier = Modifier.fillMaxSize()
                .onSizeChanged { canvasSize = it }
                .pointerInput(sourceWidth, sourceHeight, canvasSize) {
                    var activeHandle: CropDragHandle? = null
                    var startRect = currentCropRect
                    var totalDrag = Offset.Zero
                    detectDragGestures(
                        onDragStart = { position ->
                            val display = fittedImageRect(canvasSize, sourceWidth, sourceHeight)
                            startRect = currentCropRect
                            activeHandle = hitTestCropHandle(position, display, startRect, sourceWidth, sourceHeight, 12.dp.toPx())
                            totalDrag = Offset.Zero
                        },
                        onDragEnd = { activeHandle = null },
                        onDragCancel = { activeHandle = null },
                    ) { change, dragAmount ->
                        val handle = activeHandle ?: return@detectDragGestures
                        change.consume()
                        totalDrag += dragAmount
                        val display = fittedImageRect(canvasSize, sourceWidth, sourceHeight)
                        if (display.width <= 0f || display.height <= 0f) return@detectDragGestures
                        val deltaX = (totalDrag.x * sourceWidth / display.width).roundToInt()
                        val deltaY = (totalDrag.y * sourceHeight / display.height).roundToInt()
                        currentOnCropRectChange(
                            FineCropGeometry.adjust(startRect, handle, deltaX, deltaY, sourceWidth, sourceHeight),
                        )
                    }
                },
        ) {
            val display = fittedImageRect(canvasSize, sourceWidth, sourceHeight)
            if (display.width <= 0f || display.height <= 0f) return@Canvas
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(display.left.roundToInt(), display.top.roundToInt()),
                dstSize = IntSize(display.width.roundToInt(), display.height.roundToInt()),
            )

            val crop = displayCropRect(display, cropRect, sourceWidth, sourceHeight)
            val shade = Color.Black.copy(alpha = 0.56f)
            drawRect(shade, Offset(display.left, display.top), androidx.compose.ui.geometry.Size(display.width, crop.top - display.top))
            drawRect(shade, Offset(display.left, crop.bottom), androidx.compose.ui.geometry.Size(display.width, display.bottom - crop.bottom))
            drawRect(shade, Offset(display.left, crop.top), androidx.compose.ui.geometry.Size(crop.left - display.left, crop.height))
            drawRect(shade, Offset(crop.right, crop.top), androidx.compose.ui.geometry.Size(display.right - crop.right, crop.height))
            drawRect(
                color = selectionColor,
                topLeft = Offset(crop.left, crop.top),
                size = androidx.compose.ui.geometry.Size(crop.width, crop.height),
                style = Stroke(width = 2.dp.toPx()),
            )

            val handleRadius = 4.5.dp.toPx()
            val handlePoints = listOf(
                Offset(crop.left, crop.top), Offset(crop.centerX, crop.top), Offset(crop.right, crop.top),
                Offset(crop.left, crop.centerY), Offset(crop.right, crop.centerY),
                Offset(crop.left, crop.bottom), Offset(crop.centerX, crop.bottom), Offset(crop.right, crop.bottom),
            )
            handlePoints.forEach { point ->
                drawCircle(Color.White, handleRadius, point)
                drawCircle(selectionColor, handleRadius, point, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
    }
}

private data class DisplayRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
    val centerX: Float get() = left + width / 2f
    val centerY: Float get() = top + height / 2f
}

private fun fittedImageRect(canvasSize: IntSize, sourceWidth: Int, sourceHeight: Int): DisplayRect {
    if (canvasSize.width <= 0 || canvasSize.height <= 0) return DisplayRect(0f, 0f, 0f, 0f)
    val scale = min(canvasSize.width.toFloat() / sourceWidth, canvasSize.height.toFloat() / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    return DisplayRect((canvasSize.width - width) / 2f, (canvasSize.height - height) / 2f, width, height)
}

private fun displayCropRect(display: DisplayRect, crop: ImageRect, sourceWidth: Int, sourceHeight: Int): DisplayRect =
    DisplayRect(
        left = display.left + crop.x.toFloat() / sourceWidth * display.width,
        top = display.top + crop.y.toFloat() / sourceHeight * display.height,
        width = crop.width.toFloat() / sourceWidth * display.width,
        height = crop.height.toFloat() / sourceHeight * display.height,
    )

private fun hitTestCropHandle(
    position: Offset,
    display: DisplayRect,
    crop: ImageRect,
    sourceWidth: Int,
    sourceHeight: Int,
    tolerance: Float,
): CropDragHandle? {
    val shown = displayCropRect(display, crop, sourceWidth, sourceHeight)
    val insideExpanded = position.x in (shown.left - tolerance)..(shown.right + tolerance) &&
        position.y in (shown.top - tolerance)..(shown.bottom + tolerance)
    if (!insideExpanded) return null
    val nearLeft = abs(position.x - shown.left) <= tolerance
    val nearRight = abs(position.x - shown.right) <= tolerance
    val nearTop = abs(position.y - shown.top) <= tolerance
    val nearBottom = abs(position.y - shown.bottom) <= tolerance
    return when {
        nearLeft && nearTop -> CropDragHandle.TOP_LEFT
        nearRight && nearTop -> CropDragHandle.TOP_RIGHT
        nearLeft && nearBottom -> CropDragHandle.BOTTOM_LEFT
        nearRight && nearBottom -> CropDragHandle.BOTTOM_RIGHT
        nearLeft -> CropDragHandle.LEFT
        nearRight -> CropDragHandle.RIGHT
        nearTop -> CropDragHandle.TOP
        nearBottom -> CropDragHandle.BOTTOM
        position.x in shown.left..shown.right && position.y in shown.top..shown.bottom -> CropDragHandle.MOVE
        else -> null
    }
}

private fun formatPercent(value: Int, original: Int): String = "%.1f%%".format(value * 100.0 / original)
