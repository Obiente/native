package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NextcloudFileListingTest {
    @Test
    fun cachedSnapshotsAreExplicitInFolderSummary() {
        assertEquals(
            "Cached · 4 items",
            nextcloudFileListingSummary(
                source = NextcloudFileListingSource.Cache,
                visibleCount = 4,
                totalCount = 4,
                filtered = false,
            ),
        )
        assertEquals(
            "Cached · 2 of 4",
            nextcloudFileListingSummary(
                source = NextcloudFileListingSource.Cache,
                visibleCount = 2,
                totalCount = 4,
                filtered = true,
            ),
        )
    }

    @Test
    fun networkSnapshotsKeepTheNormalCompactSummary() {
        assertEquals(
            "4 items",
            nextcloudFileListingSummary(
                source = NextcloudFileListingSource.Network,
                visibleCount = 4,
                totalCount = 4,
                filtered = false,
            ),
        )
    }

    @Test
    fun refreshFailurePreservesTheMeaningOfRetainedContent() {
        assertEquals(
            "Could not refresh this folder. Showing the previous contents.",
            nextcloudFileRefreshFailure(true, IllegalStateException("offline")),
        )
        assertEquals(
            "offline",
            nextcloudFileRefreshFailure(false, IllegalStateException("offline")),
        )
    }

    @Test
    fun impossibleCountsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            nextcloudFileListingSummary(
                source = NextcloudFileListingSource.Cache,
                visibleCount = 5,
                totalCount = 4,
                filtered = true,
            )
        }
    }
}
