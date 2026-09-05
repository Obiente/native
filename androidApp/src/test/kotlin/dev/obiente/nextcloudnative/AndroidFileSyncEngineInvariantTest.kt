package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.FileSyncConfiguration
import dev.obiente.nextcloudnative.app.FileSyncContentVerificationCandidate
import dev.obiente.nextcloudnative.app.FileSyncCoordinatorState
import dev.obiente.nextcloudnative.app.FileSyncDirection
import dev.obiente.nextcloudnative.app.FileSyncOperation
import dev.obiente.nextcloudnative.app.FileSyncPair
import dev.obiente.nextcloudnative.app.FileSyncPendingUploadCleanup
import dev.obiente.nextcloudnative.app.LocalSyncEntry
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.scanFileSyncPair
import dev.obiente.nextcloudnative.app.claimNextFileSyncOperation
import dev.obiente.nextcloudnative.app.releaseCancelledFileSyncOperation
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex

class AndroidFileSyncEngineInvariantTest {
    @Test
    fun verifiedContentHashWinsOverEarlierSafScanEvidence() {
        val verified = LocalSyncEntry(
            relativePath = "Archive.bin",
            kind = SyncEntryKind.File,
            revision = "same-revision",
            size = 2L,
            contentHash = "sha256:${"2".repeat(64)}",
        )
        val unverified = verified.copy(relativePath = "Other.bin", contentHash = null)
        val scanHashes = mapOf(
            verified.relativePath to "sha256:${"1".repeat(64)}",
            unverified.relativePath to "sha256:${"3".repeat(64)}",
        )

        val reconciled = retainNewestAndroidFileSyncLocalContentHashes(
            localEntries = listOf(verified, unverified),
            scanContentHashes = scanHashes,
            verifiedPaths = setOf(verified.relativePath),
        )

        assertEquals(verified.contentHash, reconciled[0].contentHash)
        assertEquals(scanHashes.getValue(unverified.relativePath), reconciled[1].contentHash)
    }

    @Test
    fun accountRetirementRemovesOnlyItsPersistedSyncPairsAndLabels() {
        val first = FileSyncPair(
            id = "first-pair",
            accountId = "first-account",
            localRootId = "first-root",
            remoteRootPath = "Pictures",
            configuration = FileSyncConfiguration(deviceLabel = "Phone"),
        )
        val retained = FileSyncPair(
            id = "retained-pair",
            accountId = "retained-account",
            localRootId = "retained-root",
            remoteRootPath = "Documents",
            configuration = FileSyncConfiguration(deviceLabel = "Phone"),
        )
        val state = AndroidFileSyncPersistedState(
            coordinator = FileSyncCoordinatorState(listOf(first, retained)),
            localDisplayNames = mapOf(first.id to "Camera", retained.id to "Documents"),
        )

        val retired = removeAndroidFileSyncAccountPairs(state, first.accountId)

        assertEquals(listOf(retained), retired.coordinator.pairs)
        assertEquals(mapOf(retained.id to "Documents"), retired.localDisplayNames)
    }

