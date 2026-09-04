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
    fun unsupportedRegistryVersionUsesLegacySessionWithoutOverwritingFutureData() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")
        val futureRegistry = """
            {
              "version": 2,
              "activeAccount": "future-account",
              "records": [{"future": true}]
            }
        """.trimIndent()

        val restored = restoreNextcloudAccountRegistry(futureRegistry, session)

        assertEquals(NextcloudAccountRegistrySource.LegacySession, restored.source)
        assertEquals(NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion, restored.recoveryReason)
        assertEquals(session.accountRecord(), restored.registry.activeAccount)
        assertFalse(restored.needsPersistence)
    }

    @Test
    fun unsupportedRegistryVersionWithoutLegacyCredentialsRemainsUntouched() {
        val futureRegistry = """{"version":99,"accounts":[]}"""

        val restored = restoreNextcloudAccountRegistry(futureRegistry, legacySession = null)

        assertEquals(NextcloudAccountRegistry.Empty, restored.registry)
        assertEquals(NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion, restored.recoveryReason)
        assertFalse(restored.needsPersistence)
    }

    @Test
    fun futureRegistryVersionBeyondIntRangeRemainsUntouched() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")
        val futureRegistry = """{"version":2147483648,"accounts":[]}"""

        val restored = restoreNextcloudAccountRegistry(futureRegistry, session)

        assertEquals(NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion, restored.recoveryReason)
        assertEquals(session.accountRecord(), restored.registry.activeAccount)
        assertFalse(restored.needsPersistence)
    }

    @Test
    fun extremelyLongBoundedFutureRegistryVersionRemainsUntouched() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")
        val futureRegistry = """{"version":${"9".repeat(16 * 1024)},"accounts":[]}"""

        val restored = restoreNextcloudAccountRegistry(futureRegistry, session)

        assertEquals(NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion, restored.recoveryReason)
        assertEquals(session.accountRecord(), restored.registry.activeAccount)
        assertFalse(restored.needsPersistence)
    }

    @Test
    fun oversizedFutureRegistryRemainsUntouchedAfterBoundedVersionInspection() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")
        val futureRegistry = """{"version":2,"future":"${"x".repeat(300 * 1024)}"}"""

        val restored = restoreNextcloudAccountRegistry(futureRegistry, session)

        assertEquals(NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion, restored.recoveryReason)
        assertEquals(session.accountRecord(), restored.registry.activeAccount)
        assertFalse(restored.needsPersistence)
    }

    @Test
    fun oversizedFutureRegistryBeyondIntRangeRemainsUntouched() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")
        val futureRegistry = """{"version":2147483648,"future":"${"x".repeat(300 * 1024)}"}"""

        val restored = restoreNextcloudAccountRegistry(futureRegistry, session)

        assertEquals(NextcloudAccountRegistryRecoveryReason.UnsupportedRegistryVersion, restored.recoveryReason)
        assertEquals(session.accountRecord(), restored.registry.activeAccount)
        assertFalse(restored.needsPersistence)
    }

    @Test
    fun oversizedCurrentRegistryStillUsesTheVersionSpecificSizeLimit() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")
        val oversizedRegistry = """{"version":1,"padding":"${"x".repeat(300 * 1024)}"}"""

        val restored = restoreNextcloudAccountRegistry(oversizedRegistry, session)

        assertEquals(NextcloudAccountRegistryRecoveryReason.MalformedRegistry, restored.recoveryReason)
        assertEquals(session.accountRecord(), restored.registry.activeAccount)
        assertTrue(restored.needsPersistence)
    }

    @Test
    fun zeroAndNegativeRegistryVersionsRemainMalformedAndRepairable() {
        val session = session("https://cloud.example.test", "alice", "private-app-password")

        listOf(0, -1).forEach { version ->
            val restored = restoreNextcloudAccountRegistry(
                encoded = """{"version":$version,"accounts":[]}""",
                legacySession = session,
            )

            assertEquals(NextcloudAccountRegistrySource.LegacySession, restored.source)
            assertEquals(NextcloudAccountRegistryRecoveryReason.MalformedRegistry, restored.recoveryReason)
            assertEquals(session.accountRecord(), restored.registry.activeAccount)
            assertTrue(restored.needsPersistence)
        }
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
    fun fullRegistryPreservesTheStaleActiveRecordWhileSelectingTheLegacySession() {
        val legacy = session("https://legacy.example.test", "alice", "private-app-password")
        val persistedAccounts = (0 until MAX_LOCAL_ACCOUNTS).map { index ->
            session("https://account-$index.example.test", "user-$index", "secret-$index").accountRecord()
        }
        val staleActive = persistedAccounts[17]
        val displaced = persistedAccounts
            .filterNot { account -> account.id == staleActive.id }
            .maxBy { account -> account.id.storageKey }
        val encoded = encodeNextcloudAccountRegistry(
            NextcloudAccountRegistry(persistedAccounts, staleActive.id),
        )

        val restored = restoreNextcloudAccountRegistry(encoded, legacy)

        assertEquals(NextcloudAccountRegistryRecoveryReason.ActiveSessionMismatch, restored.recoveryReason)
        assertEquals(legacy.accountId, restored.registry.activeAccountId)
        assertEquals(MAX_LOCAL_ACCOUNTS, restored.registry.accounts.size)
        assertTrue(legacy.accountRecord() in restored.registry.accounts)
        assertTrue(staleActive in restored.registry.accounts)
        assertFalse(displaced in restored.registry.accounts)
        assertTrue(restored.needsPersistence)
    }

    @Test
    fun fullUnselectedRegistryUsesADeterministicBoundedReplacement() {
        val legacy = session("https://legacy.example.test", "alice", "private-app-password")
        val persistedAccounts = (0 until MAX_LOCAL_ACCOUNTS).map { index ->
            session("https://account-$index.example.test", "user-$index", "secret-$index").accountRecord()
        }
        val displaced = persistedAccounts.maxBy { account -> account.id.storageKey }
        val forward = encodeNextcloudAccountRegistry(
            NextcloudAccountRegistry(persistedAccounts, activeAccountId = null),
        )
        val reverse = encodeNextcloudAccountRegistry(
            NextcloudAccountRegistry(persistedAccounts.reversed(), activeAccountId = null),
        )

        val restoredForward = restoreNextcloudAccountRegistry(forward, legacy)
        val restoredReverse = restoreNextcloudAccountRegistry(reverse, legacy)

        assertEquals(restoredForward, restoredReverse)
        assertEquals(MAX_LOCAL_ACCOUNTS, restoredForward.registry.accounts.size)
        assertEquals(legacy.accountId, restoredForward.registry.activeAccountId)
        assertTrue(legacy.accountRecord() in restoredForward.registry.accounts)
        assertFalse(displaced in restoredForward.registry.accounts)
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
