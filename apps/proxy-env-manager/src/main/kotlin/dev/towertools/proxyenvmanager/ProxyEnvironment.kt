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

    const val LOCAL_HTTP_PROXY = "http://127.0.0.1:15236"
    const val LOCAL_ALL_PROXY = "socks5://127.0.0.1:15235"
    const val LOCAL_NO_PROXY = "localhost,127.0.0.1,::1,192.168.31.1,192.168.31.100"

    val names = listOf(HTTP_PROXY, HTTPS_PROXY, ALL_PROXY, NO_PROXY)
}

data class ProxyPresets(
    val httpProxy: String = ProxyVariables.LOCAL_HTTP_PROXY,
    val httpsProxy: String = ProxyVariables.LOCAL_HTTP_PROXY,
    val allProxy: String = ProxyVariables.LOCAL_ALL_PROXY,
    val noProxy: String = ProxyVariables.LOCAL_NO_PROXY,
) {
    fun asMap(): Map<String, String> = linkedMapOf(
        ProxyVariables.HTTP_PROXY to httpProxy,
        ProxyVariables.HTTPS_PROXY to httpsProxy,
        ProxyVariables.ALL_PROXY to allProxy,
        ProxyVariables.NO_PROXY to noProxy,
    )
}
