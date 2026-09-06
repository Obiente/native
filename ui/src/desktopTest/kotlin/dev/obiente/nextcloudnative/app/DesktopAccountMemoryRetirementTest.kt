package dev.obiente.nextcloudnative.app

import java.io.IOException
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopAccountMemoryRetirementTest {
    @Test
    fun `committed credential removal retires memory before journal commit can fail`() = runBlocking {
        val events = mutableListOf<String>()

        val removed = removeDesktopAccountBeforeSyncPairCleanup(
            accountId = "a".repeat(64),
            accountStorageKey = "b".repeat(64),
            prepareCleanup = { _, _, _, _ -> events += "prepare" },
            commitCleanup = { events += "commit"; throw IOException("disk full") },
            clearCleanup = { events += "clear" },
            accountOwnership = { DesktopAccountOwnership.Absent },
            removeCredential = { events += "credential"; true },
            removeSyncPairs = { events += "cleanup" },
            retireCommittedAccount = { events += "retire" },
            recordCleanupFailure = { events += "failure" },
        )

        assertTrue(removed)
        assertEquals(listOf("prepare", "credential", "retire", "commit", "failure"), events)
    }

    @Test
    fun `post-commit credential throw retires but confirmed presence does not`() = runBlocking {
        suspend fun attempt(ownership: DesktopAccountOwnership): Boolean {
            var retired = false
            runCatching {
                removeDesktopAccountBeforeSyncPairCleanup(
                    accountId = "c".repeat(64),
                    prepareCleanup = { _, _, _, _ -> },
                    commitCleanup = {},
                    clearCleanup = {},
                    accountOwnership = { ownership },
                    removeCredential = { throw IOException("credential result lost") },
                    removeSyncPairs = {},
                    retireCommittedAccount = { retired = true },
                    recordCleanupFailure = {},
                )
            }
            return retired
        }

        assertTrue(attempt(DesktopAccountOwnership.Absent))
        assertFalse(attempt(DesktopAccountOwnership.Present))
    }

    @Test
    fun `committed removal fences file cache before physical cleanup can fail`() = runBlocking {
        val root = Files.createTempDirectory("desktop-file-cache-commit-fence-").toFile()
        val preferences = Preferences.userRoot().node("desktop-file-cache-commit-fence-${UUID.randomUUID()}")
        val accountId = "d".repeat(64)
        val cache = DesktopFileReadCache(root, preferences = preferences)
        val staleProducer = checkNotNull(cache.producer(accountId))
        val privateContent = NextcloudFileContent(
            "private".encodeToByteArray(),
            "text/plain",
            "etag-private",
        )
        try {
            assertTrue(
                cache.storeContent(
                    accountId,
                    "Notes/private.txt",
                    privateContent,
                    cacheProducer = staleProducer,
                ),
            )

            val removed = removeDesktopAccountBeforeSyncPairCleanup(
                accountId = accountId,
                prepareCleanup = { _, _, _, _ -> },
                commitCleanup = {},
                clearCleanup = {},
                accountOwnership = { DesktopAccountOwnership.Absent },
                removeCredential = { true },
                removeSyncPairs = {
                    assertTrue(root.resolve(accountId).isDirectory)
                    assertFalse(
                        cache.storeContent(
                            accountId,
                            "Notes/late.txt",
                            privateContent,
                            cacheProducer = staleProducer,
                        ),
                    )
                    error("synthetic cleanup failure before physical cache removal")
                },
                retireCommittedAccount = { cache.retireAccount(accountId) },
                recordCleanupFailure = {},
            )

            assertTrue(removed)
            assertFalse(root.resolve(accountId).resolve("index-v1.json").readText().contains("late.txt"))
        } finally {
            runCatching { cache.removeAccount(accountId) }
            preferences.removeNode()
            root.deleteRecursively()
        }
    }

    @Test
    fun unknownCredentialRemovalFailureKeepsMemoryActive() = runBlocking {
        var memoryRetired = false

        assertFailsWith<IllegalStateException> {
            removeDesktopAccountBeforeSyncPairCleanup(
                accountId = ACCOUNT_ID,
                prepareCleanup = { _, _, _, _ -> },
                commitCleanup = {},
                clearCleanup = {},
                accountOwnership = { DesktopAccountOwnership.Unknown },
                removeCredential = { error("credential removal outcome is unknown") },
                removeSyncPairs = {},
                retireCommittedAccount = { memoryRetired = true },
                recordCleanupFailure = {},
            )
        }

        assertFalse(memoryRetired)
    }

    @Test
    fun journalReportsPendingCleanupSeparatelyFromUnknownData() {
        val preferences = Preferences.userRoot().node("desktop-memory-cleanup-test-${UUID.randomUUID()}")
        try {
            val journal = DesktopAccountSyncPairCleanupJournal(preferences)
            journal.prepare(ACCOUNT_ID, MUTATION_SCOPE, ACCOUNT_STORAGE_KEY)

            val block = journal.accountActivationBlock(ACCOUNT_ID, ACCOUNT_STORAGE_KEY)
            assertEquals(DesktopAccountActivationBlock.PendingCleanup, block)
            val failure = assertFailsWith<IllegalStateException> {
                requireDesktopAccountActivationAllowed(block)
            }
            assertEquals(DESKTOP_PENDING_CLEANUP_STATE_MESSAGE, failure.message)

            preferences.put("fsac.$ACCOUNT_ID", "future-phase")
            val unknownBlock = journal.accountActivationBlock(ACCOUNT_ID, ACCOUNT_STORAGE_KEY)
            assertEquals(DesktopAccountActivationBlock.UnknownJournalData, unknownBlock)
            val unknownFailure = assertFailsWith<IllegalStateException> {
                requireDesktopAccountActivationAllowed(unknownBlock)
            }
            assertEquals(DESKTOP_UNKNOWN_CLEANUP_STATE_MESSAGE, unknownFailure.message)
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun v3CleanupBlocksCanonicalEquivalentAccountActivationByStorageKey() {
        val preferences = Preferences.userRoot().node("desktop-memory-cleanup-test-${UUID.randomUUID()}")
        val original = NextcloudSession("https://cloud.example.test", "alice", "password")
        val equivalent = original.copy(serverUrl = "https://CLOUD.EXAMPLE.TEST:443/")
        val originalProviderId = desktopFileCacheAccountId(original)
        val equivalentProviderId = desktopFileCacheAccountId(equivalent)
        try {
            assertEquals(original.accountId, equivalent.accountId)
            assertNotEquals(originalProviderId, equivalentProviderId)
            val journal = DesktopAccountSyncPairCleanupJournal(preferences)
            journal.prepare(originalProviderId, MUTATION_SCOPE, original.accountId.storageKey)

            assertTrue(journal.blocksAccountActivation(equivalentProviderId, equivalent.accountId.storageKey))
            assertFailsWith<IllegalStateException> {
                journal.requireAccountActivationAllowed(equivalent.accountRecord())
            }
        } finally {
            preferences.removeNode()
        }
    }

    @Test
    fun cleanupWithoutStorageKeyBlocksCanonicalEquivalentActivationUntilRecovery() {
        val original = NextcloudSession("https://cloud.example.test", "alice", "password")
        val equivalent = original.copy(serverUrl = "https://CLOUD.EXAMPLE.TEST:443/")
        val originalProviderId = desktopFileCacheAccountId(original)
        val equivalentProviderId = desktopFileCacheAccountId(equivalent)
        assertNotEquals(originalProviderId, equivalentProviderId)

        listOf("prepared", "v2|prepared|$MUTATION_SCOPE").forEach { encoded ->
            val preferences = Preferences.userRoot().node("desktop-memory-cleanup-test-${UUID.randomUUID()}")
            try {
                preferences.put("fsac.$originalProviderId", encoded)
                val journal = DesktopAccountSyncPairCleanupJournal(preferences)

                assertTrue(journal.blocksAccountActivation(equivalentProviderId, equivalent.accountId.storageKey))
                assertFailsWith<IllegalStateException> {
                    journal.requireAccountActivationAllowed(equivalent.accountRecord())
                }
            } finally {
                preferences.removeNode()
            }
        }
    }

    private companion object {
        const val ACCOUNT_ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val ACCOUNT_STORAGE_KEY = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val MUTATION_SCOPE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
