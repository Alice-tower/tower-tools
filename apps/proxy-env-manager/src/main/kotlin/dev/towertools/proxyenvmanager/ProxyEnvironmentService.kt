package dev.towertools.proxyenvmanager

class ProxyEnvironmentService(
    private val store: UserEnvironmentStore = WindowsUserEnvironmentStore,
) {
    fun read(): ProxyEnvironment = ProxyEnvironment(
        httpProxy = store.read(ProxyVariables.HTTP_PROXY),
        httpsProxy = store.read(ProxyVariables.HTTPS_PROXY),
    )

    fun setLocalProxy(): ProxyEnvironment = changeBoth { name ->
        store.write(name, ProxyVariables.LOCAL_PROXY)
    }

    fun clear(): ProxyEnvironment = changeBoth(store::delete)

    private fun changeBoth(change: (String) -> Unit): ProxyEnvironment {
        val previous = read()
        try {
            change(ProxyVariables.HTTP_PROXY)
            change(ProxyVariables.HTTPS_PROXY)
            store.broadcastChange()
            return read()
        } catch (failure: Throwable) {
            runCatching {
                restore(ProxyVariables.HTTP_PROXY, previous.httpProxy)
                restore(ProxyVariables.HTTPS_PROXY, previous.httpsProxy)
                store.broadcastChange()
            }.onFailure { rollbackFailure -> failure.addSuppressed(rollbackFailure) }
            throw failure
        }
    }

    private fun restore(name: String, value: String?) {
        if (value == null) store.delete(name) else store.write(name, value)
    }
}
