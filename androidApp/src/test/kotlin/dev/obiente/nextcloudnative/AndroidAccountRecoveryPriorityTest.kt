package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncPair
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
    fun accountRetirementRetainsPairMappingUntilEverySafGrantReleaseIsAttempted() = runBlocking {
        val retiredPairs = listOf(
            fileSyncPair("retired-a", "content://documents/first"),
            fileSyncPair("retired-b", "content://documents/second"),
        )
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            retireConfiguredFileSyncAccountPairs(
                retiredPairs = retiredPairs,
                retainedPairs = emptyList(),
                reconcileLocalDownloads = { true },
                cancelSchedule = {},
                cancelNotification = {},
                persistRetirement = { events += "persist-retirement" },
                releaseLocalGrant = { localRootId ->
                    events += "release-$localRootId"
                    if (localRootId.endsWith("first")) error("synthetic grant release interruption")
                },
            )
        }

        assertEquals(listOf("release-content://documents/first"), events)
    }

    private fun fileSyncPair(id: String, localRootId: String) = FileSyncPair(
        id = id,
        accountId = "removed-account",
        localRootId = localRootId,
        remoteRootPath = "Documents",
        configuration = FileSyncConfiguration(deviceLabel = "Phone"),
    )
}
