package dev.towertools.towerreader

data class ReaderSettings(
    val theme: String = "paper",
    val fontFamily: String = "Microsoft YaHei",
    val fontSize: Int = 24,
    val lineHeight: Double = 1.8,
    val margin: Int = 40,
    val spread: String = "auto",
    val pdfZoom: Double = 1.0,
    val pdfDark: Boolean = false,
) {
    val background: String get() = if (theme == "dark") "#202329" else "#f4eedf"
    val foreground: String get() = if (theme == "dark") "#d5d5d0" else "#34382f"
    fun scriptObject(): String = "{theme:" + jsString(theme) + ",font:" + jsString(fontFamily) +
        ",size:" + fontSize + ",line:" + lineHeight + ",margin:" + margin +
        ",spread:" + jsString(spread) + ",background:" + jsString(background) + ",foreground:" + jsString(foreground) + "}"
}

fun jsString(value: String): String = buildString {
    append('"')
    value.forEach { c -> when (c) {
        '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n")
        '\r' -> append("\\r"); '\t' -> append("\\t"); '<' -> append("\\u003c")
        '\u2028' -> append("\\u2028"); '\u2029' -> append("\\u2029")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    } }
    append('"')
}
