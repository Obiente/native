package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
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
    fun `legacy account key returns a migration plan without writing during load`() {
        val storage = MemoryStorage()
        val repository = AppWorkspacePinsRepository(storage)
        val current = "a".repeat(64)
        val legacy = "b".repeat(64)
        assertTrue(repository.save(legacy, listOf("files", "deck")))

        val loaded = repository.loadWithProvenance(current, legacy)

        assertEquals(listOf("files", "deck"), loaded.appIds)
        assertFalse(loaded.storageAuthoritative)
        assertTrue(loaded.legacyMigrationRequired)
        assertEquals(defaultAppWorkspacePinnedIds(), repository.load(current))
        assertTrue(repository.save(current, loaded.appIds))
        assertTrue(storage.values.keys.any { key -> key.endsWith(current) })
    }

    @Test
    fun `legacy pin read cancellation remains control flow`() {
        val current = "e".repeat(64)
        val legacy = "f".repeat(64)
        val repository = AppWorkspacePinsRepository(object : HomeWorkspaceLayoutStorage {
            override fun read(persistenceKey: String): String? =
                if (persistenceKey.endsWith(current)) null else throw CancellationException("synthetic cancellation")

            override fun write(persistenceKey: String, encodedSnapshot: String) = Unit
        })

        assertFailsWith<CancellationException> {
            repository.loadWithProvenance(current, legacy)
        }
    }

    @Test
    fun `pin toggles canonical aliases without duplicates`() {
        assertEquals(listOf("files", "spreed"), toggleAppWorkspacePin(listOf("files"), "talk"))
        assertEquals(listOf("files"), toggleAppWorkspacePin(listOf("files", "spreed"), "talk"))
    }

    @Test
    fun `pin limit matches the rendered Home shortcut capacity`() {
        val full = List(MAX_APP_WORKSPACE_PINS) { index -> "app-$index" }

        assertFailsWith<IllegalArgumentException> {
            toggleAppWorkspacePin(full, "one-more")
        }
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

    @Test
    fun `fallback app discovery never removes persisted pins`() {
        assertEquals(
            null,
            reconciledAppWorkspacePinsForDiscovery(
                appIds = listOf("files", "deck"),
                installedAppIds = listOf("files"),
                appsAuthoritative = false,
            ),
        )
    }

    @Test
    fun `failed storage reads retain fallback provenance`() {
        val repository = AppWorkspacePinsRepository(FailingReadStorage())

        val loaded = repository.loadWithProvenance("e".repeat(64))

        assertEquals(defaultAppWorkspacePinnedIds(), loaded.appIds)
        assertFalse(loaded.storageAuthoritative)
    }

    private class MemoryStorage : HomeWorkspaceLayoutStorage {
        val values = mutableMapOf<String, String>()

        override fun read(persistenceKey: String): String? = values[persistenceKey]

        override fun write(persistenceKey: String, encodedSnapshot: String) {
            values[persistenceKey] = encodedSnapshot
        }
    }

    private class FailingReadStorage : HomeWorkspaceLayoutStorage {
        override fun read(persistenceKey: String): String? = error("storage unavailable")

        override fun write(persistenceKey: String, encodedSnapshot: String) = Unit
    }
}
