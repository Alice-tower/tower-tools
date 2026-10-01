package dev.towertools.towerreader

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class SingleInstanceTest {
    @Test fun eachAdditionalLaunchActivatesTheExistingWindowExactlyOnce() {
        // Gradle gives tests an isolated LOCALAPPDATA; never use the running user's instance lock.
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            val id="dev.towertools.reader-test.${UUID.randomUUID()}"
            val activations=AtomicInteger()
            assertNotNull(SingleInstance.acquire(id)).use { first ->
                first.onActivate { activations.incrementAndGet() }
                repeat(3) { index ->
                    assertNull(SingleInstance.acquire(id))
                    assertEquals(index+1,activations.get(),"A successful activation must stop retrying")
                }
            }
            assertNotNull(SingleInstance.acquire(id)).close()
        }
    }
}