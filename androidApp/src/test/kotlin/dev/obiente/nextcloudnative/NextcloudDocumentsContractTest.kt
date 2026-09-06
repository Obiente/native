package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
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
    fun `recovery permit resolves only its inactive target operation`() {
        val active = session("active", "active-secret")
        val removed = session("removed", "removed-secret")
        val removedDocument = NextcloudDocumentIds.documentId(removed, "Sync/report.txt")
        val activeDocument = NextcloudDocumentIds.documentId(active, "Documents/current.txt")

        withAndroidDocumentsProviderRecoveryPermit(
            removed,
            removedDocument,
            AndroidDocumentsProviderRecoveryOperation.QueryChildren,
        ) {
            assertEquals(
                removed,
                resolveSession(removedDocument, AndroidDocumentsProviderRecoveryOperation.QueryChildren) { active }
                    ?.session,
            )
            assertEquals(
                active,
                resolveSession(activeDocument, AndroidDocumentsProviderRecoveryOperation.QueryDocument) { active }
                    ?.session,
            )
            assertEquals(
                null,
                resolveSession(removedDocument, AndroidDocumentsProviderRecoveryOperation.QueryChildren) { active },
            )
        }

        assertEquals(
            null,
            resolveSession(removedDocument, AndroidDocumentsProviderRecoveryOperation.QueryChildren) { active },
        )
    }

    @Test
    fun `recovery permit is confined to its synchronous dispatch thread`() {
        val unavailable = session("unavailable", "")
        val documentId = NextcloudDocumentIds.documentId(unavailable, "Sync")
        val resolved = AtomicReference<NextcloudSession?>()

        withAndroidDocumentsProviderRecoveryPermit(
            unavailable,
            documentId,
            AndroidDocumentsProviderRecoveryOperation.QueryChildren,
        ) {
            val dispatch = thread(start = true) {
                resolved.set(
                    resolveSession(documentId, AndroidDocumentsProviderRecoveryOperation.QueryChildren) { null }
                        ?.session,
                )
            }
            dispatch.join()
            assertEquals(
                unavailable,
                resolveSession(documentId, AndroidDocumentsProviderRecoveryOperation.QueryChildren) { null }
                    ?.session,
            )
        }

        assertEquals(null, resolved.get())
    }

    @Test
    fun `external provider caller cannot consume an inactive recovery permit`() {
        val active = session("active", "active-secret")
        val removed = session("removed", "removed-secret")
        val removedDocument = NextcloudDocumentIds.documentId(removed, "Sync")

        withAndroidDocumentsProviderRecoveryPermit(
            removed,
            removedDocument,
            AndroidDocumentsProviderRecoveryOperation.Delete,
        ) {
            assertEquals(
                null,
                resolveSession(
                    removedDocument,
                    AndroidDocumentsProviderRecoveryOperation.Delete,
                    allowRecoveryPermit = false,
                ) { active },
            )
            assertEquals(
                removed,
                resolveSession(removedDocument, AndroidDocumentsProviderRecoveryOperation.Delete) { active }?.session,
            )
        }
    }

    @Test
    fun `paused recovery rejects unrelated mutation and writable open`() {
        val active = session("active", "active-secret")
        val removed = session("removed", "removed-secret")
        val recoveryDocument = NextcloudDocumentIds.documentId(removed, "Sync/.nextcloud-native-stage")
        val unrelatedDocument = NextcloudDocumentIds.documentId(removed, "Sync/private.txt")
        val permitInstalled = CountDownLatch(1)
        val finishRecovery = CountDownLatch(1)

        val recovery = thread(start = true) {
            withAndroidDocumentsProviderRecoveryPermit(
                removed,
                recoveryDocument,
                AndroidDocumentsProviderRecoveryOperation.Rename,
            ) {
                permitInstalled.countDown()
                finishRecovery.await()
            }
        }
        permitInstalled.await()
        try {
            assertEquals(
                null,
                resolveSession(unrelatedDocument, AndroidDocumentsProviderRecoveryOperation.Rename) { active },
            )
            assertEquals(
                null,
                resolveSession(recoveryDocument, AndroidDocumentsProviderRecoveryOperation.OpenWrite) { active },
            )
            assertEquals(
                null,
                resolveSession(recoveryDocument, AndroidDocumentsProviderRecoveryOperation.Rename) { active }?.session,
            )
        } finally {
            finishRecovery.countDown()
            recovery.join()
        }
    }

    @Test
    fun `read descriptor session survives its consumed permit without enabling write open`() {
        val removed = session("removed", "removed-secret")
        val documentId = NextcloudDocumentIds.documentId(removed, "Sync/recovery-backup")
        lateinit var descriptorSession: AndroidDocumentsProviderResolvedSession

        withAndroidDocumentsProviderRecoveryPermit(
            removed,
            documentId,
            AndroidDocumentsProviderRecoveryOperation.OpenRead,
        ) {
            descriptorSession = checkNotNull(
                resolveSession(documentId, AndroidDocumentsProviderRecoveryOperation.OpenRead) { null },
            )
            assertEquals(
                null,
                resolveSession(documentId, AndroidDocumentsProviderRecoveryOperation.OpenWrite) { null },
            )
        }

        assertEquals(removed, descriptorSession.session)
        assertTrue(descriptorSession.recoveryAuthorized)
        assertEquals(
            null,
            resolveSession(documentId, AndroidDocumentsProviderRecoveryOperation.OpenRead) { null },
        )
    }

    @Test
    fun `recovery permit does not change external handoff session resolution`() {
        val active = session("active", "active-secret")
        val handoffDocumentId = "nch1:0123456789abcdef0123456789abcdef"

        assertEquals(
            active,
            requireAndroidDocumentsProviderCallSession(
                handoffDocumentId,
                AndroidDocumentsProviderRecoveryOperation.OpenRead,
            ) { active }.session,
        )
    }

    @Test
    fun `recovery permit wins over a new credential incarnation of the same account`() {
        val removed = session("same-account", "removed-secret")
        val replacement = session("same-account", "replacement-secret")
        val documentId = NextcloudDocumentIds.documentId(removed, "Sync/recovery-backup")

        withAndroidDocumentsProviderRecoveryPermit(
            removed,
            documentId,
            AndroidDocumentsProviderRecoveryOperation.OpenRead,
        ) {
            val recovery = resolveSession(
                documentId,
                AndroidDocumentsProviderRecoveryOperation.OpenRead,
            ) { replacement }
            assertEquals(removed, recovery?.session)
            assertTrue(recovery?.recoveryAuthorized == true)

            val ordinary = resolveSession(
                documentId,
                AndroidDocumentsProviderRecoveryOperation.OpenRead,
            ) { replacement }
            assertEquals(replacement, ordinary?.session)
            assertFalse(ordinary?.recoveryAuthorized == true)
        }
    }

    @Test
    fun `recovery uri helpers use direct provider calls and deny unsupported operations`() {
        fun recoveryUri(operation: AndroidDocumentsProviderRecoveryOperation) =
            androidDocumentsProviderRecoveryUri(
                documentId = "document-id",
                operation = operation,
                buildDocumentUri = { id -> "document:$id" },
                buildChildDocumentsUri = { id -> "children:$id" },
            )

        assertEquals("children:document-id", recoveryUri(AndroidDocumentsProviderRecoveryOperation.QueryChildren))
        assertEquals("document:document-id", recoveryUri(AndroidDocumentsProviderRecoveryOperation.OpenRead))
        assertEquals("document:document-id", recoveryUri(AndroidDocumentsProviderRecoveryOperation.Rename))
        assertEquals("document:document-id", recoveryUri(AndroidDocumentsProviderRecoveryOperation.Delete))
        listOf(
            AndroidDocumentsProviderRecoveryOperation.QueryDocument,
            AndroidDocumentsProviderRecoveryOperation.OpenWrite,
            AndroidDocumentsProviderRecoveryOperation.Create,
            AndroidDocumentsProviderRecoveryOperation.Move,
        ).forEach { operation ->
            assertFailsWith<IllegalStateException> { recoveryUri(operation) }
        }

        val session = session("removed", "removed-secret")
        val documentId = NextcloudDocumentIds.documentId(session, "Sync")
        assertFailsWith<IllegalArgumentException> {
            withAndroidDocumentsProviderRecoveryPermit(
                session,
                documentId,
                AndroidDocumentsProviderRecoveryOperation.OpenWrite,
            ) {
                error("write recovery must remain unreachable")
            }
        }
    }

    @Test
    fun `recovery rename normalizes a changed document id back to the durable tree`() {
        val normalized = normalizeAndroidDocumentsProviderRecoveryResult(
            recoveryEnabled = true,
            document = "tree:old-id",
            result = "direct:new-id",
            documentIdOf = { result -> result.substringAfter(':') },
            buildTreeDocumentUri = { _, id -> "tree:$id" },
        )
        val ordinary = normalizeAndroidDocumentsProviderRecoveryResult(
            recoveryEnabled = false,
            document = "tree:old-id",
            result = "tree:new-id",
            documentIdOf = { error("ordinary results stay unchanged") },
            buildTreeDocumentUri = { _, _ -> error("ordinary results stay unchanged") },
        )

        assertEquals("tree:new-id", normalized)
        assertEquals("tree:new-id", ordinary)
    }

    @Test
    fun `recovery permit is cleared when recovery fails`() {
        val removed = session("removed", "removed-secret")
        val documentId = NextcloudDocumentIds.documentId(removed, "Sync")

        assertFailsWith<IOException> {
            withAndroidDocumentsProviderRecoveryPermit(
                removed,
                documentId,
                AndroidDocumentsProviderRecoveryOperation.QueryChildren,
            ) {
                throw IOException("synthetic recovery failure")
            }
        }

        assertEquals(
            null,
            resolveSession(documentId, AndroidDocumentsProviderRecoveryOperation.QueryChildren) { null },
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

    private fun session(loginName: String, appPassword: String) = NextcloudSession(
        serverUrl = "https://cloud.example.test",
        loginName = loginName,
        appPassword = appPassword,
    )

    private fun resolveSession(
        documentId: String,
        operation: AndroidDocumentsProviderRecoveryOperation,
        allowRecoveryPermit: Boolean = true,
        loadActiveSession: () -> NextcloudSession?,
    ): AndroidDocumentsProviderResolvedSession? = resolveAndroidDocumentsProviderSession(
        documentId = documentId,
        operation = operation,
        allowRecoveryPermit = allowRecoveryPermit,
        loadActiveSession = loadActiveSession,
    )
}
