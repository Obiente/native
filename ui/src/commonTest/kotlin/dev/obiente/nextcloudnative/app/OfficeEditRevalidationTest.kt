package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OfficeEditRevalidationTest {
    private val file = NextcloudFile("Documents/test.pdf", "test.pdf", false, "application/pdf", 10, null,
        fileId = 42, hasPreview = true, etag = "v1", permissions = "RW")
    private val capabilities = NextcloudDocumentEditingCapabilities(
        mapOf("editor" to NextcloudDocumentEditorCapability("editor", "Editor", setOf("application/pdf"), emptySet(), true)),
        emptyMap(), supportsFileId = true,
    )
    private val request = (planOfficeEditSession(file, capabilities) as OfficeEditSessionPlan.Ready).request

    @Test
    fun startsOnlyAfterFreshStableIdLookupProvesTheReviewedWritableVersion() = runBlocking {
        var resolved = false
        val session = beginRevalidatedOfficeEdit(request, capabilities,
            resolveFile = { id -> assertEquals(42L, id); resolved = true; file },
            beginSession = { verified ->
                assertTrue(resolved)
                assertEquals(request, verified)
                NextcloudDocumentEditSession("https://cloud.example/apps/files/directEditing/token")
            },
        )
        assertEquals("NextcloudDocumentEditSession(url=<redacted>)", session.toString())
    }

    @Test
    fun refusesChangedMissingMovedOrNonWritableSourcesWithoutRequestingAToken() = runBlocking {
        listOf(null, file.copy(fileId = 43), file.copy(etag = "v2"), file.copy(etag = null),
            file.copy(permissions = "R"), file.copy(permissions = null), file.copy(isDirectory = true),
            file.copy(originalAccessAllowed = false), file.copy(path = "Moved/test.pdf"),
            file.copy(mimeType = "application/x-unknown"),
        ).forEach { current ->
            assertFailsWith<OfficeEditSourceChangedException> {
                beginRevalidatedOfficeEdit(request, capabilities, { current }, { error("Must not create a token") })
            }
        }
    }

    @Test
    fun offlineAndCancelledLookupsCannotFallBackToTheOldListing(): Unit = runBlocking {
        val offline = IllegalStateException("offline")
        assertSame(offline, assertFailsWith<IllegalStateException> {
            beginRevalidatedOfficeEdit(request, capabilities, { throw offline }, { error("Must not create a token") })
        })
        assertFailsWith<CancellationException> {
            beginRevalidatedOfficeEdit(request, capabilities, { throw CancellationException() }, { error("Must not create a token") })
        }
    }

    @Test
    fun tokenPolicyIsBoundedAndHasNoUrlEncodedOrUnicodeAmbiguity() {
        listOf("a", "Token_123-abc", "a".repeat(1024)).forEach { assertTrue(isValidOfficeDirectEditingToken(it)) }
        listOf("", "token.value", "%61", "token%20value", "a/b", "a\\b", "token\n", "\u00e9", "a".repeat(1025))
            .forEach { assertFalse(isValidOfficeDirectEditingToken(it)) }
    }
}
