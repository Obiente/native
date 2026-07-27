package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.DurableUploadScope
import dev.obiente.nextcloudnative.app.DurableUploadState
import dev.obiente.nextcloudnative.app.NextcloudApiMethod
import dev.obiente.nextcloudnative.app.NextcloudMultipartUploadRequest
import dev.obiente.nextcloudnative.app.afterProcessRecovery
import dev.obiente.nextcloudnative.app.localUploadFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidDurableMultipartUploadPolicyTest {
    @Test
    fun `deck attachment resource binds board stack card and request path`() {
        val scope = DurableUploadScope("deck-attachment", "42")
        val resource = resolveDurableUploadResource(scope, fixtureRequest(cardId = 42))

        assertEquals("deck-attachment", resource.feature)
        assertEquals("7", resource.boardId)
        assertEquals("11", resource.stackId)
        assertEquals("42", resource.itemId)

        assertFailsWith<IllegalArgumentException> {
            resolveDurableUploadResource(scope, fixtureRequest(cardId = 43))
        }
        assertFailsWith<IllegalArgumentException> {
            resolveDurableUploadResource(
                scope,
                fixtureRequest(cardId = 42).copy(
                    relativePath = "/index.php/apps/deck/api/v1.1/boards/7/cards/42/attachments",
                ),
            )
        }
    }

    @Test
    fun `queue rejects duplicates and bounds account and card ownership`() {
        val fourForCard = (1..4).map { index ->
            fixtureJob(index = index, account = ACCOUNT_A, cardId = 42)
        }
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(
                fourForCard,
                fixtureJob(index = 5, account = ACCOUNT_A, cardId = 42),
            )
        }

        requireCanAddDurableUpload(
            fourForCard,
            fixtureJob(index = 5, account = ACCOUNT_A, cardId = 43),
        )
        requireCanAddDurableUpload(
            fourForCard,
            fixtureJob(index = 5, account = ACCOUNT_B, cardId = 42),
        )

        val duplicateSelection = fixtureJob(index = 20, account = ACCOUNT_B, cardId = 99).copy(
            request = fixtureRequest(cardId = 99, selectionId = fourForCard.first().request.file.selectionId),
        )
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(fourForCard, duplicateSelection)
        }

        val twelveForAccount = (1..12).map { index ->
            fixtureJob(index = index, account = ACCOUNT_A, cardId = index.toLong())
        }
        assertFailsWith<IllegalArgumentException> {
            requireCanAddDurableUpload(
                twelveForAccount,
                fixtureJob(index = 13, account = ACCOUNT_A, cardId = 13),
            )
        }
    }

    @Test
    fun `queue pruning retains active work and only the newest terminal history`() {
        val active = listOf(
            fixtureJob(index = 1, account = ACCOUNT_A, cardId = 1),
            fixtureJob(index = 2, account = ACCOUNT_B, cardId = 2),
        )
        val terminal = (1..70).map { index ->
            fixtureJob(
                index = index + 100,
                account = if (index % 2 == 0) ACCOUNT_A else ACCOUNT_B,
                cardId = (index + 100).toLong(),
                state = DurableUploadState.Completed,
                updatedAt = index.toLong(),
            )
        }

        val pruned = pruneDurableUploadJobs(active + terminal)

        assertEquals(AndroidDurableMultipartUploadStore.MAX_STORED_UPLOADS, pruned.size)
        assertTrue(pruned.containsAll(active))
        assertFalse(pruned.any { it.state == DurableUploadState.Completed && it.updatedAtEpochMillis <= 8L })
        assertTrue(pruned.any { it.state == DurableUploadState.Completed && it.updatedAtEpochMillis == 70L })
    }

    @Test
    fun `only queued work can start and recovered in flight work becomes unknown`() {
        assertTrue(
            isAllowedDurableUploadTransition(
                DurableUploadState.Queued,
                DurableUploadState.Uploading,
            ),
        )
        assertTrue(
            isAllowedDurableUploadTransition(
                DurableUploadState.Uploading,
                DurableUploadState.OutcomeUnknown,
            ),
        )
        assertFalse(
            isAllowedDurableUploadTransition(
                DurableUploadState.OutcomeUnknown,
                DurableUploadState.Uploading,
            ),
        )
        assertFalse(
            isAllowedDurableUploadTransition(
                DurableUploadState.Failed,
                DurableUploadState.Uploading,
            ),
        )
        assertEquals(
            DurableUploadState.OutcomeUnknown,
            DurableUploadState.Uploading.afterProcessRecovery(),
        )
        assertEquals(
            DurableUploadState.Queued,
            DurableUploadState.Queued.afterProcessRecovery(),
        )
    }

    @Test
    fun `only definite client rejection avoids an unknown outcome`() {
        assertEquals(DurableUploadState.Completed, durableUploadStateForHttpResponse(201))
        assertEquals(DurableUploadState.Failed, durableUploadStateForHttpResponse(400))
        assertEquals(DurableUploadState.Failed, durableUploadStateForHttpResponse(409))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(302))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(408))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(425))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(429))
        assertEquals(DurableUploadState.OutcomeUnknown, durableUploadStateForHttpResponse(500))
    }

    private fun fixtureJob(
        index: Int,
        account: String,
        cardId: Long,
        state: DurableUploadState = DurableUploadState.Queued,
        updatedAt: Long = index.toLong(),
    ): AndroidDurableMultipartUploadJob {
        val scope = DurableUploadScope("deck-attachment", cardId.toString())
        val request = fixtureRequest(cardId, selectionId = selectionId(index))
        return AndroidDurableMultipartUploadJob(
            id = "upload-${index.toString().padStart(16, '0')}",
            accountId = account,
            scope = scope,
            resource = resolveDurableUploadResource(scope, request),
            request = request,
            state = state,
            message = null,
            updatedAtEpochMillis = updatedAt,
        )
    }

    private fun fixtureRequest(
        cardId: Long,
        selectionId: String = selectionId(cardId.toInt()),
    ): NextcloudMultipartUploadRequest = NextcloudMultipartUploadRequest(
        method = NextcloudApiMethod.POST,
        relativePath = "/index.php/apps/deck/api/v1.1/boards/7/stacks/11/cards/$cardId/attachments",
        file = localUploadFile(
            selectionId = selectionId,
            displayName = "fixture-$cardId.txt",
            mimeType = "text/plain",
            sizeBytes = 16L,
        ),
        maximumFileBytes = 1024L,
    )

    private fun selectionId(index: Int): String = "selection-${index.toString().padStart(16, '0')}"

    private companion object {
        const val ACCOUNT_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val ACCOUNT_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
