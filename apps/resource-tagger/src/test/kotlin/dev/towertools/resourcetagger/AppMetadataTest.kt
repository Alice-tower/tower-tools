package dev.towertools.resourcetagger

import kotlin.test.Test
import kotlin.test.assertTrue

class AppMetadataTest {
    @Test
    fun applicationIdUsesTowerToolsNamespace() {
        assertTrue(AppMetadata.id.startsWith("dev.towertools."))
    }
}
