package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.RemoteFolderSelectionAccess
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidIncomingShareStateTest {
    @Test
    fun staleWorkerTransitionCannotOverwriteCancellation() {
        val canceled = request(AndroidIncomingShareState.Canceled)

        assertNull(
            transitionIncomingShareRequest(
                canceled,
                expected = setOf(AndroidIncomingShareState.Queued),
                target = AndroidIncomingShareState.Uploading,
            ),
        )
        assertEquals(AndroidIncomingShareState.Canceled, canceled.state)
    }

    @Test
    fun processRestartCannotReplayAnUploadingRequest() {
        val uploading = request(AndroidIncomingShareState.Uploading)

        val recovered = transitionIncomingShareRequest(
            uploading,
            expected = setOf(AndroidIncomingShareState.Uploading),
            target = AndroidIncomingShareState.OutcomeUnknown,
            message = "Check Files before trying again.",
        )

        assertEquals(AndroidIncomingShareState.OutcomeUnknown, recovered?.state)
        assertEquals("Check Files before trying again.", recovered?.message)
    }

    @Test
    fun expectedTransitionPreservesDurableProgress() {
        val uploading = request(AndroidIncomingShareState.Uploading).copy(
            completedFiles = 1,
            uploadedNames = listOf("first.txt"),
        )

        val failed = transitionIncomingShareRequest(
            uploading,
            expected = setOf(AndroidIncomingShareState.Uploading),
            target = AndroidIncomingShareState.Failed,
            message = "Network unavailable",
        )

        assertEquals(1, failed?.completedFiles)
        assertEquals(listOf("first.txt"), failed?.uploadedNames)
        assertEquals("Network unavailable", failed?.message)
    }

    @Test
    fun partiallyCompletedRetryKeepsItsOriginalDestination() {
        val failed = request(AndroidIncomingShareState.Failed).copy(
            accountId = "account-1",
            userId = "user-1",
            destinationPath = "Shared/Phone",
            completedFiles = 1,
            uploadedNames = listOf("first.txt"),
            automaticTransferAttempts = 3,
            retryNotBeforeEpochMillis = 123_456L,
        )

        val queued = prepareIncomingShareRequestForQueue(
            current = failed,
            accountId = "account-1",
            userId = "user-1",
            destinationPath = "Shared/Phone",
        )

        assertEquals(AndroidIncomingShareState.Queued, queued.state)
        assertEquals(1, queued.completedFiles)
        assertEquals("Shared/Phone", queued.destinationPath)
        assertEquals(0, queued.automaticTransferAttempts)
        assertNull(queued.retryNotBeforeEpochMillis)
        assertFailsWith<IllegalArgumentException> {
            prepareIncomingShareRequestForQueue(
                current = failed,
                accountId = "account-1",
                userId = "user-1",
                destinationPath = "Somewhere/Else",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            prepareIncomingShareRequestForQueue(
                current = failed,
                accountId = "another-account",
                userId = "user-1",
                destinationPath = "Shared/Phone",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            prepareIncomingShareRequestForQueue(
                current = failed,
                accountId = "account-1",
                userId = "another-user",
                destinationPath = "Shared/Phone",
            )
        }
    }

    @Test
    fun shareTargetAcceptsOnlyTemporaryGrantCompatibleContentUris() {
        assertTrue(isSupportedIncomingShareUriScheme("content"))
        assertFalse(isSupportedIncomingShareUriScheme("file"))
        assertFalse(isSupportedIncomingShareUriScheme("https"))
        assertFalse(isSupportedIncomingShareUriScheme(null))
    }

    @Test
    fun clipInspectionRetainsOneOverflowItemForExplicitRejection() {
        assertEquals(0, incomingShareClipItemsToInspect(0))
        assertEquals(100, incomingShareClipItemsToInspect(100))
        assertEquals(101, incomingShareClipItemsToInspect(101))
        assertEquals(101, incomingShareClipItemsToInspect(4_000))
        assertFailsWith<IllegalArgumentException> { incomingShareClipItemsToInspect(-1) }
        assertFalse(incomingShareChannelCountsExceedLimit(100, 100))
        assertTrue(incomingShareChannelCountsExceedLimit(0, 101))
        assertTrue(incomingShareChannelCountsExceedLimit(101, 0))
    }

    @Test
    fun rejectedPutCanRetryButTransportFailureHasUnknownOutcome() {
        assertFalse(
            incomingShareMutationOutcomeUnknown(
                DocumentWebDavException(
                    DocumentWebDavError.Throttled,
                    429,
                    "Wait",
                    retryAfterSeconds = 12,
                ),
                mutationInFlight = true,
            ),
        )
        assertFalse(
            incomingShareMutationOutcomeUnknown(
                DocumentWebDavException(
                    DocumentWebDavError.InsufficientStorage,
                    507,
                    "The server is full.",
                ),
                mutationInFlight = true,
            ),
        )
        assertFalse(
            incomingShareMutationOutcomeUnknown(
                DocumentWebDavException(
                    DocumentWebDavError.TooLarge,
                    413,
                    "The file is too large.",
                ),
                mutationInFlight = true,
            ),
        )
        assertTrue(
            incomingShareMutationOutcomeUnknown(
                IllegalStateException("Connection closed"),
                mutationInFlight = true,
            ),
        )
        assertFalse(
            incomingShareMutationOutcomeUnknown(
                IllegalStateException("Folder lookup failed"),
                mutationInFlight = false,
            ),
        )
    }

    @Test
    fun largeFilesUseBoundedOfficialChunkSizes() {
        val ordinarySize = DIRECT_INCOMING_SHARE_UPLOAD_BYTES + INCOMING_SHARE_CHUNK_BYTES
        val ordinaryChunk = requireNotNull(incomingShareChunkSize(ordinarySize))
        assertEquals(3, incomingShareChunkCount(ordinarySize, ordinaryChunk))
        val multiGigabyteSize = 120L * 1024L * 1024L * 1024L
        val scaledChunk = requireNotNull(incomingShareChunkSize(multiGigabyteSize))
        assertTrue(incomingShareChunkCount(multiGigabyteSize, scaledChunk) <= MAX_NEXTCLOUD_UPLOAD_CHUNKS)
        assertEquals(null, incomingShareChunkSize(2L * 1024L * 1024L * 1024L * 1024L))
        assertTrue(java.io.IOException("offline").isRetryableIncomingShareTransferFailure())
        assertTrue(
            DocumentWebDavException(DocumentWebDavError.Throttled, 429, "Wait")
                .isRetryableIncomingShareTransferFailure(),
        )
        assertFalse(
            DocumentWebDavException(DocumentWebDavError.Permission, 403, "No")
                .isRetryableIncomingShareTransferFailure(),
        )
    }

    @Test
    fun resumableChunkDoesNotTurnPermanentFailureIntoAutomaticRetry() {
        val permanent = DocumentWebDavException(DocumentWebDavError.Permission, 403, "No")

        assertFalse(shouldRetryIncomingShareTransfer(permanent, mutationInFlight = false, automaticTransferAttempts = 0))
        assertTrue(
            shouldRetryIncomingShareTransfer(
                java.io.IOException("offline"),
                mutationInFlight = false,
                automaticTransferAttempts = 0,
            ),
        )
        assertFalse(
            shouldRetryIncomingShareTransfer(
                java.io.IOException("offline"),
                mutationInFlight = true,
                automaticTransferAttempts = 0,
            ),
        )
        assertFalse(
            shouldRetryIncomingShareTransfer(
                java.io.IOException("offline"),
                mutationInFlight = false,
                automaticTransferAttempts = MAX_INCOMING_SHARE_TRANSFER_ATTEMPTS - 1,
            ),
        )
    }

    @Test
    fun recreatedExpiredChunkCollectionDiscardsPersistedProgress() {
        assertTrue(shouldResetIncomingShareChunkProgress(collectionCreated = true, uploadedChunks = 3))
        assertFalse(shouldResetIncomingShareChunkProgress(collectionCreated = false, uploadedChunks = 3))
        assertFalse(shouldResetIncomingShareChunkProgress(collectionCreated = true, uploadedChunks = 0))
        assertFailsWith<IllegalArgumentException> {
            shouldResetIncomingShareChunkProgress(collectionCreated = true, uploadedChunks = -1)
        }
    }

    @Test
    fun chunkCleanupStateIsDurableAndCannotOverlapACommit() {
        val cleanup = AndroidIncomingShareChunkSession(
            fileIndex = 0,
            targetName = "large.bin",
            uploadId = "01234567-89ab-cdef-0123-456789abcdef",
            uploadedChunks = 3,
            cleanupPending = true,
        )

        assertTrue(cleanup.cleanupPending)
        assertFalse(cleanup.commitInFlight)
        assertFailsWith<IllegalArgumentException> {
            cleanup.copy(commitInFlight = true)
        }
    }

    @Test
    fun cleanupPendingChunkCannotBeRequeued() {
        val failed = request(AndroidIncomingShareState.Failed).copy(
            accountId = "account-1",
            userId = "user-1",
            destinationPath = "Shared/Phone",
            chunkSession = AndroidIncomingShareChunkSession(
                fileIndex = 0,
                targetName = "first.txt",
                uploadId = "01234567-89ab-cdef-0123-456789abcdef",
                cleanupPending = true,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            prepareIncomingShareRequestForQueue(
                current = failed,
                accountId = "account-1",
                userId = "user-1",
                destinationPath = "Shared/Phone",
            )
        }
    }

    @Test
    fun fullyJournaledUploadCanRecoverAsCompleted() {
        val completed = request(AndroidIncomingShareState.Uploading).copy(
            completedFiles = 2,
            uploadedNames = listOf("first.txt", "second.txt"),
        )

        assertTrue(completed.isFullyJournaledIncomingShareUpload())
        assertFalse(completed.copy(completedFiles = 1, uploadedNames = listOf("first.txt"))
            .isFullyJournaledIncomingShareUpload())
        assertFalse(completed.copy(state = AndroidIncomingShareState.Failed)
            .isFullyJournaledIncomingShareUpload())
    }

    @Test
    fun workerRestartResumesOnlyBeforeAVisibleMutationOrBetweenSavedChunks() {
        val preflight = request(AndroidIncomingShareState.Uploading)
        val chunked = preflight.copy(
            chunkSession = AndroidIncomingShareChunkSession(
                fileIndex = 0,
                targetName = "first.txt",
                uploadId = "01234567-89ab-cdef-0123-456789abcdef",
                uploadedChunks = 2,
            ),
        )

        assertTrue(preflight.canSafelyResumeAfterWorkerRestart())
        assertTrue(chunked.canSafelyResumeAfterWorkerRestart())
        assertFalse(
            preflight.copy(
                visibleMutationInFlight = true,
                visibleMutationTargetName = "first.txt",
            ).canSafelyResumeAfterWorkerRestart(),
        )
        assertFalse(
            chunked.copy(chunkSession = requireNotNull(chunked.chunkSession).copy(commitInFlight = true))
                .canSafelyResumeAfterWorkerRestart(),
        )
    }

    @Test
    fun terminalChunkSessionMustSurviveUntilRemoteCleanup() {
        val terminal = request(AndroidIncomingShareState.Failed)
        assertTrue(terminal.canReleaseIncomingShareRequest())
        assertFalse(
            terminal.copy(
                chunkSession = AndroidIncomingShareChunkSession(
                    fileIndex = 0,
                    targetName = "first.txt",
                    uploadId = "01234567-89ab-cdef-0123-456789abcdef",
                    cleanupPending = true,
                ),
            ).canReleaseIncomingShareRequest(),
        )
    }

    @Test
    fun shareFolderSeparatesFileAndDirectoryCreationPermissions() {
        assertEquals(
            RemoteFolderSelectionAccess.Allowed,
            incomingShareFolderSelectionAccess(DocumentDirectoryAccess(true, false)),
        )
        assertEquals(
            RemoteFolderSelectionAccess.DirectoryCreationOnly,
            incomingShareFolderSelectionAccess(DocumentDirectoryAccess(false, true)),
        )
        assertTrue(
            incomingShareFolderSelectionAccess(DocumentDirectoryAccess(false, false)) is
                RemoteFolderSelectionAccess.Denied,
        )
    }

    @Test
    fun foregroundAndTerminalNotificationsUseDifferentIds() {
        val requestId = "01234567-89ab-cdef-0123-456789abcdef"
        assertTrue(incomingShareForegroundNotificationId(requestId) > 0)
        assertTrue(incomingShareNotificationId(requestId) > 0)
        assertFalse(incomingShareForegroundNotificationId(requestId) == incomingShareNotificationId(requestId))
    }

    @Test
    fun throttledFinalMoveIsARejectedRatherThanAmbiguousMutation() {
        val throttled = DocumentWebDavException(DocumentWebDavError.Throttled, 429, "Wait")

        assertFalse(incomingShareMutationOutcomeUnknown(throttled, mutationInFlight = true))
        assertTrue(throttled.isRetryableIncomingShareTransferFailure())
    }

    @Test
    fun automaticRetriesUseDurableAttemptCountsAndServerDelay() {
        val uploading = request(AndroidIncomingShareState.Uploading).copy(automaticTransferAttempts = 2)

        val queued = prepareIncomingShareRequestForAutomaticRetry(
            uploading,
            message = "Nextcloud asked this upload to wait before retrying.",
            retryNotBeforeEpochMillis = 240_000L,
        )

        assertEquals(AndroidIncomingShareState.Queued, queued.state)
        assertEquals(3, queued.automaticTransferAttempts)
        assertEquals(240_000L, queued.retryNotBeforeEpochMillis)
        assertFailsWith<IllegalArgumentException> {
            prepareIncomingShareRequestForAutomaticRetry(
                uploading.copy(automaticTransferAttempts = MAX_INCOMING_SHARE_TRANSFER_ATTEMPTS - 1),
                message = "No attempts remain.",
                retryNotBeforeEpochMillis = null,
            )
        }
    }

    @Test
    fun longRetryAfterOverridesTheShortWorkManagerBackoff() {
        val now = 5_000L
        assertEquals(
            now + 120_000L,
            DocumentWebDavException(
                DocumentWebDavError.Throttled,
                429,
                "Wait",
                retryAfterSeconds = 120,
            ).incomingShareRetryNotBeforeEpochMillis(now),
        )
        assertNull(
            DocumentWebDavException(
                DocumentWebDavError.Throttled,
                429,
                "Wait",
                retryAfterSeconds = 12,
            ).incomingShareRetryNotBeforeEpochMillis(now),
        )
        assertNull(
            DocumentWebDavException(DocumentWebDavError.Permission, 403, "No")
                .incomingShareRetryNotBeforeEpochMillis(now),
        )
    }

    @Test
    fun chunkCleanupRetriesOnlyFailuresThatCanRecover() {
        assertTrue(java.io.IOException("offline").isRetryableIncomingShareChunkCleanupFailure())
        assertTrue(
            DocumentWebDavException(DocumentWebDavError.Locked, 423, "Locked")
                .isRetryableIncomingShareChunkCleanupFailure(),
        )
        assertTrue(
            DocumentWebDavException(DocumentWebDavError.Throttled, 429, "Wait")
                .isRetryableIncomingShareChunkCleanupFailure(),
        )
        assertTrue(
            DocumentWebDavException(DocumentWebDavError.Server, 503, "Unavailable")
                .isRetryableIncomingShareChunkCleanupFailure(),
        )
        assertFalse(
            DocumentWebDavException(DocumentWebDavError.Permission, 403, "No")
                .isRetryableIncomingShareChunkCleanupFailure(),
        )
        assertEquals(
            120_000L,
            DocumentWebDavException(
                DocumentWebDavError.Throttled,
                429,
                "Wait",
                retryAfterSeconds = 120,
            ).incomingShareChunkCleanupRetryDelayMillis(1_000L),
        )
        assertNull(
            DocumentWebDavException(DocumentWebDavError.Throttled, 429, "Wait", retryAfterSeconds = 12)
                .incomingShareChunkCleanupRetryDelayMillis(1_000L),
        )
        assertTrue(canRetryIncomingShareChunkCleanup(6))
        assertFalse(canRetryIncomingShareChunkCleanup(7))
        assertEquals(8, MAX_INCOMING_SHARE_CHUNK_CLEANUP_ATTEMPTS)
    }

    @Test
    fun staleEnqueueCompletionCannotReplaceANewerShare() {
        assertTrue(isCurrentIncomingShareEnqueue(4, 4, "request-a", "request-a"))
        assertFalse(isCurrentIncomingShareEnqueue(3, 4, "request-a", "request-a"))
        assertFalse(isCurrentIncomingShareEnqueue(4, 4, "request-a", "request-b"))
        assertFalse(isCurrentIncomingShareEnqueue(4, 4, "request-a", null))
    }

    @Test
    fun collisionResponsesAdvanceNamesWithoutBecomingUnknownOutcomes() {
        assertFalse(
            DocumentWebDavException(
                DocumentWebDavError.AlreadyExists,
                409,
                "Parent is missing",
            ).isIncomingShareNameCollision(),
        )
        assertTrue(
            DocumentWebDavException(
                DocumentWebDavError.Conflict,
                412,
                "Precondition failed",
            ).isIncomingShareNameCollision(),
        )
        assertFalse(
            DocumentWebDavException(
                DocumentWebDavError.AlreadyExists,
                405,
                "Method not allowed",
            ).isIncomingShareNameCollision(),
        )
        assertFalse(
            DocumentWebDavException(
                DocumentWebDavError.Permission,
                403,
                "Forbidden",
            ).isIncomingShareNameCollision(),
        )
    }

    @Test
    fun canceledMutationRemainsVisibleAsAnUnknownFileOutcome() {
        val canceled = request(AndroidIncomingShareState.Canceled).copy(
            message = CANCELED_INCOMING_SHARE_MUTATION_WARNING,
        )

        assertTrue(canceled.requiresIncomingShareRecovery("account-1"))
        assertFalse(canceled.canReleaseForIncomingShareReplacement())
        assertEquals(
            dev.obiente.nextcloudnative.app.IncomingShareUploadFileStatus.OutcomeUnknown,
            canceled.toPresentation().files.first().status,
        )
        assertFalse(
            canceled.copy(accountId = "another-account")
                .requiresIncomingShareRecovery("account-1"),
        )
        assertFalse(canceled.copy(accountId = null).requiresIncomingShareRecovery("account-1"))
        assertTrue(
            canceled.copy(message = "Upload canceled before a transfer was active.")
                .requiresIncomingShareRecovery("account-1"),
        )
        assertFalse(
            canceled.copy(message = "Upload canceled before a transfer was active.")
                .canReleaseForIncomingShareReplacement(),
        )
        assertFalse(
            canceled.copy(
                message = "Upload canceled before a transfer was active.",
                chunkSession = AndroidIncomingShareChunkSession(
                    fileIndex = 0,
                    targetName = "large.bin",
                    uploadId = "01234567-89ab-cdef-0123-456789abcdef",
                ),
            ).canReleaseForIncomingShareReplacement(),
        )
    }

    @Test
    fun incompleteDestinationSnapshotProbesBeforeStartingAChunkSession() {
        val occupied = mutableSetOf<String>()
        val probed = mutableListOf<String>()

        val selected = selectIncomingShareTransferTarget(
            displayName = "archive.bin",
            occupiedNames = occupied,
            destinationSnapshotComplete = false,
        ) { candidate ->
            probed += candidate
            candidate == "archive.bin"
        }

        assertEquals("archive (1).bin", selected)
        assertEquals(listOf("archive.bin", "archive (1).bin"), probed)
        assertEquals(setOf("archive.bin"), occupied)
    }

    @Test
    fun resumedChunkAbandonsANameAlreadyPresentInACompleteSnapshot() {
        var probed = false

        assertTrue(
            shouldAbandonResumedIncomingShareTarget(
                targetName = "archive.bin",
                occupiedNames = setOf("archive.bin"),
                destinationSnapshotComplete = true,
            ) {
                probed = true
                false
            },
        )
        assertFalse(probed)
    }

    @Test
    fun unknownOutcomeBecomesRetryableOnlyAfterRemoteAbsenceIsVerified() {
        val unknown = request(AndroidIncomingShareState.OutcomeUnknown).copy(
            visibleMutationInFlight = true,
            visibleMutationTargetName = "first.txt",
            message = "The upload result is unknown.",
        )

        val stillUnknown = reconcileIncomingShareUnknownOutcome(unknown, targetExists = true)
        assertEquals(AndroidIncomingShareState.OutcomeUnknown, stillUnknown.state)
        assertTrue(stillUnknown.visibleMutationInFlight)

        val retryable = reconcileIncomingShareUnknownOutcome(unknown, targetExists = false)
        assertEquals(AndroidIncomingShareState.Failed, retryable.state)
        assertFalse(retryable.visibleMutationInFlight)
        assertNull(retryable.visibleMutationTargetName)
    }

    @Test
    fun onlyCompletedShareRecoveriesExpireAutomatically() {
        assertTrue(request(AndroidIncomingShareState.Completed).canExpireIncomingShareRecovery())
        assertFalse(request(AndroidIncomingShareState.Failed).canExpireIncomingShareRecovery())
        assertFalse(request(AndroidIncomingShareState.OutcomeUnknown).canExpireIncomingShareRecovery())
        assertFalse(request(AndroidIncomingShareState.Canceled).canExpireIncomingShareRecovery())
        assertTrue(
            request(AndroidIncomingShareState.Canceled)
                .copy(discardRequested = true)
                .canExpireIncomingShareRecovery(),
        )
    }

    @Test
    fun activePresentationCannotReleaseAConcurrentTerminalResult() {
        val presented = request(AndroidIncomingShareState.Uploading)
        val terminal = presented.copy(
            state = AndroidIncomingShareState.OutcomeUnknown,
            message = "Check Files before trying again.",
        )

        assertFalse(shouldReleasePresentedIncomingShareRequest(presented, terminal))
        assertTrue(shouldReleasePresentedIncomingShareRequest(terminal, terminal))
        assertFalse(
            terminal.incomingShareReleaseFingerprint() ==
                terminal.copy(message = "A newer terminal result").incomingShareReleaseFingerprint(),
        )
    }

    @Test
    fun onlyUuidRecoveryReferencesAreRetained() {
        assertTrue(isValidIncomingShareRequestId("01234567-89ab-cdef-0123-456789abcdef"))
        assertFalse(isValidIncomingShareRequestId("../../not-a-request"))
    }

    @Test
    fun failurePresentationKeepsCompletedAndFailedFileOutcomesVisible() {
        val presentation = request(AndroidIncomingShareState.Failed).copy(
            completedFiles = 1,
            uploadedNames = listOf("first (1).txt"),
            message = "Network unavailable",
        ).toPresentation()

        assertEquals(1, presentation.completedFiles)
        assertEquals(
            dev.obiente.nextcloudnative.app.IncomingShareUploadFileStatus.Uploaded,
            presentation.files[0].status,
        )
        assertEquals("first (1).txt", presentation.files[0].uploadedName)
        assertEquals(
            dev.obiente.nextcloudnative.app.IncomingShareUploadFileStatus.Failed,
            presentation.files[1].status,
        )
    }

    @Test
    fun recoveryDirectoryPollingUsesStableBoundedPages() {
        val directories = List(35) { index ->
            IncomingShareRecoveryDirectory(
                id = "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}",
                lastModifiedMillis = 1_000L - index,
            )
        }

        val first = selectIncomingShareRecoveryDirectoryPage(directories, cursor = null)
        assertEquals(INCOMING_SHARE_RECOVERY_DIRECTORY_PAGE_SIZE, first.directories.size)
        assertEquals(directories.first(), first.directories.first())
        assertEquals(directories[31], first.directories.last())
        assertEquals(directories[31].encodeCursor(), first.nextCursor)

        val second = selectIncomingShareRecoveryDirectoryPage(directories, cursor = first.nextCursor)
        assertEquals(directories.drop(32), second.directories)
        assertNull(second.nextCursor)
        assertFailsWith<IllegalArgumentException> {
            selectIncomingShareRecoveryDirectoryPage(directories, cursor = "not-a-cursor")
        }
    }

    @Test
    fun stagedUploadIdentityRejectsSameLengthContentChanges() {
        val root = Files.createTempDirectory("incoming-share-identity").toFile()
        try {
            val staged = root.resolve("staged-file").apply { writeText("alpha") }
            val expectedHash = "sha256:" + MessageDigest.getInstance("SHA-256")
                .digest("alpha".encodeToByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val source = AndroidIncomingShareFile(
                id = "11111111-2222-3333-4444-555555555555",
                displayName = "note.txt",
                mimeType = "text/plain",
                sizeBytes = 5,
                stagedName = "staged-file",
                contentHash = expectedHash,
            )

            requireValidIncomingShareStagedFile(staged, source, NoDocumentRequestCancellation)
            staged.writeText("bravo")
            assertFailsWith<IllegalArgumentException> {
                requireValidIncomingShareStagedFile(staged, source, NoDocumentRequestCancellation)
            }
            staged.writeText("shorter")
            assertFailsWith<IllegalArgumentException> {
                requireValidIncomingShareStagedFile(staged, source, NoDocumentRequestCancellation)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun request(state: AndroidIncomingShareState) = AndroidIncomingShareRequest(
        id = "01234567-89ab-cdef-0123-456789abcdef",
        files = listOf(
            AndroidIncomingShareFile(
                id = "11111111-2222-3333-4444-555555555555",
                displayName = "first.txt",
                mimeType = "text/plain",
                sizeBytes = 5,
                stagedName = "staged-first",
            ),
            AndroidIncomingShareFile(
                id = "66666666-7777-8888-9999-000000000000",
                displayName = "second.txt",
                mimeType = "text/plain",
                sizeBytes = 6,
                stagedName = "staged-second",
            ),
        ),
        state = state,
        accountId = "account-1",
    )
}
