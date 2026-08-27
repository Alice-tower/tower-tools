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
    const val LOCAL_NO_PROXY = "localhost,127.0.0.1,192.168.31.0/24"

    val presets = linkedMapOf(
        HTTP_PROXY to LOCAL_HTTP_PROXY,
        HTTPS_PROXY to LOCAL_HTTP_PROXY,
        ALL_PROXY to LOCAL_ALL_PROXY,
        NO_PROXY to LOCAL_NO_PROXY,
    )
}
