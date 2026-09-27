package dev.obiente.nextcloudnative

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidCredentialResetCompletionTest {
    @Test fun failingDiagnosticsDoNotInterruptCommittedRetirements(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.completionFailures[FIRST] = IOException("synthetic completion failure")
        var diagnostics = 0

        fixture.reset {
            diagnostics += 1
            throw IOException("synthetic diagnostic failure")
        }

        assertEquals(1, diagnostics)
        fixture.assertCommittedWithPendingFirst()
        fixture.completionFailures.clear()
        fixture.store.reconcilePending({ AndroidDocumentProviderAccountOwnership.Absent })
        assertFalse("retirement:$FIRST" in fixture.records)
        assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(FIRST) }
    }

    @Test fun diagnosticCancellationPropagatesAfterRemainingRetirementsComplete(): Unit = runBlocking {
        val fixture = Fixture()
        fixture.completionFailures[FIRST] = IOException("synthetic completion failure")
        val cancellation = CancellationException("synthetic diagnostic cancellation")

        val observed = assertFailsWith<CancellationException> {
            fixture.reset { throw cancellation }
        }

        assertCancellationOrigin(cancellation, observed)
        fixture.assertCommittedWithPendingFirst()
    }

    @Test fun completionCancellationIsNotDiagnosedAndKeepsPrecedence(): Unit = runBlocking {
        val fixture = Fixture()
        val cancellation = CancellationException("synthetic completion cancellation")
        fixture.completionFailures[FIRST] = cancellation
        fixture.completionFailures[SECOND] = IOException("synthetic second completion failure")
        var diagnostics = 0

        val observed = assertFailsWith<CancellationException> {
            fixture.reset {
                diagnostics += 1
                throw CancellationException("synthetic later diagnostic cancellation")
            }
        }

        assertCancellationOrigin(cancellation, observed)
        assertEquals(1, diagnostics)
        assertTrue(fixture.credentialsCleared)
        assertEquals(listOf(FIRST, SECOND), fixture.completionAttempts)
        assertTrue("retirement:$FIRST" in fixture.records)
        assertTrue("retirement:$SECOND" in fixture.records)
    }

    @Test fun failedCredentialCommitStillRollsBackBeforeAnyCompletion(): Unit = runBlocking {
        val fixture = Fixture()
        val cancellation = CancellationException("synthetic credential cancellation")

        val observed = assertFailsWith<CancellationException> {
            retireAndroidDocumentProviderIncarnationsForCredentialReset(
                fixture.store,
                AndroidAccountRemovalLifetimeGuard(),
                clearCredentials = { throw cancellation },
                recordCompletionFailure = { error("completion must not run before commit") },
            )
        }

        assertCancellationOrigin(cancellation, observed)
        assertFalse(fixture.credentialsCleared)
        assertEquals(setOf(FIRST, SECOND), fixture.records.keys)
        assertEquals(fixture.original, fixture.store.activeIncarnation(FIRST))
        assertEquals(fixture.original, fixture.store.activeIncarnation(SECOND))
    }

    @Test fun cancellationAfterCredentialCommitKeepsRetirementForRecovery(): Unit = runBlocking {
        val fixture = Fixture()
        val cancellation = CancellationException("synthetic postcommit maintenance cancellation")
        val observed = assertFailsWith<CancellationException> {
            retireAndroidDocumentProviderIncarnationsForCredentialReset(
                fixture.store,
                AndroidAccountRemovalLifetimeGuard(),
                clearCredentials = { markCommitted ->
                    fixture.credentialsCleared = true
                    markCommitted()
                    throw cancellation
                },
                recordCompletionFailure = { error("Cancellation must not be diagnosed") },
            )
        }
        assertCancellationOrigin(cancellation, observed)
        assertTrue(fixture.credentialsCleared)
        assertEquals(emptyList<String>(), fixture.completionAttempts)
        for (account in listOf(FIRST, SECOND)) {
            assertTrue("retirement:$account" in fixture.records)
            assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(account) }
        }
        fixture.store.reconcilePending({ AndroidDocumentProviderAccountOwnership.Absent })
        for (account in listOf(FIRST, SECOND)) {
            assertFalse("retirement:$account" in fixture.records)
            assertFailsWith<IllegalStateException> { fixture.store.activeIncarnation(account) }
        }
    }

    private fun assertCancellationOrigin(expected: CancellationException, observed: CancellationException) {
        var cause: Throwable? = observed
        repeat(8) {
            if (cause === expected) return
            cause = cause?.cause
        }
        error("The original cancellation must remain in the bounded cause chain")
    }

    private class Fixture {
        val original = NextcloudDocumentIncarnation.Versioned("1".repeat(32))
        val records = linkedMapOf(
            FIRST to encodeAndroidDocumentProviderIncarnationRecord(AndroidDocumentProviderIncarnationRecord.Active(original)),
            SECOND to encodeAndroidDocumentProviderIncarnationRecord(AndroidDocumentProviderIncarnationRecord.Active(original)),
        )
        val completionFailures = mutableMapOf<String, Exception>()
        val completionAttempts = mutableListOf<String>()
        var credentialsCleared = false
        val store = AndroidDocumentProviderIncarnationStore(
            read = records::get,
            commit = { key, value ->
                if (credentialsCleared && key.startsWith("retirement:") && value == null) {
                    val account = key.removePrefix("retirement:")
                    completionAttempts += account
                    completionFailures[account]?.let { throw it }
                }
                if (value == null) records.remove(key) else records[key] = value
                true
            },
            keys = { records.keys.toSet() },
        )

        suspend fun reset(recordFailure: (Exception) -> Unit) =
            retireAndroidDocumentProviderIncarnationsForCredentialReset(
                store,
                AndroidAccountRemovalLifetimeGuard(),
                clearCredentials = { credentialsCleared = true; it() },
                recordCompletionFailure = recordFailure,
            )

        fun assertCommittedWithPendingFirst() {
            assertTrue(credentialsCleared)
            assertEquals(listOf(FIRST, SECOND), completionAttempts)
            assertTrue("retirement:$FIRST" in records)
            assertFalse("retirement:$SECOND" in records)
            assertFailsWith<IllegalStateException> { store.activeIncarnation(FIRST) }
            assertFailsWith<IllegalStateException> { store.activeIncarnation(SECOND) }
        }
    }

    private companion object {
        val FIRST = "a".repeat(64)
        val SECOND = "b".repeat(64)
    }
}
