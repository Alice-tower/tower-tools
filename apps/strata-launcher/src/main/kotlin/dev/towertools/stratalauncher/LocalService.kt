package dev.towertools.stratalauncher

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

enum class PortStatus(val label: String) {
    CHECKING("检测中"), CONNECTED("已连通"), DISCONNECTED("未连通"),
}

object LocalService {
    const val PORT = 7271
    const val WEB_URL = "http://127.0.0.1:$PORT"
    const val API_URL = "$WEB_URL/v1"

    internal fun reachable(port: Int = PORT): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    /** Collection belongs to the window, so switching tabs never restarts the monitor. */
    internal fun observe(port: Int = PORT, intervalMillis: Long = 2_000): Flow<PortStatus> = flow {
        while (currentCoroutineContext().isActive) {
            emit(if (reachable(port)) PortStatus.CONNECTED else PortStatus.DISCONNECTED)
            delay(intervalMillis)
        }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()
}
