package dev.towertools.proxyenvmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsUserEnvironmentStoreIntegrationTest {
    @Test
    fun `writes and removes the real current-user proxy values then restores them`() {
        if (System.getenv("TOWER_RUN_PROXY_ENV_INTEGRATION") != "1") return

        val store = WindowsUserEnvironmentStore
        val service = ProxyEnvironmentService(store)
        val original = service.read()

        try {
            assertEquals(
                ProxyEnvironment(ProxyVariables.LOCAL_PROXY, ProxyVariables.LOCAL_PROXY),
                service.setLocalProxy(),
            )
            assertNull(service.clear().httpProxy)
            assertNull(service.read().httpsProxy)
        } finally {
            restore(store, ProxyVariables.HTTP_PROXY, original.httpProxy)
            restore(store, ProxyVariables.HTTPS_PROXY, original.httpsProxy)
            store.broadcastChange()
        }

        assertEquals(original, service.read())
    }

    private fun restore(store: UserEnvironmentStore, name: String, value: String?) {
        if (value == null) store.delete(name) else store.write(name, value)
    }
}
