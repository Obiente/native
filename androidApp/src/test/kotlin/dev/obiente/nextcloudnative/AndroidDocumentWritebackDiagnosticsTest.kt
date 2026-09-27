package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidDocumentWritebackDiagnosticsTest {
    private val original = NextcloudSession("https://cloud.example.test", "reader", "old")

    @Test fun equivalentAddressAndPasswordRotationUsesTheRetainedScope() {
        val replacement = original.copy(serverUrl = "https://CLOUD.example.test:443/", appPassword = "new")
        assertEquals(original.accountId, replacement.accountId)
        val scopes = mutableListOf<String>()
        withCurrentAndroidWritebackDiagnosticScope(original, { replacement }, publish = scopes::add)
        assertEquals(listOf(NextcloudDocumentIds.accountKey(replacement)), scopes)
    }

    @Test fun removalOrAnotherRotationCannotPublishAnObsoleteScope() {
        for (after in listOf(null, original.copy(appPassword = "new"), original.copy(loginName = "other"))) {
            var reads = 0
            val scopes = mutableListOf<String>()
            withCurrentAndroidWritebackDiagnosticScope(original, { if (reads++ == 0) original else after }, publish = scopes::add)
            assertTrue(scopes.isEmpty())
        }
    }

    @Test fun removalLeaseSkipsOptionalDiagnosticsWithoutWaiting(): Unit = runBlocking {
        val guard = AndroidAccountOperationGuard()
        val scopes = mutableListOf<String>()
        guard.withAccount(NextcloudDocumentIds.accountKey(original)) {
            withCurrentAndroidWritebackDiagnosticScope(original, { original }, guard, scopes::add)
        }
        assertTrue(scopes.isEmpty())
        withCurrentAndroidWritebackDiagnosticScope(original, { original }, guard, scopes::add)
        assertEquals(listOf(NextcloudDocumentIds.accountKey(original)), scopes)
    }

    @Test fun cancellationRemainsControlFlow() {
        assertFailsWith<CancellationException> {
            withCurrentAndroidWritebackDiagnosticScope(original, { throw CancellationException("synthetic") }) { }
        }
    }
}
