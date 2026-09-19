package dev.towertools.proxyenvmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import java.nio.file.Files

class ProxyEnvironmentServiceTest {
    @Test
    fun setsAllVariablesToConfiguredPresets() {
        val store = FakeStore()
        val configured = ProxyPresets("http://localhost:8080", "http://localhost:8080", "socks5://localhost:1080", "localhost")
        val result = ProxyEnvironmentService(store, FakePresetStore(configured)).setLocalProxy()

        assertEquals(configured.httpProxy, result.httpProxy)
        assertEquals(configured.httpsProxy, result.httpsProxy)
        assertEquals(configured.allProxy, result.allProxy)
        assertEquals(configured.noProxy, result.noProxy)
    }

    @Test
    fun unconfiguredPresetsCannotChangeEnvironment() {
        val store = FakeStore(ProxyVariables.HTTP_PROXY to "http://existing")
        assertFailsWith<IllegalArgumentException> {
            ProxyEnvironmentService(store, FakePresetStore()).setLocalProxy()
        }
        assertEquals(mapOf(ProxyVariables.HTTP_PROXY to "http://existing"), store.values)
    }

    @Test
    fun clearsAllVariables() {
        val store = FakeStore(
            ProxyVariables.HTTP_PROXY to "http://old-http",
            ProxyVariables.HTTPS_PROXY to "http://old-https",
            ProxyVariables.ALL_PROXY to "socks5://old-all",
            ProxyVariables.NO_PROXY to "old-no-proxy",
        )
        val result = ProxyEnvironmentService(store, FakePresetStore()).clear()

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

        val configured = ProxyPresets("http://localhost:8080", "http://localhost:8080", "socks5://localhost:1080", "localhost")
        assertFailsWith<IllegalStateException> { ProxyEnvironmentService(store, FakePresetStore(configured)).setLocalProxy() }
        assertEquals("http://old-http", store.values[ProxyVariables.HTTP_PROXY])
        assertEquals("http://old-https", store.values[ProxyVariables.HTTPS_PROXY])
        assertEquals("socks5://old-all", store.values[ProxyVariables.ALL_PROXY])
        assertEquals("old-no-proxy", store.values[ProxyVariables.NO_PROXY])
    }

    @Test
    fun savedPresetsAreUsedWhenApplyingEnvironment() {
        val store = FakeStore()
        val presetStore = FakePresetStore()
        val service = ProxyEnvironmentService(store, presetStore)
        val edited = ProxyPresets("http://localhost:8080", "http://localhost:8081", "socks5://localhost:1080", "")

        service.savePresets(edited)
        assertEquals(edited, service.presets())
        val result = service.setLocalProxy()
        assertEquals(edited.httpProxy, result.httpProxy)
        assertEquals(edited.httpsProxy, result.httpsProxy)
        assertEquals(edited.allProxy, result.allProxy)
        assertEquals(edited.noProxy, result.noProxy)
    }

    @Test
    fun blankProxyPresetIsRejectedWithoutSaving() {
        val presetStore = FakePresetStore()
        val service = ProxyEnvironmentService(FakeStore(), presetStore)

        assertFailsWith<IllegalArgumentException> {
            service.savePresets(ProxyPresets(httpProxy = " "))
        }
        assertEquals(ProxyPresets(), presetStore.read())
    }

    @Test
    fun filePresetsSurviveStoreRecreation() {
        val directory = Files.createTempDirectory("proxy-presets-test-")
        try {
            val path = directory.resolve("presets.properties")
            val edited = ProxyPresets("http://localhost:8080", "http://localhost:8081", "socks5://localhost:1080", "localhost,测试")
            assertEquals(ProxyPresets(), FileProxyPresetStore(path).read())
            FileProxyPresetStore(path).write(edited)
            assertEquals(edited, FileProxyPresetStore(path).read())
        } finally {
            Files.deleteIfExists(directory.resolve("presets.properties"))
            Files.deleteIfExists(directory)
        }
    }

    private class FakePresetStore(var value: ProxyPresets = ProxyPresets()) : ProxyPresetStore {
        override fun read(): ProxyPresets = value
        override fun write(presets: ProxyPresets) { value = presets }
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
