package dev.towertools.launcher.tabs.cmd

/** Fits a path into the measured width while keeping its beginning and filename end. */
internal fun middleEllipsizePath(path: String, maxWidth: Int, measureWidth: (String) -> Int): String {
    if (maxWidth <= 0) return ""
    if (measureWidth(path) <= maxWidth) return path

    val ellipsis = "…"
    if (measureWidth(ellipsis) > maxWidth) return ""

    val codePoints = path.codePointCount(0, path.length)
    var low = 0
    var high = codePoints - 1
    var fitted = ellipsis
    while (low <= high) {
        val kept = (low + high) / 2
        val prefixEnd = path.offsetByCodePoints(0, (kept + 1) / 2)
        val suffixStart = path.offsetByCodePoints(0, codePoints - kept / 2)
        val candidate = path.substring(0, prefixEnd) + ellipsis + path.substring(suffixStart)
        if (measureWidth(candidate) <= maxWidth) {
            fitted = candidate
            low = kept + 1
        } else {
            high = kept - 1
        }
    }
    return fitted
}
