package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppWorkspacePinsTest {
    @Test
    fun `pins persist per opaque account scope and preserve order`() {
        val storage = MemoryStorage()
        val repository = AppWorkspacePinsRepository(storage)
        val firstAccount = "a".repeat(64)
        val secondAccount = "b".repeat(64)

        assertEquals(defaultAppWorkspacePinnedIds(), repository.load(firstAccount))
        assertTrue(repository.save(firstAccount, listOf("deck", "talk", "files")))
        assertEquals(listOf("deck", "spreed", "files"), repository.load(firstAccount))
        assertEquals(defaultAppWorkspacePinnedIds(), repository.load(secondAccount))
        assertTrue(storage.values.keys.single().endsWith(firstAccount))
    }

    @Test
    fun `pin toggles canonical aliases without duplicates`() {
        assertEquals(listOf("files", "spreed"), toggleAppWorkspacePin(listOf("files"), "talk"))
        assertEquals(listOf("files"), toggleAppWorkspacePin(listOf("files", "spreed"), "talk"))
    }

    @Test
    fun `invalid persisted pin data falls back to defaults`() {
        val storage = MemoryStorage()
        val repository = AppWorkspacePinsRepository(storage)
        val account = "c".repeat(64)
        storage.write("apps:pins:1:$account", "{not-json")

        assertEquals(defaultAppWorkspacePinnedIds(), repository.load(account))
        assertFalse(repository.save(account, listOf("../unsafe")))
    }

    @Test
    fun `supported punctuation is retained and unsafe paths remain rejected`() {
        val storage = MemoryStorage()
        val repository = AppWorkspacePinsRepository(storage)
        val account = "d".repeat(64)

        assertTrue(repository.save(account, listOf("assistant-ai", "files_external.v2")))
        assertEquals(listOf("assistant-ai", "files_external.v2"), repository.load(account))
        assertFalse(repository.save(account, listOf("../unsafe")))
    }

    @Test
    fun `successful discovery removes unavailable pins before enforcing the limit`() {
        assertEquals(
            listOf("files", "deck"),
            reconcileAppWorkspacePinnedIds(
                appIds = listOf("files", "disabled-app", "deck"),
                installedAppIds = listOf("deck", "files"),
            ),
        )
    }

    private class MemoryStorage : HomeWorkspaceLayoutStorage {
        val values = mutableMapOf<String, String>()

        override fun read(persistenceKey: String): String? = values[persistenceKey]

        override fun write(persistenceKey: String, encodedSnapshot: String) {
            values[persistenceKey] = encodedSnapshot
        }
    }
}
