package dev.towertools.proxyenvmanager

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProxyEnvironmentDraftTest {
    private val original = ProxyEnvironment("http://old", "http://secure", "socks5://old", "localhost")
    private val presets = ProxyPresets("http://new", "http://new-secure", "socks5://new", "")

    @Test fun mixedEditsAndUndoRemainInMemoryUntilSave() {
        val store = EditingTestStore(original)
        val service = ProxyEnvironmentService(store, EditingTestPresets(presets))
        val initial = ProxyEnvironmentDraft(service.read())
        val edits = initial.usePreset(ProxyVariables.HTTP_PROXY, presets).clear(ProxyVariables.HTTPS_PROXY)
        assertEquals(original, service.read())
        assertTrue(store.mutations.isEmpty())
        assertEquals(mapOf(ProxyVariables.HTTP_PROXY to "http://new", ProxyVariables.HTTPS_PROXY to null), edits.changes)
        val undo = edits.undo(ProxyVariables.HTTP_PROXY)
        assertEquals(mapOf(ProxyVariables.HTTPS_PROXY to null), undo.changes)
        assertTrue(undo.discard().changes.isEmpty())
        val result = service.saveChanges(edits)
        assertEquals(original.copy(httpProxy = "http://new", httpsProxy = null), result)
        assertEquals(listOf(ProxyVariables.HTTP_PROXY, ProxyVariables.HTTPS_PROXY), store.mutations)
        assertTrue(ProxyEnvironmentDraft(result).changes.isEmpty())
    }

    @Test fun returningToOriginalValueRemovesPendingChangeAndEmptyIsDistinctFromDeletion() {
        val initial = ProxyEnvironmentDraft(original)
        val same = presets.copy(httpProxy = original.httpProxy!!)
        assertTrue(initial.clear(ProxyVariables.HTTP_PROXY).usePreset(ProxyVariables.HTTP_PROXY, same).changes.isEmpty())
        val absent = ProxyEnvironmentDraft(original.copy(noProxy = null))
        assertEquals(mapOf(ProxyVariables.NO_PROXY to ""), absent.usePreset(ProxyVariables.NO_PROXY, presets).changes)
        assertTrue(absent.usePreset(ProxyVariables.NO_PROXY, presets).clear(ProxyVariables.NO_PROXY).changes.isEmpty())
        assertEquals(mapOf(ProxyVariables.NO_PROXY to null), ProxyEnvironmentDraft(original.copy(noProxy = "")).clear(ProxyVariables.NO_PROXY).changes)
    }

    @Test fun refreshRetainsOnlyEditedTargetsOrDiscardsEverything() {
        val edits = ProxyEnvironmentDraft(original).usePreset(ProxyVariables.HTTP_PROXY, presets)
        val latest = original.copy(httpProxy = "http://external", noProxy = "external-host")
        val retained = edits.refresh(latest, true)
        assertEquals(latest, retained.current)
        assertEquals("http://new", retained.proposed.httpProxy)
        assertEquals("external-host", retained.proposed.noProxy)
        assertEquals(mapOf(ProxyVariables.HTTP_PROXY to "http://new"), retained.changes)
        assertEquals(ProxyEnvironmentDraft(latest), edits.refresh(latest, false))
        assertTrue(edits.refresh(latest.copy(httpProxy = "http://new"), true).changes.isEmpty())
    }

    @Test fun savedPresetChangesDoNotAlterPreparedValues() {
        val presetStore = EditingTestPresets(presets)
        val store = EditingTestStore(original)
        val service = ProxyEnvironmentService(store, presetStore)
        val edits = ProxyEnvironmentDraft(service.read()).useAllPresets(service.presets())
        service.savePresets(presets.copy(httpProxy = "http://later"))
        assertTrue(store.mutations.isEmpty())
        assertEquals("http://new", service.saveChanges(edits).httpProxy)
        assertEquals("http://later", service.presets().httpProxy)
    }

    @Test fun externalChangesToAnEditedTargetRejectTheWholeSaveWithoutWrites() {
        val store = EditingTestStore(original)
        val service = ProxyEnvironmentService(store, EditingTestPresets(presets))
        val edits = ProxyEnvironmentDraft(service.read()).useAllPresets(presets)
        store.values[ProxyVariables.HTTPS_PROXY] = "http://external"
        val failure = assertFailsWith<ProxyEnvironmentConflict> { service.saveChanges(edits) }
        assertEquals(listOf(ProxyVariables.HTTPS_PROXY), failure.names)
        assertEquals("http://external", failure.latest.httpsProxy)
        assertTrue(store.mutations.isEmpty())
        assertEquals("http://old", store.values[ProxyVariables.HTTP_PROXY])
    }

    @Test fun externalChangesToUntouchedVariablesArePreservedAndNoOpDoesNotWrite() {
        val store = EditingTestStore(original)
        val service = ProxyEnvironmentService(store, EditingTestPresets(presets))
        val edits = ProxyEnvironmentDraft(service.read()).usePreset(ProxyVariables.HTTP_PROXY, presets)
        store.values[ProxyVariables.NO_PROXY] = "external-host"
        assertEquals(original.copy(httpProxy = "http://new", noProxy = "external-host"), service.saveChanges(edits))
        assertEquals(listOf(ProxyVariables.HTTP_PROXY), store.mutations)
        store.mutations.clear()
        service.saveChanges(ProxyEnvironmentDraft(service.read()))
        assertTrue(store.mutations.isEmpty())
    }

    @Test fun failedMixedSaveRestoresEveryAttemptedValueAndCanBeRetried() {
        for (previous in listOf(null, "", "http://old")) {
            val before = original.copy(httpProxy = previous)
            val store = EditingTestStore(before).apply { failOnce = ProxyVariables.HTTPS_PROXY }
            val service = ProxyEnvironmentService(store, EditingTestPresets(presets))
            val edits = ProxyEnvironmentDraft(before).usePreset(ProxyVariables.HTTP_PROXY, presets).clear(ProxyVariables.HTTPS_PROXY)
            assertFailsWith<ProxyEnvironmentSaveFailure> { service.saveChanges(edits) }
            assertEquals(before, service.read())
            assertEquals(listOf(ProxyVariables.HTTP_PROXY, ProxyVariables.HTTPS_PROXY, ProxyVariables.HTTPS_PROXY, ProxyVariables.HTTP_PROXY), store.mutations)
            assertEquals(before.copy(httpProxy = "http://new", httpsProxy = null), service.saveChanges(edits))
        }
    }

    @Test fun rollbackContinuesAfterOneRestoreFailsAndReportsThatVariable() {
        val store = EditingTestStore(original).apply {
            failOnce = ProxyVariables.ALL_PROXY
            failRestore = ProxyVariables.HTTPS_PROXY
        }
        val service = ProxyEnvironmentService(store, EditingTestPresets(presets))
        val edits = ProxyEnvironmentDraft(original).useAllPresets(presets)
        val failure = assertFailsWith<ProxyEnvironmentSaveFailure> { service.saveChanges(edits) }
        assertTrue(failure.message!!.contains(ProxyVariables.HTTPS_PROXY))
        assertEquals(listOf(ProxyVariables.HTTPS_PROXY), failure.rollbackFailures)
        assertEquals(original.copy(httpsProxy = presets.httpsProxy), failure.latest)
        assertEquals(original.httpProxy, store.values[ProxyVariables.HTTP_PROXY])
        assertEquals(original.allProxy, store.values[ProxyVariables.ALL_PROXY])
        assertEquals(original.noProxy, store.values[ProxyVariables.NO_PROXY])
        assertEquals("http://new-secure", store.values[ProxyVariables.HTTPS_PROXY])
    }

    @Test fun incompleteRollbackStillReportsFailedVariablesWhenLatestReadFails() {
        val store = EditingTestStore(original).apply {
            failOnce = ProxyVariables.ALL_PROXY
            failRestore = ProxyVariables.HTTPS_PROXY
            failReadAfterFailure = ProxyVariables.NO_PROXY
        }
        val service = ProxyEnvironmentService(store, EditingTestPresets(presets))
        val failure = assertFailsWith<ProxyEnvironmentSaveFailure> {
            service.saveChanges(ProxyEnvironmentDraft(original).useAllPresets(presets))
        }
        assertEquals(listOf(ProxyVariables.HTTPS_PROXY), failure.rollbackFailures)
        assertEquals(null, failure.latest)
        assertTrue(failure.cause!!.suppressed.any { it.message == "simulated recovery read failure" })
        assertEquals(presets.httpsProxy, store.values[ProxyVariables.HTTPS_PROXY])
    }
    @Test fun readFailureBeforeSaveDoesNotWriteAndReadFailureAfterSaveRollsBack() {
        val store = EditingTestStore(original)
        val service = ProxyEnvironmentService(store, EditingTestPresets(presets))
        val edits = ProxyEnvironmentDraft(original).useAllPresets(presets)
        store.failRead = true
        assertFailsWith<IllegalStateException> { service.saveChanges(edits) }
        assertTrue(store.mutations.isEmpty())
        store.failReadAfterWrite = true
        assertFailsWith<ProxyEnvironmentSaveFailure> { service.saveChanges(edits) }
        assertEquals(original, service.read())
    }
}

