package dev.towertools.proxyenvmanager

class ProxyEnvironmentService(
    private val store: UserEnvironmentStore = WindowsUserEnvironmentStore,
    private val presetStore: ProxyPresetStore = FileProxyPresetStore(AppPaths.proxyPresetsFile),
) {
    fun presets(): ProxyPresets = presetStore.read()

    fun savePresets(presets: ProxyPresets): ProxyPresets {
        require(presets.httpProxy.isNotBlank()) { "HTTP_PROXY 预设不能为空" }
        require(presets.httpsProxy.isNotBlank()) { "HTTPS_PROXY 预设不能为空" }
        require(presets.allProxy.isNotBlank()) { "ALL_PROXY 预设不能为空" }
        presetStore.write(presets)
        return presets
    }

    fun read(): ProxyEnvironment = ProxyEnvironment(
        httpProxy = store.read(ProxyVariables.HTTP_PROXY),
        httpsProxy = store.read(ProxyVariables.HTTPS_PROXY),
        allProxy = store.read(ProxyVariables.ALL_PROXY),
        noProxy = store.read(ProxyVariables.NO_PROXY),
    )

    fun setLocalProxy(): ProxyEnvironment {
        val configured = presets()
        require(configured.isConfigured()) { "请先设置 HTTP_PROXY、HTTPS_PROXY 和 ALL_PROXY 预设" }
        val values = configured.asMap()
        return changeAll { name -> store.write(name, values.getValue(name)) }
    }

    fun clear(): ProxyEnvironment = changeAll(store::delete)

    fun applyPreset(name: String): ProxyEnvironment {
        require(name in ProxyVariables.names) { "不支持的代理环境变量：$name" }
        val configured = presets()
        require(configured.isConfigured()) { "请先设置 HTTP_PROXY、HTTPS_PROXY 和 ALL_PROXY 预设" }
        return changeOne(name) { store.write(name, configured.asMap().getValue(name)) }
    }

    fun clear(name: String): ProxyEnvironment = changeOne(name) { store.delete(name) }

    private fun changeOne(name: String, change: () -> Unit): ProxyEnvironment {
        require(name in ProxyVariables.names) { "不支持的代理环境变量：$name" }
        val previous = store.read(name)
        try {
            change()
            return read()
        } catch (failure: Throwable) {
            runCatching { restore(name, previous) }
                .onFailure { rollbackFailure -> failure.addSuppressed(rollbackFailure) }
            throw failure
        }
    }

    private fun changeAll(change: (String) -> Unit): ProxyEnvironment {
        val previous = read()
        try {
            ProxyVariables.names.forEach(change)
            return read()
        } catch (failure: Throwable) {
            runCatching {
                restore(ProxyVariables.HTTP_PROXY, previous.httpProxy)
                restore(ProxyVariables.HTTPS_PROXY, previous.httpsProxy)
                restore(ProxyVariables.ALL_PROXY, previous.allProxy)
                restore(ProxyVariables.NO_PROXY, previous.noProxy)
            }.onFailure { rollbackFailure -> failure.addSuppressed(rollbackFailure) }
            throw failure
        }
    }

    private fun restore(name: String, value: String?) {
        if (value == null) store.delete(name) else store.write(name, value)
    }
}
