package dev.towertools.resourcetagger

import java.util.concurrent.CancellationException

data class ScanProgress(val bucket: String = "读取 Root", val count: Int = 0, val committing: Boolean = false, val cancelling: Boolean = false)

/** Cancellation is cooperative: an outstanding filesystem call must return first. */
class ScanControl {
    val startedAt = System.nanoTime()
    @Volatile var progress = ScanProgress(); private set
    @Synchronized fun cancel() { if (!progress.committing) progress = progress.copy(cancelling = true) }
    fun check() { if (progress.cancelling) throw CancellationException("扫描已取消，原有数据已保留。") }
    @Synchronized fun report(bucket: String, count: Int) {
        check()
        progress = progress.copy(bucket = bucket, count = count)
    }
    @Synchronized fun beginCommit() {
        check()
        progress = progress.copy(committing = true)
    }
}
