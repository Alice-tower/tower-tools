package dev.towertools.towerreader.weread

import java.net.URI

object WeReadLocation {
    const val shelf = "https://weread.qq.com/web/shelf"
    fun restorable(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.host == "weread.qq.com" && uri.userInfo == null &&
            uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.path == "/web/shelf" || uri.path.startsWith("/web/reader/"))
    }.getOrDefault(false)
    fun restore(value: String) = value.takeIf(::restorable) ?: shelf
    fun allowed(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 &&
            (uri.host == "qq.com" || uri.host?.endsWith(".qq.com") == true ||
                uri.host == "weixin.com" || uri.host?.endsWith(".weixin.com") == true)
    }.getOrDefault(false)
}
