package dev.towertools.proxyenvmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProxyEnvironmentServiceTest {
    @Test
    fun setsAllVariablesToPresets() {
        val store = FakeStore()
        val result = ProxyEnvironmentService(store).setLocalProxy()

        assertEquals(ProxyVariables.LOCAL_HTTP_PROXY, result.httpProxy)
        assertEquals(ProxyVariables.LOCAL_HTTP_PROXY, result.httpsProxy)
        assertEquals(ProxyVariables.LOCAL_ALL_PROXY, result.allProxy)
        assertEquals(ProxyVariables.LOCAL_NO_PROXY, result.noProxy)
    }

    @Test
    fun clearsAllVariables() {
        val store = FakeStore(
            ProxyVariables.HTTP_PROXY to "http://old-http",
            ProxyVariables.HTTPS_PROXY to "http://old-https",
            ProxyVariables.ALL_PROXY to "socks5://old-all",
            ProxyVariables.NO_PROXY to "old-no-proxy",
        )
        val result = ProxyEnvironmentService(store).clear()

        assertNull(result.httpProxy)
        assertNull(result.httpsProxy)
        assertNull(result.allProxy)
        assertNull(result.noProxy)
    }

    @Test
    fun restoresOriginalValuesWhenLaterWriteFails() {
        val store = FakeStore(
            ProxyVariables.HTTP_PROXY to "http://old-http",
            ProxyVariables.HTTPS_PROXY to "http://old-https",
            ProxyVariables.ALL_PROXY to "socks5://old-all",
            ProxyVariables.NO_PROXY to "old-no-proxy",
        ).apply { failingName = ProxyVariables.NO_PROXY }

        assertFailsWith<IllegalStateException> { ProxyEnvironmentService(store).setLocalProxy() }
        assertEquals("http://old-http", store.values[ProxyVariables.HTTP_PROXY])
        assertEquals("http://old-https", store.values[ProxyVariables.HTTPS_PROXY])
        assertEquals("socks5://old-all", store.values[ProxyVariables.ALL_PROXY])
        assertEquals("old-no-proxy", store.values[ProxyVariables.NO_PROXY])
    }

    private class FakeStore(vararg initial: Pair<String, String>) : UserEnvironmentStore {
        val values = initial.toMap().toMutableMap()
        var failingName: String? = null
        private var failureConsumed = false

        override fun read(name: String): String? = values[name]

        override fun write(name: String, value: String) {
            if (name == failingName && !failureConsumed) {
                failureConsumed = true
                throw IllegalStateException("simulated write failure")
            }
            values[name] = value
        }

        override fun delete(name: String) {
            values.remove(name)
        }
    }
}
