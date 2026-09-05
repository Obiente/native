package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudSession
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidAccountPreviewCleanupRecoveryTest {
    @Test
    fun readdedCanonicalAccountRetriesTheRemovedPreviewIdentity() = runBlocking {
        val removed = session().copy(serverUrl = "HTTPS://CLOUD.EXAMPLE.TEST:443/")
        val readded = session().copy(serverUrl = "https://cloud.example.test")
        val pending = pendingAndroidAccountRemovalCleanup(removed)
        val retried = mutableListOf<Pair<String, String?>>()

        retryAndroidAccountOwnedStateCleanup(readded, pending) { _, workIdentity, previewIdentity, _ ->
            retried += workIdentity to previewIdentity
        }

        assertEquals(removed.accountId, readded.accountId)
        assertFalse(NextcloudDocumentIds.cacheAccountId(removed) == NextcloudDocumentIds.cacheAccountId(readded))
        assertEquals(
            NextcloudDocumentIds.accountKey(removed) to NextcloudDocumentIds.cacheAccountId(removed),
            retried.single(),
        )
    }

    @Test
    fun legacyCleanupWithoutPreviewIdentityDoesNotTargetAReaddedAccount() = runBlocking {
        val readded = session().copy(serverUrl = "https://cloud.example.test")
        val legacy = requireNotNull(
            decodeAndroidPendingAccountRemovalCleanup(
                "${readded.accountId.storageKey}:${NextcloudDocumentIds.accountKey(readded)}",
            ),
        )
        val retriedPreviewIdentities = mutableListOf<String?>()

        retryAndroidAccountOwnedStateCleanup(readded, legacy) { _, _, previewIdentity, _ ->
            retriedPreviewIdentities += previewIdentity
        }

        assertEquals(listOf<String?>(null), retriedPreviewIdentities)
        assertFalse(NextcloudDocumentIds.cacheAccountId(readded) in retriedPreviewIdentities)
    }

    @Test
    fun cleanupTombstoneCarriesThePathConfinedPreviewIdentityAndReadsLegacyEntries() {
        val pending = pendingAndroidAccountRemovalCleanup(session())

        assertEquals(64, requireNotNull(pending.previewCacheIdentity).length)
        assertEquals(64, requireNotNull(pending.durableMutationIdentity).length)
        assertTrue(pending.previewCacheIdentity.startsWith(pending.workIdentity))
        assertEquals(pending, decodeAndroidPendingAccountRemovalCleanup(encodeAndroidPendingAccountRemovalCleanup(pending)))
        assertNull(
            decodeAndroidPendingAccountRemovalCleanup(
                "${pending.accountStorageKey}:${pending.workIdentity}",
            )?.previewCacheIdentity,
        )
        assertNull(
            decodeAndroidPendingAccountRemovalCleanup(
                "${pending.accountStorageKey}:${pending.workIdentity}",
            )?.durableMutationIdentity,
        )
        val mismatchedIdentity = if (pending.workIdentity.first() == 'f') "e".repeat(64) else "f".repeat(64)
        assertFailsWith<IllegalArgumentException> { pending.copy(previewCacheIdentity = mismatchedIdentity) }
    }

    @Test
    fun previewDeletionFailureRetainsCommittedCleanupUntilARecoverySucceeds() = runBlocking {
        val pending = pendingAndroidAccountRemovalCleanup(session())
        var previewAttempts = 0
        var otherCleanupAttempts = 0
        var cleanupMarkerClears = 0
        var diagnosed = 0
        var failPreviewDeletion = true
        suspend fun removeAccountOwnedState() {
            runAndroidAccountOwnedStateCleanups(
                previewCacheIdentity = pending.previewCacheIdentity,
                clearPreviewAccount = {
                    previewAttempts += 1
                    if (failPreviewDeletion) error("synthetic preview deletion failure")
                },
                cleanups = listOf({ otherCleanupAttempts += 1 }),
            )
        }

        removeAndroidAccountCredentialData(
            active = false,
            removeQueuedUploads = { removeAccountOwnedState() },
            clearActiveAccount = {},
            rollbackActiveRemoval = {},
            persistInactiveRemoval = {},
            rollbackInactiveRemoval = {},
            completeCommittedCleanup = { cleanupMarkerClears += 1 },
            recordCommittedCleanupFailure = { diagnosed += 1 },
        )

        assertEquals(0, cleanupMarkerClears)
        assertEquals(1, diagnosed)
        failPreviewDeletion = false
        retryAndroidAccountRemovalCleanup(
            accountOwnedByRegistry = false,
            removeAccountOwnedWork = { removeAccountOwnedState() },
            clearCleanup = { cleanupMarkerClears += 1 },
        )
        assertEquals(1, cleanupMarkerClears)
        assertEquals(2, previewAttempts)
        assertEquals(2, otherCleanupAttempts)
    }

    @Test
    fun journaledPreviewDeletionIsPathConfinedAndIdempotent() = runBlocking {
        val root = Files.createTempDirectory("android-preview-account-cleanup-").toFile()
        try {
            val pending = pendingAndroidAccountRemovalCleanup(session())
            val previewIdentity = requireNotNull(pending.previewCacheIdentity)
            val cache = AndroidNativeMediaPreviewCache(root, maximumBytes = 1_024L)
            val key = NativeMediaPreviewCacheKey(previewIdentity, 1L, "etag", 64, "decoder-v1")
            assertTrue(cache.store(key, byteArrayOf(1), cache.accountGeneration(previewIdentity)))

            repeat(2) {
                runAndroidAccountOwnedStateCleanups(
                    previewCacheIdentity = previewIdentity,
                    clearPreviewAccount = cache::clearAccount,
                    cleanups = emptyList(),
                )
            }

            assertNull(cache.load(key))
            assertFailsWith<IllegalArgumentException> { cache.clearAccount("../outside") }
        } finally {
            root.deleteRecursively()
        }
        Unit
    }

    private fun session() = NextcloudSession(
        serverUrl = "https://cloud.example.test/nextcloud",
        loginName = "preview-user",
        appPassword = "fixture-password",
    )
}