    @Test
    fun accountRetirementPreservesPairsThatStillOwnRemoteUploadRecovery() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Pictures",
            configuration = FileSyncConfiguration(deviceLabel = "Phone"),
            pendingUploadCleanups = listOf(
                FileSyncPendingUploadCleanup(
                    uploadId = "123e4567-e89b-12d3-a456-426614174000",
                    relativePath = "photo.jpg",
                ),
            ),
        )
        val state = AndroidFileSyncPersistedState(
            coordinator = FileSyncCoordinatorState(listOf(pair)),
            localDisplayNames = mapOf(pair.id to "Camera"),
        )

        assertFailsWith<IllegalArgumentException> {
            removeAndroidFileSyncAccountPairs(state, pair.accountId)
        }
        assertEquals(listOf(pair), state.coordinator.pairs)
        assertEquals(mapOf(pair.id to "Camera"), state.localDisplayNames)
    }

    @Test
    fun scheduleRestorationRejectsAStaleAccountSwitch() {
        val selected = NextcloudSession("https://cloud.example.test/nextcloud", "alice", "secret")
        val other = NextcloudSession("https://cloud.example.test/nextcloud", "bob", "other-secret")

        assertTrue(
            isAndroidFileSyncScheduleRestorationCurrent(
                NextcloudDocumentIds.accountKey(selected),
                selected,
            ),
        )
        assertFalse(
            isAndroidFileSyncScheduleRestorationCurrent(
                NextcloudDocumentIds.accountKey(selected),
                other,
            ),
        )
    }

    @Test
    fun scheduleRestorationStopsImmediateRetriesAfterTheBoundedBudget() {
        assertEquals(BackgroundSyncWorkerDisposition.Retry, scheduleRestorationFailureDisposition(0))
        assertEquals(BackgroundSyncWorkerDisposition.Retry, scheduleRestorationFailureDisposition(1))
        assertEquals(BackgroundSyncWorkerDisposition.Retry, scheduleRestorationFailureDisposition(2))
        assertEquals(BackgroundSyncWorkerDisposition.Retry, scheduleRestorationFailureDisposition(20))
    }

    @Test
    fun largeFileDirectoryReplacementKeepsTheDirectoryUntilProtectedPublication() {
        val directory = RemoteSyncEntry("archive.bin", SyncEntryKind.Directory, "directory-etag")

        assertTrue(
            shouldProtectAndroidFileSyncDirectoryReplacement(
                LocalSyncEntry("archive.bin", SyncEntryKind.File, "local-1", 21L * 1024L * 1024L),
                directory,
            ),
        )
        assertTrue(
            shouldProtectAndroidFileSyncDirectoryReplacement(
                LocalSyncEntry("archive.bin", SyncEntryKind.File, "local-1", size = null),
                directory,
            ),
        )
        assertFalse(
            shouldProtectAndroidFileSyncDirectoryReplacement(
                LocalSyncEntry("archive.bin", SyncEntryKind.File, "local-1", 1L * 1024L * 1024L),
                directory,
            ),
        )
    }

    @Test
    fun workerCancellationIsNeverConvertedIntoFailedSyncWork() {
        val cancellation = CancellationException("Worker stopped")

        assertEquals(
            cancellation,
            assertFailsWith<CancellationException> {
                rethrowAndroidFileSyncCancellation(cancellation)
            },
        )
        rethrowAndroidFileSyncCancellation(IllegalStateException("ordinary operation failure"))
    }

    @Test
    fun cancellationCancelsTheActiveTransportCall() {
        var transportCancelled = false
        val cancellation = AndroidFileSyncRunCancellation { true }
        cancellation.setOnCancelAction { transportCancelled = true }

        cancellation.cancel()

        assertTrue(transportCancelled)
        assertFailsWith<CancellationException> { cancellation.throwIfCancelled() }
    }

    @Test
    fun cancelledWorkReturnsToReadyWithoutConsumingItsAttempt() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Pictures",
            configuration = FileSyncConfiguration(deviceLabel = "Phone"),
        )
        val planned = scanFileSyncPair(
            FileSyncCoordinatorState(listOf(pair)),
            pair.id,
            localEntries = listOf(LocalSyncEntry("large.bin", SyncEntryKind.File, "local-1")),
            remoteEntries = emptyList(),
            nowEpochMillis = 1L,
        )
        val claim = claimNextFileSyncOperation(planned, pair.id, nowEpochMillis = 2L)
        val workId = requireNotNull(claim.command).workId

        val released = releaseCancelledFileSyncOperation(claim.state, pair.id, workId)
            .pairs.single().workItems.single()

        assertEquals(dev.obiente.nextcloudnative.app.FileSyncExecutionState.Ready, released.state)
        assertEquals(0, released.attemptCount)
        assertEquals(null, released.lastAttemptEpochMillis)
    }

    @Test
    fun weakSafRevisionVerificationCompletesOneUnboundedGenerationWithoutDurableSlices() {
        val candidate = FileSyncContentVerificationCandidate(
            relativePath = "Archive/large.bin",
            localRevision = "metadata-only-revision",
            remoteEtag = "\"remote-generation\"",
            expectedSizeBytes = Long.MAX_VALUE,
        )
        var observedMaximum = 0L

        val result = verifyAndroidFileSyncGeneration(
            candidate = candidate,
            readLocal = { expectedBytes, maximumBytes ->
                assertEquals(Long.MAX_VALUE, expectedBytes)
                observedMaximum = maximumBytes
                AndroidFileSyncContentHashRead("sha256:${"a".repeat(64)}", Long.MAX_VALUE)
            },
            verifyRemote = { hash, expectedBytes, maximumBytes ->
                assertEquals("sha256:${"a".repeat(64)}", hash)
                assertEquals(Long.MAX_VALUE, expectedBytes)
                assertEquals(Long.MAX_VALUE, maximumBytes)
                true
            },
        )

        assertEquals(Long.MAX_VALUE, observedMaximum)
        assertEquals(result.localContentHash, result.matchingContentHash)
    }

    @Test
    fun downloadsStreamIntoTheProtectedLocalStageWithoutACacheDuplicate() {
        val destination = ByteArrayOutputStream()
        var localStage: ByteArrayOutputStream? = null
        var remoteDestination: ByteArrayOutputStream? = null

        streamAndroidFileSyncDownload(
            declaredByteCount = 3L,
            writeLocal = { write ->
                localStage = destination
                write(destination)
            },
            readRemote = { output, maximumBytes ->
                assertEquals(3L, maximumBytes)
                remoteDestination = output as ByteArrayOutputStream
                output.write(byteArrayOf(1, 2, 3))
            },
        )

        assertTrue(localStage === remoteDestination)
        assertEquals(listOf<Byte>(1, 2, 3), destination.toByteArray().toList())
    }

    @Test
    fun androidPlanningRetainsTheSnapshotCompatibleWorkLimit() {
        assertEquals(10_000, ANDROID_FILE_SYNC_MAX_WORK_ITEMS)
        assertEquals(1_000, ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE)
        assertTrue(ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE in 1 until ANDROID_FILE_SYNC_MAX_WORK_ITEMS)
    }

    @Test
    fun androidPlanningKeepsActionableWorkBeyondAFullSkippedPrefix() {
        val pair = FileSyncPair(
            id = "pair",
            accountId = "account",
            localRootId = "root",
            remoteRootPath = "Pictures",
            configuration = FileSyncConfiguration(
                direction = FileSyncDirection.DownloadOnly,
                deviceLabel = "Phone",
            ),
        )
        val localEntries = (0 until ANDROID_FILE_SYNC_MAX_WORK_ITEMS).map { index ->
            val suffix = index.toString().padStart(5, '0')
            LocalSyncEntry("Local/$suffix.jpg", SyncEntryKind.File, "local-$suffix")
        }
        val remoteOnly = RemoteSyncEntry("Remote/download.jpg", SyncEntryKind.File, "remote-download")

        val planned = scanFileSyncPair(
            FileSyncCoordinatorState(listOf(pair)),
            pair.id,
            localEntries,
            listOf(remoteOnly),
            nowEpochMillis = 10L,
            maximumWorkItems = ANDROID_FILE_SYNC_MAX_WORK_ITEMS,
            reservedNonExecutableWorkItems = ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE,
        ).pairs.single()

        assertTrue(planned.workItems.any { it.operation is FileSyncOperation.Download })
        assertTrue(
            planned.workItems.count { it.operation is FileSyncOperation.Skipped } <=
                ANDROID_FILE_SYNC_NON_EXECUTABLE_RESERVE,
        )
    }

    @Test
    fun pairRemovalDoesNotPersistOrCancelWhenLedgerCleanupFails() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            removeConfiguredFileSyncPair(
                reconcileLocalDownloads = {
                    events += "reconcile"
                    true
                },
                cleanRemoteUploads = { events += "remote"; true },
                cleanLedger = {
                    events += "clean"
                    error("ledger unavailable")
                },
                persistRemoval = { events += "persist" },
                cancelSchedule = { events += "cancel" },
                releaseLocalGrant = { events += "release" },
            )
        }

        assertEquals(listOf("reconcile", "remote", "clean"), events)
    }

    @Test
    fun pairRemovalReconcilesBeforePersistingAndReleasingItsGrant() = runBlocking {
        val events = mutableListOf<String>()

        val removed = removeConfiguredFileSyncPair(
            reconcileLocalDownloads = {
                events += "reconcile"
                true
            },
            cleanRemoteUploads = { events += "remote"; true },
            cleanLedger = { events += "clean" },
            persistRemoval = { events += "persist" },
            cancelSchedule = { events += "cancel" },
            releaseLocalGrant = { events += "release" },
        )

        assertTrue(removed)
        assertEquals(listOf("reconcile", "remote", "clean", "persist", "cancel", "release"), events)
    }

    @Test
    fun pairRemovalRetainsItsStateAndGrantWhenLocalRecoveryIsUnavailable() = runBlocking {
        val events = mutableListOf<String>()

        val removed = removeConfiguredFileSyncPair(
            reconcileLocalDownloads = {
                events += "reconcile"
                false
            },
            cleanRemoteUploads = { events += "remote"; true },
            cleanLedger = { events += "clean" },
            persistRemoval = { events += "persist" },
            cancelSchedule = { events += "cancel" },
            releaseLocalGrant = { events += "release" },
        )

        assertFalse(removed)
        assertEquals(listOf("reconcile"), events)
    }

    @Test
    fun pairRemovalRetainsItsStateWhenRemoteRecoveryIsUnavailable() = runBlocking {
        val events = mutableListOf<String>()

        val removed = removeConfiguredFileSyncPair(
            reconcileLocalDownloads = {
                events += "reconcile"
                true
            },
            cleanRemoteUploads = {
                events += "remote"
                false
            },
            cleanLedger = { events += "clean" },
            persistRemoval = { events += "persist" },
            cancelSchedule = { events += "cancel" },
            releaseLocalGrant = { events += "release" },
        )

        assertFalse(removed)
        assertEquals(listOf("reconcile", "remote"), events)
    }

    @Test
    fun pairRemovalAllowsExpiredSafGrantWhenNoRecoveryIsPending() {
        var reconciled = false

        val safeToRemove = reconcileSafDownloadsBeforePairRemoval(
            hasPersistedGrant = false,
            hasPendingRecovery = false,
        ) { reconciled = true }

        assertTrue(safeToRemove)
        assertFalse(reconciled)
    }

    @Test
    fun pairRemovalDoesNotTraverseALargeGrantedTreeWithoutPendingRecovery() {
        var reconciled = false

        val safeToRemove = reconcileSafDownloadsBeforePairRemoval(
            hasPersistedGrant = true,
            hasPendingRecovery = false,
        ) {
            reconciled = true
            error("A tree without owned recovery rows must not be traversed")
        }

        assertTrue(safeToRemove)
        assertFalse(reconciled)
    }

    @Test
    fun pairRemovalRetainsExpiredSafGrantPairWhileRecoveryIsPending() {
        var reconciled = false

        val safeToRemove = reconcileSafDownloadsBeforePairRemoval(
            hasPersistedGrant = false,
            hasPendingRecovery = true,
        ) { reconciled = true }

        assertFalse(safeToRemove)
        assertFalse(reconciled)
    }

    @Test
    fun pairRemovalRecoveryContinuationTracksCoroutineJobCancellation() {
        val job = Job()
        val shouldContinue = androidFileSyncJobContinuation(job)

        assertTrue(shouldContinue())
        job.cancel()

        assertFalse(shouldContinue())
    }

    @Test
    fun pairRemovalStopsAfterAReconciliationThatCancelsItsJob() = runBlocking {
        val events = mutableListOf<String>()
        val removal = launch {
            removeConfiguredFileSyncPair(
                reconcileLocalDownloads = {
                    events += "reconcile"
                    currentCoroutineContext().cancel()
                    true
                },
                cleanRemoteUploads = { events += "remote"; true },
                cleanLedger = { events += "clean" },
                persistRemoval = { events += "persist" },
                cancelSchedule = { events += "cancel-schedule" },
                releaseLocalGrant = { events += "release" },
            )
        }

        removal.join()

        assertTrue(removal.isCancelled)
        assertEquals(listOf("reconcile"), events)
    }

    @Test
    fun pairRemovalFinishesCleanupNonCancellablyAfterPersistence() = runBlocking {
        val events = mutableListOf<String>()
        lateinit var removal: Job
        removal = launch(start = CoroutineStart.LAZY) {
            removeConfiguredFileSyncPair(
                reconcileLocalDownloads = { events += "reconcile"; true },
                cleanRemoteUploads = { events += "remote"; true },
                cleanLedger = { events += "clean" },
                persistRemoval = {
                    events += "persist"
                    removal.cancel()
                },
                cancelSchedule = { events += "cancel-schedule" },
                releaseLocalGrant = { events += "release" },
            )
        }

        removal.start()
        removal.join()

        assertTrue(removal.isCancelled)
        assertEquals(
            listOf("reconcile", "remote", "clean", "persist", "cancel-schedule", "release"),
            events,
        )
    }

    @Test
    fun pairRemovalCannotBeCancelledBetweenLedgerCleanupAndPersistence() = runBlocking {
        val events = mutableListOf<String>()
        lateinit var removal: Job
        removal = launch(start = CoroutineStart.LAZY) {
            removeConfiguredFileSyncPair(
                reconcileLocalDownloads = { events += "reconcile"; true },
                cleanRemoteUploads = { events += "remote"; true },
                cleanLedger = {
                    events += "clean"
                    removal.cancel()
                },
                persistRemoval = { events += "persist" },
                cancelSchedule = { events += "cancel-schedule" },
                releaseLocalGrant = { events += "release" },
            )
        }

        removal.start()
        removal.join()

        assertTrue(removal.isCancelled)
        assertEquals(
            listOf("reconcile", "remote", "clean", "persist", "cancel-schedule", "release"),
            events,
        )
    }

    @Test
    fun pairRemovalReconcilesDownloadsWhenAnotherPairRetainsTheSafGrant() {
        var reconciled = false

        val safeToRemove = reconcileSafDownloadsBeforePairRemoval(
            hasPersistedGrant = true,
            hasPendingRecovery = true,
        ) { reconciled = true }

        assertTrue(safeToRemove)
        assertTrue(reconciled)
    }

    @Test
    fun accountRetirementReconcilesBeforePersistingAndReleasesOnlyUnsharedSafGrants() = runBlocking {
        val sharedRoot = "content://documents/shared"
        val retiredRoot = "content://documents/retired"
        val retiredPairs = listOf(
            fileSyncPair("retired-a", "removed-account", sharedRoot),
            fileSyncPair("retired-b", "removed-account", retiredRoot),
            fileSyncPair("retired-c", "removed-account", retiredRoot),
        )
        val retainedPairs = listOf(fileSyncPair("retained", "retained-account", sharedRoot))
        val events = mutableListOf<String>()

        retireConfiguredFileSyncAccountPairs(
            retiredPairs = retiredPairs,
            retainedPairs = retainedPairs,
            reconcileLocalDownloads = { pair -> events += "reconcile-${pair.id}"; true },
            cancelSchedule = { pair -> events += "cancel-${pair.id}" },
            cancelNotification = { pair -> events += "cancel-notification-${pair.id}" },
            persistRetirement = { events += "persist-retirement" },
            releaseLocalGrant = { localRootId -> events += "release-$localRootId" },
        )

        assertEquals(
            listOf(
                "reconcile-retired-a",
                "reconcile-retired-b",
                "reconcile-retired-c",
                "cancel-retired-a",
                "cancel-notification-retired-a",
                "cancel-retired-b",
                "cancel-notification-retired-b",
                "cancel-retired-c",
                "cancel-notification-retired-c",
                "persist-retirement",
                "release-$retiredRoot",
            ),
            events,
        )
    }

    @Test
    fun accountRetirementKeepsPairsAndGrantsWhenLocalRecoveryIsUnavailable() = runBlocking {
        val retiredPairs = listOf(
            fileSyncPair("retired-a", "removed-account", "content://documents/first"),
            fileSyncPair("retired-b", "removed-account", "content://documents/second"),
        )
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            retireConfiguredFileSyncAccountPairs(
                retiredPairs = retiredPairs,
                retainedPairs = emptyList(),
                reconcileLocalDownloads = { pair -> events += "reconcile-${pair.id}"; pair.id == "retired-a" },
                cancelSchedule = { pair -> events += "cancel-${pair.id}" },
                cancelNotification = { pair -> events += "cancel-notification-${pair.id}" },
                persistRetirement = { events += "persist-retirement" },
                releaseLocalGrant = { localRootId -> events += "release-$localRootId" },
            )
        }

        assertEquals(listOf("reconcile-retired-a", "reconcile-retired-b"), events)
    }

    @Test
    fun accountRetirementKeepsPairIdsUntilEveryScheduleCancellationCompletes() = runBlocking {
        val retiredPairs = listOf(
            fileSyncPair("pair-a", "removed-account", "first-root"),
            fileSyncPair("pair-b", "removed-account", "second-root"),
        )
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            retireConfiguredFileSyncAccountPairs(
                retiredPairs = retiredPairs,
                retainedPairs = emptyList(),
                reconcileLocalDownloads = { true },
                cancelSchedule = { pair ->
                    events += "cancel-${pair.id}"
                    if (pair.id == "pair-b") error("synthetic WorkManager cancellation failure")
                },
                cancelNotification = { pair -> events += "cancel-notification-${pair.id}" },
                persistRetirement = { events += "persist-retirement" },
                releaseLocalGrant = { localRootId -> events += "release-$localRootId" },
            )
        }

        assertEquals(listOf("cancel-pair-a", "cancel-notification-pair-a", "cancel-pair-b"), events)
    }

    @Test
    fun accountRetirementKeepsPairStateWhenConflictNotificationCannotBeCanceled() = runBlocking {
        val pair = fileSyncPair("pair", "removed-account", "root")
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            retireConfiguredFileSyncAccountPairs(
                retiredPairs = listOf(pair),
                retainedPairs = emptyList(),
                reconcileLocalDownloads = { true },
                cancelSchedule = { events += "cancel-schedule" },
                cancelNotification = {
                    events += "cancel-notification"
                    error("synthetic notification cancellation failure")
                },
                persistRetirement = { events += "persist-retirement" },
                releaseLocalGrant = { events += "release-grant" },
            )
        }

        assertEquals(listOf("cancel-schedule", "cancel-notification"), events)
    }

    @Test
    fun pairRemovalRecoveryPropagatesCancellationBeforeAnyMutation() = runBlocking {
        val events = mutableListOf<String>()

        assertFailsWith<CancellationException> {
            removeConfiguredFileSyncPair(
                reconcileLocalDownloads = {
                    events += "reconcile"
                    throw CancellationException("pair removal cancelled")
                },
                cleanRemoteUploads = { events += "remote"; true },
                cleanLedger = { events += "clean" },
                persistRemoval = { events += "persist" },
                cancelSchedule = { events += "cancel" },
                releaseLocalGrant = { events += "release" },
            )
        }

        assertEquals(listOf("reconcile"), events)
    }

    @Test
    fun reconciliationSkipsAnActiveSyncWithoutBlockingTransferHistory() = runBlocking {
        val lock = Mutex(locked = true)
        var reconciled = false
        val completed = runWhenFileSyncIdle(lock) {
            reconciled = true
        }

        assertFalse(completed)
        assertFalse(reconciled)
        lock.unlock()
    }

    @Test
    fun reconciliationRunsWhenFileSyncIsIdle() = runBlocking {
        val lock = Mutex()
        var reconciled = false
        val completed = runWhenFileSyncIdle(lock) {
            reconciled = true
        }

        assertTrue(completed)
        assertTrue(reconciled)
    }

    @Test
    fun reconciliationCanBeDeferredWithoutBlockingTheInitialRead() = runBlocking {
        val lock = Mutex(locked = true)
        var reconciled = false
        var deferred: Job? = null

        val completed = runWhenFileSyncIdle(lock) {
            reconciled = true
        }
        if (!completed) {
            deferred = deferFileSyncActionUntilIdle(lock, CoroutineScope(coroutineContext)) {
                reconciled = true
            }
        }

        assertFalse(completed)
        assertFalse(reconciled)
        lock.unlock()
        deferred?.join()
        assertTrue(reconciled)
    }

    @Test
    fun deferredReconciliationWaitsForRunningSourceWorkAfterEngineIdle() = runBlocking {
        val lock = Mutex(locked = true)
        var runningSourceIds = setOf("pair-1")
        val events = mutableListOf<String>()
        val sourceWorkObserved = CompletableDeferred<Unit>()
        val allowSourceWorkToFinish = CompletableDeferred<Unit>()

        val deferred = launch {
            runFileSyncActionWhenSourceWorkIdle(
                lock = lock,
                runningSourceIds = {
                    events += "inspect:${runningSourceIds.sorted()}"
                    runningSourceIds
                },
                awaitSourcesNotRunning = { running ->
                    assertFalse(lock.isLocked)
                    events += "await:${running.sorted()}"
                    sourceWorkObserved.complete(Unit)
                    allowSourceWorkToFinish.await()
                    runningSourceIds = emptySet()
                },
                action = {
                    assertTrue(lock.isLocked)
                    events += "reconcile"
                },
            )
        }

        assertTrue(events.isEmpty())
        lock.unlock()
        sourceWorkObserved.await()
        assertFalse("reconcile" in events)
        allowSourceWorkToFinish.complete(Unit)
        deferred.join()
        assertEquals(
            listOf(
                "inspect:[pair-1]",
                "await:[pair-1]",
                "inspect:[]",
                "reconcile",
            ),
            events,
        )
    }

    @Test
    fun deferredReconciliationReloadsAndRechecksNewRunningSources() = runBlocking {
        val lock = Mutex()
        var runningSourceIds = setOf("pair-1")
        val awaitedSources = mutableListOf<Set<String>>()
        var reconciled = false

        runFileSyncActionWhenSourceWorkIdle(
            lock = lock,
            runningSourceIds = { runningSourceIds },
            awaitSourcesNotRunning = { running ->
                awaitedSources += running
                runningSourceIds = when (running) {
                    setOf("pair-1") -> setOf("pair-2")
                    else -> emptySet()
                }
            },
            action = { reconciled = true },
        )

        assertEquals(listOf(setOf("pair-1"), setOf("pair-2")), awaitedSources)
        assertTrue(reconciled)
    }

    @Test
    fun idleGateReleasesTheEngineLockWhenItsActionFails() = runBlocking {
        val lock = Mutex()

        assertFailsWith<IllegalStateException> {
            runWhenFileSyncIdle(lock) {
                error("synthetic reconciliation failure")
            }
        }

        assertFalse(lock.isLocked)
    }

    @Test
    fun staleSnapshotReadDuringRemovalDefersSchedulingFromAFreshSnapshot() = runBlocking {
        val lock = Mutex(locked = true)
        var persistedPairIds = listOf("pair-1")
        val schedulingSnapshots = mutableListOf<List<String>>()
        var deferredScheduling: Job? = null

        val snapshotWhileRemovalOwnsLock = loadFileSyncPresentationSnapshot(
            lock = lock,
            load = { persistedPairIds.toList() },
            scheduleWhenIdle = { schedulingSnapshots += it.toList() },
            scheduleAfterIdle = {
                deferredScheduling = deferFileSyncSnapshotActionUntilIdle(
                    lock = lock,
                    scope = CoroutineScope(coroutineContext),
                    load = { persistedPairIds.toList() },
                    action = { schedulingSnapshots += it },
                )
            },
        )

        assertEquals(listOf("pair-1"), snapshotWhileRemovalOwnsLock)
        assertTrue(schedulingSnapshots.isEmpty())
        assertTrue(deferredScheduling != null)

        persistedPairIds = emptyList()
        lock.unlock()
        requireNotNull(deferredScheduling).join()

        assertEquals(listOf(emptyList()), schedulingSnapshots)
    }

    @Test
    fun busyPresentationReadSchedulesPersistedPairsAfterIdle() = runBlocking {
        val lock = Mutex(locked = true)
        val scheduledSnapshots = mutableListOf<List<String>>()
        var deferredScheduling: Job? = null

        val displayedSnapshot = loadFileSyncPresentationSnapshot(
            lock = lock,
            load = { listOf("pair-1") },
            scheduleWhenIdle = { scheduledSnapshots += it },
            scheduleAfterIdle = {
                deferredScheduling = deferFileSyncSnapshotActionUntilIdle(
                    lock = lock,
                    scope = CoroutineScope(coroutineContext),
                    load = { listOf("pair-1") },
                    action = { scheduledSnapshots += it },
                )
            },
        )

        assertEquals(listOf("pair-1"), displayedSnapshot)
        assertTrue(scheduledSnapshots.isEmpty())
        lock.unlock()
        requireNotNull(deferredScheduling).join()
        assertEquals(listOf(listOf("pair-1")), scheduledSnapshots)
    }

    @Test
    fun cancelledDeferredSnapshotSchedulingReleasesItsDedupeMarker() = runBlocking {
        val lock = Mutex(locked = true)
        var finished = false

        val deferred = deferFileSyncSnapshotActionUntilIdle(
            lock = lock,
            scope = CoroutineScope(coroutineContext),
            load = { emptyList<String>() },
            onFinished = { finished = true },
            action = {},
        )

        deferred.cancelAndJoin()
        assertTrue(finished)
        lock.unlock()
    }

    @Test
    fun logoutInvalidatesDeferredSchedulingBeforeCancelAllReturns() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        guard.restorePersistedSession(load = { "account-old" }, accountIdOf = { it })
        val oldToken = requireNotNull(guard.capture("account-old"))
        val events = mutableListOf<String>()

        guard.clearSession(
            persist = { events += "clear-session" },
            cancelAll = { events += "cancel-all" },
        )
        val scheduled = guard.runIfCurrent(oldToken) {
            events += "schedule-old-account"
        }

        assertFalse(scheduled)
        assertEquals(listOf("clear-session", "cancel-all"), events)
        assertEquals(null, guard.capture("account-old"))
    }

    @Test
    fun sessionReplacementRejectsOldAccountAndAllowsCurrentAccount() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        guard.restorePersistedSession(load = { "account-old" }, accountIdOf = { it })
        val oldToken = requireNotNull(guard.capture("account-old"))
        val events = mutableListOf<String>()

        guard.replaceSession(
            replacementAccountId = "account-new",
            persist = { events += "save-new-session" },
            cancelAll = { events += "cancel-old-work" },
            restoreSchedules = { events += "restore-$it-work" },
        )
        val newToken = requireNotNull(guard.capture("account-new"))

        assertFalse(guard.runIfCurrent(oldToken) { events += "schedule-old-account" })
        assertTrue(guard.runIfCurrent(newToken) { events += "schedule-new-account" })
        assertEquals(
            listOf(
                "save-new-session",
                "cancel-old-work",
                "restore-account-new-work",
                "schedule-new-account",
            ),
            events,
        )
    }

    @Test
    fun failedSessionReplacementPreservesOldAuthorityAndSchedules() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        guard.restorePersistedSession(load = { "account-old" }, accountIdOf = { it })
        val oldToken = requireNotNull(guard.capture("account-old"))
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            guard.replaceSession(
                replacementAccountId = "account-new",
                persist = {
                    events += "save-new-session"
                    error("synthetic persistence failure")
                },
                cancelAll = { events += "cancel-old-work" },
                publishAccount = { events += "publish-$it" },
                restoreSchedules = { events += "restore-$it-work" },
            )
        }

        assertTrue(guard.runIfCurrent(oldToken) { events += "old-account-still-current" })
        assertEquals(listOf("save-new-session", "old-account-still-current"), events)
        assertEquals(null, guard.capture("account-new"))
    }

    @Test
    fun postCommitScheduleFailureDoesNotRejectTheSelectedAccount() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        guard.restorePersistedSession(load = { "account-old" }, accountIdOf = { it })
        val events = mutableListOf<String>()

        guard.replaceSession(
            replacementAccountId = "account-new",
            persist = { events += "save-new-session" },
            cancelAll = {
                events += "cancel-old-work"
                error("synthetic WorkManager failure")
            },
            publishAccount = { events += "publish-$it" },
            restoreSchedules = {
                events += "restore-$it-work"
                error("synthetic enqueue failure")
            },
            onScheduleMaintenanceFailure = { events += "diagnose" },
        )

        assertEquals(
            listOf(
                "save-new-session",
                "publish-account-new",
                "cancel-old-work",
                "diagnose",
                "restore-account-new-work",
                "diagnose",
            ),
            events,
        )
        assertTrue(guard.capture("account-new") != null)
    }

    @Test
    fun failedSessionClearPreservesOldAuthorityAndSchedules() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        guard.restorePersistedSession(load = { "account-old" }, accountIdOf = { it })
        val oldToken = requireNotNull(guard.capture("account-old"))
        val events = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            guard.clearSession(
                persist = {
                    events += "clear-session"
                    error("synthetic persistence failure")
                },
                cancelAll = { events += "cancel-all" },
                clearPublishedAccount = { events += "publish-none" },
            )
        }

        assertTrue(guard.runIfCurrent(oldToken) { events += "old-account-still-current" })
        assertEquals(listOf("clear-session", "old-account-still-current"), events)
    }

    @Test
    fun newSessionGenerationCanScheduleWhileOldDedupeEntryFinishes() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        val registry = DeferredFileSyncPairSchedulingRegistry()
        guard.restorePersistedSession(load = { "account" }, accountIdOf = { it })
        val oldToken = requireNotNull(guard.capture("account"))
        val oldScheduling = DeferredFileSyncPairScheduling(oldToken, "user")
        assertTrue(registry.acquire(oldScheduling))

        guard.clearSession(persist = {}, cancelAll = {})
        guard.restorePersistedSession(load = { "account" }, accountIdOf = { it })
        val newToken = requireNotNull(guard.capture("account"))
        val newScheduling = DeferredFileSyncPairScheduling(newToken, "user")

        assertTrue(registry.acquire(newScheduling))
        registry.release(oldScheduling)
        assertFalse(registry.acquire(newScheduling))
        registry.release(newScheduling)
        assertTrue(registry.acquire(newScheduling))
    }

    @Test
    fun staleSessionLoadCannotRestoreAuthorityAfterLogoutCompletes() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        val loadEntered = CountDownLatch(1)
        val allowLoadToFinish = CountDownLatch(1)
        val events = mutableListOf<String>()

        val loadThread = Thread {
            guard.restorePersistedSession(
                load = {
                    events += "read-old-session"
                    loadEntered.countDown()
                    check(allowLoadToFinish.await(5, TimeUnit.SECONDS))
                    "old-session"
                },
                accountIdOf = {
                    events += "restore-old-authority"
                    "account-old"
                },
                publishAccount = { _, accountId -> events += "publish-${accountId ?: "none"}" },
            )
        }
        loadThread.start()
        assertTrue(loadEntered.await(5, TimeUnit.SECONDS))

        val clearThread = Thread {
            guard.clearSession(
                persist = { events += "clear-session" },
                cancelAll = { events += "cancel-all" },
                clearPublishedAccount = { events += "publish-none" },
            )
        }
        clearThread.start()
        assertThreadBlocked(clearThread)
        allowLoadToFinish.countDown()
        loadThread.join(5_000)
        clearThread.join(5_000)

        assertFalse(loadThread.isAlive)
        assertFalse(clearThread.isAlive)
        assertEquals(
            listOf(
                "read-old-session",
                "restore-old-authority",
                "publish-account-old",
                "clear-session",
                "publish-none",
                "cancel-all",
            ),
            events,
        )
        assertEquals(null, guard.capture("account-old"))
    }

    @Test
    fun staleSessionLoadCannotOverrideCrossAccountReplacement() {
        val guard = AndroidFileSyncSessionSchedulingGuard()
        val loadEntered = CountDownLatch(1)
        val allowLoadToFinish = CountDownLatch(1)
        val events = mutableListOf<String>()

        val loadThread = Thread {
            guard.restorePersistedSession(
                load = {
                    events += "read-old-session"
                    loadEntered.countDown()
                    check(allowLoadToFinish.await(5, TimeUnit.SECONDS))
                    "old-session"
                },
                accountIdOf = {
                    events += "restore-old-authority"
                    "account-old"
                },
                publishAccount = { _, accountId -> events += "publish-$accountId" },
            )
        }
        loadThread.start()
        assertTrue(loadEntered.await(5, TimeUnit.SECONDS))

        val replacementThread = Thread {
            guard.replaceSession(
                replacementAccountId = "account-new",
                persist = { events += "save-new-session" },
                cancelAll = { events += "cancel-old-work" },
                publishAccount = { accountId -> events += "publish-$accountId" },
            )
        }
        replacementThread.start()
        assertThreadBlocked(replacementThread)
        allowLoadToFinish.countDown()
        loadThread.join(5_000)
        replacementThread.join(5_000)

        assertFalse(loadThread.isAlive)
        assertFalse(replacementThread.isAlive)
        assertEquals(
            listOf(
                "read-old-session",
                "restore-old-authority",
                "publish-account-old",
                "save-new-session",
                "publish-account-new",
                "cancel-old-work",
            ),
            events,
        )
        assertEquals(null, guard.capture("account-old"))
        assertTrue(guard.capture("account-new") != null)
    }

    private fun fileSyncPair(
        id: String,
        accountId: String,
        localRootId: String,
    ) = FileSyncPair(
        id = id,
        accountId = accountId,
        localRootId = localRootId,
        remoteRootPath = "Documents",
        configuration = FileSyncConfiguration(deviceLabel = "Phone"),
    )

    private fun assertThreadBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (
            thread.isAlive &&
            thread.state != Thread.State.BLOCKED &&
            System.nanoTime() < deadline
        ) {
            Thread.yield()
        }
        assertEquals(Thread.State.BLOCKED, thread.state)
    }
}
