package dev.towertools.proxyenvmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ProxyEnvironmentServiceTest {
    @Test
    fun setsBothVariablesToLocalProxyAndBroadcastsOnce() {
        val store = FakeStore()
        val result = ProxyEnvironmentService(store).setLocalProxy()

        assertEquals(ProxyVariables.LOCAL_PROXY, result.httpProxy)
        assertEquals(ProxyVariables.LOCAL_PROXY, result.httpsProxy)
        assertEquals(1, store.broadcasts)
    }

    @Test
    fun clearsBothVariables() {
        val store = FakeStore(
            ProxyVariables.HTTP_PROXY to "http://old-http",
            ProxyVariables.HTTPS_PROXY to "http://old-https",
        )
        val result = ProxyEnvironmentService(store).clear()

        assertNull(result.httpProxy)
        assertNull(result.httpsProxy)
        assertEquals(1, store.broadcasts)
    }

    @Test
    fun restoresOriginalValuesWhenSecondWriteFails() {
        val store = FakeStore(
            ProxyVariables.HTTP_PROXY to "http://old-http",
            ProxyVariables.HTTPS_PROXY to "http://old-https",
        ).apply { failingName = ProxyVariables.HTTPS_PROXY }

        assertFailsWith<IllegalStateException> { ProxyEnvironmentService(store).setLocalProxy() }
        assertEquals("http://old-http", store.values[ProxyVariables.HTTP_PROXY])
        assertEquals("http://old-https", store.values[ProxyVariables.HTTPS_PROXY])
        assertTrue(store.broadcasts >= 1)
    }

    private class FakeStore(vararg initial: Pair<String, String>) : UserEnvironmentStore {
        val values = initial.toMap().toMutableMap()
        var broadcasts = 0
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

        override fun broadcastChange() {
            broadcasts++
        }
    }
}
