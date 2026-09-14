package dev.obiente.nextcloudnative

import android.content.SharedPreferences
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidCleanupJournalRecoveryTest {
    private val session = NextcloudSession("https://cloud.example.test", "alice", "synthetic-secret")
    private val other = NextcloudSession("https://cloud.example.test", "bob", "synthetic-secret")

    @Test fun quarantinePreservesKnownWorkAndRequiresAccountRecoveryAfterRestart() {
        val valid = encodeAndroidPendingAccountRemovalCleanup(pendingAndroidAccountRemovalCleanup(other))
        val recovery = assertNotNull(quarantineAndroidCleanupJournal(setOf(valid, "damaged")))
        assertEquals(setOf("damaged"), recovery.quarantined)
        assertEquals(setOf(valid, ANDROID_CLEANUP_RECOVERY_FENCE), recovery.active)
        val snapshot = restoreAndroidPendingAccountRemovalCleanups(recovery.active)
        assertEquals(0, snapshot.malformedEntryCount)
        assertNull(restoreAndroidSessionAfterRemovalCleanup(session.accountId, { snapshot }) { session })
        assertNull(restoreAndroidSessionAfterRemovalCleanup(other.accountId, { snapshot }) { other })
    }

    @Test fun onlyVerifiedAccountCanResumeAndNewCorruptionInvalidatesPriorDecisions() {
        val reviewed = markAndroidCleanupAccountReviewed(setOf(ANDROID_CLEANUP_RECOVERY_FENCE), session.accountId.storageKey)
        val snapshot = restoreAndroidPendingAccountRemovalCleanups(reviewed)
        assertEquals(session, restoreAndroidSessionAfterRemovalCleanup(session.accountId, { snapshot }) { session })
        assertNull(restoreAndroidSessionAfterRemovalCleanup(other.accountId, { snapshot }) { other })
        val renewed = assertNotNull(quarantineAndroidCleanupJournal(reviewed + "damaged"))
        assertTrue(restoreAndroidPendingAccountRemovalCleanups(renewed.active).reviewedAccounts.isEmpty())
        assertEquals(reviewed, removeAndroidAccountRemovalCleanup(reviewed, other.accountId.storageKey) {
            error("Recovery metadata is not malformed")
        })
    }

    @Test fun cleanupFailureAndCancellationKeepAccountFenced() = runBlocking {
        listOf(IllegalStateException("unresolved local changes"), CancellationException("cancelled")).forEach { failure ->
            val fixture = JournalFixture(setOf(ANDROID_CLEANUP_RECOVERY_FENCE))
            assertFailsWith<Exception> {
                retryAndroidCleanupBeforeActivation(session, fixture.journal, { emptyList() }, {},
                    { _, _, _, _, _ -> throw failure }, {})
            }
            assertTrue(fixture.journal.snapshot().reviewedAccounts.isEmpty())
            assertEquals(1, fixture.journal.pending().size)
            assertNull(restoreAndroidSessionAfterRemovalCleanup(session.accountId, fixture.journal::snapshot) { session })
        }
    }

    @Test fun successfulCleanupSurvivesRestartAndDoesNotRepeatForReviewedAccount() = runBlocking {
        val fixture = JournalFixture(setOf("damaged"))
        fixture.journal.quarantineMalformedForReset()
        assertEquals(setOf("damaged"), fixture.values[ANDROID_CLEANUP_QUARANTINE_KEY])
        var completed = 0
        repeat(2) {
            retryAndroidCleanupBeforeActivation(session, fixture.journal, { emptyList() }, {},
                { _, _, _, _, _ -> completed++ }, {})
        }
        assertEquals(1, completed)
        assertEquals(setOf(session.accountId.storageKey), fixture.journal.snapshot().reviewedAccounts)
        assertTrue(fixture.journal.pending().isEmpty())
    }

    @Test fun recoveryPreservesExistingOwnershipUntilItsCleanupSucceeds() = runBlocking {
        val previous = pendingAndroidAccountRemovalCleanup(session).copy(workIdentity = "e".repeat(32), previewCacheIdentity = "e".repeat(64))
        val fixture = JournalFixture(setOf(ANDROID_CLEANUP_RECOVERY_FENCE, encodeAndroidPendingAccountRemovalCleanup(previous)))
        assertFailsWith<IllegalStateException> {
            retryAndroidCleanupBeforeActivation(session, fixture.journal, { emptyList() }, {},
                { _, identity, _, _, _ ->
                    assertEquals(previous.workIdentity, identity)
                    throw IllegalStateException("existing cleanup is incomplete")
                }, {})
        }
        assertEquals(setOf(previous), fixture.journal.pending())
        val cleaned = mutableListOf<String>()
        retryAndroidCleanupBeforeActivation(session, fixture.journal, { emptyList() }, {},
            { _, identity, _, _, _ -> cleaned += identity }, {})
        assertEquals(listOf(previous.workIdentity, pendingAndroidAccountRemovalCleanup(session).workIdentity), cleaned)
        assertEquals(setOf(session.accountId.storageKey), fixture.journal.snapshot().reviewedAccounts)
    }

    @Test fun reviewedAccountHistoryIsBoundedAndNeverDropsFence() {
        var encoded = setOf(ANDROID_CLEANUP_RECOVERY_FENCE)
        repeat(100) { encoded = markAndroidCleanupAccountReviewed(encoded, it.toString(16).padStart(64, '0')) }
        assertEquals(64, restoreAndroidPendingAccountRemovalCleanups(encoded).reviewedAccounts.size)
        assertTrue(ANDROID_CLEANUP_RECOVERY_FENCE in encoded)
    }

    private class JournalFixture(initial: Set<String>) {
        val values = mutableMapOf(ANDROID_PENDING_ACCOUNT_REMOVAL_CLEANUP_KEY to initial)
        private val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getStringSet" -> values[args[0] as String] ?: emptySet<String>()
                "edit" -> editor()
                else -> error("Unexpected preference operation")
            }
        } as SharedPreferences
        val journal = AndroidAccountRemovalCleanupJournal(preferences, { check(it.commit()) }, {})
        private fun editor(): SharedPreferences.Editor {
            val edits = mutableMapOf<String, Set<String>?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putStringSet" -> {
                        edits[args[0] as String] = (args[1] as Set<*>).map { it as String }.toSet()
                        proxy
                    }
                    "remove" -> { edits[args[0] as String] = null; proxy }
                    "commit" -> {
                        edits.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        true
                    }
                    else -> error("Unexpected editor operation")
                }
            } as SharedPreferences.Editor
        }
    }
}
