package dev.obiente.nextcloudnative

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
            destinationPath = "Shared/Phone",
            completedFiles = 1,
            uploadedNames = listOf("first.txt"),
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
        assertFailsWith<IllegalArgumentException> {
            prepareIncomingShareRequestForQueue(
                current = failed,
                accountId = "account-1",
                userId = "user-1",
                destinationPath = "Somewhere/Else",
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
        assertEquals(3, incomingShareChunkCount(DIRECT_INCOMING_SHARE_UPLOAD_BYTES + INCOMING_SHARE_CHUNK_BYTES))
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
    fun throttledFinalMoveIsARejectedRatherThanAmbiguousMutation() {
        val throttled = DocumentWebDavException(DocumentWebDavError.Throttled, 429, "Wait")

        assertFalse(incomingShareMutationOutcomeUnknown(throttled, mutationInFlight = true))
        assertTrue(throttled.isRetryableIncomingShareTransferFailure())
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
        assertEquals(
            dev.obiente.nextcloudnative.app.IncomingShareUploadFileStatus.OutcomeUnknown,
            canceled.toPresentation().files.first().status,
        )
        assertFalse(
            canceled.copy(accountId = "another-account")
                .requiresIncomingShareRecovery("account-1"),
        )
        assertFalse(
            canceled.copy(message = "Upload canceled before a transfer was active.")
                .requiresIncomingShareRecovery("account-1"),
        )
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
    )
}
