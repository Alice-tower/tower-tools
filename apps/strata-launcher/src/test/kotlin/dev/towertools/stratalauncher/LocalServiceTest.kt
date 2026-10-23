package dev.towertools.stratalauncher

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class LocalServiceTest {
    private fun listener() = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }

    @Test fun probeDetectsListeningAndClosedPorts() {
        val server = listener()
        val port = server.localPort
        server.use { assertTrue(LocalService.reachable(port)) }
        assertFalse(LocalService.reachable(port))
    }

    @Test fun observationUpdatesAfterListenerStopsAndCollectionCanEnd() = runBlocking {
        listener().use { server ->
            val statuses = mutableListOf<PortStatus>()
            withTimeout(3_000) {
                LocalService.observe(server.localPort, intervalMillis = 5).take(2).collect { status ->
                    statuses += status
                    if (status == PortStatus.CONNECTED) server.close()
                }
            }
            assertEquals(listOf(PortStatus.CONNECTED, PortStatus.DISCONNECTED), statuses)
        }
    }
}
