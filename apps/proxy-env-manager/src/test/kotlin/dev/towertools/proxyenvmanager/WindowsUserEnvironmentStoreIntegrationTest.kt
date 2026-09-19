package dev.towertools.proxyenvmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowsUserEnvironmentStoreIntegrationTest {
    @Test
    fun `writes and removes the real current-user proxy values then restores them`() {
        if (System.getenv("TOWER_RUN_PROXY_ENV_INTEGRATION") != "1") return

        val store = WindowsUserEnvironmentStore
        val configured = ProxyPresets("http://127.0.0.1:8080", "http://127.0.0.1:8080", "socks5://127.0.0.1:1080", "localhost")
        val presetStore = object : ProxyPresetStore {
            override fun read() = configured
            override fun write(presets: ProxyPresets) = error("不应保存测试预设")
        }
        val service = ProxyEnvironmentService(store, presetStore)
        val original = service.read()

        try {
            assertEquals(
                ProxyEnvironment(
                    configured.httpProxy,
                    configured.httpsProxy,
                    configured.allProxy,
                    configured.noProxy,
                ),
                service.setLocalProxy(),
            )
            assertNull(service.clear().httpProxy)
            assertNull(service.read().httpsProxy)
            assertNull(service.read().allProxy)
            assertNull(service.read().noProxy)
        } finally {
            restore(store, ProxyVariables.HTTP_PROXY, original.httpProxy)
            restore(store, ProxyVariables.HTTPS_PROXY, original.httpsProxy)
            restore(store, ProxyVariables.ALL_PROXY, original.allProxy)
            restore(store, ProxyVariables.NO_PROXY, original.noProxy)
        }

        assertEquals(original, service.read())
    }

    private fun restore(store: UserEnvironmentStore, name: String, value: String?) {
        if (value == null) store.delete(name) else store.write(name, value)
    }
}
