package dev.towertools.imageprocessor

internal enum class CropDragHandle {
    MOVE,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

internal object FineCropGeometry {
    fun adjust(
        start: ImageRect,
        handle: CropDragHandle,
        deltaX: Int,
        deltaY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): ImageRect {
        require(sourceWidth > 0 && sourceHeight > 0)
        if (handle == CropDragHandle.MOVE) {
            return start.copy(
                x = (start.x + deltaX).coerceIn(0, sourceWidth - start.width),
                y = (start.y + deltaY).coerceIn(0, sourceHeight - start.height),
            )
        }

        var left = start.x
        var top = start.y
        var right = start.x + start.width
        var bottom = start.y + start.height

        if (handle in setOf(CropDragHandle.LEFT, CropDragHandle.TOP_LEFT, CropDragHandle.BOTTOM_LEFT)) {
            left = (start.x + deltaX).coerceIn(0, right - 1)
        }
        if (handle in setOf(CropDragHandle.RIGHT, CropDragHandle.TOP_RIGHT, CropDragHandle.BOTTOM_RIGHT)) {
            right = (start.x + start.width + deltaX).coerceIn(left + 1, sourceWidth)
        }
        if (handle in setOf(CropDragHandle.TOP, CropDragHandle.TOP_LEFT, CropDragHandle.TOP_RIGHT)) {
            top = (start.y + deltaY).coerceIn(0, bottom - 1)
        }
        if (handle in setOf(CropDragHandle.BOTTOM, CropDragHandle.BOTTOM_LEFT, CropDragHandle.BOTTOM_RIGHT)) {
            bottom = (start.y + start.height + deltaY).coerceIn(top + 1, sourceHeight)
        }
        return ImageRect(left, top, right - left, bottom - top)
    }
}
