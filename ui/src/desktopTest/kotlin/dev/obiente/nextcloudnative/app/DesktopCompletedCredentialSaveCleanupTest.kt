package dev.obiente.nextcloudnative.app

import java.util.prefs.AbstractPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DesktopCompletedCredentialSaveCleanupTest {
    @Test
    fun everyPersistedCompletionBoundaryCanRestoreTheCommittedSession() {
        val preferences = SnapshotPreferences()
        val session = NextcloudSession("https://cloud.example.test", "alice", "synthetic-password")
        val secrets = MemorySecrets()
        DesktopAccountCredentialPersistence(preferences, secrets, {}).saveSession(session)
        preferences.put("accountCredentialSaveServer", session.serverUrl)
        preferences.put("accountCredentialSaveLogin", session.loginName)
        preferences.put("accountCredentialSavePhase", "secret-written")
        preferences.snapshots.clear()

        clearDesktopCompletedCredentialSave(preferences, preferences::flush)

        assertFalse(preferences.snapshots.isEmpty())
        preferences.snapshots.forEach { persisted ->
            val restarted = SnapshotPreferences(persisted)
            val persistence = DesktopAccountCredentialPersistence(restarted, secrets, {})
            assertEquals(session, persistence.loadActiveSession())
            assertNull(restarted.get("accountCredentialSavePhase", null))
            assertNull(restarted.get("accountCredentialSaveServer", null))
            assertNull(restarted.get("accountCredentialSaveLogin", null))
        }
    }

    @Test
    fun failedTerminalMarkerFlushKeepsIdentityAvailableForRestart() {
        val preferences = SnapshotPreferences(mapOf(
            "accountCredentialSaveServer" to "https://cloud.example.test",
            "accountCredentialSaveLogin" to "alice",
            "accountCredentialSavePhase" to "secret-written",
        ))
        kotlin.test.assertFailsWith<IllegalStateException> {
            clearDesktopCompletedCredentialSave(preferences) { error("synthetic flush failure") }
        }
        assertEquals("https://cloud.example.test", preferences.get("accountCredentialSaveServer", null))
        assertEquals("alice", preferences.get("accountCredentialSaveLogin", null))
    }

    private class SnapshotPreferences(initial: Map<String, String> = emptyMap()) : AbstractPreferences(null, "") {
        private val values = initial.toMutableMap()
        val snapshots = mutableListOf<Map<String, String>>()
        override fun putSpi(key: String, value: String) { values[key] = value; snapshot() }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key); snapshot() }
        override fun removeNodeSpi() { values.clear() }
        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = emptyArray()
        override fun childSpi(name: String): AbstractPreferences = error("No child preferences expected")
        override fun syncSpi() = Unit
        override fun flushSpi() { snapshot() }
        private fun snapshot() { snapshots += values.toMap() }
    }

    private class MemorySecrets : DesktopSecretStore {
        private val values = mutableMapOf<String, ByteArray>()
        override fun load(reference: DesktopSecretReference): ByteArray? = values[reference.targetName]?.copyOf()
        override fun save(reference: DesktopSecretReference, username: String?, secret: ByteArray) {
            values[reference.targetName] = secret.copyOf()
        }
        override fun clear(reference: DesktopSecretReference) { values.remove(reference.targetName) }
    }
}
