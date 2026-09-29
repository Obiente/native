package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilesRequestStateTest {
    @Test
    fun `late failure rolls back only its file after another favorite succeeds`() {
        val owner = FilesFavoriteMutations()
        val a = file("a")
        val b = file("b")
        assertTrue(owner.begin(a, true))
        assertTrue(owner.begin(b, true))
        var visible = owner.refreshed(listOf(a, b), 0)
        owner.succeeded(b.path)
        visible = requireNotNull(visible.withFavorite(a.path, requireNotNull(owner.failed(a.path))))
        assertFalse(visible[0].favorite)
        assertTrue(visible[1].favorite)
    }

    @Test
    fun `same resource changes are serialized and refresh metadata is preserved`() {
        val owner = FilesFavoriteMutations()
        val a = file("a")
        assertTrue(owner.begin(a, true))
        assertFalse(owner.begin(a.copy(favorite = true), false))
        val refreshed = owner.refreshed(listOf(a.copy(name = "renamed", etag = "new")), owner.revision)
        val rolledBack = requireNotNull(refreshed.withFavorite(a.path, requireNotNull(owner.failed(a.path))))
        assertEquals("renamed", rolledBack.single().name)
        assertEquals("new", rolledBack.single().etag)
        assertFalse(rolledBack.single().favorite)
    }

    @Test
    fun `refresh started before successful mutation cannot clear that mutation`() {
        val owner = FilesFavoriteMutations()
        val a = file("a")
        val read = owner.revision
        owner.begin(a, true)
        owner.succeeded(a.path)
        assertTrue(owner.refreshed(listOf(a), read).single().favorite)
        assertFalse(owner.refreshed(listOf(a), owner.revision).single().favorite)
    }

    @Test
    fun `reopened share dialog rejects prior completion even for same file`() {
        val identity = FileShareLoadIdentity()
        val a = identity.replace()
        identity.replace()
        val b = identity.replace()
        assertTrue(identity.accepts(b))
        assertFalse(identity.accepts(a))
    }

    @Test
    fun `cancelled noncooperative read cannot publish results or failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var state = "replacement"
        val obsolete = launch {
            filesRequest {
                withContext(NonCancellable) {
                    started.complete(Unit)
                    release.await()
                }
                "obsolete"
            }.onSuccess { state = it }.onFailure { state = "error" }
        }
        started.await()
        obsolete.cancel()
        release.complete(Unit)
        obsolete.join()
        assertEquals("replacement", state)
    }

    private fun file(path: String) = NextcloudFile(path, path, false, "text/plain", 1, null, null, false)
}
