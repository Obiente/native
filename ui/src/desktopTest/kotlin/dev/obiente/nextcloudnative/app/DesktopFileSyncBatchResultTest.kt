package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DesktopFileSyncBatchResultTest {
    @Test
    fun `batch result distinguishes complete stopped waiting and failed checks`() {
        assertIs<FileSyncCenterActionResult.Completed>(desktopFileSyncBatchResult(0, 0, 0, paused = false))

        val paused = assertIs<FileSyncCenterActionResult.Stopped>(
            desktopFileSyncBatchResult(0, 0, 0, paused = true),
        )
        assertEquals("Desktop syncing paused before all folders were checked.", paused.message)

        val stopped = assertIs<FileSyncCenterActionResult.Stopped>(
            desktopFileSyncBatchResult(0, 1, 0, paused = false),
        )
        assertEquals("1 desktop sync folder stopped before the check completed.", stopped.message)

        val waiting = assertIs<FileSyncCenterActionResult.Completed>(
            desktopFileSyncBatchResult(0, 0, 2, paused = false),
        )
        assertEquals("2 desktop sync folders are waiting for network or power rules.", waiting.message)

        assertIs<FileSyncCenterActionResult.Rejected>(desktopFileSyncBatchResult(1, 1, 1, paused = true))
    }
}
