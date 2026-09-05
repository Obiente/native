package dev.obiente.nextcloudnative

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextcloudDocumentsContractTest {
    @Test
    fun `documents authority follows the runtime application id`() {
        assertEquals(
            "dev.obiente.nextcloudnative.documents",
            nextcloudDocumentsAuthority("dev.obiente.nextcloudnative"),
        )
        assertEquals(
            "dev.obiente.nextcloudnative.dev.documents",
            nextcloudDocumentsAuthority("dev.obiente.nextcloudnative.dev"),
        )
    }

    @Test
    fun `documents authority rejects a missing application id`() {
        assertFailsWith<IllegalArgumentException> { nextcloudDocumentsAuthority(" ") }
    }

    @Test
    fun `account removal rejects retained document writebacks`() {
        requireAndroidAccountRemovalWritebacksResolved(resolved = true)

        val failure = assertFailsWith<IllegalStateException> {
            requireAndroidAccountRemovalWritebacksResolved(resolved = false)
        }

        assertTrue(failure.message.orEmpty().contains("pending document changes"))
    }

    @Test
    fun `document grant revocation covers reads writes and descendants`() {
        assertTrue(NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(NEXTCLOUD_DOCUMENTS_URI_GRANT_FLAGS and android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
    }

    @Test
    fun `account removal revokes both document and tree grant scopes`() {
        assertEquals(
            listOf("document", "tree"),
            AndroidAccountDocumentGrantScope.entries.map(AndroidAccountDocumentGrantScope::pathSegment),
        )
    }

    @Test
    fun `account removal preflight runs before remote credential revocation`() = runBlocking {
        var revoked = false
        var removed = false

        assertFailsWith<IllegalStateException> {
            revokeAndroidSessionAfterRemovalPreflight(
                preflight = { error("pending account-owned recovery") },
                revoke = { revoked = true },
                removeLocalAccount = { removed = true },
            )
        }

        assertFalse(revoked)
        assertFalse(removed)
    }

    @Test
    fun `remote revocation and local removal share one ordered operation`() = runBlocking {
        val events = mutableListOf<String>()

        revokeAndroidSessionAfterRemovalPreflight(
            preflight = { events += "preflight" },
            revoke = { events += "revoke" },
            removeLocalAccount = { events += "remove-local" },
        )

        assertEquals(listOf("preflight", "revoke", "remove-local"), events)
    }

    @Test
    fun `remote revocation ambiguity still completes local removal`() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IOException> {
            revokeAndroidSessionAfterRemovalPreflight(
                preflight = { events += "preflight" },
                revoke = {
                    events += "revoke"
                    throw IOException("synthetic ambiguous response")
                },
                removeLocalAccount = { events += "remove-local" },
            )
        }

        assertEquals(listOf("preflight", "revoke", "remove-local"), events)
    }

    @Test
    fun `cancellation after remote revocation starts still completes local removal`() = runBlocking {
        val revokeStarted = CompletableDeferred<Unit>()
        val removalCompleted = CompletableDeferred<Unit>()

        val operation = launch {
            revokeAndroidSessionAfterRemovalPreflight(
                preflight = {},
                revoke = {
                    revokeStarted.complete(Unit)
                    awaitCancellation()
                },
                removeLocalAccount = { removalCompleted.complete(Unit) },
            )
        }
        revokeStarted.await()
        operation.cancel()
        operation.join()

        assertTrue(operation.isCancelled)
        assertTrue(removalCompleted.isCompleted)
    }
}
