package dev.towertools.resourcetagger

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SingleInstance private constructor(
    private val appId: String,
    private val lockChannel: FileChannel,
    private val fileLock: FileLock,
    private val server: ServerSocket,
) : AutoCloseable {
    private val activationHandler = AtomicReference<() -> Unit>({})

    init {
        thread(name = "$appId-activation", isDaemon = true) {
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 1_000
                        val request = socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine()
                        if (request == appId) {
                            activationHandler.get().invoke()
                            socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                                writer.write("OK")
                                writer.newLine()
                            }
                        }
                    }
                }.onFailure {
                    if (!server.isClosed) AppLog.logger.warning("Activation request failed: ${it.message}")
                }
            }
        }
    }

    fun onActivate(handler: () -> Unit) {
        activationHandler.set(handler)
    }

    override fun close() {
        runCatching { server.close() }
        runCatching { fileLock.release() }
        runCatching { lockChannel.close() }
    }

    companion object {
        fun acquire(appId: String): SingleInstance? {
            val lockPath = AppPaths.dataDirectory.resolve("instance.lock")
            val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = runCatching { channel.tryLock() }.getOrNull()

            if (lock == null) {
                channel.close()
                notifyExisting(appId)
                return null
            }

            val server = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getLoopbackAddress(), portFor(appId)))
                }
            }.getOrElse {
                lock.release()
                channel.close()
                throw it
            }

            return SingleInstance(appId, channel, lock, server)
        }

        internal fun notifyExisting(appId: String, port: Int = portFor(appId)): Boolean {
            repeat(8) {
                val acknowledged = runCatching {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 250)
                        socket.soTimeout = 1_000
                        // The socket owns both streams; closing the writer also closes the reply channel.
                        val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
                        writer.write(appId)
                        writer.newLine()
                        writer.flush()
                        socket.getInputStream().bufferedReader(StandardCharsets.UTF_8).readLine() == "OK"
                    }
                }.getOrDefault(false)

                if (acknowledged) return true
                Thread.sleep(100)
            }
            return false
        }

        private fun portFor(appId: String): Int {
            val digest = MessageDigest.getInstance("SHA-256").digest(appId.toByteArray(StandardCharsets.UTF_8))
            val positive = ByteBuffer.wrap(digest.copyOfRange(0, 4)).int and Int.MAX_VALUE
            return 40_000 + positive % 20_000
        }
    }
}
