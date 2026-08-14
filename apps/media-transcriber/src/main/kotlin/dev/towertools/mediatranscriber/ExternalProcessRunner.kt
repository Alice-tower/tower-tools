package dev.towertools.mediatranscriber

import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ProcessRequest(val command: List<String>, val workingDirectory: Path)
data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String, val cancelled: Boolean)

class CancellationHandle {
    private val cancelled = AtomicBoolean(false)
    private val process = AtomicReference<Process?>()

    fun attach(value: Process) {
        process.set(value)
        if (cancelled.get()) terminate(value)
    }

    fun detach(value: Process) = process.compareAndSet(value, null)
    fun isCancelled(): Boolean = cancelled.get()

    fun cancel() {
        cancelled.set(true)
        process.get()?.let(::terminate)
    }

    private fun terminate(value: Process) {
        value.descendants().forEach { it.destroy() }
        value.destroy()
        if (!value.waitFor(750, TimeUnit.MILLISECONDS)) {
            value.descendants().forEach { if (it.isAlive) it.destroyForcibly() }
            value.destroyForcibly()
        }
    }
}

interface ProcessRunner {
    fun run(
        request: ProcessRequest,
        cancellation: CancellationHandle = CancellationHandle(),
        onStdoutLine: (String) -> Unit = {},
        onStderrLine: (String) -> Unit = {},
    ): ProcessResult
}

class ExternalProcessRunner(private val technicalLog: (String) -> Unit = {}) : ProcessRunner {
    override fun run(
        request: ProcessRequest,
        cancellation: CancellationHandle,
        onStdoutLine: (String) -> Unit,
        onStderrLine: (String) -> Unit,
    ): ProcessResult {
        require(request.command.isNotEmpty())
        val process = ProcessBuilder(request.command)
            .directory(request.workingDirectory.toFile())
            .redirectErrorStream(false)
            .start()
        cancellation.attach(process)
        val pool = Executors.newFixedThreadPool(2)
        val out = StringBuilder()
        val err = StringBuilder()
        fun collect(line: String, target: StringBuilder, callback: (String) -> Unit) {
            synchronized(target) {
                if (target.length < 262_144) target.appendLine(line)
            }
            callback(line)
        }
        val stdout = pool.submit { process.inputStream.bufferedReader().useLines { it.forEach { line -> collect(line, out, onStdoutLine) } } }
        val stderr = pool.submit { process.errorStream.bufferedReader().useLines { it.forEach { line -> collect(line, err, onStderrLine) } } }
        return try {
            val exit = process.waitFor()
            stdout.get()
            stderr.get()
            val stdoutText = out.toString()
            val stderrText = err.toString()
            if (stdoutText.isNotBlank()) technicalLog("外部进程 stdout（截断）：\n${stdoutText.takeLast(16_384)}")
            if (stderrText.isNotBlank()) technicalLog("外部进程 stderr（截断）：\n${stderrText.takeLast(16_384)}")
            ProcessResult(exit, stdoutText, stderrText, cancellation.isCancelled())
        } finally {
            cancellation.detach(process)
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
}
