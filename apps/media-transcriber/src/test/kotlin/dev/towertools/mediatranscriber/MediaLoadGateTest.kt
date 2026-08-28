package dev.towertools.mediatranscriber

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaLoadGateTest {
    private val first = Paths.get("C:/Media/first.mp4")
    private val second = Paths.get("C:/Media/second.mp4")

    @Test fun readyDependenciesProceedWithoutQueueing() {
        val gate = MediaLoadGate()
        assertEquals(MediaLoadDecision.Proceed, gate.request(first, dependenciesReady = true))
        assertEquals(PendingMediaCompletion.None, gate.dependencyCheckCompleted(mediaAvailable = true))
    }

    @Test fun firstRequestWaitsAndRepeatedRequestsDoNotReplaceIt() {
        val gate = MediaLoadGate()
        assertEquals(MediaLoadDecision.Queued, gate.request(first, dependenciesReady = false))
        assertEquals(MediaLoadDecision.Duplicate, gate.request(first, dependenciesReady = false))
        assertEquals(MediaLoadDecision.Occupied, gate.request(second, dependenciesReady = false))
        assertEquals(PendingMediaCompletion.Load(first), gate.dependencyCheckCompleted(mediaAvailable = true))
    }

    @Test fun failedDependencyCheckRejectsAndClearsWaitingRequest() {
        val gate = MediaLoadGate()
        assertEquals(MediaLoadDecision.Queued, gate.request(first, dependenciesReady = false))
        assertEquals(PendingMediaCompletion.Reject(first), gate.dependencyCheckCompleted(mediaAvailable = false))
        assertEquals(MediaLoadDecision.Queued, gate.request(second, dependenciesReady = false))
    }
}
