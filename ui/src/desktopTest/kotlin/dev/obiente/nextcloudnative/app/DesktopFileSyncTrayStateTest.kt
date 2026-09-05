package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopFileSyncTrayStateTest {
    @Test
    fun tooltipSummarizesEveryOperationalPhase() {
        assertEquals(
            "nati.ve - syncing",
            DesktopFileSyncTraySnapshot(DesktopFileSyncTrayPhase.Syncing).tooltip(),
        )
        assertEquals(
            "nati.ve - sync paused",
            DesktopFileSyncTraySnapshot(DesktopFileSyncTrayPhase.Paused).tooltip(),
        )
        assertEquals(
            "nati.ve - attention needed; 1 conflict; 2 failed",
            DesktopFileSyncTraySnapshot(
                phase = DesktopFileSyncTrayPhase.NeedsAttention,
                conflictCount = 1,
                failedCount = 2,
            ).tooltip(),
        )
        assertEquals(
            "nati.ve - 7 pending",
            DesktopFileSyncTraySnapshot(
                phase = DesktopFileSyncTrayPhase.Idle,
                pairCount = 3,
                pendingCount = 7,
            ).tooltip(),
        )
        assertEquals(
            "nati.ve - up to date",
            DesktopFileSyncTraySnapshot(
                phase = DesktopFileSyncTrayPhase.Idle,
                pairCount = 3,
            ).tooltip(),
        )
    }

    @Test
    fun snapshotRejectsNegativeCountsAndBlankMessages() {
        assertFailsWith<IllegalArgumentException> {
            DesktopFileSyncTraySnapshot(DesktopFileSyncTrayPhase.Idle, pendingCount = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            DesktopFileSyncTraySnapshot(DesktopFileSyncTrayPhase.Idle, message = "")
        }
    }
}
