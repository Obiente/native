package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAccountRemovalIntegrationTest {
    @Test fun failedSafRetirementPreservesDiagnosticAliasesAndGrantsAcrossIndependentCleanup(): Unit = runBlocking {
        val scopes = mutableSetOf("historical", "canonical")
        var revoked = false
        var otherCleanup = false
        assertFailsWith<IllegalStateException> {
            runAndroidAccountRemovalCleanups(listOf(
                { retireAndroidFileSyncBeforeGrantRevocation(
                    { error("synthetic pending recovery") },
                    { scopes.clear(); revoked = true },
                ) },
                { otherCleanup = true },
            ))
        }
        assertEquals(setOf("historical", "canonical"), scopes)
        assertFalse(revoked)
        assertTrue(otherCleanup)
        retireAndroidFileSyncBeforeGrantRevocation({}, { scopes.clear(); revoked = true })
        assertTrue(scopes.isEmpty())
        assertTrue(revoked)
    }

    @Test fun recoveryPrecedesCanonicalExclusiveLeaseAndRetirement(): Unit = runBlocking {
        val session = NextcloudSession("https://cloud.example.test", "reader", "synthetic")
        val guard = AndroidAccountOperationGuard()
        val events = mutableListOf<String>()
        withTimeout(1_000) {
            withPreparedAndroidAccountRemovalLease(
                session, guard, AndroidAccountRemovalLifetimeGuard(),
                prepare = { guard.withAccount(NextcloudDocumentIds.accountKey(session)) { events += "recovered" } },
                revalidate = {
                    assertTrue(guard.tryWithAccounts(androidAccountOperationIdentities(session), { true }, { false }))
                    events += "verified"
                },
            ) { events += "retired" }
        }
        assertEquals(listOf("recovered", "verified", "retired"), events)
    }

    @Test fun cancelledRevalidationNeverStartsRetirement(): Unit = runBlocking {
        val session = NextcloudSession("https://cloud.example.test", "reader", "synthetic")
        var retired = false
        assertFailsWith<CancellationException> {
            withPreparedAndroidAccountRemovalLease(
                session, AndroidAccountOperationGuard(), AndroidAccountRemovalLifetimeGuard(),
                prepare = {}, revalidate = { throw CancellationException("synthetic") },
            ) { retired = true }
        }
        assertFalse(retired)
    }
}
