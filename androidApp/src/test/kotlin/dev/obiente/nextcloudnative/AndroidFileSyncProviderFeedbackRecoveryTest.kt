package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.SyncEntryKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AndroidFileSyncProviderFeedbackRecoveryTest {
    private val applicationId = "dev.obiente.nextcloudnative.dev"
    private val ownAuthority = nextcloudDocumentsAuthority(applicationId)
    private val accountKey = "0123456789abcdef0123456789abcdef"

    @Test
    fun `restored own provider root stops before remote preparation`() {
        var remoteCalls = 0
        val rejection = androidFileSyncRootRejection(
            "content://$ownAuthority/tree/${NextcloudDocumentIds.rootId(accountKey)}",
            applicationId,
        )
        if (rejection == null) remoteCalls += 1

        assertEquals(AndroidPickerUriRejection.OwnDocumentsProvider, rejection)
        assertEquals(0, remoteCalls)
    }

    @Test
    fun `legacy own provider recovery visits only recorded parent and allows removal`() = runBlocking {
        val pair = FileSyncPair(
            id = "pair",
            accountId = accountKey,
            localRootId = "content://$ownAuthority/tree/${NextcloudDocumentIds.rootId(accountKey)}",
            remoteRootPath = "Mirror",
            configuration = FileSyncConfiguration(deviceLabel = "Phone"),
            baselines = listOf(
                FileSyncBaseline("Archive/kept.txt", SyncEntryKind.File, "local", "remote"),
            ),
        )
        val recordedStageId = NextcloudDocumentIds.documentId(
            accountKey,
            "Pending/.nextcloud-native-download-123e4567-e89b-12d3-a456-426614174000",
        )
        val candidates = androidSafOwnedDownloadRecoveryDirectories(
            NextcloudDocumentIds.rootId(accountKey),
            androidSafOwnedDownloadRecoveryPaths(pair),
            setOf(recordedStageId),
        )
        val pendingDocumentIds = mutableSetOf(NextcloudDocumentIds.documentId(accountKey, "Pending"))
        val events = mutableListOf<String>()

        val removed = removeConfiguredFileSyncPair(
            reconcileLocalDownloads = {
                reconcileRecordedAndroidSafDownloadDirectories(
                    candidates = candidates,
                    hasPendingRecovery = pendingDocumentIds::isNotEmpty,
                    hasPendingForDirectory = { candidate -> candidate.documentId in pendingDocumentIds },
                    reconcileDirectory = { candidate ->
                        events += "reconcile:${candidate.relativePath}"
                        pendingDocumentIds -= candidate.documentId
                    },
                )
            },
            cleanRemoteUploads = { events += "remote-cleanup"; true },
            cleanLedger = { events += "ledger-cleanup" },
            persistRemoval = { events += "persist-removal" },
            cancelSchedule = { events += "cancel-schedule" },
            releaseLocalGrant = { events += "release-grant" },
        )

        assertTrue(removed)
        assertEquals(
            listOf(
                "reconcile:Pending",
                "remote-cleanup",
                "ledger-cleanup",
                "persist-removal",
                "cancel-schedule",
                "release-grant",
            ),
            events,
        )
    }
}
