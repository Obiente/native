package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.NextcloudAccountRegistry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.accountRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidAccountRecoveryPriorityTest {
    @Test
    fun accountRegistryAdapterPreservesTheActiveAccount() {
        val expected = NextcloudSession("https://cloud.example.test/nextcloud", "alice", "secret")
        val registry = NextcloudAccountRegistry.Empty.upsertAndSelect(expected.accountRecord())

        assertEquals(
            AndroidExpectedAccountState.Active,
            registry.asAccountRetentionSnapshot().expectedAccountState(NextcloudDocumentIds.accountKey(expected)),
        )
    }

    @Test
    fun scheduleRestorationRetriesOnlyWhenTheExpectedAccountMayStillBeActive() {
        val expected = NextcloudSession("https://cloud.example.test/nextcloud", "alice", "secret")
        val other = NextcloudSession("https://cloud.example.test/nextcloud", "bob", "other-secret")
        val expectedIdentity = NextcloudDocumentIds.accountKey(expected)

        assertTrue(
            shouldRetryAndroidFileSyncScheduleRestoration(
                expectedIdentity,
                AndroidAccountRetentionSnapshot.Available(
                    accounts = listOf(expected.accountRecord(), other.accountRecord()),
                    activeAccountId = expected.accountId,
                ),
            ),
        )
        assertFalse(
            shouldRetryAndroidFileSyncScheduleRestoration(
                expectedIdentity,
                AndroidAccountRetentionSnapshot.Available(
                    accounts = listOf(expected.accountRecord(), other.accountRecord()),
                    activeAccountId = other.accountId,
                ),
            ),
        )
        assertTrue(
            shouldRetryAndroidFileSyncScheduleRestoration(
                expectedIdentity,
                AndroidAccountRetentionSnapshot.Unavailable,
            ),
        )
    }

    @Test
    fun offlineJobRetriesUntilTheExpectedAccountIsConfirmedAbsent() {
        val expected = NextcloudSession("https://cloud.example.test/nextcloud", "alice", "secret")
        val other = NextcloudSession("https://cloud.example.test/nextcloud", "bob", "other-secret")
        val expectedIdentity = NextcloudDocumentIds.accountKey(expected)

        assertTrue(
            shouldRetryAndroidOfflineJobForMissingSession(
                expectedIdentity,
                AndroidAccountRetentionSnapshot.Available(
                    accounts = listOf(expected.accountRecord(), other.accountRecord()),
                    activeAccountId = expected.accountId,
                ),
            ),
        )
        assertTrue(
            shouldRetryAndroidOfflineJobForMissingSession(
                expectedIdentity,
                AndroidAccountRetentionSnapshot.Unavailable,
            ),
        )
        assertTrue(
            shouldRetryAndroidOfflineJobForMissingSession(
                expectedIdentity,
                AndroidAccountRetentionSnapshot.Available(
                    accounts = listOf(expected.accountRecord(), other.accountRecord()),
                    activeAccountId = other.accountId,
                ),
            ),
        )
        assertFalse(
            shouldRetryAndroidOfflineJobForMissingSession(
                expectedIdentity,
                AndroidAccountRetentionSnapshot.Available(
                    accounts = listOf(other.accountRecord()),
                    activeAccountId = other.accountId,
                ),
            ),
        )
    }

    @Test
    fun accountRetirementPersistsAfterEveryGrantCleanupIsPrepared() = runBlocking {
        val retiredPairs = listOf(
            fileSyncPair("retired-a", "content://documents/first"),
            fileSyncPair("retired-b", "content://documents/second"),
        )
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            retireConfiguredFileSyncAccountPairs(
                retiredPairs = retiredPairs,
                reconcileLocalDownloads = { true },
                cancelSchedule = {},
                cancelNotification = {},
                prepareLocalGrantCleanup = { pairId -> events += "prepare-$pairId" },
                persistRetirement = { events += "persist-retirement" },
                finishLocalGrantCleanup = { pairId ->
                    events += "finish-$pairId"
                    if (pairId == "retired-a") error("synthetic grant release interruption")
                },
            )
        }

        assertEquals(
            listOf("prepare-retired-a", "prepare-retired-b", "persist-retirement", "finish-retired-a"),
            events,
        )
    }

    private fun fileSyncPair(id: String, localRootId: String) = FileSyncPair(
        id = id,
        accountId = "removed-account",
        localRootId = localRootId,
        remoteRootPath = "Documents",
        configuration = FileSyncConfiguration(deviceLabel = "Phone"),
    )
}
