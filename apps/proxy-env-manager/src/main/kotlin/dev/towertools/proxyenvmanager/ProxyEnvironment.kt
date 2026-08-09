package dev.towertools.proxyenvmanager

data class ProxyEnvironment(
    val httpProxy: String?,
    val httpsProxy: String?,
)

object ProxyVariables {
    const val HTTP_PROXY = "HTTP_PROXY"
    const val HTTPS_PROXY = "HTTPS_PROXY"
    const val LOCAL_PROXY = "http://127.0.0.1:15236"
}
