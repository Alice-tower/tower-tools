package dev.towertools.proxyenvmanager

data class ProxyEnvironment(
    val httpProxy: String?,
    val httpsProxy: String?,
    val allProxy: String?,
    val noProxy: String?,
)

object ProxyVariables {
    const val HTTP_PROXY = "HTTP_PROXY"
    const val HTTPS_PROXY = "HTTPS_PROXY"
    const val ALL_PROXY = "ALL_PROXY"
    const val NO_PROXY = "NO_PROXY"

    val names = listOf(HTTP_PROXY, HTTPS_PROXY, ALL_PROXY, NO_PROXY)
}

data class ProxyPresets(
    val httpProxy: String = "",
    val httpsProxy: String = "",
    val allProxy: String = "",
    val noProxy: String = "",
) {
    fun isConfigured(): Boolean = httpProxy.isNotBlank() && httpsProxy.isNotBlank() && allProxy.isNotBlank()

    fun asMap(): Map<String, String> = linkedMapOf(
        ProxyVariables.HTTP_PROXY to httpProxy,
        ProxyVariables.HTTPS_PROXY to httpsProxy,
        ProxyVariables.ALL_PROXY to allProxy,
        ProxyVariables.NO_PROXY to noProxy,
    )
}
