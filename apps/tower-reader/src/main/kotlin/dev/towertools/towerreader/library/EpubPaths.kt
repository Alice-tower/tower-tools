package dev.towertools.towerreader.library

import java.net.URLDecoder
import java.nio.file.Path

/** Resolve an EPUB URI within the archive, never an OS path or an external URL. */
internal fun resolveEpubResource(base: String, href: String): String? = runCatching {
    if (href.contains(':') || href.startsWith('/') || href.startsWith("\\")) return null
    val clean = URLDecoder.decode(href.substringBefore('#').substringBefore('?').replace("+", "%2B"), "UTF-8")
    if (clean.contains(':') || clean.startsWith('/') || clean.contains('\\')) return null
    if (clean.isEmpty()) return base
    val path = (Path.of(base).parent?.resolve(clean) ?: Path.of(clean)).normalize().toString().replace('\\', '/')
    path.takeIf { it != ".." && !it.startsWith("../") }
}.getOrNull()