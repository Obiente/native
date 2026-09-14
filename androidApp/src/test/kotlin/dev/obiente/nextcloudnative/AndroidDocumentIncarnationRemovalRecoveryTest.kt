package dev.obiente.nextcloudnative

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AndroidDocumentIncarnationRemovalRecoveryTest {
    @Test fun sameProcessRetryResumesRetirementWhileCredentialMutationIsLocked() {
        val fixture = Fixture()
        val old = fixture.store.prepareForAccountSave(ACCOUNT, false)
        val retirement = fixture.store.retireForRemoval(ACCOUNT)
        val mutation = Mutex(locked = true)
        assertFalse(reconcileAndroidDocumentProviderAccountRemovalsWhenCredentialMutationIdle(mutation) {
            error("the credential mutation owns reconciliation")
        })
        val resumed = requireNotNull(fixture.store.resumePendingRemoval(ACCOUNT))
        assertEquals(retirement, resumed)
        renewRecoveredAndroidDocumentIncarnation(fixture.store, resumed)
        assertNotEquals(old, fixture.store.activeIncarnation(ACCOUNT))
        assertFalse("retirement:$ACCOUNT" in fixture.records)
    }

    @Test fun pendingRetirementRejectsAnUnrelatedIncarnationChange() {
        val fixture = Fixture()
        fixture.store.retireForRemoval(ACCOUNT)
        val changed = "1:active:" + "a".repeat(32)
        fixture.records[ACCOUNT] = changed
        assertFailsWith<IllegalStateException> { fixture.store.resumePendingRemoval(ACCOUNT) }
        assertEquals(changed, fixture.records[ACCOUNT])
        assertTrue("retirement:$ACCOUNT" in fixture.records)
    }

    @Test fun nonStringPreferenceRemovalRollbackRemainsUnavailable() {
        val fixture = Fixture(wrongType = true)
        val retirement = fixture.store.retireForRemoval(ACCOUNT)
        assertEquals(NextcloudDocumentIncarnation.Legacy, retirement.incarnation)
        fixture.store.rollback(retirement)
        assertFailsWith<IllegalArgumentException> { fixture.store.activeIncarnation(ACCOUNT) }
        assertFalse("retirement:$ACCOUNT" in fixture.records)
        val second = fixture.store.retireForRemoval(ACCOUNT)
        renewRecoveredAndroidDocumentIncarnation(fixture.store, second)
        assertTrue(fixture.store.activeIncarnation(ACCOUNT) is NextcloudDocumentIncarnation.Versioned)
    }

    @Test fun failedJournalWriteLeavesOriginalWrongTypeUntouched() {
        val fixture = Fixture(wrongType = true)
        fixture.failJournal = true
        assertFailsWith<IllegalStateException> { fixture.store.retireForRemoval(ACCOUNT) }
        assertTrue(fixture.wrongType)
        assertTrue(fixture.records.isEmpty())
    }

    @Test fun interruptionAfterJournalWriteCanResumeOrRollBackWithoutAuthorizingLegacy() {
        for (rollback in listOf(false, true)) {
            val fixture = Fixture(wrongType = true)
            fixture.failAccount = true
            assertFailsWith<IllegalStateException> { fixture.store.retireForRemoval(ACCOUNT) }
            assertTrue(fixture.wrongType)
            fixture.failAccount = false
            if (rollback) {
                fixture.store.reconcilePending({ AndroidDocumentProviderAccountOwnership.Present })
                assertFailsWith<IllegalArgumentException> { fixture.store.activeIncarnation(ACCOUNT) }
            } else {
                val resumed = requireNotNull(fixture.store.resumePendingRemoval(ACCOUNT))
                renewRecoveredAndroidDocumentIncarnation(fixture.store, resumed)
                assertTrue(fixture.store.activeIncarnation(ACCOUNT) is NextcloudDocumentIncarnation.Versioned)
            }
            assertFalse("retirement:$ACCOUNT" in fixture.records)
        }
    }

    @Test fun storageIoAndCancellationAreNotClassifiedAsMalformedPreferenceTypes() {
        for (failure in listOf(IOException("synthetic unavailable storage"), CancellationException("synthetic cancellation"))) {
            val fixture = Fixture()
            fixture.readFailure = failure
            val observed = assertFailsWith<Exception> { fixture.store.retireForRemoval(ACCOUNT) }
            assertSame(failure, observed)
            assertTrue(fixture.records.isEmpty())
        }
    }

    private class Fixture(var wrongType: Boolean = false) {
        val records = mutableMapOf<String, String>()
        var failJournal = false
        var failAccount = false
        var readFailure: Exception? = null
        val store = AndroidDocumentProviderIncarnationStore(
            read = { key ->
                if (key == ACCOUNT) {
                    readFailure?.let { throw it }
                    if (wrongType) throw ClassCastException("synthetic preference type")
                }
                records[key]
            },
            commit = { key, value ->
                if ((key == ACCOUNT && failAccount) || (key == "retirement:$ACCOUNT" && failJournal)) false
                else {
                    if (key == ACCOUNT) wrongType = false
                    if (value == null) records.remove(key) else records[key] = value
                    true
                }
            },
            keys = { records.keys.toSet() },
        )
    }

    private companion object { val ACCOUNT = "1".repeat(64) }
}
