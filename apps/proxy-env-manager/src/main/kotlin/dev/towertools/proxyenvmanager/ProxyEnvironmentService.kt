package dev.towertools.proxyenvmanager

class ProxyEnvironmentService(
    private val store: UserEnvironmentStore = WindowsUserEnvironmentStore,
) {
    fun read(): ProxyEnvironment = ProxyEnvironment(
        httpProxy = store.read(ProxyVariables.HTTP_PROXY),
        httpsProxy = store.read(ProxyVariables.HTTPS_PROXY),
        allProxy = store.read(ProxyVariables.ALL_PROXY),
        noProxy = store.read(ProxyVariables.NO_PROXY),
    )

    fun setLocalProxy(): ProxyEnvironment = changeAll { name ->
        store.write(name, ProxyVariables.presets.getValue(name))
    }

    fun clear(): ProxyEnvironment = changeAll(store::delete)

    private fun changeAll(change: (String) -> Unit): ProxyEnvironment {
        val previous = read()
        try {
            ProxyVariables.presets.keys.forEach(change)
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
