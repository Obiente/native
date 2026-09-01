package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NextcloudAccountRegistryTest {
    @Test
    fun restartPreservesTheExactActiveAccount() {
        val first = session("https://one.example.test/cloud", "alice", "first-secret")
        val second = session("https://two.example.test/cloud", "alice", "second-secret")
        val beforeRestart = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())
            .select(first.accountId)
        val encoded = encodeNextcloudAccountRegistry(requireNotNull(beforeRestart))

        val restored = restoreNextcloudAccountRegistry(encoded, first)

        assertEquals(NextcloudAccountRegistrySource.Persisted, restored.source)
        assertEquals(first.accountId, restored.registry.activeAccountId)
        assertEquals(setOf(first.accountId, second.accountId), restored.registry.accounts.map { it.id }.toSet())
        assertFalse(restored.needsPersistence)
    }

    @Test
    fun encodingIsDeterministicAcrossInsertionOrder() {
        val first = session("https://one.example.test", "alice", "first-secret").accountRecord()
        val second = session("https://two.example.test", "alice", "second-secret").accountRecord()
        val firstOrder = NextcloudAccountRegistry(listOf(first, second), second.id)
        val secondOrder = NextcloudAccountRegistry(listOf(second, first), second.id)

        assertEquals(
            encodeNextcloudAccountRegistry(firstOrder),
            encodeNextcloudAccountRegistry(secondOrder),
        )
    }

    @Test
    fun legacySessionMigratesToOneSelectedCredentialFreeRecord() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")

        val restored = restoreNextcloudAccountRegistry(encoded = null, legacySession = session)
        val encoded = encodeNextcloudAccountRegistry(restored.registry)

        assertEquals(NextcloudAccountRegistrySource.LegacySession, restored.source)
        assertEquals(session.accountId, restored.registry.activeAccountId)
        assertTrue(restored.needsPersistence)
        assertFalse(encoded.contains(session.appPassword))
        assertFalse(encoded.contains("appPassword"))
    }

    @Test
    fun duplicateCanonicalIdentitiesFallBackToTheValidLegacySession() {
        val session = session("https://cloud.example.test/Cloud", "alice", "private-app-password")
        val id = session.accountId.storageKey
        val duplicateRegistry = """
            {
              "version": 1,
              "activeAccountId": "$id",
              "accounts": [
                {"id": "$id", "serverUrl": "https://cloud.example.test/Cloud", "loginName": "alice"},
                {"id": "$id", "serverUrl": "HTTPS://CLOUD.EXAMPLE.TEST:443/Cloud/", "loginName": "alice"}
              ]
            }
        """.trimIndent()

        val restored = restoreNextcloudAccountRegistry(duplicateRegistry, session)

        assertEquals(NextcloudAccountRegistrySource.LegacySession, restored.source)
        assertEquals(NextcloudAccountRegistryRecoveryReason.MalformedRegistry, restored.recoveryReason)
        assertEquals(listOf(session.accountRecord()), restored.registry.accounts)
        assertEquals(session.accountId, restored.registry.activeAccountId)
    }

    @Test
    fun malformedRegistryNeverDiscardsAValidLegacySession() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")

        val restored = restoreNextcloudAccountRegistry("{not-json", session)

        assertEquals(NextcloudAccountRegistrySource.LegacySession, restored.source)
        assertEquals(NextcloudAccountRegistryRecoveryReason.MalformedRegistry, restored.recoveryReason)
        assertEquals(session.accountRecord(), restored.registry.activeAccount)
    }

    @Test
    fun staleActiveSelectionPreservesRecordsWithoutRebindingLegacyCredentials() {
        val legacy = session("https://one.example.test", "alice", "private-app-password")
        val other = session("https://two.example.test", "alice", "other-private-app-password")
        val encoded = encodeNextcloudAccountRegistry(singleAccountRegistry(other))

        val restored = restoreNextcloudAccountRegistry(encoded, legacy)

        assertEquals(NextcloudAccountRegistrySource.LegacySession, restored.source)
        assertEquals(NextcloudAccountRegistryRecoveryReason.ActiveSessionMismatch, restored.recoveryReason)
        assertEquals(legacy.accountId, restored.registry.activeAccountId)
        assertEquals(
            setOf(legacy.accountRecord(), other.accountRecord()),
            restored.registry.accounts.toSet(),
        )
    }

    @Test
    fun removalDoesNotSilentlySelectAnotherAccount() {
        val first = session("https://one.example.test", "alice", "first-secret")
        val second = session("https://two.example.test", "alice", "second-secret")
        val registry = NextcloudAccountRegistry.Empty
            .upsertAndSelect(first.accountRecord())
            .upsertAndSelect(second.accountRecord())

        val removed = registry.remove(second.accountId)

        assertNull(removed.activeAccountId)
        assertEquals(listOf(first.accountRecord()), removed.accounts)
        assertNull(removed.select(second.accountId))
    }

    @Test
    fun recoveryDiagnosticsContainNoAccountOrCredentialValues() {
        val session = session("https://private.example.test", "private-user", "private-app-password")
        val restored = restoreNextcloudAccountRegistry("invalid", session)
        val diagnostic = requireNotNull(restored.recoveryReason).diagnosticCode

        assertEquals("ACCOUNT_REGISTRY_MALFORMED", diagnostic)
        assertFalse(diagnostic.contains(session.serverUrl))
        assertFalse(diagnostic.contains(session.loginName))
        assertFalse(diagnostic.contains(session.appPassword))
    }

    private fun session(serverUrl: String, loginName: String, appPassword: String) = NextcloudSession(
        serverUrl = serverUrl,
        loginName = loginName,
        appPassword = appPassword,
    )
}