internal class EditingTestPresets(var value: ProxyPresets) : ProxyPresetStore {
    override fun read() = value
    override fun write(presets: ProxyPresets) { value = presets }
}

internal class EditingTestStore(initial: ProxyEnvironment) : UserEnvironmentStore {
    val values = initial.asMap().filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
    val mutations = mutableListOf<String>()
    var failOnce: String? = null
    var failRestore: String? = null
    var failRead = false
    var failReadAfterWrite = false
    var failReadAfterFailure: String? = null
    private var failed = false

    override fun read(name: String): String? {
        if (failed && name == failReadAfterFailure) throw IllegalStateException("simulated recovery read failure")
        if (failRead || (failReadAfterWrite && mutations.isNotEmpty())) {
            failRead = false
            failReadAfterWrite = false
            throw IllegalStateException("simulated read failure")
        }
        return values[name]
    }
    override fun write(name: String, value: String) = mutate(name, value)
    override fun delete(name: String) = mutate(name, null)
    private fun mutate(name: String, value: String?) {
        mutations += name
        if (failed && name == failRestore) throw IllegalStateException("simulated restore failure")
        if (value == null) values.remove(name) else values[name] = value
        if (name == failOnce) {
            failOnce = null
            failed = true
            throw IllegalStateException("simulated failure after mutation")
        }
    }
}
