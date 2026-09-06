package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncBaseline
import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.SyncEntryKind
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
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

    @Test
    fun `account removal recovers target downloads before credential deletion`() = runBlocking {
        val retained = pair("retained", "other-account")
        val first = pair("first", accountKey)
        val second = pair("second", accountKey)
        val events = mutableListOf<String>()

        removeAndroidAccountCredentialData(
            active = true,
            prepareAccountRemoval = {
                reconcileConfiguredFileSyncAccountDownloadsBeforeCredentialRemoval(
                    pairs = listOf(retained, first, second),
                    accountId = accountKey,
                    reconcileLocalDownloads = { pair ->
                        events += "recover:${pair.id}"
                        true
                    },
                )
            },
            removeQueuedUploads = { events += "remove-owned-state" },
            clearActiveAccount = { events += "delete-credential" },
            rollbackActiveRemoval = {},
            persistInactiveRemoval = {},
            rollbackInactiveRemoval = {},
        )

        assertEquals(
            listOf("recover:first", "recover:second", "delete-credential", "remove-owned-state"),
            events,
        )
    }

    @Test
    fun `account removal blocks unclassified ownership evidence`() {
        val root = Files.createTempDirectory("saf-provider-removal-invalid-row-").toFile()
        try {
            val invalid = root.resolve("unclassified.row").apply { writeBytes(byteArrayOf(0x01)) }
            val store = AndroidSafDownloadOwnershipStore(root)

            val removalReady = reconcileSafDownloadsBeforePairRemoval(
                hasPersistedGrant = true,
                hasPendingRecovery = store.hasPendingTransactions(),
                reconcile = { store.indexed() },
            )

            assertFalse(removalReady)
            assertTrue(invalid.isFile)
            assertTrue(store.hasPendingTransactions())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `cancelled own provider retirement stops before the next directory`() {
        val events = mutableListOf<String>()
        var continuationChecks = 0

        assertFailsWith<CancellationException> {
            reconcileRecordedAndroidSafDownloadDirectories(
                candidates = listOf("first", "second"),
                hasPendingRecovery = { true },
                hasPendingForDirectory = {
                    events += "check:$it"
                    true
                },
                shouldContinue = {
                    continuationChecks += 1
                    continuationChecks < 3
                },
                reconcileDirectory = { events += "reconcile:$it" },
            )
        }

        assertEquals(listOf("check:first", "reconcile:first"), events)
    }

    @Test
    fun `relocated recovery directory keeps its exact relative path`() {
        val root = NextcloudDocumentIds.documentId(accountKey, "Sync")
        val relocated = NextcloudDocumentIds.documentId(accountKey, "Sync/Moved/Parent")

        assertEquals(
            AndroidSafOwnedDownloadRecoveryDirectory(relocated, "Moved/Parent"),
            androidSafOwnedDownloadRecoveryDirectory(root, relocated),
        )
    }

    private fun pair(id: String, owner: String) = FileSyncPair(
        id = id,
        accountId = owner,
        localRootId = "content://external/tree/$id",
        remoteRootPath = id,
        configuration = FileSyncConfiguration(deviceLabel = "Phone"),
    )
}
