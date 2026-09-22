package dev.towertools.resourcetagger

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Test
import kotlin.test.*

class SingleInstanceTest {
    private fun port(instance: SingleInstance): Int {
        val field = SingleInstance::class.java.getDeclaredField("server").apply { isAccessible = true }
        return (field.get(instance) as ServerSocket).localPort
    }

    @Test fun `repeat launch receives acknowledgement and activates exactly once`() {
        val id = "test-${UUID.randomUUID()}"
        assertNotNull(SingleInstance.acquire(id)).use { instance ->
            val count = AtomicInteger()
            instance.onActivate { count.incrementAndGet() }
            assertTrue(SingleInstance.notifyExisting(id))
            assertEquals(1, count.get())
            assertNull(SingleInstance.acquire(id))
            assertEquals(2, count.get())
        }
        // Closing the first instance releases both the lock and listening port.
        assertNotNull(SingleInstance.acquire(id)).close()
    }

    @Test fun `idle connection times out and later activation succeeds while idle client stays open`() {
        val id = "test-${UUID.randomUUID()}"
        assertNotNull(SingleInstance.acquire(id)).use { instance ->
            val count = AtomicInteger()
            instance.onActivate { count.incrementAndGet() }
            Socket(InetAddress.getLoopbackAddress(), port(instance)).use { idle ->
                // No newline: the server must time out rather than wait for this client to close.
                idle.getOutputStream().write('x'.code)
                idle.getOutputStream().flush()
                Socket(InetAddress.getLoopbackAddress(), port(instance)).use { client ->
                    client.soTimeout = 4_000
                    val writer = client.getOutputStream().bufferedWriter(Charsets.UTF_8)
                    writer.write(id); writer.newLine(); writer.flush()
                    assertEquals("OK", client.getInputStream().bufferedReader(Charsets.UTF_8).readLine())
                    assertEquals(1, count.get())
                }
            }
        }
    }

    @Test fun `unresponsive activation server cannot make notifying client wait indefinitely`() {
        val sockets = CopyOnWriteArrayList<Socket>()
        val executor = Executors.newFixedThreadPool(2)
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            try {
                executor.submit {
                    try { while (!server.isClosed) sockets.add(server.accept()) }
                    catch (_: java.net.SocketException) { }
                }
                val result = executor.submit<Boolean> { SingleInstance.notifyExisting("no-reply", server.localPort) }
                assertFalse(result.get(15, TimeUnit.SECONDS))
                assertEquals(8, sockets.size)
            } finally {
                server.close()
                sockets.forEach { it.close() }
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }
}
