package dev.obiente.nextcloudnative.app

import kotlin.test.Test
import kotlin.test.assertEquals

class FileSyncExperienceTest {
    @Test
    fun `workspace filters represent syncing attention paused and offline states`() {
        val syncing = pair(id = "syncing", running = 2, local = "Studio archive")
        val attention = pair(id = "attention", failed = 1, local = "Client selects")
        val paused = pair(id = "paused", paused = true, local = "Archive 2024")
        val offline = pair(
            id = "offline",
            local = "Travel library",
            offline = true,
        )
        val pairs = listOf(syncing, attention, paused, offline)

        assertEquals(listOf("syncing"), filterFileSyncPairs(pairs, FileSyncListFilter.Syncing, "").map { it.id })
        assertEquals(listOf("attention"), filterFileSyncPairs(pairs, FileSyncListFilter.Attention, "").map { it.id })
        assertEquals(listOf("paused"), filterFileSyncPairs(pairs, FileSyncListFilter.Paused, "").map { it.id })
        assertEquals(listOf("offline"), filterFileSyncPairs(pairs, FileSyncListFilter.Offline, "").map { it.id })
    }

    @Test
    fun `skipped work and schedule prose do not impersonate live pair state`() {
        val pair = pair(
            id = "active",
            local = "Archive",
            skipped = 3,
            schedule = "Runs after a network connection becomes reachable",
        )

        assertEquals(emptyList(), filterFileSyncPairs(listOf(pair), FileSyncListFilter.Paused, ""))
        assertEquals(emptyList(), filterFileSyncPairs(listOf(pair), FileSyncListFilter.Offline, ""))
    }

    @Test
    fun `workspace search matches names paths and directions without changing order`() {
        val pairs = listOf(
            pair(id = "photos", local = "Studio archive", localPath = "Pictures/Studio", remote = "Photos/Studio"),
            pair(
                id = "documents",
                local = "Project documents",
                localPath = "Nextcloud/Projects",
                remote = "Work/Projects",
                direction = FileSyncDirection.UploadOnly,
            ),
        )

        assertEquals(listOf("photos"), filterFileSyncPairs(pairs, FileSyncListFilter.All, "pictures").map { it.id })
        assertEquals(listOf("documents"), filterFileSyncPairs(pairs, FileSyncListFilter.All, "device to nextcloud").map { it.id })
        assertEquals(listOf("photos", "documents"), filterFileSyncPairs(pairs, FileSyncListFilter.All, "").map { it.id })
    }

    private fun pair(
        id: String,
        local: String,
        localPath: String? = null,
        remote: String = "Files/$id",
        direction: FileSyncDirection = FileSyncDirection.Bidirectional,
        running: Int = 0,
        failed: Int = 0,
        skipped: Int = 0,
        schedule: String? = null,
        paused: Boolean = false,
        offline: Boolean = false,
    ): FileSyncPairSummary = FileSyncPairSummary(
        id = id,
        localDisplayName = local,
        localRootPath = localPath,
        remoteRootPath = remote,
        configuration = FileSyncConfiguration(
            deviceLabel = "Desktop",
            direction = direction,
        ),
        readyCount = 0,
        runningCount = running,
        conflicts = emptyList(),
        failedCount = failed,
        skippedCount = skipped,
        lastScanEpochMillis = null,
        scheduleDescription = schedule,
        runState = if (paused) FileSyncPairRunState.Paused else FileSyncPairRunState.Active,
        networkState = if (offline) FileSyncNetworkState.WaitingForNetwork else FileSyncNetworkState.Available,
    )
}
