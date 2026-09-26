package dev.towertools.resourcetagger

import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.test.*

class ScanControlTest {
    @Test fun `cancel prevents commit but cannot interrupt an accepted commit`() {
        val cancelled = ScanControl()
        cancelled.report("bucket-000001", 100)
        cancelled.cancel()
        assertFailsWith<CancellationException> { cancelled.beginCommit() }
        assertEquals(100, cancelled.progress.count)
        val committing = ScanControl()
        committing.beginCommit()
        committing.cancel()
        committing.check()
        assertTrue(committing.progress.committing)
        assertFalse(committing.progress.cancelling)
    }
}
