package dev.towertools.imageprocessor

import kotlin.test.Test
import kotlin.test.assertTrue

class AppMetadataTest {
    @Test
    fun applicationIdUsesTowerToolsNamespace() {
        assertTrue(AppMetadata.id.startsWith("dev.towertools."))
    }
}
