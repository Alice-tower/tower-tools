package dev.towertools.mediatranscriber

import java.nio.file.Path

internal sealed interface MediaLoadDecision {
    data object Proceed : MediaLoadDecision
    data object Queued : MediaLoadDecision
    data object Duplicate : MediaLoadDecision
    data object Occupied : MediaLoadDecision
}

internal sealed interface PendingMediaCompletion {
    data object None : PendingMediaCompletion
    data class Load(val path: Path) : PendingMediaCompletion
    data class Reject(val path: Path) : PendingMediaCompletion
}

internal class MediaLoadGate {
    private var pending: Path? = null

    fun request(path: Path, dependenciesReady: Boolean): MediaLoadDecision {
        if (dependenciesReady) return MediaLoadDecision.Proceed
        val waiting = pending
        return when {
            waiting == null -> {
                pending = path
                MediaLoadDecision.Queued
            }
            waiting == path -> MediaLoadDecision.Duplicate
            else -> MediaLoadDecision.Occupied
        }
    }

    fun dependencyCheckCompleted(mediaAvailable: Boolean): PendingMediaCompletion {
        val waiting = pending ?: return PendingMediaCompletion.None
        pending = null
        return if (mediaAvailable) PendingMediaCompletion.Load(waiting) else PendingMediaCompletion.Reject(waiting)
    }
}
