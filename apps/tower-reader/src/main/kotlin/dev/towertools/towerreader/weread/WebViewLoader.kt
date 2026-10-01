package dev.towertools.towerreader.weread

import com.sun.jna.NativeLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

internal object WebViewLoader {
    const val version = "1.0.4258.31"
    fun load(cache: Path): NativeLibrary {
        val directory = cache.resolve("webview2-loader-$version")
        Files.createDirectories(directory)
        val bytes = checkNotNull(javaClass.getResourceAsStream("/native/windows-x64/WebView2Loader.dll")) {
            "程序缺少网页组件，请重新构建或完整复制便携目录。"
        }.use { it.readBytes() }
        val target = directory.resolve("WebView2Loader.dll")
        fun digest(data: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))
        if (!Files.isRegularFile(target) || digest(Files.readAllBytes(target)) != digest(bytes)) {
            val temp = Files.createTempFile(directory, "loader-", ".tmp")
            try { Files.write(temp, bytes); Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING) }
            finally { Files.deleteIfExists(temp) }
        }
        return NativeLibrary.getInstance(target.toAbsolutePath().toString())
    }
}
