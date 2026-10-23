package dev.towertools.stratalauncher

import kotlin.test.Test
import kotlin.test.assertTrue

class AppMetadataTest {
    @Test
    fun applicationIdUsesTowerToolsNamespace() {
        assertTrue(AppMetadata.id.startsWith("dev.towertools."))
    }
}
