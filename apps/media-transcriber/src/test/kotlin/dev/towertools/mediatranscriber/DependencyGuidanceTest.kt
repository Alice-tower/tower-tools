package dev.towertools.mediatranscriber

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyGuidanceTest {
    @Test fun providesWindowsDownloadsAndMultilingualModelTiers() {
        assertTrue(DependencyGuidance.ffmpegDownloadUrl.startsWith("https://"))
        assertTrue(DependencyGuidance.whisperCliDownloadUrl.contains("releases/latest"))
        assertTrue(DependencyGuidance.modelDownloadUrl.contains("huggingface.co"))
        assertEquals(listOf("Tiny", "Base", "Small", "Medium", "Large V3 Turbo", "Large V3"), DependencyGuidance.models.map { it.model })
        assertTrue(DependencyGuidance.models.none { it.model.endsWith(".en") })
    }
}
