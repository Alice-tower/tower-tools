package dev.towertools.launcher

import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SingleInstanceTest {
    @Test
    fun activationReadsAcknowledgementBeforeClosingConnection() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 3_000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val received = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                        socket.getOutputStream().write("OK\n".toByteArray(StandardCharsets.UTF_8))
                        received
                    }
                }
                assertTrue(SingleInstance.notifyExisting("test.launcher", server.localPort))
                assertEquals("test.launcher", request.get(3, TimeUnit.SECONDS))
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
