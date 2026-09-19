package dev.towertools.proxyenvmanager

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

interface ProxyPresetStore {
    fun read(): ProxyPresets
    fun write(presets: ProxyPresets)
}

class FileProxyPresetStore(private val path: Path) : ProxyPresetStore {
    override fun read(): ProxyPresets {
        if (!Files.exists(path)) return ProxyPresets()
        val properties = Properties()
        Files.newInputStream(path).use(properties::load)
        return ProxyPresets(
            httpProxy = properties.getProperty(ProxyVariables.HTTP_PROXY)
                ?: error("预设文件缺少 HTTP_PROXY"),
            httpsProxy = properties.getProperty(ProxyVariables.HTTPS_PROXY)
                ?: error("预设文件缺少 HTTPS_PROXY"),
            allProxy = properties.getProperty(ProxyVariables.ALL_PROXY)
                ?: error("预设文件缺少 ALL_PROXY"),
            noProxy = properties.getProperty(ProxyVariables.NO_PROXY)
                ?: error("预设文件缺少 NO_PROXY"),
        )
    }

    override fun write(presets: ProxyPresets) {
        val directory = path.toAbsolutePath().parent
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "proxy-presets-", ".tmp")
        try {
            val properties = Properties()
            presets.asMap().forEach(properties::setProperty)
            Files.newOutputStream(temporary).use { properties.store(it, "ProxyEnvManager presets") }
            Files.move(
                temporary, path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
