package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PhotoTimelinePagingTest {
    @Test
    fun refreshAndAppendKeepMoreThanEightyItemsInStableOrder() {
        val firstEntries = (1L..200L).map { id ->
            entry(id, capturedAt = 10_000L - id)
        }
        val refresh = PhotoTimelineState().beginRefresh()
        val refreshToken = requireNotNull(refresh.token)
        var state = refresh.state.accept(
            refreshToken,
            PhotoTimelinePage(firstEntries, PhotoTimelineCursor("page-2")),
        )

        val append = state.beginNextPage()
        val appendToken = requireNotNull(append.token)
        state = append.state.accept(
            appendToken,
            PhotoTimelinePage(
                entries = (190L..350L).map { id -> entry(id, capturedAt = 10_000L - id) },
                nextCursor = null,
            ),
        )

        assertEquals(350, state.entries.size)
        assertEquals((1L..350L).toList(), state.entries.map { it.file.fileId })
        assertFalse(state.canLoadNextPage)
        assertNull(state.nextCursor)
    }

    @Test
    fun newerPageRecordReplacesDuplicateFileIdentityAndPathFallbackDeduplicates() {
        val original = entry(
            id = 42L,
            capturedAt = 2_000L,
            path = "Photos/Old name.jpg",
        )
        val moved = entry(
            id = 42L,
            capturedAt = 3_000L,
            path = "Photos/New name.jpg",
        )
        val pathOnly = entry(
            id = null,
            capturedAt = 1_000L,
            path = "/Photos/Path only.jpg/",
        )
        val pathOnlyUpdated = entry(
            id = null,
            capturedAt = 1_500L,
            path = "Photos/Path only.jpg",
        )

        val merged = mergePhotoTimelineEntries(
            existing = listOf(original, pathOnly),
            incoming = listOf(moved, pathOnlyUpdated),
        )

        assertEquals(listOf(moved, pathOnlyUpdated), merged)
    }

    @Test
    fun retentionLimitSlidesTowardOlderHistoryWithoutStoppingPaging() {
        val refresh = PhotoTimelineState(retentionLimit = 3).beginRefresh()
        var state = refresh.state.accept(
            requireNotNull(refresh.token),
            PhotoTimelinePage(
                entries = listOf(
                    entry(1L, 3L),
                    entry(2L, 2L),
                    entry(3L, 1L),
                ),
                nextCursor = PhotoTimelineCursor("older"),
            ),
        )

        assertEquals(3, state.entries.size)
        assertTrue(state.canLoadNextPage)
        assertFalse(state.hasDiscardedNewerEntries)

        val older = state.beginNextPage()
        state = older.state.accept(
            requireNotNull(older.token),
            PhotoTimelinePage(
                entries = listOf(entry(4L, 0L), entry(5L, -1L)),
                nextCursor = PhotoTimelineCursor("oldest"),
            ),
        )

        assertEquals(listOf(3L, 4L, 5L), state.entries.map { it.file.fileId })
        assertEquals(2, state.discardedNewerEntries)
        assertTrue(state.canLoadNextPage)
        assertTrue(state.hasDiscardedNewerEntries)

        val oldest = state.beginNextPage()
        state = oldest.state.accept(
            requireNotNull(oldest.token),
            PhotoTimelinePage(listOf(entry(6L, -2L)), null),
        )
        assertEquals(listOf(4L, 5L, 6L), state.entries.map { it.file.fileId })
        assertEquals(3, state.discardedNewerEntries)
        assertFalse(state.canLoadNextPage)

        val newest = state.beginRefresh()
        state = newest.state.accept(
            requireNotNull(newest.token),
            PhotoTimelinePage(listOf(entry(1L, 3L)), PhotoTimelineCursor("older")),
        )
        assertEquals(listOf(1L), state.entries.map { it.file.fileId })
        assertEquals(0, state.discardedNewerEntries)
        assertFalse(state.hasDiscardedNewerEntries)
    }

    @Test
    fun pageTokenCarriesAndEnforcesTheBoundedRequestSize() {
        val refresh = PhotoTimelineState(pageSize = 2).beginRefresh()
        val token = requireNotNull(refresh.token)

        assertEquals(2, token.pageSize)
        assertFailsWith<IllegalArgumentException> {
            refresh.state.accept(
                token,
                PhotoTimelinePage(
                    entries = listOf(entry(1L, 3L), entry(2L, 2L), entry(3L, 1L)),
                    nextCursor = null,
                ),
            )
        }
    }

    @Test
    fun cancelledAndSupersededLoadsIgnoreLateResults() {
        val first = PhotoTimelineState().beginRefresh()
        val firstToken = requireNotNull(first.token)
        val cancelled = first.state.cancelPendingLoad()
        val ignoredAfterCancel = cancelled.accept(
            firstToken,
            PhotoTimelinePage(listOf(entry(1L, 1L)), null),
        )

        assertSame(cancelled, ignoredAfterCancel)
        assertTrue(ignoredAfterCancel.entries.isEmpty())

        val second = cancelled.beginRefresh()
        val third = second.state.beginRefresh()
        val secondLate = third.state.accept(
            requireNotNull(second.token),
            PhotoTimelinePage(listOf(entry(2L, 2L)), null),
        )
        val thirdAccepted = secondLate.accept(
            requireNotNull(third.token),
            PhotoTimelinePage(listOf(entry(3L, 3L)), null),
        )

        assertTrue(secondLate.entries.isEmpty())
        assertEquals(listOf(3L), thirdAccepted.entries.map { it.file.fileId })
        assertSame(thirdAccepted, thirdAccepted.cancel(firstToken))
    }

    @Test
    fun replacementOwnerResumesInheritedLoadAndIgnoresOldOwnerCancellation() {
        val cached = PhotoTimelineState(entries = listOf(entry(1L, 100L)))
        val firstOwner = cached.beginNewestRevalidation()
        val firstToken = requireNotNull(firstOwner.token)

        val replacementOwner = firstOwner.state.beginReplacingPendingLoad(firstToken.kind)
        val replacementToken = requireNotNull(replacementOwner.token)
        val afterOldOwnerCancellation = replacementOwner.state.cancel(firstToken)

        assertEquals(PhotoTimelineLoadKind.RevalidateNewest, replacementToken.kind)
        assertTrue(replacementToken.generation > firstToken.generation)
        assertSame(replacementOwner.state, afterOldOwnerCancellation)

        val accepted = afterOldOwnerCancellation.accept(
            replacementToken,
            PhotoTimelinePage(listOf(entry(2L, 110L), entry(1L, 100L)), null),
        )
        assertEquals(listOf(2L, 1L), accepted.entries.map { it.file.fileId })
        assertNull(accepted.loading)
    }

    @Test
    fun failureBelongsOnlyToTheActiveGenerationAndKeepsLoadedItems() {
        val loaded = PhotoTimelineState(
            entries = listOf(entry(1L, 1L)),
            nextCursor = PhotoTimelineCursor("older"),
        )
        val append = loaded.beginNextPage()
        val failed = append.state.fail(requireNotNull(append.token), "  timed out  ")

        assertEquals("timed out", failed.error)
        assertEquals(loaded.entries, failed.entries)
        assertTrue(failed.canLoadNextPage)
        assertEquals(PhotoTimelineLoadKind.NextPage, failed.recoveryLoadKind)
    }

    @Test
    fun firstPageRevalidationRemovesMissingFileTiedAtCachedBoundaryTimestamp() {
        val cached = PhotoTimelineState(
            entries = listOf(
                entry(4L, 100L),
                entry(3L, 100L),
                entry(2L, 100L),
            ),
            nextCursor = PhotoTimelineCursor("after-first-page"),
        )
        val revalidation = cached.beginNewestRevalidation()
        val accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(
                    entry(4L, 100L),
                    entry(2L, 100L),
                ),
                nextCursor = PhotoTimelineCursor("after-revalidated-first-page"),
            ),
        )

        assertEquals(setOf(4L, 2L), accepted.entries.mapNotNull { it.file.fileId }.toSet())
        assertFalse(accepted.entries.any { entry -> entry.file.fileId == 3L })
        assertFalse(accepted.revalidationCursorCatchUp)
        assertTrue(accepted.revalidationPendingRemovalIdentities.isEmpty())
    }

    @Test
    fun revalidationCrossesDeletedCachedTailUsingExactFileIdTieBreaker() {
        val cached = PhotoTimelineState(
            entries = listOf(
                entry(4L, 100L),
                entry(3L, 100L),
                entry(2L, 100L),
            ),
            nextCursor = PhotoTimelineCursor("after-first-page"),
        )
        val revalidation = cached.beginNewestRevalidation()
        val accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(
                    entry(4L, 100L),
                    entry(3L, 100L),
                    entry(1L, 100L),
                ),
                nextCursor = PhotoTimelineCursor("after-crossed-boundary"),
            ),
        )

        assertEquals(setOf(4L, 3L, 1L), accepted.entries.mapNotNull { it.file.fileId }.toSet())
        assertFalse(accepted.entries.any { entry -> entry.file.fileId == 2L })
        assertFalse(accepted.revalidationCursorCatchUp)
        assertTrue(accepted.revalidationPendingRemovalIdentities.isEmpty())
    }

    @Test
    fun newestRevalidationReplaysEvenWhenItsVisiblePagingKeysAreUnchanged() {
        val cached = PhotoTimelineState(
            entries = listOf(
                entry(1L, 100L),
                entry(2L, 98L),
                entry(3L, 50L),
            ),
            nextCursor = PhotoTimelineCursor("after-cached-history"),
            loadedOlderPages = true,
        )
        val revalidation = cached.beginNewestRevalidation()

        assertEquals(cached.entries, revalidation.state.entries)
        assertEquals(
            PhotoTimelineLoadKind.RevalidateNewest,
            requireNotNull(revalidation.token).kind,
        )

        val accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(
                    entry(1L, 100L, "Photos/renamed.jpg"),
                    entry(2L, 98L),
                ),
                nextCursor = PhotoTimelineCursor("after-new-first-page"),
            ),
        )

        assertEquals(
            listOf(1L, 2L, 3L),
            accepted.entries.map { it.file.fileId },
        )
        assertEquals("Photos/renamed.jpg", accepted.entries.first().file.path)
        assertEquals(PhotoTimelineCursor("after-new-first-page"), accepted.nextCursor)
        assertTrue(accepted.loadedOlderPages)
        assertTrue(accepted.revalidationCursorCatchUp)
        assertEquals("file:3", accepted.revalidationCursorCatchUpTailIdentity)
    }

    @Test
    fun sameTimestampDeletionRestartsFromFreshCursorAndCatchesUpToCachedTail() {
        val cached = PhotoTimelineState(
            entries = listOf(
                entry(4L, 100L),
                entry(3L, 100L),
                entry(2L, 100L),
                entry(1L, 100L),
            ),
            nextCursor = PhotoTimelineCursor("same-time-offset-4"),
            loadedOlderPages = true,
        )
        val revalidation = cached.beginNewestRevalidation()
        var accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(entry(4L, 100L), entry(2L, 100L)),
                nextCursor = PhotoTimelineCursor("same-time-offset-2"),
            ),
        )

        assertEquals(PhotoTimelineCursor("same-time-offset-2"), accepted.nextCursor)
        assertTrue(accepted.revalidationCursorCatchUp)
        assertEquals("file:1", accepted.revalidationCursorCatchUpTailIdentity)
        assertEquals(
            listOf(1L, 2L, 3L, 4L),
            accepted.entries.map { it.file.fileId },
        )
        assertEquals(
            setOf("file:1", "file:3"),
            accepted.revalidationPendingRemovalIdentities,
        )

        val replay = accepted.beginNextPage()
        accepted = replay.state.accept(
            requireNotNull(replay.token),
            PhotoTimelinePage(
                entries = listOf(entry(2L, 100L), entry(1L, 100L)),
                nextCursor = PhotoTimelineCursor("same-time-offset-4-corrected"),
            ),
        )

        assertEquals(
            PhotoTimelineCursor("same-time-offset-4-corrected"),
            accepted.nextCursor,
        )
        assertFalse(accepted.revalidationCursorCatchUp)
        assertNull(accepted.revalidationCursorCatchUpTailIdentity)
        assertEquals(
            listOf(1L, 2L, 4L),
            accepted.entries.map { it.file.fileId },
        )
        assertTrue(accepted.revalidationPendingRemovalIdentities.isEmpty())
    }

    @Test
    fun deletedCachedTailIsRemovedOnlyAfterCatchUpExhaustsTheServer() {
        val cached = PhotoTimelineState(
            entries = listOf(
                entry(1L, 100L),
                entry(2L, 90L),
                entry(3L, 80L),
            ),
            nextCursor = PhotoTimelineCursor("after-cached-history"),
            loadedOlderPages = true,
        )
        val revalidation = cached.beginNewestRevalidation()
        var accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(entry(1L, 100L)),
                nextCursor = PhotoTimelineCursor("after-newest"),
            ),
        )

        assertEquals(
            listOf(1L, 2L, 3L),
            accepted.entries.map { it.file.fileId },
        )
        assertEquals(
            setOf("file:2", "file:3"),
            accepted.revalidationPendingRemovalIdentities,
        )

        val middle = accepted.beginNextPage()
        accepted = middle.state.accept(
            requireNotNull(middle.token),
            PhotoTimelinePage(
                entries = listOf(entry(2L, 90L)),
                nextCursor = PhotoTimelineCursor("after-middle"),
            ),
        )

        assertEquals(setOf("file:3"), accepted.revalidationPendingRemovalIdentities)
        assertTrue(accepted.entries.any { entry -> entry.file.fileId == 3L })

        val exhausted = accepted.beginNextPage()
        accepted = exhausted.state.accept(
            requireNotNull(exhausted.token),
            PhotoTimelinePage(entries = emptyList(), nextCursor = null),
        )

        assertEquals(listOf(1L, 2L), accepted.entries.map { it.file.fileId })
        assertFalse(accepted.revalidationCursorCatchUp)
        assertTrue(accepted.revalidationPendingRemovalIdentities.isEmpty())
    }

    @Test
    fun newestPageRevalidationReplaysFromItsCursorWhenItEvictsTheCachedTail() {
        val cached = PhotoTimelineState(
            entries = listOf(entry(1L, 100L), entry(2L, 90L), entry(3L, 80L)),
            nextCursor = PhotoTimelineCursor("after-cached-tail"),
            retentionLimit = 3,
            loadedOlderPages = true,
        )
        val revalidation = cached.beginNewestRevalidation()
        var accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(entry(4L, 110L), entry(1L, 100L)),
                nextCursor = PhotoTimelineCursor("after-newest-page"),
            ),
        )

        assertEquals(listOf(4L, 1L, 2L), accepted.entries.map { it.file.fileId })
        assertEquals(PhotoTimelineCursor("after-newest-page"), accepted.nextCursor)
        assertTrue(accepted.revalidationCursorCatchUp)

        val duplicateReplay = accepted.beginNextPage()
        accepted = duplicateReplay.state.accept(
            requireNotNull(duplicateReplay.token),
            PhotoTimelinePage(
                entries = listOf(entry(2L, 90L)),
                nextCursor = PhotoTimelineCursor("after-replayed-cache"),
            ),
        )

        assertEquals(listOf(4L, 1L, 2L), accepted.entries.map { it.file.fileId })
        assertEquals(PhotoTimelineCursor("after-replayed-cache"), accepted.nextCursor)
        assertNull(accepted.error)
        assertTrue(accepted.revalidationCursorCatchUp)

        val missingTail = accepted.beginNextPage()
        accepted = missingTail.state.accept(
            requireNotNull(missingTail.token),
            PhotoTimelinePage(
                entries = listOf(entry(3L, 80L)),
                nextCursor = null,
            ),
        )

        assertEquals(listOf(1L, 2L, 3L), accepted.entries.map { it.file.fileId })
        assertEquals(1, accepted.discardedNewerEntries)
        assertNull(accepted.nextCursor)
        assertFalse(accepted.revalidationCursorCatchUp)
    }

    @Test
    fun revalidationCursorCatchUpStopsAtItsRequestBound() {
        val catchingUp = PhotoTimelineState(
            entries = listOf(entry(1L, 100L), entry(2L, 90L)),
            nextCursor = PhotoTimelineCursor("catch-up"),
            loadedOlderPages = true,
            revalidationCursorCatchUpPagesRemaining = 1,
            revalidationCursorCatchUpTailIdentity = "file:3",
            revalidationPendingRemovalIdentities = setOf("file:2", "file:3"),
        )
        val next = catchingUp.beginNextPage()
        val stopped = next.state.accept(
            requireNotNull(next.token),
            PhotoTimelinePage(
                entries = listOf(entry(1L, 100L)),
                nextCursor = PhotoTimelineCursor("still-replaying"),
            ),
        )

        assertNull(stopped.nextCursor)
        assertFalse(stopped.revalidationCursorCatchUp)
        assertEquals(listOf(1L, 2L), stopped.entries.map { it.file.fileId })
        assertTrue(stopped.revalidationPendingRemovalIdentities.isEmpty())
        assertEquals(PhotoTimelineLoadKind.Refresh, stopped.recoveryLoadKind)
        assertEquals(
            "The photo timeline revalidation exceeded its paging limit.",
            stopped.error,
        )
    }

    @Test
    fun revalidationCursorCatchUpContinuesAfterAChangedReplayPageUntilTheTail() {
        val catchingUp = PhotoTimelineState(
            entries = listOf(entry(1L, 100L), entry(2L, 90L), entry(3L, 80L)),
            nextCursor = PhotoTimelineCursor("replay"),
            loadedOlderPages = true,
            revalidationCursorCatchUpPagesRemaining = 2,
            revalidationCursorCatchUpTailIdentity = "file:3",
        )
        val changed = catchingUp.beginNextPage()
        val stillCatchingUp = changed.state.accept(
            requireNotNull(changed.token),
            PhotoTimelinePage(
                entries = listOf(entry(4L, 85L)),
                nextCursor = PhotoTimelineCursor("closer-to-tail"),
            ),
        )

        assertTrue(stillCatchingUp.revalidationCursorCatchUp)
        assertEquals(1, stillCatchingUp.revalidationCursorCatchUpPagesRemaining)
        assertEquals("file:3", stillCatchingUp.revalidationCursorCatchUpTailIdentity)
    }

    @Test
    fun failedAndCancelledCatchUpNeverApplyPendingRemovals() {
        val cached = PhotoTimelineState(
            entries = listOf(
                entry(1L, 100L),
                entry(2L, 90L),
                entry(3L, 80L),
            ),
            nextCursor = PhotoTimelineCursor("after-cached-history"),
            loadedOlderPages = true,
        )
        val revalidation = cached.beginNewestRevalidation()
        val catchingUp = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(entry(1L, 100L)),
                nextCursor = PhotoTimelineCursor("after-newest"),
            ),
        )
        val next = catchingUp.beginNextPage()
        val failed = next.state.fail(requireNotNull(next.token), "Timed out")

        assertEquals(listOf(1L, 2L, 3L), failed.entries.map { it.file.fileId })
        assertEquals(
            setOf("file:2", "file:3"),
            failed.revalidationPendingRemovalIdentities,
        )

        val retry = failed.beginNextPage()
        val cancelled = retry.state.cancelPendingLoad()

        assertEquals(listOf(1L, 2L, 3L), cancelled.entries.map { it.file.fileId })
        assertEquals(
            setOf("file:2", "file:3"),
            cancelled.revalidationPendingRemovalIdentities,
        )
        assertTrue(cancelled.revalidationCursorCatchUp)
    }

    @Test
    fun stalledCatchUpDiscardsItsTransactionWithoutApplyingPendingRemovals() {
        val cursor = PhotoTimelineCursor("repeated")
        val catchingUp = PhotoTimelineState(
            entries = listOf(entry(1L, 100L), entry(2L, 90L)),
            nextCursor = cursor,
            loadedOlderPages = true,
            revalidationCursorCatchUpPagesRemaining = 2,
            revalidationCursorCatchUpTailIdentity = "file:3",
            revalidationPendingRemovalIdentities = setOf("file:2", "file:3"),
        )
        val next = catchingUp.beginNextPage()
        val stopped = next.state.accept(
            requireNotNull(next.token),
            PhotoTimelinePage(
                entries = listOf(entry(1L, 100L)),
                nextCursor = cursor,
            ),
        )

        assertEquals(listOf(1L, 2L), stopped.entries.map { it.file.fileId })
        assertNull(stopped.nextCursor)
        assertFalse(stopped.revalidationCursorCatchUp)
        assertTrue(stopped.revalidationPendingRemovalIdentities.isEmpty())
        assertEquals("The server repeated the same photo timeline page.", stopped.error)
    }

    @Test
    fun incompleteOptionalRawCoveragePreservesRawUntilCatchUpCompletes() {
        val cachedRaw = entry(1L, 100L, "Photos/cached.raf")
        val cachedNormal = entry(2L, 90L)
        val cachedOlder = entry(3L, 50L)
        val cached = PhotoTimelineState(
            entries = listOf(cachedRaw, cachedNormal, cachedOlder),
            nextCursor = PhotoTimelineCursor("after-cache"),
            loadedOlderPages = true,
        )
        val revalidation = cached.beginNewestRevalidation()
        var accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(entry(4L, 110L), entry(5L, 80L)),
                nextCursor = PhotoTimelineCursor("after-newest-page"),
                optionalRawRemovalAuthoritative = false,
            ),
        )

        assertEquals(
            listOf(4L, 1L, 2L, 5L, 3L),
            accepted.entries.map { it.file.fileId },
        )
        assertEquals(
            setOf("file:1", "file:2", "file:3"),
            accepted.revalidationPendingRemovalIdentities,
        )
        assertFalse(accepted.revalidationPendingRawRemovalAuthoritative)

        val catchUp = accepted.beginNextPage()
        accepted = catchUp.state.accept(
            requireNotNull(catchUp.token),
            PhotoTimelinePage(
                entries = listOf(cachedOlder),
                nextCursor = null,
            ),
        )

        assertEquals(
            listOf(4L, 1L, 5L, 3L),
            accepted.entries.map { it.file.fileId },
        )
        assertTrue(accepted.entries.any { it.file.fileId == 1L })
        assertFalse(accepted.entries.any { it.file.fileId == 2L })
        assertFalse(accepted.revalidationCursorCatchUp)
        assertTrue(accepted.revalidationPendingRemovalIdentities.isEmpty())
    }

    @Test
    fun terminalRevalidationRetainsRawWhenOptionalCoverageWasIncomplete() {
        val cached = PhotoTimelineState(
            entries = listOf(
                entry(1L, 100L, "Photos/cached.raf"),
                entry(2L, 90L),
            ),
            nextCursor = null,
            loadedOlderPages = true,
        )
        val revalidation = cached.beginNewestRevalidation()
        val accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(entry(3L, 110L)),
                nextCursor = null,
                optionalRawRemovalAuthoritative = false,
            ),
        )

        assertEquals(listOf(3L, 1L), accepted.entries.map { it.file.fileId })
        assertFalse(accepted.entries.any { it.file.fileId == 2L })
    }

    @Test
    fun terminalNewestPageRevalidationRemovesAllAbsentCachedEntries() {
        val cached = PhotoTimelineState(
            entries = listOf(
                entry(1L, 100L),
                entry(2L, 90L, "Photos/removed.raf"),
                entry(3L, 50L),
            ),
            nextCursor = PhotoTimelineCursor("after-cached-history"),
            loadedOlderPages = true,
        )
        val revalidation = cached.beginNewestRevalidation()
        val accepted = revalidation.state.accept(
            requireNotNull(revalidation.token),
            PhotoTimelinePage(
                entries = listOf(entry(1L, 105L)),
                nextCursor = null,
            ),
        )

        assertEquals(listOf(1L), accepted.entries.map { it.file.fileId })
        assertNull(accepted.nextCursor)
        assertFalse(accepted.loadedOlderPages)
    }

    @Test
    fun rawObservationRemainsStickyAcrossLaterPagesWithoutRawEntries() {
        val refresh = PhotoTimelineState().beginRefresh()
        val observed = refresh.state.accept(
            requireNotNull(refresh.token),
            PhotoTimelinePage(
                entries = listOf(entry(1L, 100L)),
                nextCursor = PhotoTimelineCursor("older"),
                rawObserved = true,
            ),
        )
        val next = observed.beginNextPage()
        val accepted = next.state.accept(
            requireNotNull(next.token),
            PhotoTimelinePage(
                entries = listOf(entry(2L, 90L)),
                nextCursor = null,
                rawObserved = false,
            ),
        )

        assertTrue(observed.rawEverObserved)
        assertTrue(accepted.rawEverObserved)
    }

    @Test
    fun displacedOlderWindowRequiresExplicitReturnToNewestBeforeRevalidation() {
        val displaced = PhotoTimelineState(
            entries = listOf(entry(3L, 50L)),
            nextCursor = PhotoTimelineCursor("still-older"),
            discardedNewerEntries = 2,
        )

        val revalidation = displaced.beginNewestRevalidation()

        assertSame(displaced, revalidation.state)
        assertNull(revalidation.token)
    }

    @Test
    fun failedReturnToNewestKeepsTheDisplacedWindowAndRetryMarker() {
        val displaced = PhotoTimelineState(
            entries = listOf(entry(3L, 50L)),
            nextCursor = PhotoTimelineCursor("still-older"),
            discardedNewerEntries = 2,
            loadedOlderPages = true,
        )
        val refresh = displaced.beginRefresh()

        assertEquals(2, refresh.state.discardedNewerEntries)
        assertEquals(displaced.entries, refresh.state.entries)
        assertEquals(displaced.nextCursor, refresh.state.nextCursor)

        val failed = refresh.state.fail(
            requireNotNull(refresh.token),
            "Server unavailable",
        )

        assertEquals(displaced.entries, failed.entries)
        assertEquals(displaced.nextCursor, failed.nextCursor)
        assertEquals(2, failed.discardedNewerEntries)
        assertTrue(failed.hasDiscardedNewerEntries)
        assertFalse(failed.canPrefetchNextPage)
        assertTrue(failed.canLoadNextPage)
        assertEquals(PhotoTimelineLoadKind.Refresh, failed.failedLoadKind)
        assertEquals("Server unavailable", failed.error)
    }

    @Test
    fun failedNewestRevalidationKeepsCachedEntriesAndExposesItsRetryKind() {
        val cached = PhotoTimelineState(
            entries = listOf(entry(1L, 100L)),
            nextCursor = PhotoTimelineCursor("older"),
        )
        val revalidation = cached.beginNewestRevalidation()
        val failed = revalidation.state.fail(
            requireNotNull(revalidation.token),
            "Could not check for newer photos.",
        )

        assertEquals(cached.entries, failed.entries)
        assertEquals(cached.nextCursor, failed.nextCursor)
        assertEquals(PhotoTimelineLoadKind.RevalidateNewest, failed.failedLoadKind)
        assertEquals("Could not check for newer photos.", failed.error)
    }

    @Test
    fun repeatedServerPageStopsAutomaticPagingWithoutGrowingTheTimeline() {
        val cursor = PhotoTimelineCursor("same")
        val loaded = PhotoTimelineState(
            entries = listOf(entry(1L, 1L)),
            nextCursor = cursor,
        )
        val append = loaded.beginNextPage()
        val stopped = append.state.accept(
            requireNotNull(append.token),
            PhotoTimelinePage(listOf(entry(1L, 1L)), cursor),
        )

        assertEquals(loaded.entries, stopped.entries)
        assertNull(stopped.nextCursor)
        assertFalse(stopped.canLoadNextPage)
        assertEquals(PhotoTimelineLoadKind.Refresh, stopped.recoveryLoadKind)
        assertEquals("The server repeated the same photo timeline page.", stopped.error)

        val recovery = stopped.beginRefresh()
        assertEquals(stopped.entries, recovery.state.entries)
        assertEquals(PhotoTimelineLoadKind.Refresh, requireNotNull(recovery.token).kind)
    }

    @Test
    fun repeatedServerPageStopsEvenWhenTheServerChangesItsCursor() {
        val loaded = PhotoTimelineState(
            entries = listOf(entry(1L, 1L)),
            nextCursor = PhotoTimelineCursor("first"),
        )
        val append = loaded.beginNextPage()
        val stopped = append.state.accept(
            requireNotNull(append.token),
            PhotoTimelinePage(
                entries = listOf(entry(1L, 1L)),
                nextCursor = PhotoTimelineCursor("different-but-ignored"),
            ),
        )

        assertNull(stopped.nextCursor)
        assertFalse(stopped.canLoadNextPage)
        assertEquals(PhotoTimelineLoadKind.Refresh, stopped.recoveryLoadKind)
        assertEquals("The server repeated the same photo timeline page.", stopped.error)
    }

    @Test
    fun pagingStopsWhenAChangedCursorOnlyReplaysAnEvictedIdentity() {
        val loaded = PhotoTimelineState(
            entries = listOf(entry(2L, 2L), entry(3L, 1L)),
            nextCursor = PhotoTimelineCursor("older"),
            retentionLimit = 2,
            discardedNewerEntries = 1,
        )
        val append = loaded.beginNextPage()
        val stopped = append.state.accept(
            requireNotNull(append.token),
            PhotoTimelinePage(
                entries = listOf(entry(1L, 3L)),
                nextCursor = PhotoTimelineCursor("loop"),
            ),
        )

        assertEquals(loaded.entries, stopped.entries)
        assertEquals(1, stopped.discardedNewerEntries)
        assertNull(stopped.nextCursor)
        assertFalse(stopped.canLoadNextPage)
    }

    @Test
    fun visibleGridItemsMapAroundMonthHeadersWithoutRefreshingOffscreenMedia() {
        val dateIndex = PhotoTimelineDateIndex(
            sections = listOf(
                PhotoTimelineMonthSection(
                    month = PhotoTimelineMonth(2026, 7),
                    firstItemIndex = 0,
                    itemCount = 3,
                ),
                PhotoTimelineMonthSection(
                    month = PhotoTimelineMonth(2026, 6),
                    firstItemIndex = 3,
                    itemCount = 2,
                ),
            ),
            totalItemCount = 5,
        )

        assertEquals(
            listOf(0, 1, 3, 4),
            photoTimelineStackIndicesForGridItems(
                dateIndex = dateIndex,
                gridItemIndices = listOf(
                    0, // July header.
                    1,
                    2,
                    4, // June header.
                    5,
                    6,
                    99,
                    -1,
                ),
            ),
        )
    }

    @Test
    fun stackingPreservesGlobalNewestFirstOrderAcrossFolders() {
        val entries = listOf(
            entry(1L, 5L, "Photos/A/paired.raf"),
            entry(2L, 4L, "Photos/B/middle.jpg"),
            entry(3L, 3L, "Photos/A/paired.jpg"),
        )

        val indexedStacks = buildPhotoTimelineStackEntries(entries)
        val stacks = indexedStacks.map(PhotoTimelineStackEntry::stack)

        assertEquals(
            listOf("paired.jpg", "middle.jpg"),
            stacks.map { it.cover.name },
        )
        assertEquals(
            listOf(5L, 4L),
            indexedStacks.map(PhotoTimelineStackEntry::capturedAtEpochSeconds),
        )
    }

    @Test
    fun dateIndexBuildsMonthAndYearStopsWithJumpFractions() {
        val entries = listOf(
            entry(1L, timestamp("Fri, 01 Mar 2024 12:00:00 GMT")),
            entry(2L, timestamp("Thu, 29 Feb 2024 12:00:00 GMT")),
            entry(3L, timestamp("Mon, 01 Jan 2024 12:00:00 GMT")),
            entry(4L, timestamp("Sun, 31 Dec 2023 12:00:00 GMT")),
        )

        val index = buildPhotoTimelineDateIndex(entries)

        assertEquals(
            listOf(
                PhotoTimelineMonthSection(PhotoTimelineMonth(2024, 3), 0, 1),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2024, 2), 1, 1),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2024, 1), 2, 1),
                PhotoTimelineMonthSection(PhotoTimelineMonth(2023, 12), 3, 1),
            ),
            index.sections,
        )
        assertEquals("February 2024", index.sectionAtFraction(0.34f)?.month?.label)
        assertEquals(2, index.itemIndexFor(PhotoTimelineMonth(2024, 1)))
        assertEquals(1f, index.fractionFor(PhotoTimelineMonth(2023, 12)))
        assertNull(index.itemIndexFor(PhotoTimelineMonth(2022, 1)))
    }

    @Test
    fun timezoneResolverMovesBoundaryMediaIntoTheCorrectLocalMonth() {
        val boundary = entry(
            id = 1L,
            capturedAt = timestamp("Fri, 01 Mar 2024 00:30:00 GMT"),
        )

        val utc = buildPhotoTimelineDateIndex(listOf(boundary))
        val west = buildPhotoTimelineDateIndex(
            listOf(boundary),
            FixedOffsetPhotoTimelineMonthResolver(offsetMinutes = -60),
        )

        assertEquals(PhotoTimelineMonth(2024, 3), utc.sections.single().month)
        assertEquals(PhotoTimelineMonth(2024, 2), west.sections.single().month)
    }

    @Test
    fun preEpochDatesUseFloorDivisionAcrossTimezoneBoundaries() {
        val boundary = entry(
            id = 1L,
            capturedAt = timestamp("Thu, 01 Jan 1970 00:30:00 GMT"),
        )
        val west = buildPhotoTimelineDateIndex(
            listOf(boundary),
            FixedOffsetPhotoTimelineMonthResolver(offsetMinutes = -60),
        )

        assertEquals(PhotoTimelineMonth(1969, 12), west.sections.single().month)
    }

    @Test
    fun nextcloudFileConversionRejectsDirectoriesAndMissingDates() {
        val valid = file(1L, "Photos/Photo.jpg", "Sun, 27 Jul 2026 09:00:00 GMT")
        val directory = valid.copy(isDirectory = true)
        val missing = valid.copy(lastModified = null)

        assertEquals(timestamp(requireNotNull(valid.lastModified)), valid.toPhotoTimelineEntryOrNull()?.capturedAtEpochSeconds)
        assertNull(directory.toPhotoTimelineEntryOrNull())
        assertNull(missing.toPhotoTimelineEntryOrNull())
    }

    @Test
    fun sectionGridIndicesIncludeEarlierMonthHeaders() {
        val index = buildPhotoTimelineDateIndex(
            listOf(
                entry(1L, timestamp("Fri, 01 Mar 2024 12:00:00 GMT")),
                entry(2L, timestamp("Thu, 29 Feb 2024 12:00:00 GMT")),
                entry(3L, timestamp("Wed, 28 Feb 2024 12:00:00 GMT")),
                entry(4L, timestamp("Sun, 31 Dec 2023 12:00:00 GMT")),
            ),
        )

        assertEquals(0, photoTimelineGridIndex(index.sections[0], 0))
        assertEquals(2, photoTimelineGridIndex(index.sections[1], 1))
        assertEquals(5, photoTimelineGridIndex(index.sections[2], 2))
        assertEquals(0, activePhotoTimelineSectionIndex(index, 1))
        assertEquals(1, activePhotoTimelineSectionIndex(index, 4))
        assertEquals(2, activePhotoTimelineSectionIndex(index, 5))
    }

    @Test
    fun davTimestampFormattingRoundTripsBeforeAndAfterUnixEpoch() {
        listOf(
            "Mon, 01 Jan 0001 00:00:00 GMT",
            "Wed, 31 Dec 1969 23:59:59 GMT",
            "Thu, 01 Jan 1970 00:00:00 GMT",
            "Mon, 27 Jul 2026 09:16:50 GMT",
        ).forEach { value ->
            val epoch = requireNotNull(parseDavMediaSearchTimestamp(value))
            assertEquals(value, formatDavMediaSearchTimestamp(epoch))
        }
    }

    @Test
    fun davTimelineFetchesTheCombinedPageSizeFromEveryActivePartition() =
        kotlinx.coroutines.runBlocking {
            val firstImages = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
                file(
                    id = id,
                    path = "Photos/image-$id.jpg",
                    lastModified = formatDavMediaSearchTimestamp(10_000L - id),
                )
            }
            val firstVideos = listOf(
                file(
                    10_001L,
                    "Photos/video.mp4",
                    formatDavMediaSearchTimestamp(9_500L),
                ).copy(mimeType = "video/mp4"),
            )
            val requestBodies = mutableListOf<String>()

            val firstPage = collectMediaTimelineDavPage(
                userId = "account",
                cursor = null,
                execute = { body ->
                    requestBodies += body
                    MediaSearchDavTransportResponse(
                        status = 207,
                        body = (if ("image/%" in body) "images" else "videos").encodeToByteArray(),
                    )
                },
                parse = { body ->
                    when (body.decodeToString()) {
                        "images" -> firstImages
                        "videos" -> firstVideos
                        else -> emptyList()
                    }
                },
                shouldSearchRaw = { false },
            )

            assertEquals(2, requestBodies.size)
            assertTrue(requestBodies.all { "<d:nresults>200</d:nresults>" in it })
            assertEquals(DEFAULT_PHOTO_TIMELINE_PAGE_SIZE, firstPage.files.size)
            assertTrue(firstPage.files.all { it.mimeType != "video/mp4" })
            assertFalse(firstPage.optionalRawRemovalAuthoritative)
            val cursor = requireNotNull(firstPage.nextCursor)
            requestBodies.clear()

            val secondPage = collectMediaTimelineDavPage(
                userId = "account",
                cursor = cursor,
                execute = { body ->
                    requestBodies += body
                    MediaSearchDavTransportResponse(
                        207,
                        (if ("image/%" in body) "older-image" else "video").encodeToByteArray(),
                    )
                },
                parse = { body ->
                    when (body.decodeToString()) {
                        "older-image" -> listOf(
                            file(
                                999L,
                                "Photos/older.jpg",
                                formatDavMediaSearchTimestamp(1L),
                            ),
                        )
                        "video" -> firstVideos
                        else -> emptyList()
                    }
                },
                shouldSearchRaw = { error("RAW discovery must not repeat on cursor pages.") },
            )

            assertEquals(2, requestBodies.size)
            val imageRequest = requestBodies.single { "image/%" in it }
            val videoRequest = requestBodies.single { "video/%" in it }
            assertTrue("<d:or>" in imageRequest)
            assertTrue("<d:lt><d:prop><d:getlastmodified/></d:prop>" in imageRequest)
            assertTrue("<d:lt><d:prop><oc:fileid/></d:prop>" in imageRequest)
            assertTrue("GMT</d:literal>" in imageRequest)
            assertFalse("<sd:firstresult>" in imageRequest)
            assertFalse("<d:lte>" in videoRequest)
            assertTrue(
                """xmlns:sd="https://github.com/icewind1991/SearchDAV/ns"""" in
                    imageRequest,
            )
            assertEquals(
                listOf("Photos/video.mp4", "Photos/older.jpg"),
                secondPage.files.map(NextcloudFile::path),
            )
            assertNull(secondPage.nextCursor)
        }

    @Test
    fun davTimelineKeysetDoesNotSkipAfterConsumedEqualTimestampFileIsDeleted() =
        kotlinx.coroutines.runBlocking {
            val timestamp = "Mon, 27 Jul 2026 09:16:50 GMT"
            val allImages = (450L downTo 1L).map { id ->
                file(
                    id = id,
                    path = "Photos/image-$id.jpg",
                    lastModified = timestamp,
                )
            }.toMutableList()
            val requestedFileIds = mutableListOf<Long>()
            val requestedBodies = mutableListOf<String>()

            suspend fun load(cursor: PhotoTimelineCursor?): MediaTimelineDavPage =
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = cursor,
                    execute = { body ->
                        requestedBodies += body
                        val isImage = "<d:literal>image/%</d:literal>" in body
                        val keysetFileId = Regex(
                            """<d:lt><d:prop><oc:fileid/></d:prop><d:literal>(\d+)</d:literal></d:lt>""",
                        ).find(body)?.groupValues?.get(1)?.toLong()
                        val offset = Regex(
                            """<sd:firstresult>(\d+)</sd:firstresult>""",
                        ).find(body)?.groupValues?.get(1)?.toInt() ?: 0
                        if (isImage && keysetFileId != null) requestedFileIds += keysetFileId
                        MediaSearchDavTransportResponse(
                            status = 207,
                            body = if (isImage) {
                                "images:${keysetFileId ?: "offset-$offset"}".encodeToByteArray()
                            } else {
                                "videos".encodeToByteArray()
                            },
                        )
                    },
                    parse = { body ->
                        val marker = body.decodeToString()
                        if (marker.startsWith("images:")) {
                            val continuation = marker.substringAfter(':')
                            if (continuation.startsWith("offset-")) {
                                allImages
                                    .drop(continuation.removePrefix("offset-").toInt())
                                    .take(PHOTO_TIMELINE_PARTITION_PAGE_SIZE)
                            } else {
                                val boundaryFileId = continuation.toLong()
                                allImages
                                    .filter { file -> requireNotNull(file.fileId) < boundaryFileId }
                                    .take(PHOTO_TIMELINE_PARTITION_PAGE_SIZE)
                            }
                        } else {
                            emptyList()
                        }
                    },
                    shouldSearchRaw = { false },
                )

            val pages = buildList {
                var cursor: PhotoTimelineCursor? = null
                do {
                    val page = load(cursor)
                    add(page)
                    if (size == 1) {
                        assertTrue(requireNotNull(page.nextCursor).value.startsWith("v4|"))
                        allImages.removeAll { file -> file.fileId == 450L }
                    }
                    cursor = page.nextCursor
                } while (cursor != null)
            }
            val loaded = pages.flatMap(MediaTimelineDavPage::files)

            assertEquals(listOf(251L, 51L), requestedFileIds)
            assertEquals(450, loaded.size)
            assertEquals(450, loaded.map(NextcloudFile::path).distinct().size)
            assertTrue(requestedBodies.drop(2).all { body -> "<sd:firstresult>" !in body })
            assertNull(pages.last().nextCursor)
        }

    @Test
    fun davTimelineRetriesRejectedKeysetOnceAndKeepsLaterPagesInOffsetMode() =
        kotlinx.coroutines.runBlocking {
            val timestamp = "Mon, 27 Jul 2026 09:16:50 GMT"
            val allImages = (450L downTo 1L).map { id ->
                file(id, "Photos/image-$id.jpg", timestamp)
            }
            val imageRequests = mutableListOf<String>()

            suspend fun load(cursor: PhotoTimelineCursor?): MediaTimelineDavPage =
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = cursor,
                    execute = { body ->
                        if ("image/%" !in body) {
                            MediaSearchDavTransportResponse(
                                207,
                                "videos".encodeToByteArray(),
                            )
                        } else {
                            imageRequests += body
                            val isKeyset =
                                "<d:lt><d:prop><oc:fileid/></d:prop>" in body
                            if (isKeyset) {
                                MediaSearchDavTransportResponse(
                                    422,
                                    "unsupported".encodeToByteArray(),
                                )
                            } else {
                                val offset = Regex(
                                    """<sd:firstresult>(\d+)</sd:firstresult>""",
                                ).find(body)?.groupValues?.get(1)?.toInt() ?: 0
                                MediaSearchDavTransportResponse(
                                    207,
                                    "images:$offset".encodeToByteArray(),
                                )
                            }
                        }
                    },
                    parse = { body ->
                        val marker = body.decodeToString()
                        if (marker.startsWith("images:")) {
                            allImages
                                .drop(marker.substringAfter(':').toInt())
                                .take(PHOTO_TIMELINE_PARTITION_PAGE_SIZE)
                        } else {
                            emptyList()
                        }
                    },
                    shouldSearchRaw = { false },
                )

            val first = load(null)
            val second = load(requireNotNull(first.nextCursor))
            assertTrue(requireNotNull(second.nextCursor).value.contains("|i:o,"))
            val third = load(requireNotNull(second.nextCursor))

            assertEquals(4, imageRequests.size)
            assertEquals(2, imageRequests[1].countDavOrderClauses())
            assertTrue("<d:lt><d:prop><oc:fileid/></d:prop>" in imageRequests[1])
            assertEquals(1, imageRequests[2].countDavOrderClauses())
            assertTrue("<sd:firstresult>200</sd:firstresult>" in imageRequests[2])
            assertEquals(1, imageRequests[3].countDavOrderClauses())
            assertFalse("<d:lt><d:prop><oc:fileid/></d:prop>" in imageRequests[3])
            assertTrue("<sd:firstresult>400</sd:firstresult>" in imageRequests[3])
            assertEquals(50, third.files.size)
            assertNull(third.nextCursor)
        }

    @Test
    fun davTimelineRetriesRejectedInitialOrderingAndKeepsLaterPagesCompatible() =
        kotlinx.coroutines.runBlocking {
            val firstImages = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
                file(
                    id = id,
                    path = "Photos/image-$id.jpg",
                    lastModified = (30_000L - id).toString(),
                )
            }
            val requests = mutableListOf<String>()

            suspend fun load(cursor: PhotoTimelineCursor?): MediaTimelineDavPage =
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = cursor,
                    execute = { body ->
                        requests += body
                        when {
                            body.countDavOrderClauses() == 2 ->
                                MediaSearchDavTransportResponse(400, "unsupported-order".encodeToByteArray())
                            "<d:literal>image/%</d:literal>" in body &&
                                "<d:lte>" !in body ->
                                MediaSearchDavTransportResponse(207, "images".encodeToByteArray())
                            "<d:literal>image/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(207, "older-images".encodeToByteArray())
                            else ->
                                MediaSearchDavTransportResponse(207, "videos".encodeToByteArray())
                        }
                    },
                    parse = { body ->
                        when (body.decodeToString()) {
                            "images" -> firstImages
                            "older-images", "videos" -> emptyList()
                            else -> error("Unexpected timeline response.")
                        }
                    },
                    shouldSearchRaw = { false },
                )

            val first = load(null)
            val second = load(requireNotNull(first.nextCursor))

            assertEquals(listOf(2, 1, 1, 1), requests.map { body -> body.countDavOrderClauses() })
            assertTrue(requireNotNull(first.nextCursor).value.contains("|i:o,"))
            assertTrue("<sd:firstresult>1</sd:firstresult>" in requests.last())
            assertNull(second.nextCursor)
        }

    @Test
    fun davTimelineUsesCompatibleOrderingForRawAfterMimeFallback() =
        kotlinx.coroutines.runBlocking {
            val requests = mutableListOf<String>()
            val detectedRaw = file(
                id = 1L,
                path = "Photos/detected.raf",
                lastModified = "30000",
            ).copy(mimeType = "image/x-fuji-raf")

            val page = collectMediaTimelineDavPage(
                userId = "account",
                cursor = null,
                execute = { body ->
                    requests += body
                    when {
                        body.countDavOrderClauses() == 2 ->
                            MediaSearchDavTransportResponse(422, "unsupported-order".encodeToByteArray())
                        "<d:literal>image/%</d:literal>" in body ->
                            MediaSearchDavTransportResponse(207, "image".encodeToByteArray())
                        "<d:literal>video/%</d:literal>" in body ->
                            MediaSearchDavTransportResponse(207, "video".encodeToByteArray())
                        else ->
                            MediaSearchDavTransportResponse(207, "raw".encodeToByteArray())
                    }
                },
                parse = { body ->
                    when (body.decodeToString()) {
                        "image" -> listOf(detectedRaw)
                        "video", "raw" -> emptyList()
                        else -> error("Unexpected timeline response.")
                    }
                },
                shouldSearchRaw = { files -> files.any(NextcloudFile::isRawPhoto) },
            )

            val rawRequests = requests.filter { body ->
                rawPhotoFileNameSearchPatterns().any(body::contains)
            }
            assertEquals(listOf(detectedRaw), page.files)
            assertTrue(rawRequests.isNotEmpty())
            assertTrue(rawRequests.all { body -> body.countDavOrderClauses() == 1 })
            assertTrue(page.optionalRawRemovalAuthoritative)
        }

    @Test
    fun davTimelineRawKeysetFallbackUsesSingleOrderOffsetMode() =
        kotlinx.coroutines.runBlocking {
            val firstRawPattern = rawPhotoFileNameSearchPatterns().first()
            val initialRaw = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
                file(
                    id = 10_000L + id,
                    path = "Photos/raw-$id.raf",
                    lastModified = (20_000L - id).toString(),
                ).copy(mimeType = "application/octet-stream")
            }
            val rawCursorRequests = mutableListOf<String>()
            var cursorPage = false

            suspend fun load(cursor: PhotoTimelineCursor?): MediaTimelineDavPage =
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = cursor,
                    execute = { body ->
                        when {
                            "<d:literal>image/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(207, "image".encodeToByteArray())
                            "<d:literal>video/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(207, "video".encodeToByteArray())
                            "<d:literal>$firstRawPattern</d:literal>" in body -> {
                                if (cursorPage) rawCursorRequests += body
                                if (
                                    cursorPage &&
                                    "<d:lt><d:prop><oc:fileid/></d:prop>" in body
                                ) {
                                    MediaSearchDavTransportResponse(422, "unsupported-keyset".encodeToByteArray())
                                } else {
                                    MediaSearchDavTransportResponse(
                                        207,
                                        (if (cursorPage) "raw-older" else "raw-initial").encodeToByteArray(),
                                    )
                                }
                            }
                            else ->
                                MediaSearchDavTransportResponse(207, "raw-empty".encodeToByteArray())
                        }
                    },
                    parse = { body ->
                        when (body.decodeToString()) {
                            "image" -> listOf(
                                file(
                                    id = 1L,
                                    path = "Photos/detected.raf",
                                    lastModified = "30000",
                                ).copy(mimeType = "image/x-fuji-raf"),
                            )
                            "raw-initial" -> initialRaw
                            "video", "raw-empty", "raw-older" -> emptyList()
                            else -> error("Unexpected timeline response.")
                        }
                    },
                    shouldSearchRaw = { files -> files.any(NextcloudFile::isRawPhoto) },
                )

            val first = load(null)
            cursorPage = true
            val second = load(requireNotNull(first.nextCursor))

            assertEquals(listOf(2, 1), rawCursorRequests.map { body -> body.countDavOrderClauses() })
            assertTrue("<sd:firstresult>1</sd:firstresult>" in rawCursorRequests.last())
            assertNull(second.nextCursor)
        }

    @Test
    fun davTimelineDecodesLegacyCursorVersionsWithoutChangingOffsetRequests() =
        kotlinx.coroutines.runBlocking {
            listOf(
                "v2|i:100,7|v:end",
                "v3|i:100,7|v:end|r:",
                "v3c|g:1|i:100,7|v:end|r:",
            ).forEach { encoded ->
                val requests = mutableListOf<String>()
                val page = collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = PhotoTimelineCursor(encoded),
                    execute = { body ->
                        requests += body
                        MediaSearchDavTransportResponse(207, "empty".encodeToByteArray())
                    },
                    parse = { emptyList() },
                    shouldSearchRaw = { error("Legacy cursor pages must not restart RAW discovery.") },
                )

                assertEquals(1, requests.size)
                assertTrue("<d:lte>" in requests.single())
                assertTrue("<sd:firstresult>7</sd:firstresult>" in requests.single())
                assertFalse("<d:lt><d:prop><oc:fileid/></d:prop>" in requests.single())
                assertNull(page.nextCursor)
            }
        }

    @Test
    fun davTimelineKeysetXmlSelectsStrictlyOlderTimestampAndFileIdPairs() {
        val timestamp = 1_722_074_210L
        val body = mediaSearchDavRequestBody(
            userId = "account",
            maximumResults = PHOTO_TIMELINE_PARTITION_PAGE_SIZE,
            mimeTypePatterns = listOf("image/%"),
            excludeCollections = false,
            strictlyBeforeEpochSeconds = timestamp,
            strictlyBeforeFileId = 251L,
        )
        val formatted = formatDavMediaSearchTimestamp(timestamp)

        assertTrue(
            "<d:lt><d:prop><d:getlastmodified/></d:prop>" +
                "<d:literal>$formatted</d:literal></d:lt>" in body,
        )
        assertTrue(
            "<d:eq><d:prop><d:getlastmodified/></d:prop>" +
                "<d:literal>$formatted</d:literal></d:eq>" in body,
        )
        assertTrue(
            "<d:lt><d:prop><oc:fileid/></d:prop><d:literal>251</d:literal></d:lt>" in body,
        )
        assertFalse("<sd:firstresult>" in body)
    }

    @Test
    fun davTimelinePagesRawPartitionsOnlyAfterRawWasObserved() =
        kotlinx.coroutines.runBlocking {
            val firstRawPattern = rawPhotoFileNameSearchPatterns().first()
            val initialRaw = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
                file(
                    id = 10_000L + id,
                    path = "Photos/raw-$id.3fr",
                    lastModified = (20_000L - id).toString(),
                ).copy(mimeType = "application/octet-stream")
            }
            val olderRaw = (1L..20L).map { id ->
                file(
                    id = 20_000L + id,
                    path = "Photos/older-$id.3fr",
                    lastModified = (10_000L - id).toString(),
                ).copy(mimeType = "application/octet-stream")
            }
            var cursorPage = false
            val executed = mutableListOf<String>()

            suspend fun load(cursor: PhotoTimelineCursor?): MediaTimelineDavPage =
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = cursor,
                    execute = { body ->
                        executed += body
                        when {
                            "<d:literal>image/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(207, "image".encodeToByteArray())
                            "<d:literal>video/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(207, "video".encodeToByteArray())
                            "<d:literal>$firstRawPattern</d:literal>" in body ->
                                MediaSearchDavTransportResponse(
                                    207,
                                    (if (cursorPage) "raw-older" else "raw-initial").encodeToByteArray(),
                                )
                            else -> MediaSearchDavTransportResponse(422, "unsupported".encodeToByteArray())
                        }
                    },
                    parse = { body ->
                        when (body.decodeToString()) {
                            "image" -> listOf(
                                file(
                                    1L,
                                    "Photos/detected.raf",
                                    "30000",
                                ).copy(mimeType = "image/x-fuji-raf"),
                            )
                            "video" -> emptyList()
                            "raw-initial" -> initialRaw
                            "raw-older" -> olderRaw
                            else -> error("Unexpected timeline response.")
                        }
                    },
                    shouldSearchRaw = { files -> files.any(NextcloudFile::isRawPhoto) },
                )

            val first = load(null)
            assertEquals(DEFAULT_PHOTO_TIMELINE_PAGE_SIZE, first.files.size)
            assertFalse(first.optionalRawRemovalAuthoritative)
            assertTrue(
                executed
                    .filter { body -> rawPhotoFileNameSearchPatterns().any(body::contains) }
                    .all { body -> "<d:nresults>200</d:nresults>" in body },
            )
            val next = requireNotNull(first.nextCursor)
            assertTrue(next.value.startsWith("v4|"))
            assertTrue("|r:" in next.value)

            executed.clear()
            cursorPage = true
            val second = load(next)

            assertEquals(20, second.files.size)
            assertNull(second.nextCursor)
            assertTrue(executed.isNotEmpty())
            assertTrue(executed.all { body -> rawPhotoFileNameSearchPatterns().any(body::contains) })
            assertTrue(
                executed.any { body ->
                    "<d:lt><d:prop><oc:fileid/></d:prop>" in body
                },
            )
        }

    @Test
    fun davTimelineKeepsSearchingRawAfterItWasPreviouslyObserved() =
        kotlinx.coroutines.runBlocking {
            val executed = mutableListOf<String>()
            val rawPreviouslyObserved = true
            val discoveredRaw = file(
                id = 44L,
                path = "Photos/newly-discovered.raf",
                lastModified = "30000",
            ).copy(mimeType = "application/octet-stream")

            val page = collectMediaTimelineDavPage(
                userId = "account",
                cursor = null,
                execute = { body ->
                    executed += body
                    val marker = when {
                        "<d:literal>image/%</d:literal>" in body -> "image"
                        "<d:literal>video/%</d:literal>" in body -> "video"
                        else -> "raw"
                    }
                    MediaSearchDavTransportResponse(207, marker.encodeToByteArray())
                },
                parse = { body ->
                    when (body.decodeToString()) {
                        "image", "video" -> emptyList()
                        "raw" -> listOf(discoveredRaw)
                        else -> error("Unexpected timeline response.")
                    }
                },
                shouldSearchRaw = { mimeFiles ->
                    rawPreviouslyObserved || mimeFiles.any(NextcloudFile::isRawPhoto)
                },
            )

            assertTrue(
                executed.any { body ->
                    rawPhotoFileNameSearchPatterns().any(body::contains)
                },
            )
            assertEquals(listOf("Photos/newly-discovered.raf"), page.files.map { it.path })
            assertTrue(page.rawObserved)
        }

    @Test
    fun davTimelineSkipsRawProbesUntilRawHasBeenObserved() =
        kotlinx.coroutines.runBlocking {
            val executed = mutableListOf<String>()
            val rawPreviouslyObserved = false

            val page = collectMediaTimelineDavPage(
                userId = "account",
                cursor = null,
                execute = { body ->
                    executed += body
                    MediaSearchDavTransportResponse(207, "empty".encodeToByteArray())
                },
                parse = { emptyList() },
                shouldSearchRaw = { mimeFiles ->
                    rawPreviouslyObserved || mimeFiles.any(NextcloudFile::isRawPhoto)
                },
            )

            assertEquals(2, executed.size)
            assertTrue(
                executed.none { body ->
                    rawPhotoFileNameSearchPatterns().any(body::contains)
                },
            )
            assertFalse(page.rawObserved)
        }

    @Test
    fun davTimelineKeepsMimeMediaWhenOptionalInitialRawSearchReturnsServerError() =
        kotlinx.coroutines.runBlocking {
            val ordinary = file(
                id = 1L,
                path = "Photos/ordinary.jpg",
                lastModified = "30000",
            )

            val page = collectMediaTimelineDavPage(
                userId = "account",
                cursor = null,
                execute = { body ->
                    when {
                        "<d:literal>image/%</d:literal>" in body ->
                            MediaSearchDavTransportResponse(207, "image".encodeToByteArray())
                        "<d:literal>video/%</d:literal>" in body ->
                            MediaSearchDavTransportResponse(207, "video".encodeToByteArray())
                        else ->
                            MediaSearchDavTransportResponse(500, "raw-error".encodeToByteArray())
                    }
                },
                parse = { body ->
                    when (body.decodeToString()) {
                        "image" -> listOf(ordinary)
                        "video" -> emptyList()
                        else -> error("The failed optional RAW response must not be parsed.")
                    }
                },
                shouldSearchRaw = { true },
            )

            assertEquals(listOf(ordinary), page.files)
            assertFalse(page.optionalRawRemovalAuthoritative)
        }

    @Test
    fun davTimelineKeepsMimeMediaWhenOptionalInitialRawTransportThrows() =
        kotlinx.coroutines.runBlocking {
            val ordinary = file(
                id = 1L,
                path = "Photos/ordinary.jpg",
                lastModified = "30000",
            )

            val page = collectMediaTimelineDavPage(
                userId = "account",
                cursor = null,
                execute = { body ->
                    when {
                        "<d:literal>image/%</d:literal>" in body ->
                            MediaSearchDavTransportResponse(207, "image".encodeToByteArray())
                        "<d:literal>video/%</d:literal>" in body ->
                            MediaSearchDavTransportResponse(207, "video".encodeToByteArray())
                        else -> error("Synthetic optional RAW transport failure.")
                    }
                },
                parse = { body ->
                    when (body.decodeToString()) {
                        "image" -> listOf(ordinary)
                        "video" -> emptyList()
                        else -> error("Unexpected timeline response.")
                    }
                },
                shouldSearchRaw = { true },
            )

            assertEquals(listOf(ordinary), page.files)
            assertFalse(page.optionalRawRemovalAuthoritative)
        }

    @Test
    fun davTimelineKeepsMimeMediaWhenOptionalInitialRawResponseIsMalformed() =
        kotlinx.coroutines.runBlocking {
            val ordinary = file(
                id = 1L,
                path = "Photos/ordinary.jpg",
                lastModified = "30000",
            )

            val page = collectMediaTimelineDavPage(
                userId = "account",
                cursor = null,
                execute = { body ->
                    val marker = when {
                        "<d:literal>image/%</d:literal>" in body -> "image"
                        "<d:literal>video/%</d:literal>" in body -> "video"
                        else -> "malformed-raw"
                    }
                    MediaSearchDavTransportResponse(207, marker.encodeToByteArray())
                },
                parse = { body ->
                    when (body.decodeToString()) {
                        "image" -> listOf(ordinary)
                        "video" -> emptyList()
                        else -> error("Synthetic malformed optional RAW response.")
                    }
                },
                shouldSearchRaw = { true },
            )

            assertEquals(listOf(ordinary), page.files)
            assertFalse(page.optionalRawRemovalAuthoritative)
        }

    @Test
    fun davTimelineDoesNotSwallowOptionalRawCancellation() =
        kotlinx.coroutines.runBlocking {
            assertFailsWith<CancellationException> {
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = null,
                    execute = { body ->
                        when {
                            "<d:literal>image/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(207, "image".encodeToByteArray())
                            "<d:literal>video/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(207, "video".encodeToByteArray())
                            else -> throw CancellationException("Synthetic cancellation.")
                        }
                    },
                    parse = { emptyList() },
                    shouldSearchRaw = { true },
                )
            }
            Unit
        }

    @Test
    fun davTimelineKeepsMimeCursorPageWhenOptionalRawSearchReturnsServerError() =
        kotlinx.coroutines.runBlocking {
            val initialImages = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
                file(
                    id = id,
                    path = "Photos/image-$id.jpg",
                    lastModified = (30_000L - id).toString(),
                )
            }
            val initialRaw = (1L..PHOTO_TIMELINE_PARTITION_PAGE_SIZE.toLong()).map { id ->
                file(
                    id = 10_000L + id,
                    path = "Photos/raw-$id.raf",
                    lastModified = (20_000L - id).toString(),
                ).copy(mimeType = "application/octet-stream")
            }
            val ordinaryOlder = file(
                id = 20_001L,
                path = "Photos/ordinary-older.jpg",
                lastModified = "10000",
            )
            var cursorPage = false

            suspend fun load(cursor: PhotoTimelineCursor?): MediaTimelineDavPage =
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = cursor,
                    execute = { body ->
                        when {
                            "<d:literal>image/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(
                                    207,
                                    (if (cursorPage) "image-older" else "images").encodeToByteArray(),
                                )
                            "<d:literal>video/%</d:literal>" in body ->
                                MediaSearchDavTransportResponse(207, "video".encodeToByteArray())
                            cursorPage ->
                                MediaSearchDavTransportResponse(500, "raw-error".encodeToByteArray())
                            else ->
                                MediaSearchDavTransportResponse(207, "raw".encodeToByteArray())
                        }
                    },
                    parse = { body ->
                        when (body.decodeToString()) {
                            "images" -> initialImages
                            "image-older" -> listOf(ordinaryOlder)
                            "video" -> emptyList()
                            "raw" -> initialRaw
                            else -> error("The failed optional RAW response must not be parsed.")
                        }
                    },
                    shouldSearchRaw = { true },
                )

            val first = load(null)
            cursorPage = true
            val second = load(requireNotNull(first.nextCursor))

            assertEquals(listOf(ordinaryOlder), second.files)
            assertFalse(second.optionalRawRemovalAuthoritative)
        }

    @Test
    fun davTimelineTreatsInitialMimeServerErrorAsFatal() =
        kotlinx.coroutines.runBlocking {
            val failure = assertFailsWith<IllegalStateException> {
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = null,
                    execute = {
                        MediaSearchDavTransportResponse(500, "mime-error".encodeToByteArray())
                    },
                    parse = { emptyList() },
                    shouldSearchRaw = { true },
                )
            }

            assertEquals("WebDAV media search failed (HTTP 500).", failure.message)
        }

    @Test
    fun davTimelineTreatsInitialMimeParseFailureAsFatal() =
        kotlinx.coroutines.runBlocking {
            val failure = assertFailsWith<IllegalStateException> {
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = null,
                    execute = {
                        MediaSearchDavTransportResponse(207, "malformed-mime".encodeToByteArray())
                    },
                    parse = { error("Synthetic malformed MIME response.") },
                    shouldSearchRaw = { true },
                )
            }

            assertEquals("Synthetic malformed MIME response.", failure.message)
        }

    @Test
    fun davTimelineReportsAuthoritativeOptionalRawCoverageOnlyWhenEveryPatternWasQueried() =
        kotlinx.coroutines.runBlocking {
            suspend fun load(searchRaw: Boolean): MediaTimelineDavPage =
                collectMediaTimelineDavPage(
                    userId = "account",
                    cursor = null,
                    execute = { body ->
                        val marker = when {
                            "<d:literal>image/%</d:literal>" in body -> "image"
                            "<d:literal>video/%</d:literal>" in body -> "video"
                            else -> "raw"
                        }
                        MediaSearchDavTransportResponse(207, marker.encodeToByteArray())
                    },
                    parse = { body ->
                        when (body.decodeToString()) {
                            "image" -> listOf(
                                file(
                                    1L,
                                    "Photos/detected.raf",
                                    "30000",
                                ).copy(mimeType = "image/x-fuji-raf"),
                            )
                            "video", "raw" -> emptyList()
                            else -> error("Unexpected timeline response.")
                        }
                    },
                    shouldSearchRaw = { searchRaw },
                )

            assertFalse(load(searchRaw = false).optionalRawRemovalAuthoritative)
            assertTrue(load(searchRaw = true).optionalRawRemovalAuthoritative)
        }

    private fun entry(
        id: Long?,
        capturedAt: Long,
        path: String = "Photos/$id.jpg",
    ) = PhotoTimelineEntry(
        file = file(id, path, capturedAt.toString()),
        capturedAtEpochSeconds = capturedAt,
    )

    private fun file(
        id: Long?,
        path: String,
        lastModified: String,
    ) = NextcloudFile(
        path = path,
        name = path.substringAfterLast('/'),
        isDirectory = false,
        mimeType = "image/jpeg",
        size = 1L,
        lastModified = lastModified,
        fileId = id,
        hasPreview = true,
    )

    private fun timestamp(value: String): Long = requireNotNull(parseDavMediaSearchTimestamp(value))

    private fun String.countDavOrderClauses(): Int =
        Regex("<d:order>").findAll(this).count()
}
